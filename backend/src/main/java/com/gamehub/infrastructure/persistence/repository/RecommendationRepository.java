package com.gamehub.infrastructure.persistence.repository;

import com.gamehub.infrastructure.persistence.entity.RecommendationEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface RecommendationRepository
        extends JpaRepository<RecommendationEntity, RecommendationEntity.RecommendationId> {

    List<RecommendationEntity> findByUserIdOrderByScoreDesc(UUID userId, Pageable pageable);

    /**
     * Replaces one recommendation.
     *
     * <p>Unconditional upsert, unlike the leaderboard. A recommendation is a
     * current opinion rather than an accumulating achievement, so the newest
     * computation always wins and there is nothing to protect from being
     * overwritten.
     */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO recommendations (user_id, game_id, score, reason, algorithm, generated_at)
            VALUES (:userId, :gameId, :score, :reason, :algorithm, now())
            ON CONFLICT (user_id, game_id) DO UPDATE
               SET score = EXCLUDED.score,
                   reason = EXCLUDED.reason,
                   algorithm = EXCLUDED.algorithm,
                   generated_at = now()
            """, nativeQuery = true)
    int upsert(@Param("userId") UUID userId,
               @Param("gameId") UUID gameId,
               @Param("score") BigDecimal score,
               @Param("reason") String reason,
               @Param("algorithm") String algorithm);

    /**
     * Clears a stale set before writing a fresh one, so a game that has
     * dropped out of the top N does not linger forever from an earlier run.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM RecommendationEntity r WHERE r.userId = :userId")
    int deleteAllForUser(@Param("userId") UUID userId);
}
