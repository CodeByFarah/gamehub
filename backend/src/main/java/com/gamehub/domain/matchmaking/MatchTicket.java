package com.gamehub.domain.matchmaking;

import com.gamehub.domain.common.Region;

import java.time.Instant;
import java.util.UUID;

/**
 * One queued request to be matched, as the matchmaking algorithm sees it.
 *
 * <p>Deliberately a plain immutable record with no JPA or Redis annotations.
 * The matchmaking algorithm is the part of this system most worth testing
 * exhaustively, and it can only be tested exhaustively if constructing its
 * inputs costs nothing. Adapters in {@code infrastructure} translate database
 * rows and Redis hashes into this shape.
 *
 * @param ticketId    identity of this queue entry, not of the player
 * @param userId      the player
 * @param gameId      the game being queued for
 * @param region      where the player is routed
 * @param skillRating Elo-style rating at enqueue time
 * @param latencyMs   client-measured RTT to its nearest edge, in milliseconds
 * @param enqueuedAt  when the player joined the queue
 */
public record MatchTicket(
        UUID ticketId,
        UUID userId,
        UUID gameId,
        Region region,
        int skillRating,
        int latencyMs,
        Instant enqueuedAt) {

    public MatchTicket {
        if (ticketId == null || userId == null || gameId == null) {
            throw new IllegalArgumentException("ticketId, userId and gameId are required");
        }
        if (region == null) {
            throw new IllegalArgumentException("region is required");
        }
        if (enqueuedAt == null) {
            throw new IllegalArgumentException("enqueuedAt is required");
        }
        if (skillRating < 0) {
            throw new IllegalArgumentException("skillRating must not be negative");
        }
        if (latencyMs < 0) {
            throw new IllegalArgumentException("latencyMs must not be negative");
        }
    }

    /**
     * Seconds this ticket has been waiting as of {@code now}.
     *
     * <p>Clamped at zero. Clock skew between the instance that enqueued the
     * ticket and the instance running the matchmaker tick can otherwise produce
     * a negative wait, which would flow into the cost function as a negative
     * patience bonus and make a fresh ticket look less urgent than a stale one.
     */
    public long waitSeconds(Instant now) {
        long elapsed = now.getEpochSecond() - enqueuedAt.getEpochSecond();
        return Math.max(0L, elapsed);
    }
}
