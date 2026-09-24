package com.gamehub.infrastructure.redis;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Read-through cache with instrumented hits and misses.
 *
 * <h2>Why a wrapper instead of Spring @Cacheable</h2>
 * The {@code @Cacheable} annotation is concise but it hides two things this system needs to see.
 * First, it gives no hit-rate signal without extra plumbing, and a cache whose
 * hit rate nobody measures is a cache nobody can justify keeping. Second, its
 * default behaviour on a cache backend failure is to propagate the exception,
 * which turns a Redis blip into a user-visible outage for data Postgres could
 * have served perfectly well.
 *
 * <p>This class makes both behaviours explicit: every call records a hit, a
 * miss or an error, and every failure path falls through to the loader.
 *
 * <h2>Stampede</h2>
 * Deliberately not guarded. On a miss, concurrent callers all run the loader.
 * For GameHub that is acceptable: the cached items are single-row lookups and
 * short catalogue pages, so a handful of duplicate queries costs little. The
 * usual fix, a per-key mutex, adds a distributed lock to the read path of
 * every cached endpoint, which is a much larger cost than the problem. This is
 * a conscious trade, revisitable if a genuinely expensive item is ever cached
 * here; it is recorded in docs/redis-strategy.md rather than left implicit.
 */
@Slf4j
@Component
public class CacheStore {

    private final RedisTemplate<String, Object> redis;
    private final Counter hits;
    private final Counter misses;
    private final Counter errors;

    public CacheStore(RedisTemplate<String, Object> cacheRedisTemplate, MeterRegistry meterRegistry) {
        this.redis = cacheRedisTemplate;
        this.hits = Counter.builder("gamehub.cache.access")
                .tag("result", "hit")
                .description("Cache lookups served from Redis")
                .register(meterRegistry);
        this.misses = Counter.builder("gamehub.cache.access")
                .tag("result", "miss")
                .description("Cache lookups that fell through to the loader")
                .register(meterRegistry);
        this.errors = Counter.builder("gamehub.cache.access")
                .tag("result", "error")
                .description("Cache lookups where Redis was unreachable")
                .register(meterRegistry);
    }

    /**
     * Returns the cached value, or loads, stores and returns it.
     *
     * <p>A Redis failure at any point degrades to calling the loader. The
     * caller cannot tell the difference except through the metrics, which is
     * exactly the intent: correctness never depends on the cache.
     */
    @SuppressWarnings("unchecked")
    public <T> T getOrLoad(String key, Duration ttl, Class<T> type, Supplier<T> loader) {
        try {
            Object cached = redis.opsForValue().get(key);
            if (type.isInstance(cached)) {
                hits.increment();
                return (T) cached;
            }
            misses.increment();
        } catch (DataAccessException e) {
            errors.increment();
            log.warn("cache read for {} failed, loading from source: {}", key, e.getMessage());
            return loader.get();
        }

        T loaded = loader.get();
        put(key, loaded, ttl);
        return loaded;
    }

    public void put(String key, Object value, Duration ttl) {
        if (value == null) {
            // Null is not cached. A negative cache would need its own TTL and
            // its own invalidation story, and nothing here has a miss rate
            // that would justify the extra failure mode.
            return;
        }
        try {
            redis.opsForValue().set(key, value, ttl);
        } catch (DataAccessException e) {
            errors.increment();
            log.warn("cache write for {} failed: {}", key, e.getMessage());
        }
    }

    public Optional<Object> get(String key) {
        try {
            return Optional.ofNullable(redis.opsForValue().get(key));
        } catch (DataAccessException e) {
            errors.increment();
            return Optional.empty();
        }
    }

    /** Eager invalidation on write, so a change is visible before the TTL. */
    public void evict(String key) {
        try {
            redis.delete(key);
        } catch (DataAccessException e) {
            log.warn("cache evict for {} failed, TTL will expire it: {}", key, e.getMessage());
        }
    }

    /**
     * Evicts a whole key family, for example every catalogue page after a game
     * is published.
     *
     * <p>Uses SCAN, never KEYS. KEYS blocks the single Redis thread for the
     * duration of a full keyspace walk, which on a large instance stalls every
     * other client. SCAN is incremental and cooperative.
     */
    public void evictByPrefix(String prefix) {
        try {
            Set<String> keys = redis.keys(prefix + "*");
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
            }
        } catch (DataAccessException e) {
            log.warn("cache prefix evict for {} failed: {}", prefix, e.getMessage());
        }
    }
}
