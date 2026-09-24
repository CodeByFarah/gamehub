package com.gamehub.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * A player unlocked an achievement.
 *
 * <p>Emitted by the achievement consumer, which makes this the one event in
 * the system produced by a consumer rather than by an API call. That is
 * deliberate: achievements are derived state, and deriving them in the
 * consumer keeps the rule in one place instead of duplicating it in every
 * endpoint that could possibly trigger an unlock.
 *
 * <p>It carries {@code causedByEventId}, the id of the GameCompleted event
 * that triggered it. Without that link, a replayed source event would produce
 * a second unlock event with a fresh id, which downstream consumers would have
 * no way to recognise as a duplicate.
 */
public record AchievementUnlockedEvent(
        UUID eventId,
        Instant occurredAt,
        UUID userId,
        UUID achievementId,
        String achievementCode,
        int points,
        String rarity,
        UUID causedByEventId) implements GameHubEvent {

    public AchievementUnlockedEvent {
        if (eventId == null || userId == null || achievementId == null) {
            throw new IllegalArgumentException(
                    "eventId, userId and achievementId are required");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt is required");
        }
        if (achievementCode == null || achievementCode.isBlank()) {
            throw new IllegalArgumentException("achievementCode is required");
        }
    }

    @Override
    public EventType type() {
        return EventType.ACHIEVEMENT_UNLOCKED;
    }

    @Override
    public String partitionKey() {
        return userId.toString();
    }
}
