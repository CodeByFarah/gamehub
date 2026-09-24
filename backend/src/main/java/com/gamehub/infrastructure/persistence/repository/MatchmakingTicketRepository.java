package com.gamehub.infrastructure.persistence.repository;

import com.gamehub.domain.common.Region;
import com.gamehub.infrastructure.persistence.entity.MatchmakingTicketEntity;
import com.gamehub.infrastructure.persistence.entity.MatchmakingTicketEntity.TicketStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MatchmakingTicketRepository extends JpaRepository<MatchmakingTicketEntity, UUID> {

    Optional<MatchmakingTicketEntity> findByUserIdAndStatus(UUID userId, TicketStatus status);

    /**
     * The waiting pool for one bucket, oldest first.
     *
     * <p>Oldest first so that when the batch limit truncates the pool, the
     * tickets dropped are the freshest. Truncating the other way would starve
     * exactly the players who have already waited longest.
     *
     * <p>Served by idx_mmq_bucket, a partial index over WAITING rows.
     */
    List<MatchmakingTicketEntity> findByGameIdAndRegionAndStatusOrderByEnqueuedAtAsc(
            UUID gameId, Region region, TicketStatus status, Pageable pageable);

    long countByStatus(TicketStatus status);

    /**
     * Atomically claims exactly the tickets named, and only those still WAITING.
     *
     * <h2>The race this closes</h2>
     * The matchmaking engine works from an in-memory snapshot. Between taking
     * that snapshot and committing a pairing, a player may have cancelled, or
     * a second instance may have matched them into a different game. Writing
     * the two tickets without re-checking would put one player in two matches.
     *
     * <p>This statement re-asserts the precondition as part of the write. The
     * caller compares the returned count with the number of tickets it meant
     * to claim; anything less means the proposal is stale and must be
     * abandoned, leaving the surviving ticket for the next tick.
     *
     * <p>A partial claim never becomes visible, because the caller runs this
     * inside a transaction and rolls back when the count does not match.
     *
     * @return how many tickets were actually transitioned
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE matchmaking_queue
               SET status = 'MATCHED',
                   match_id = :matchId,
                   matched_at = now()
             WHERE id IN (:ticketIds)
               AND status = 'WAITING'
            """, nativeQuery = true)
    int claimTicketsForMatch(@Param("ticketIds") List<UUID> ticketIds,
                             @Param("matchId") UUID matchId);

    @Modifying
    @Transactional
    @Query(value = """
            UPDATE matchmaking_queue
               SET status = 'CANCELLED'
             WHERE user_id = :userId
               AND status = 'WAITING'
            """, nativeQuery = true)
    int cancelWaitingForUser(@Param("userId") UUID userId);

    /**
     * Expiry sweep. A ticket reaching this point means the population for its
     * bucket was too thin for the acceptance threshold to ever match it, which
     * is worth surfacing rather than retrying forever.
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE matchmaking_queue
               SET status = 'EXPIRED'
             WHERE status = 'WAITING'
               AND expires_at < :now
            """, nativeQuery = true)
    int expireStaleTickets(@Param("now") Instant now);

    /** Distinct active buckets, so a tick only visits queues that exist. */
    @Query(value = """
            SELECT DISTINCT game_id, region FROM matchmaking_queue
             WHERE status = 'WAITING'
            """, nativeQuery = true)
    List<Object[]> findActiveBuckets();
}
