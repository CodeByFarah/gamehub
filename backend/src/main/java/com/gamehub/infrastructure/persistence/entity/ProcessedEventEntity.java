package com.gamehub.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * Idempotency ledger. One row per (event, consumer group) actually handled.
 *
 * <p>Kafka delivers at least once, so a consumer must assume it will see the
 * same record again after a rebalance, a redelivery, or a relay retry. Before
 * doing any work, a consumer inserts here; a primary-key violation means the
 * event was already handled and the consumer returns without acting.
 *
 * <p>Keyed by consumer group rather than globally, because one GameCompleted
 * event is legitimately processed three times, once by each of statistics,
 * achievements and recommendations. A global key would let whichever consumer
 * ran first suppress the other two.
 *
 * <p>The insert must share a transaction with the work it guards. Insert and
 * work in separate transactions reintroduces exactly the dual-write problem
 * the outbox exists to solve, only on the consumer side.
 */
@Entity
@Table(name = "processed_events")
@IdClass(ProcessedEventEntity.ProcessedEventId.class)
@Getter
@Setter
@NoArgsConstructor
public class ProcessedEventEntity {

    @Id
    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Id
    @Column(name = "consumer_group", nullable = false)
    private String consumerGroup;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt = Instant.now();

    public ProcessedEventEntity(UUID eventId, String consumerGroup) {
        this.eventId = eventId;
        this.consumerGroup = consumerGroup;
        this.processedAt = Instant.now();
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class ProcessedEventId implements Serializable {
        private UUID eventId;
        private String consumerGroup;
    }
}
