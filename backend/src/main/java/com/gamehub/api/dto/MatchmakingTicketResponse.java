package com.gamehub.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Current state of a matchmaking ticket.
 *
 * @param estimatedWaitSeconds a hint derived from recent matches in this
 *                             bucket, not a promise. Returned so the client
 *                             can show progress instead of an indeterminate
 *                             spinner, which is what makes queueing tolerable.
 */
public record MatchmakingTicketResponse(
        UUID ticketId,
        String status,
        UUID gameId,
        String region,
        int skillRating,
        Instant enqueuedAt,
        Instant expiresAt,
        long waitedSeconds,
        Long estimatedWaitSeconds,
        UUID matchId,
        List<UUID> opponentIds) {
}
