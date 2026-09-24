package com.gamehub.application.service;

import com.gamehub.config.properties.CacheProperties;
import com.gamehub.config.properties.MatchmakingProperties;
import com.gamehub.domain.common.Region;
import com.gamehub.domain.matchmaking.GreedyMatchmakingEngine;
import com.gamehub.domain.matchmaking.MatchTicket;
import com.gamehub.domain.matchmaking.ProposedMatch;
import com.gamehub.infrastructure.persistence.entity.MatchmakingTicketEntity;
import com.gamehub.infrastructure.persistence.entity.MatchmakingTicketEntity.TicketStatus;
import com.gamehub.infrastructure.persistence.repository.MatchmakingTicketRepository;
import com.gamehub.infrastructure.redis.DistributedLease;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Runs the matchmaker once per tick, per queue bucket.
 *
 * <h2>Shape of a tick</h2>
 * <ol>
 *   <li>Find the buckets that actually have waiting players.</li>
 *   <li>For each, try to take a short Redis lease.</li>
 *   <li>Read that bucket waiting pool from Postgres.</li>
 *   <li>Run the pure engine over it in memory.</li>
 *   <li>Commit each proposal through {@link MatchCommitService}, which
 *       re-validates atomically.</li>
 * </ol>
 *
 * <h2>Why the lease is an optimisation, not the safety mechanism</h2>
 * If the lease failed open and three instances ran the same bucket, they would
 * read the same pool and propose overlapping pairings. The conditional ticket
 * claim in step 5 means exactly one wins each pairing and the others roll
 * back. The outcome stays correct; only the wasted work changes. That is what
 * makes a plain Redis lease acceptable here, where it would not be if
 * correctness depended on it.
 *
 * <h2>Per-bucket leases, not one global lease</h2>
 * A single lease would serialise all matchmaking onto one instance and make
 * the tick duration the sum of every bucket. Leasing per bucket lets different
 * instances work different games and regions at the same time, which is what
 * keeps the tick horizontally scalable.
 */
@Slf4j
@Service
public class MatchmakerTickService {

    private final MatchmakingTicketRepository tickets;
    private final MatchCommitService commitService;
    private final GreedyMatchmakingEngine engine;
    private final DistributedLease lease;
    private final MatchmakingProperties properties;
    private final CacheProperties cacheProperties;
    private final MeterRegistry meterRegistry;

    private final Counter matchesCreated;
    private final Counter staleProposals;
    private final Timer tickDuration;

    public MatchmakerTickService(MatchmakingTicketRepository tickets,
                                 MatchCommitService commitService,
                                 GreedyMatchmakingEngine engine,
                                 DistributedLease lease,
                                 MatchmakingProperties properties,
                                 CacheProperties cacheProperties,
                                 MeterRegistry meterRegistry) {
        this.tickets = tickets;
        this.commitService = commitService;
        this.engine = engine;
        this.lease = lease;
        this.properties = properties;
        this.cacheProperties = cacheProperties;
        this.meterRegistry = meterRegistry;

        this.matchesCreated = Counter.builder("gamehub.matchmaking.matches")
                .description("Matches committed").register(meterRegistry);
        // Non-zero is normal under concurrency. A sustained high rate relative
        // to matches means instances are colliding and the lease is not doing
        // its job, which is a tuning signal rather than a bug.
        this.staleProposals = Counter.builder("gamehub.matchmaking.stale_proposals")
                .description("Proposals discarded because a ticket was already taken")
                .register(meterRegistry);
        this.tickDuration = Timer.builder("gamehub.matchmaking.tick")
                .description("Time to process one bucket")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    /**
     * Queue depth as a gauge. Registered after construction rather than inside
     * it, so a partially built object is never handed to the registry.
     */
    @PostConstruct
    void registerMetrics() {
        meterRegistry.gauge("gamehub.matchmaking.queue_depth", this,
                self -> self.tickets.countByStatus(TicketStatus.WAITING));
    }

    @Scheduled(fixedDelayString = "${gamehub.matchmaking.tick-interval:1s}")
    public void tick() {
        List<Object[]> buckets;
        try {
            buckets = tickets.findActiveBuckets();
        } catch (Exception e) {
            // A tick is entirely skippable. Players wait one more second.
            log.warn("could not read active matchmaking buckets: {}", e.getMessage());
            return;
        }

        for (Object[] bucket : buckets) {
            UUID gameId = (UUID) bucket[0];
            Region region = Region.valueOf(String.valueOf(bucket[1]));
            processBucket(gameId, region);
        }
    }

    private void processBucket(UUID gameId, Region region) {
        String leaseKey = "lease:matchmaker:" + gameId + ":" + region;
        lease.runExclusively(leaseKey, cacheProperties.getMatchmakerLease(),
                () -> tickDuration.record(() -> runBucket(gameId, region)));
    }

    private void runBucket(UUID gameId, Region region) {
        List<MatchmakingTicketEntity> waiting =
                tickets.findByGameIdAndRegionAndStatusOrderByEnqueuedAtAsc(
                        gameId, region, TicketStatus.WAITING,
                        PageRequest.of(0, properties.getBucketBatchSize()));

        if (waiting.size() < 2) {
            return;
        }

        List<MatchTicket> domainTickets = waiting.stream()
                .map(MatchmakingTicketEntity::toDomain)
                .toList();

        // One instant for the whole tick, so every ticket is aged against the
        // same clock reading. Reading the clock per ticket would make an
        // earlier-iterated ticket look fractionally younger, a meaningless but
        // real source of non-determinism.
        Instant now = Instant.now();
        List<ProposedMatch> proposals = engine.pair(domainTickets, now);

        for (ProposedMatch proposal : proposals) {
            try {
                commitService.commit(proposal, region)
                        .ifPresent(id -> matchesCreated.increment());
            } catch (MatchCommitService.StaleProposalException e) {
                // Expected under concurrency. The surviving ticket is
                // untouched and will be considered again on the next tick.
                staleProposals.increment();
            } catch (Exception e) {
                log.warn("failed to commit a proposal for game {}: {}", gameId, e.getMessage());
            }
        }
    }

    /**
     * Expires tickets whose TTL has passed.
     *
     * <p>Runs less often than the tick because it is one indexed sweep rather
     * than per-bucket work, and a ticket lingering a few seconds past expiry
     * harms nothing.
     *
     * <p>The repository method carries its own {@code @Transactional}, so this
     * works correctly even though it is invoked through a lambda: the
     * transaction is applied by the Spring Data repository proxy rather than
     * by a proxy around this class, which a self-invoked lambda would bypass.
     */
    @Scheduled(fixedDelayString = "PT10S")
    public void expireStaleTickets() {
        lease.runExclusively("lease:matchmaker-expiry", cacheProperties.getMatchmakerLease(),
                () -> {
                    int expired = tickets.expireStaleTickets(Instant.now());
                    if (expired > 0) {
                        // INFO rather than DEBUG: a burst here means some
                        // bucket has too thin a population to ever match.
                        log.info("expired {} matchmaking tickets past their TTL", expired);
                    }
                });
    }
}
