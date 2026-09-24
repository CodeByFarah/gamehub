package com.gamehub.domain.event;

import java.time.Instant;
import java.util.UUID;

/** A player opened a session. Consumed by statistics for concurrency metrics. */
public record GameStartedEvent(
        UUID eventId,
        Instant occurredAt,
        UUID sessionId,
        UUID userId,
        UUID gameId,
        UUID matchId,
        String clientVersion) implements GameHubEvent {

    public GameStartedEvent {
        if (eventId == null || sessionId == null || userId == null || gameId == null) {
            throw new IllegalArgumentException(
                    "eventId, sessionId, userId and gameId are required");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt is required");
        }
    }

    @Override
    public EventType type() {
        return EventType.GAME_STARTED;
    }

    @Override
    public String partitionKey() {
        return userId.toString();
    }
}
