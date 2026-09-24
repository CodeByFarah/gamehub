package com.gamehub.infrastructure.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * A best-effort mutual-exclusion lease backed by Redis.
 *
 * <h2>What it is for</h2>
 * Three scheduled jobs run on every instance: the matchmaker tick, the outbox
 * relay and the expiry sweeper. Running the matchmaker tick for the same
 * bucket on three instances at once would have them all read the same waiting
 * pool and propose overlapping pairings. The atomic ticket claim would reject
 * the duplicates, so the result would still be correct, but two thirds of the
 * work would be wasted every second.
 *
 * <h2>How it works</h2>
 * SET key token NX PX ttl. NX makes acquisition atomic: exactly one caller can
 * create the key. The token is unique per acquisition, which is what makes
 * release safe, see {@link #release}.
 *
 * <h2>What it is not</h2>
 * Not a correctness mechanism, and the distinction matters. Redis leases are
 * not safe under arbitrary failure: a paused holder can overrun its TTL while
 * still believing it holds the lease, and a Redis failover can lose the key
 * entirely. Systems that need real mutual exclusion need fencing tokens or a
 * consensus-backed lock service.
 *
 * <p>GameHub does not need that, because nothing here depends on the lease for
 * correctness. Every operation the lease guards is also protected by a
 * database constraint or an atomic conditional write: the matchmaker by the
 * conditional ticket claim, the relay by SKIP LOCKED. The lease is a work
 * deduplication optimisation layered on top. If it fails open, the system does
 * redundant work and stays correct. That is the property that makes a simple
 * Redis lease the right tool instead of an over-engineered one.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DistributedLease {

    private final StringRedisTemplate redis;
    private final RedisScript<Long> leaseReleaseScript;

    /**
     * Runs {@code work} only if this instance wins the lease.
     *
     * @return true if the work ran, false if another instance held the lease
     *         or Redis was unreachable
     */
    public boolean runExclusively(String key, Duration ttl, Runnable work) {
        String token = UUID.randomUUID().toString();
        if (!acquire(key, token, ttl)) {
            return false;
        }
        try {
            work.run();
            return true;
        } finally {
            // In a finally block, so a job that throws still releases rather
            // than blocking its own bucket until the TTL expires.
            release(key, token);
        }
    }

    /** Same, for work that produces a value. Returns empty when not acquired. */
    public <T> java.util.Optional<T> callExclusively(String key, Duration ttl, Supplier<T> work) {
        String token = UUID.randomUUID().toString();
        if (!acquire(key, token, ttl)) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.ofNullable(work.get());
        } finally {
            release(key, token);
        }
    }

    private boolean acquire(String key, String token, Duration ttl) {
        try {
            return Boolean.TRUE.equals(
                    redis.opsForValue().setIfAbsent(key, token, ttl));
        } catch (DataAccessException e) {
            // Fail closed on acquisition. Redis being down is not a reason to
            // let every instance start running the same job simultaneously;
            // skipping a tick costs one second of matchmaking latency.
            log.warn("could not acquire lease {}, skipping this run: {}", key, e.getMessage());
            return false;
        }
    }

    /**
     * Releases only if this caller still holds the lease.
     *
     * <p>The token check is the whole point. A plain DEL would let a holder
     * that overran its TTL delete the lease a different instance has since
     * acquired, allowing two instances to run concurrently precisely when
     * something is already slow.
     */
    private void release(String key, String token) {
        try {
            redis.execute(leaseReleaseScript, List.of(key), token);
        } catch (DataAccessException e) {
            // Safe to ignore. The TTL releases it anyway; the script only
            // makes release prompt rather than eventual.
            log.debug("lease {} release failed, TTL will expire it: {}", key, e.getMessage());
        }
    }
}
