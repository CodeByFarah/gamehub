package com.gamehub.config.properties;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * TTLs for every Redis key family, bound from the gamehub.cache prefix.
 *
 * <p>Collected in one place on purpose. TTLs scattered across the services that
 * happen to write each key are impossible to reason about as a whole, and
 * "what is actually cached, and for how long?" is the first question asked when
 * a stale read is reported. docs/redis-strategy.md cross-references this class
 * field by field.
 *
 * <p>Every value here is a correctness-neutral performance knob. Nothing in
 * GameHub reads from Redis without a Postgres fallback, so setting all of these
 * to zero would make the system slower and still correct. That property is what
 * makes it safe to run Redis with allkeys-lru eviction.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "gamehub.cache")
public class CacheProperties {

    /**
     * Game catalogue pages.
     *
     * <p>Short, because the catalogue is written by admins at unpredictable
     * times and a newly published game appearing up to a minute late is an
     * acceptable trade for absorbing the read volume of the Discover screen.
     * Invalidated eagerly on write as well, so the TTL is only the backstop for
     * a write that happened on another instance.
     */
    @NotNull
    private Duration gameCatalogue = Duration.ofMinutes(1);

    /**
     * Individual game detail documents.
     *
     * <p>Longer than the catalogue: detail pages are read far more often than
     * they change, and they are invalidated by key on every write.
     */
    @NotNull
    private Duration gameDetail = Duration.ofMinutes(15);

    /**
     * Leaderboard sorted sets.
     *
     * <p>Long, because these are not really a cache of a query: they are a
     * projection that is updated incrementally by ZADD as scores arrive. The
     * TTL exists so an abandoned leaderboard cannot occupy memory forever, not
     * to force a refresh.
     */
    @NotNull
    private Duration leaderboard = Duration.ofDays(2);

    /**
     * Precomputed recommendation lists.
     *
     * <p>Matches the cadence at which the recommendation consumer regenerates
     * them. A shorter TTL would just produce misses that the consumer refills
     * with identical data.
     */
    @NotNull
    private Duration recommendations = Duration.ofHours(6);

    /**
     * Per-user rank lookups.
     *
     * <p>Deliberately very short. Rank changes constantly under load, and a
     * player seeing a stale rank next to a fresh leaderboard reads as a bug.
     * This TTL exists only to collapse the burst of identical lookups that one
     * screen render produces.
     */
    @NotNull
    private Duration userRank = Duration.ofSeconds(10);

    /**
     * Lease held by whichever instance is running a matchmaker bucket tick.
     *
     * <p>Not a cache. It is a mutual-exclusion lease, and its duration is a
     * correctness-relevant value rather than a performance knob: it must exceed
     * the worst-case tick duration, or a second instance will start a tick
     * while the first is still running. See docs/matchmaking.md.
     */
    @NotNull
    private Duration matchmakerLease = Duration.ofSeconds(10);

    /** Sliding window used by the API rate limiter. */
    @NotNull
    private Duration rateLimitWindow = Duration.ofMinutes(1);
}
