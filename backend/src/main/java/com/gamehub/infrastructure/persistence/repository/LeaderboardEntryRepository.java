package com.gamehub.infrastructure.persistence.repository;

import com.gamehub.infrastructure.persistence.entity.LeaderboardEntryEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface LeaderboardEntryRepository
        extends JpaRepository<LeaderboardEntryEntity, LeaderboardEntryEntity.LeaderboardEntryId> {

    /**
     * Records a score, keeping only a personal best.
     *
     * <h2>Why the WHERE clause on the DO UPDATE</h2>
     * A plain upsert would let a later, worse score overwrite a better one,
     * because events arrive in the order Kafka delivers them and a retry can
     * replay an old score after a newer one landed. The predicate makes the
     * write monotonic: a score is only accepted if it beats the stored one.
     *
     * <p>Monotonicity is also what makes this operation idempotent. Replaying
     * the same event any number of times leaves the row unchanged after the
     * first application, so a duplicate Kafka delivery cannot corrupt a
     * standing even if the idempotency ledger were bypassed.
     *
     * <p>achieved_at is only moved when the score genuinely improves, so the
     * tie-break dimension keeps recording when the personal best was set
     * rather than when it was last confirmed.
     *
     * @return 1 if the standing changed, 0 if the existing score was better
     */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO leaderboard_entries (leaderboard_id, user_id, score, achieved_at, updated_at)
            VALUES (:leaderboardId, :userId, :score, :achievedAt, now())
            ON CONFLICT (leaderboard_id, user_id) DO UPDATE
               SET score = EXCLUDED.score,
                   achieved_at = EXCLUDED.achieved_at,
                   updated_at = now()
             WHERE leaderboard_entries.score < EXCLUDED.score
            """, nativeQuery = true)
    int upsertIfBetter(@Param("leaderboardId") UUID leaderboardId,
                       @Param("userId") UUID userId,
                       @Param("score") long score,
                       @Param("achievedAt") Instant achievedAt);

    /**
     * Ordered page used to rebuild a Redis sorted set after cache loss.
     *
     * <p>The ordering matches idx_leaderboard_entries_rank exactly, so the
     * rebuild is a single index scan with no sort node.
     */
    List<LeaderboardEntryEntity> findByLeaderboardIdOrderByScoreDescAchievedAtAsc(
            UUID leaderboardId, Pageable pageable);

    /**
     * Rank from Postgres alone, used when Redis is unavailable.
     *
     * <p>COUNT of strictly better entries, plus one. Deliberately not
     * window-function RANK(), which would have to materialise and number the
     * whole leaderboard to answer a question about one row. This counts index
     * entries and stops.
     *
     * <p>Still O(number of better players), which is why it is the fallback
     * and not the primary path. Redis ZREVRANK answers the same question in
     * O(log N). The fallback exists so Redis being down costs latency rather
     * than an error.
     */
    @Query(value = """
            SELECT COUNT(*) + 1 FROM leaderboard_entries e
             WHERE e.leaderboard_id = :leaderboardId
               AND (e.score > :score
                    OR (e.score = :score AND e.achieved_at < :achievedAt))
            """, nativeQuery = true)
    long computeRank(@Param("leaderboardId") UUID leaderboardId,
                     @Param("score") long score,
                     @Param("achievedAt") Instant achievedAt);

    long countByLeaderboardId(UUID leaderboardId);
}
