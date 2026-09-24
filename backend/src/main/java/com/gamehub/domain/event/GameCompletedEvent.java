package com.gamehub.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * A player finished a session.
 *
 * <p>The fan-out event of the whole system: statistics, achievements and
 * recommendations all consume it independently. That is the reason for the
 * event bus at all. Doing this work inline would tie the latency of ending a
 * game to three subsystems, any of which could be slow or down.
 *
 * <p>The payload is self-contained: a consumer never has to read back the
 * session row to do its job. That keeps consumers decoupled from the write
 * model, and it means replaying a topic reproduces the same results even if
 * the source row has since been deleted.
 *
 * @param score    points earned, already validated against the leaderboard
 *                 codec range at the API edge
 * @param outcome  WIN, LOSS, DRAW or ABANDONED
 * @param perfect  whether the session met the perfect-match criteria, computed
 *                 once by the producer rather than re-derived by each consumer
 *                 from rules that could drift apart
 */
public record GameCompletedEvent(
        UUID eventId,
        Instant occurredAt,
        UUID sessionId,
        UUID userId,
        UUID gameId,
        UUID matchId,
        int score,
        int durationSeconds,
        String outcome,
        boolean perfect) implements GameHubEvent {

    public GameCompletedEvent {
        if (eventId == null || sessionId == null || userId == null || gameId == null) {
            throw new IllegalArgumentException(
                    "eventId, sessionId, userId and gameId are required");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt is required");
        }
        if (score < 0) {
            throw new IllegalArgumentException("score must not be negative, got " + score);
        }
        if (durationSeconds < 0) {
            throw new IllegalArgumentException("durationSeconds must not be negative");
        }
        if (outcome == null || outcome.isBlank()) {
            throw new IllegalArgumentException("outcome is required");
        }
    }

    @Override
    public EventType type() {
        return EventType.GAME_COMPLETED;
    }

    @Override
    public String partitionKey() {
        return userId.toString();
    }

    public boolean isWin() {
        return "WIN".equals(outcome);
    }
}
