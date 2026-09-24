package com.gamehub.infrastructure.persistence.entity;

import com.gamehub.domain.common.Region;
import com.gamehub.domain.leaderboard.LeaderboardMetric;
import com.gamehub.domain.leaderboard.LeaderboardPeriod;
import com.gamehub.domain.leaderboard.LeaderboardScope;
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

import java.time.Instant;
import java.util.UUID;

/**
 * A leaderboard definition and the Redis key it projects into.
 *
 * <p>Scope, period and metric are domain enums rather than types nested here.
 * They are concepts the API speaks in, and nesting them in a JPA entity made
 * the controller signature depend on the persistence layer, which
 * {@code ArchitectureTest} rejected.
 *
 * <p>The Redis key is stored rather than derived at read time. Deriving it
 * would mean a change to the naming scheme silently splits a leaderboard in
 * two, with old writes in one key and new reads against another, and nothing
 * failing. Storing it makes such a change a migration.
 */
@Entity
@Table(name = "leaderboards")
@Getter
@Setter
@NoArgsConstructor
public class LeaderboardEntity {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "game_id")
    private UUID gameId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LeaderboardScope scope;

    @Enumerated(EnumType.STRING)
    @Column
    private Region region;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LeaderboardPeriod period = LeaderboardPeriod.ALL_TIME;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LeaderboardMetric metric = LeaderboardMetric.SCORE;

    @Column(name = "redis_key", nullable = false, unique = true)
    private String redisKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
