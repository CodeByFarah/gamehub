package com.gamehub.infrastructure.persistence.entity;

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

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * One play session.
 *
 * <p>Foreign keys are stored as raw UUIDs rather than as @ManyToOne
 * associations. This is the highest-volume table in the schema and it is read
 * in pages; object associations would produce an N+1 select per page, or force
 * a fetch join that loads a whole GameEntity to render a title. The repository
 * projects exactly the columns each screen needs instead.
 */
@Entity
@Table(name = "game_sessions")
@Getter
@Setter
@NoArgsConstructor
public class GameSessionEntity {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Column(name = "match_id")
    private UUID matchId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(nullable = false)
    private int score;

    @Enumerated(EnumType.STRING)
    @Column
    private Outcome outcome;

    @Column(name = "client_version")
    private String clientVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public boolean isOpen() {
        return endedAt == null;
    }

    /**
     * Closes the session, setting all three closure fields together.
     *
     * <p>They are set in one method because the ck_sessions_closed constraint
     * requires all three or none. Leaving callers to set them individually is
     * how a half-closed row gets written.
     */
    public void close(Instant at, int finalScore, Outcome result) {
        if (at == null || result == null) {
            throw new IllegalArgumentException("closing a session needs an instant and an outcome");
        }
        if (at.isBefore(startedAt)) {
            throw new IllegalArgumentException("a session cannot end before it started");
        }
        this.endedAt = at;
        this.durationSeconds = (int) Duration.between(startedAt, at).toSeconds();
        this.score = finalScore;
        this.outcome = result;
    }

    public enum Outcome {
        WIN, LOSS, DRAW, ABANDONED
    }
}
