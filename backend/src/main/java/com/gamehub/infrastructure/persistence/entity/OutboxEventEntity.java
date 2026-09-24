package com.gamehub.infrastructure.persistence.entity;

import com.gamehub.domain.event.EventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * A row in the transactional outbox, mapped to game_events.
 *
 * <h2>The problem this solves</h2>
 * An endpoint that ends a game must do two things: commit the session row and
 * publish GameCompleted. There is no transaction spanning Postgres and Kafka,
 * so either order is wrong:
 *
 * <ul>
 *   <li>Publish then commit: the transaction can roll back after the event is
 *       already on the topic. Consumers award achievements for a game that,
 *       as far as the database is concerned, never finished.</li>
 *   <li>Commit then publish: the process can die in between. The session is
 *       recorded and the event is lost forever, silently.</li>
 * </ul>
 *
 * <h2>The fix</h2>
 * Insert this row in the same transaction as the state change. It commits or
 * rolls back atomically with it, because it is the same transaction. A
 * separate relay then polls unpublished rows, sends them to Kafka, and stamps
 * publishedAt.
 *
 * <p>The guarantee that buys is at-least-once, not exactly-once: the relay can
 * publish and then fail before stamping, producing a duplicate. That is
 * deliberate and cheap to absorb, because every consumer is already idempotent
 * through the processed_events ledger. Chasing exactly-once here would mean
 * Kafka transactions and a much larger blast radius for a problem the
 * consumers already handle.
 */
@Entity
@Table(name = "game_events")
@Getter
@Setter
@NoArgsConstructor
public class OutboxEventEntity {

    /**
     * The id is the event id carried in the payload, not a separate surrogate.
     * Reusing it is what makes the outbox row and the Kafka record the same
     * logical event, so a duplicate publish is recognisable downstream.
     */
    @Id
    @GeneratedValue
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private EventType eventType;

    @Column(name = "schema_version", nullable = false)
    private short schemaVersion = 1;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "partition_key", nullable = false)
    private String partitionKey;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "game_id")
    private UUID gameId;

    /** Serialised event body. JSONB, so it stays queryable for debugging. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();

    /** Null until the relay has successfully published this row. */
    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "publish_attempts", nullable = false)
    private short publishAttempts;

    /** Last failure, kept so a stuck row can be diagnosed without a log dig. */
    @Column(name = "last_error")
    private String lastError;

    public void markPublished(Instant at) {
        this.publishedAt = at;
        this.lastError = null;
    }

    /**
     * Records a failed attempt. The message is truncated because a driver
     * stack trace can run to kilobytes, and storing it per attempt would let
     * one persistently failing row dominate the table.
     */
    public void markFailed(String error) {
        this.publishAttempts = (short) Math.min(Short.MAX_VALUE, this.publishAttempts + 1);
        this.lastError = error == null ? null
                : error.substring(0, Math.min(error.length(), 500));
    }

    public boolean isPublished() {
        return publishedAt != null;
    }
}
