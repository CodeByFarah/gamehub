package com.gamehub.infrastructure.persistence.repository;

import com.gamehub.infrastructure.persistence.entity.AchievementEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AchievementRepository extends JpaRepository<AchievementEntity, UUID> {

    Optional<AchievementEntity> findByCodeAndGameIdIsNull(String code);

    /**
     * Every achievement that could possibly apply to a session of this game:
     * the ones defined for the game, plus the platform-wide ones.
     *
     * <p>Loaded in one query rather than two, because the achievement consumer
     * evaluates all of them against a single event and two round trips per
     * event is pure overhead on the hottest consumer in the system.
     */
    @Query("""
            SELECT a FROM AchievementEntity a
             WHERE a.gameId = :gameId OR a.gameId IS NULL
            """)
    List<AchievementEntity> findApplicableTo(@Param("gameId") UUID gameId);
}
