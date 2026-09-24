package com.gamehub.domain.event;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.Instant;
import java.util.UUID;

/**
 * Sealed root of every event GameHub publishes.
 *
 * <p>Sealed rather than open: a consumer switch over these is checked by the
 * compiler, so adding an event type breaks the build at every place that has
 * to decide what to do about it. An open hierarchy would let a new event be
 * added and silently ignored by three consumers.
 *
 * <p>Every event carries its own {@link #eventId()}. That id is the
 * idempotency key: consumers record it in processed_events and skip replays.
 * It is minted once, when the row is written to the outbox, and never
 * regenerated on retry. Regenerating it would defeat the entire mechanism.
 *
 * <p>Jackson polymorphic typing is by a logical name, not by Java class name.
 * Class names leak package structure into the wire format and make a package
 * rename a breaking change for every consumer and every message already
 * sitting in a topic.
 */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "eventType")
@JsonSubTypes({
        @JsonSubTypes.Type(value = GameStartedEvent.class, name = "GAME_STARTED"),
        @JsonSubTypes.Type(value = GameCompletedEvent.class, name = "GAME_COMPLETED"),
        @JsonSubTypes.Type(value = AchievementUnlockedEvent.class, name = "ACHIEVEMENT_UNLOCKED"),
        @JsonSubTypes.Type(value = MatchCreatedEvent.class, name = "MATCH_CREATED"),
        @JsonSubTypes.Type(value = CloudSaveUpdatedEvent.class, name = "CLOUD_SAVE_UPDATED")
})
public sealed interface GameHubEvent
        permits GameStartedEvent, GameCompletedEvent, AchievementUnlockedEvent,
                MatchCreatedEvent, CloudSaveUpdatedEvent {

    /** Idempotency key. Stable across every retry of the same logical event. */
    UUID eventId();

    /** Domain type, matching the game_events.event_type check constraint. */
    EventType type();

    /** When the fact happened, not when it was published. */
    Instant occurredAt();

    /**
     * Kafka partition key.
     *
     * <p>Almost always the user id. Kafka guarantees ordering only within a
     * partition, and the only ordering GameHub actually needs is per-player:
     * one player cannot complete a game before starting it. Keying by user
     * buys that guarantee while still letting the topic scale across
     * partitions, which a single global ordering never could.
     */
    String partitionKey();

    /**
     * Schema version, carried as a Kafka header.
     *
     * <p>Lets a consumer reject or upcast a payload shape it does not
     * understand, instead of deserialising it into a half-populated object and
     * acting on the result.
     */
    default short schemaVersion() {
        return 1;
    }
}
