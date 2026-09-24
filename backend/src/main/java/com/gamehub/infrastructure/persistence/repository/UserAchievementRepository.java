package com.gamehub.infrastructure.persistence.repository;

import com.gamehub.infrastructure.persistence.entity.UserAchievementEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface UserAchievementRepository
        extends JpaRepository<UserAchievementEntity, UserAchievementEntity.UserAchievementId> {

    List<UserAchievementEntity> findByUserIdOrderByUnlockedAtDesc(UUID userId);

    @Query("SELECT ua.achievementId FROM UserAchievementEntity ua WHERE ua.userId = :userId")
    List<UUID> findUnlockedIds(@Param("userId") UUID userId);

    /**
     * Records an unlock, returning 0 if the player already had it.
     *
     * <p>ON CONFLICT DO NOTHING rather than an exists-check followed by an
     * insert. The consumer can see the same event twice, and two threads
     * running check-then-insert would both pass the check and one would then
     * blow up on the primary key. This never throws and tells the caller
     * whether it was genuinely new, which is what decides if an
     * AchievementUnlocked event should be emitted.
     */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO user_achievements (user_id, achievement_id, unlocked_at, source_event_id)
            VALUES (:userId, :achievementId, now(), :sourceEventId)
            ON CONFLICT (user_id, achievement_id) DO NOTHING
            """, nativeQuery = true)
    int unlockIfAbsent(@Param("userId") UUID userId,
                       @Param("achievementId") UUID achievementId,
                       @Param("sourceEventId") UUID sourceEventId);
}
