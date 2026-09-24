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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A precomputed recommendation.
 *
 * <p>Materialised by the recommendation consumer rather than scored per
 * request, because the Home screen must render from one indexed lookup. The
 * reason string is stored alongside the score so the UI can explain itself;
 * an unexplained recommendation is one players do not trust.
 */
@Entity
@Table(name = "recommendations")
@IdClass(RecommendationEntity.RecommendationId.class)
@Getter
@Setter
@NoArgsConstructor
public class RecommendationEntity {

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Id
    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Column(nullable = false)
    private BigDecimal score;

    @Column(nullable = false)
    private String reason;

    /** Which scorer produced this, so two algorithms can be compared. */
    @Column(nullable = false)
    private String algorithm = "CONTENT_BASED_V1";

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt = Instant.now();

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class RecommendationId implements Serializable {
        private UUID userId;
        private UUID gameId;
    }
}
