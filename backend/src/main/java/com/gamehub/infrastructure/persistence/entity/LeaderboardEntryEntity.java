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
 * Durable leaderboard standing. The source of truth behind the Redis ZSET.
 *
 * <p>Keeping this row is what makes the Redis copy disposable. A flushed or
 * evicted sorted set is rebuilt from here by one ordered index scan, so Redis
 * can run with allkeys-lru and losing it costs latency rather than data.
 */
@Entity
@Table(name = "leaderboard_entries")
@IdClass(LeaderboardEntryEntity.LeaderboardEntryId.class)
@Getter
@Setter
@NoArgsConstructor
public class LeaderboardEntryEntity {

    @Id
    @Column(name = "leaderboard_id", nullable = false)
    private UUID leaderboardId;

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private long score;

    /** Tie-break dimension: equal score, earlier achiever ranks higher. */
    @Column(name = "achieved_at", nullable = false)
    private Instant achievedAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class LeaderboardEntryId implements Serializable {
        private UUID leaderboardId;
        private UUID userId;
    }
}
