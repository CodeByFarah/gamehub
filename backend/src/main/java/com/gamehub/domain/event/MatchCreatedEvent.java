package com.gamehub.domain.event;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The matchmaker committed a pairing.
 *
 * <p>Partitioned by match id rather than by user, because this event concerns
 * two players and neither has a stronger claim to it. The per-user ordering
 * guarantee does not apply here and is not needed: nothing downstream orders
 * match creation against a player other events.
 *
 * @param matchQuality the accepted cost. Persisted and dashboarded so that
 *                     degradation in match quality is measurable.
 * @param waitSeconds  how long the longest-waiting participant queued. The
 *                     number players actually feel.
 */
public record MatchCreatedEvent(
        UUID eventId,
        Instant occurredAt,
        UUID matchId,
        UUID gameId,
        String region,
        List<UUID> userIds,
        double matchQuality,
        long waitSeconds) implements GameHubEvent {

    public MatchCreatedEvent {
        if (eventId == null || matchId == null || gameId == null) {
            throw new IllegalArgumentException("eventId, matchId and gameId are required");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt is required");
        }
        if (userIds == null || userIds.size() < 2) {
            throw new IllegalArgumentException("a match needs at least two participants");
        }
        if (userIds.size() != Set.copyOf(userIds).size()) {
            throw new IllegalArgumentException(
                    "the same player appears twice in match " + matchId);
        }
        userIds = List.copyOf(userIds);
    }

    @Override
    public EventType type() {
        return EventType.MATCH_CREATED;
    }

    @Override
    public String partitionKey() {
        return matchId.toString();
    }
}
