package com.gamehub.domain.event;

/**
 * Event types and the topic each is published to.
 *
 * <p>Topic names carry an explicit version suffix. When an incompatible schema
 * change is unavoidable, the new shape goes to {@code .v2} and both topics run
 * side by side until every consumer has moved. Mutating the schema of a live
 * topic in place is the change that cannot be rolled back, because the
 * messages already written cannot be un-written.
 */
public enum EventType {

    GAME_STARTED(Topics.GAME_EVENTS),
    GAME_COMPLETED(Topics.GAME_EVENTS),
    ACHIEVEMENT_UNLOCKED(Topics.ACHIEVEMENT_EVENTS),
    MATCH_CREATED(Topics.MATCH_EVENTS),
    CLOUD_SAVE_UPDATED(Topics.CLOUD_SAVE_EVENTS);

    private final String topic;

    EventType(String topic) {
        this.topic = topic;
    }

    public String topic() {
        return topic;
    }

    /**
     * Topic names, also consumed by infra/docker/kafka/create-topics.sh.
     *
     * <p>GAME_STARTED and GAME_COMPLETED deliberately share one topic. They
     * describe the same aggregate and must stay ordered relative to one
     * another for a given player; splitting them across topics would throw
     * that ordering away for no benefit.
     */
    public static final class Topics {
        public static final String GAME_EVENTS = "gamehub.game.events.v1";
        public static final String ACHIEVEMENT_EVENTS = "gamehub.achievement.events.v1";
        public static final String MATCH_EVENTS = "gamehub.match.events.v1";
        public static final String CLOUD_SAVE_EVENTS = "gamehub.cloudsave.events.v1";

        /** Suffix appended by the error handler when retries are exhausted. */
        public static final String DLT_SUFFIX = ".DLT";

        private Topics() {
        }
    }
}
