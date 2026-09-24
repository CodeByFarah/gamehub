package com.gamehub.infrastructure.persistence.entity;

import com.gamehub.domain.common.Region;
import com.gamehub.domain.matchmaking.MatchTicket;
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

import java.time.Instant;
import java.util.UUID;

/**
 * A queued matchmaking request, mapped to matchmaking_queue.
 *
 * <p>The invariant that a player holds at most one WAITING ticket is enforced
 * by uq_mmq_one_active_ticket_per_user, a partial unique index. That is
 * deliberate rather than an application-level check: two concurrent join
 * requests landing on two instances would both pass a SELECT-then-INSERT
 * check, and only a database constraint can reject the second.
 */
@Entity
@Table(name = "matchmaking_queue")
@Getter
@Setter
@NoArgsConstructor
public class MatchmakingTicketEntity {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Region region;

    @Column(name = "skill_rating", nullable = false)
    private int skillRating;

    @Column(name = "latency_ms", nullable = false)
    private int latencyMs;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TicketStatus status = TicketStatus.WAITING;

    @Column(name = "enqueued_at", nullable = false)
    private Instant enqueuedAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "matched_at")
    private Instant matchedAt;

    @Column(name = "match_id")
    private UUID matchId;

    /** Projects into the pure domain type the matchmaking engine consumes. */
    public MatchTicket toDomain() {
        return new MatchTicket(id, userId, gameId, region, skillRating, latencyMs, enqueuedAt);
    }

    /**
     * Moves this ticket to MATCHED. Sets all three fields together because
     * ck_mmq_matched requires them to agree.
     */
    public void markMatched(UUID matchIdentifier, Instant at) {
        if (matchIdentifier == null || at == null) {
            throw new IllegalArgumentException("matching a ticket needs a match id and an instant");
        }
        this.status = TicketStatus.MATCHED;
        this.matchId = matchIdentifier;
        this.matchedAt = at;
    }

    public boolean isWaiting() {
        return status == TicketStatus.WAITING;
    }

    public enum TicketStatus {
        WAITING, MATCHED, CANCELLED, EXPIRED
    }
}
