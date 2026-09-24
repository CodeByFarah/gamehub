package com.gamehub.domain.matchmaking;

import java.util.List;
import java.util.UUID;

/**
 * A pairing the engine has selected but that has not yet been committed.
 *
 * <p>Separate from a persisted match on purpose. The engine runs entirely in
 * memory over a snapshot of the queue, which means the world may have moved on
 * by the time it produces output: one of these players may have cancelled, or
 * been matched by another instance. A {@code ProposedMatch} is therefore a
 * recommendation, and it only becomes a match once
 * {@code MatchCommitService} atomically claims both tickets. See
 * docs/matchmaking.md for why that claim has to be atomic.
 *
 * @param gameId  the game both tickets queued for
 * @param tickets the participants, exactly two in the current 1v1 model
 * @param cost    the cost the scorer assigned, persisted as match_quality so
 *                degradation is measurable rather than anecdotal
 */
public record ProposedMatch(UUID gameId, List<MatchTicket> tickets, double cost) {

    public ProposedMatch {
        if (gameId == null) {
            throw new IllegalArgumentException("gameId is required");
        }
        if (tickets == null || tickets.size() != 2) {
            throw new IllegalArgumentException(
                    "a proposed match holds exactly two tickets, got "
                            + (tickets == null ? "null" : tickets.size()));
        }
        if (tickets.get(0).userId().equals(tickets.get(1).userId())) {
            throw new IllegalArgumentException(
                    "a player cannot be matched against themselves: "
                            + tickets.get(0).userId());
        }
        if (!tickets.get(0).gameId().equals(gameId) || !tickets.get(1).gameId().equals(gameId)) {
            throw new IllegalArgumentException(
                    "both tickets must belong to game " + gameId);
        }
        tickets = List.copyOf(tickets);
    }

    /** Ticket ids to claim, in a stable order. */
    public List<UUID> ticketIds() {
        return List.of(tickets.get(0).ticketId(), tickets.get(1).ticketId());
    }

    public List<UUID> userIds() {
        return List.of(tickets.get(0).userId(), tickets.get(1).userId());
    }
}
