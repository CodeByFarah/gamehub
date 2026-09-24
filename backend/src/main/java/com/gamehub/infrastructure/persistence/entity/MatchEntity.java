package com.gamehub.infrastructure.persistence.entity;

import com.gamehub.domain.common.Region;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A committed match. */
@Entity
@Table(name = "matches")
@Getter
@Setter
@NoArgsConstructor
public class MatchEntity {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Region region;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MatchStatus status = MatchStatus.CREATED;

    /**
     * The cost the matchmaker accepted, lower being better.
     *
     * <p>Persisted so match quality is a measurable time series rather than an
     * assumption. A quality regression after a policy change shows up here
     * before players start complaining.
     */
    @Column(name = "match_quality", nullable = false)
    private BigDecimal matchQuality;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    public enum MatchStatus {
        CREATED, IN_PROGRESS, COMPLETED, ABANDONED
    }
}
