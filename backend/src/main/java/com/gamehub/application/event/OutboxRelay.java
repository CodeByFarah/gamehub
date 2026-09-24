package com.gamehub.application.event;

import com.gamehub.config.properties.CacheProperties;
import com.gamehub.infrastructure.persistence.repository.OutboxEventRepository;
import com.gamehub.infrastructure.redis.DistributedLease;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Schedules publication of committed outbox rows.
 *
 * <h2>Delivery semantics</h2>
 * At least once. A row can be published and the process die before
 * publishedAt is stamped, so the next run republishes it. That duplicate is
 * expected and cheap, because every consumer deduplicates through the
 * processed_events ledger.
 *
 * <p>Exactly-once would require Kafka transactions spanning the database
 * write, which is a large amount of machinery to remove a problem the
 * consumers already solve. This is the deliberate cheaper half of that trade.
 *
 * <h2>Running on every instance</h2>
 * There is no leader election. Concurrency safety comes from
 * {@code FOR UPDATE SKIP LOCKED} in the claim query, which gives each instance
 * a disjoint batch without any of them blocking. The Redis lease layered on
 * top is only an optimisation that stops idle instances hammering the table;
 * if it fails open, correctness is unaffected and the only cost is a little
 * wasted polling.
 *
 * <p>The actual work lives in {@link OutboxBatchPublisher} because it must
 * cross a Spring proxy to be transactional. See that class for why.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelay {

    private static final Duration STUCK_THRESHOLD = Duration.ofMinutes(2);

    private final OutboxBatchPublisher batchPublisher;
    private final OutboxEventRepository outbox;
    private final DistributedLease lease;
    private final CacheProperties cacheProperties;
    private final MeterRegistry meterRegistry;

    /**
     * Backlog depth as a gauge. The single most useful number for telling
     * "Kafka is slow" apart from "the relay is not running at all".
     *
     * <p>Registered here rather than in the constructor on purpose. Handing
     * {@code this} to the registry mid-construction publishes a reference to a
     * partially initialised object, which the JDK 21 this-escape lint flags
     * and which would genuinely misbehave if the registry sampled the gauge
     * before the fields were assigned.
     */
    @PostConstruct
    void registerMetrics() {
        meterRegistry.gauge("gamehub.outbox.backlog", this, OutboxRelay::backlogDepth);
    }

    /**
     * Sub-second cadence, because this interval is the floor on how long an
     * event takes to reach a consumer. A five-second poll would make an
     * achievement appear five seconds after the game that earned it, which
     * players read as the feature being broken.
     */
    @Scheduled(fixedDelayString = "PT0.5S")
    public void relay() {
        lease.runExclusively(
                "lease:outbox-relay",
                cacheProperties.getMatchmakerLease(),
                batchPublisher::drainBatch);
    }

    /**
     * Alerting signal. Distinguishes a relay that is merely behind from one
     * that cannot make progress: a non-zero stuck count means rows have waited
     * past the threshold, which no amount of throughput explains.
     */
    @Scheduled(fixedDelayString = "PT30S")
    public void reportStuckRows() {
        long stuck = outbox.countStuckSince(Instant.now().minus(STUCK_THRESHOLD));
        if (stuck > 0) {
            log.error("{} outbox rows have been unpublished for over {}", stuck, STUCK_THRESHOLD);
        }
    }

    private double backlogDepth() {
        try {
            return outbox.countUnpublished();
        } catch (Exception e) {
            // A gauge must never throw. NaN marks the sample absent rather
            // than reporting a misleading zero backlog.
            return Double.NaN;
        }
    }
}
