package com.gamehub.infrastructure.persistence.repository;

import com.gamehub.infrastructure.persistence.entity.UserProfileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface UserProfileRepository extends JpaRepository<UserProfileEntity, UUID> {

    List<UserProfileEntity> findByUserIdIn(List<UUID> userIds);

    /**
     * Applies the outcome of one finished game to a profile.
     *
     * <p>Written as a single atomic UPDATE rather than read-modify-write. Two
     * GameCompleted events for the same player can be handled concurrently by
     * two consumer threads on different partitions; a load-then-save would
     * make them race and lose one increment. Letting the database do the
     * arithmetic removes the race entirely.
     *
     * <p>This is safe to run concurrently but it is NOT idempotent. Replay
     * protection comes from the processed_events ledger inserted in the same
     * transaction, not from this statement.
     */
    @Modifying
    @Transactional
    @Query("""
            UPDATE UserProfileEntity p
               SET p.gamesPlayed = p.gamesPlayed + 1,
                   p.gamesWon = p.gamesWon + :wonIncrement,
                   p.totalPlaytimeSeconds = p.totalPlaytimeSeconds + :durationSeconds,
                   p.xp = p.xp + :xpGained,
                   p.updatedAt = CURRENT_TIMESTAMP
             WHERE p.userId = :userId
            """)
    int applyCompletedGame(@Param("userId") UUID userId,
                           @Param("wonIncrement") int wonIncrement,
                           @Param("durationSeconds") long durationSeconds,
                           @Param("xpGained") int xpGained);

    @Modifying
    @Transactional
    @Query("""
            UPDATE UserProfileEntity p
               SET p.skillRating = :skillRating,
                   p.updatedAt = CURRENT_TIMESTAMP
             WHERE p.userId = :userId
            """)
    int updateSkillRating(@Param("userId") UUID userId, @Param("skillRating") int skillRating);
}
