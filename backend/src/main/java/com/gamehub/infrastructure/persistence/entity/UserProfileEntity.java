package com.gamehub.infrastructure.persistence.entity;

import com.gamehub.domain.common.Region;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Public gameplay identity, 1:1 with a user and sharing its primary key.
 *
 * <p>No @OneToOne back to UserEntity. The association would be traversed on
 * every profile read for no benefit, and it invites the lazy-loading failures
 * that come with open-in-view disabled. The shared key is the relationship.
 */
@Entity
@Table(name = "user_profiles")
@Getter
@Setter
@NoArgsConstructor
public class UserProfileEntity {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Region region;

    @Column(name = "skill_rating", nullable = false)
    private int skillRating = 1200;

    @Column(nullable = false)
    private int level = 1;

    @Column(nullable = false)
    private int xp;

    @Column(name = "games_played", nullable = false)
    private int gamesPlayed;

    @Column(name = "games_won", nullable = false)
    private int gamesWon;

    @Column(name = "total_playtime_seconds", nullable = false)
    private long totalPlaytimeSeconds;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /** Win rate in the closed interval 0 to 1, or 0 when nothing has been played. */
    public double winRate() {
        return gamesPlayed == 0 ? 0.0d : (double) gamesWon / gamesPlayed;
    }
}
