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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A catalogue entry.
 *
 * <p>The games.search_vector column is intentionally not mapped. It is a
 * Postgres GENERATED column; mapping it would let Hibernate try to write it
 * and fail, and reading it into Java serves no purpose because only the
 * database ever evaluates it.
 */
@Entity
@Table(name = "games")
@Getter
@Setter
@NoArgsConstructor
public class GameEntity {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(nullable = false)
    private String title;

    @Column(name = "short_description", nullable = false)
    private String shortDescription;

    @Column
    private String description;

    @Column(nullable = false)
    private String genre;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false)
    private List<String> tags = List.of();

    @Column(name = "min_players", nullable = false)
    private short minPlayers = 1;

    @Column(name = "max_players", nullable = false)
    private short maxPlayers = 1;

    @Column(name = "avg_session_minutes", nullable = false)
    private short avgSessionMinutes = 15;

    @Column(name = "supports_multiplayer", nullable = false)
    private boolean supportsMultiplayer;

    @Column(name = "supports_cloud_save", nullable = false)
    private boolean supportsCloudSave;

    @Column(name = "icon_url")
    private String iconUrl;

    @Column(name = "banner_url")
    private String bannerUrl;

    @Column(name = "rating_avg", nullable = false)
    private BigDecimal ratingAvg = BigDecimal.ZERO;

    @Column(name = "rating_count", nullable = false)
    private int ratingCount;

    @Column(name = "popularity_score", nullable = false)
    private int popularityScore;

    @Column(name = "released_at")
    private LocalDate releasedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GameStatus status = GameStatus.PUBLISHED;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public enum GameStatus {
        DRAFT, PUBLISHED, DELISTED
    }
}
