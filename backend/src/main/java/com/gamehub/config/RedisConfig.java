package com.gamehub.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.util.List;

@Configuration
public class RedisConfig {

    /**
     * Template for cached documents.
     *
     * <p>Keys are serialised as plain strings, not with the default JDK
     * serializer. Binary keys are unreadable in redis-cli, which turns every
     * cache investigation into a guessing game, and they make the key format
     * an accident of Java rather than something designed.
     *
     * <p>Values are JSON rather than Java serialisation. JDK serialisation
     * couples the cache to exact class shapes, so a field added to a DTO makes
     * every cached entry undeserialisable, and it is a well-known remote code
     * execution vector if anything untrusted can ever write to Redis.
     */
    @Bean
    public RedisTemplate<String, Object> cacheRedisTemplate(RedisConnectionFactory factory) {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);

        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer(mapper));
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer(mapper));
        template.afterPropertiesSet();
        return template;
    }

    /**
     * Releases a lease only if this holder still owns it.
     *
     * <p>A naive release is DEL on the key, and it is wrong. If holder A
     * overran its lease, the key expired, and holder B acquired it, then A
     * finishing and calling DEL would delete the lease B is currently relying
     * on, and a third holder could acquire it while B is still working. The
     * compare-and-delete below makes release safe by checking ownership and
     * deleting in one atomic step, which is the part a GET followed by a DEL
     * cannot do.
     */
    @Bean
    public RedisScript<Long> leaseReleaseScript() {
        return new DefaultRedisScript<>("""
                if redis.call('get', KEYS[1]) == ARGV[1] then
                  return redis.call('del', KEYS[1])
                else
                  return 0
                end
                """, Long.class);
    }

    /**
     * Fixed-window rate limiter.
     *
     * <p>INCR and the conditional EXPIRE must be atomic. Done as two client
     * round trips, a crash or a slow network between them leaves a counter
     * with no TTL, which never resets and locks the caller out permanently.
     *
     * <p>EXPIRE is set only when the counter is created, so the window is
     * fixed rather than sliding forward on every request. A sliding reset
     * would let a steady stream of calls hold the window open indefinitely.
     */
    @Bean
    public RedisScript<Long> rateLimitScript() {
        return new DefaultRedisScript<>("""
                local current = redis.call('incr', KEYS[1])
                if current == 1 then
                  redis.call('pexpire', KEYS[1], ARGV[1])
                end
                return current
                """, Long.class);
    }

    /**
     * Records a leaderboard score keeping only a personal best, and refreshes
     * the key TTL in the same round trip.
     *
     * <p>ZADD GT is the reason this is a script rather than a template call.
     * Spring Data ZSetOperations exposes plain ZADD and addIfAbsent, and
     * neither expresses "only if greater". Plain ZADD would let a redelivered
     * old score demote a player; addIfAbsent would refuse every legitimate
     * improvement after the first.
     *
     * <p>GT also makes the write idempotent and order-independent, which is
     * precisely what lets Kafka at-least-once delivery touch a leaderboard
     * safely.
     *
     * <p>CH makes the reply the number of entries actually changed, so the
     * caller can distinguish a real improvement from a no-op without a second
     * read.
     */
    @Bean
    public RedisScript<Long> leaderboardSubmitScript() {
        return new DefaultRedisScript<>("""
                local changed = redis.call('zadd', KEYS[1], 'GT', 'CH', ARGV[1], ARGV[2])
                redis.call('pexpire', KEYS[1], ARGV[3])
                return changed
                """, Long.class);
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    /** Exposed so the health indicator can report which key families exist. */
    @Bean
    public List<String> leaderboardKeyPrefixes() {
        return List.of("lb:global:", "lb:region:", "lb:game:");
    }
}
