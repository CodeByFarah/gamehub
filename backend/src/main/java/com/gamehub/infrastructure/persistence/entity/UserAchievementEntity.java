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
 * An unlocked achievement.
 *
 * <p>The composite primary key is the second idempotency layer for the
 * achievement consumer. Even if the processed_events check were somehow
 * bypassed, a replayed unlock hits a duplicate-key violation and the consumer
 * treats it as a no-op. Two independent mechanisms, because awarding the same
 * achievement twice is visible to the player and awkward to undo.
 */
@Entity
@Table(name = "user_achievements")
@IdClass(UserAchievementEntity.UserAchievementId.class)
@Getter
@Setter
@NoArgsConstructor
public class UserAchievementEntity {

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Id
    @Column(name = "achievement_id", nullable = false)
    private UUID achievementId;

    @Column(name = "unlocked_at", nullable = false)
    private Instant unlockedAt = Instant.now();

    /** The event that caused this unlock, so the pipeline stays auditable. */
    @Column(name = "source_event_id")
    private UUID sourceEventId;

    public UserAchievementEntity(UUID userId, UUID achievementId, UUID sourceEventId) {
        this.userId = userId;
        this.achievementId = achievementId;
        this.sourceEventId = sourceEventId;
        this.unlockedAt = Instant.now();
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class UserAchievementId implements Serializable {
        private UUID userId;
        private UUID achievementId;
    }
}
