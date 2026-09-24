package com.gamehub.infrastructure.persistence.repository;

import com.gamehub.infrastructure.persistence.entity.ProcessedEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

public interface ProcessedEventRepository
        extends JpaRepository<ProcessedEventEntity, ProcessedEventEntity.ProcessedEventId> {

    /**
     * Claims an event for one consumer group, returning 1 on success and 0 if
     * it was already handled.
     *
     * <p>ON CONFLICT DO NOTHING rather than a SELECT followed by an INSERT.
     * The check-then-act version has a window between the two statements in
     * which a concurrent consumer can insert the same row, so both would
     * believe they were first and the event would be processed twice. This is
     * a single atomic statement with no such window.
     *
     * <p>Native SQL because JPQL has no vocabulary for ON CONFLICT.
     */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO processed_events (event_id, consumer_group, processed_at)
            VALUES (:eventId, :consumerGroup, now())
            ON CONFLICT (event_id, consumer_group) DO NOTHING
            """, nativeQuery = true)
    int claim(@Param("eventId") java.util.UUID eventId,
              @Param("consumerGroup") String consumerGroup);

    /**
     * Retention sweep. The ledger only needs to outlive Kafka topic retention:
     * once a record can no longer be redelivered, remembering that we handled
     * it has no value and only costs storage.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM ProcessedEventEntity p WHERE p.processedAt < :before")
    int deleteProcessedBefore(@Param("before") Instant before);
}
