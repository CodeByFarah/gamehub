package com.gamehub.infrastructure.persistence.repository;

import com.gamehub.infrastructure.persistence.entity.OutboxEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, UUID> {

    /**
     * Claims a batch of unpublished events for this relay instance.
     *
     * <h2>Why FOR UPDATE SKIP LOCKED</h2>
     * The relay runs on every instance. Without locking, three instances would
     * read the same rows and publish each event three times. A plain FOR
     * UPDATE would fix the duplication but serialise the instances: two of
     * them would block waiting for the first to finish its batch, so adding
     * instances would not add throughput.
     *
     * <p>SKIP LOCKED gives both properties at once. Each instance takes a
     * disjoint batch and none of them waits. This is the standard way to turn
     * a database table into a work queue that scales horizontally, and it is
     * the reason the relay needs no leader election.
     *
     * <p>Ordered by occurred_at so events are published in roughly causal
     * order. Only roughly: strict ordering is not promised here and is not
     * needed, because per-user ordering is guaranteed by the Kafka partition
     * key instead.
     *
     * <p>Backed by idx_game_events_unpublished, a partial index over the
     * unpublished tail, so this scan costs the size of the backlog rather than
     * the size of all history.
     */
    @Query(value = """
            SELECT * FROM game_events
             WHERE published_at IS NULL
             ORDER BY occurred_at
             LIMIT :batchSize
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEventEntity> claimUnpublishedBatch(@Param("batchSize") int batchSize);

    @Modifying
    @Transactional
    @Query("""
            UPDATE OutboxEventEntity e
               SET e.publishedAt = :publishedAt, e.lastError = NULL
             WHERE e.id IN :ids
            """)
    int markPublished(@Param("ids") List<UUID> ids, @Param("publishedAt") Instant publishedAt);

    /** Backlog depth, exported as a gauge. A rising value means the relay is losing. */
    @Query("SELECT COUNT(e) FROM OutboxEventEntity e WHERE e.publishedAt IS NULL")
    long countUnpublished();

    /**
     * Rows stuck long enough to be worth alerting on. Distinguishes a relay
     * that is merely behind from one that cannot make progress at all.
     */
    @Query("""
            SELECT COUNT(e) FROM OutboxEventEntity e
             WHERE e.publishedAt IS NULL AND e.occurredAt < :threshold
            """)
    long countStuckSince(@Param("threshold") Instant threshold);
}
