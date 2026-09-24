package com.gamehub.infrastructure.kafka;

import com.gamehub.domain.event.EventType;
import com.gamehub.domain.event.GameCompletedEvent;
import com.gamehub.domain.event.GameHubEvent;
import com.gamehub.infrastructure.persistence.repository.GameRepository;
import com.gamehub.infrastructure.persistence.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maintains player statistics and game popularity.
 *
 * <h2>Why this is not done inline when a session completes</h2>
 * Three subsystems react to one completed game. Doing them inline would make
 * ending a game as slow as the slowest of them and as available as the least
 * available. Here, a statistics outage delays a counter; it does not stop
 * anyone playing.
 *
 * <h2>Consistency</h2>
 * Eventually consistent, on purpose. A player who finishes a game and
 * immediately opens their profile may see the previous count. The window is
 * the relay poll plus consumer lag, typically well under a second, and the
 * alternative is coupling the write path to three consumers.
 *
 * <p>Where that lag would be visible and jarring, the API does not rely on it:
 * the session response returns the completed session directly rather than
 * re-reading a derived counter.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StatisticsConsumer {

    static final String GROUP = "gamehub-statistics";

    /**
     * XP per completed game.
     *
     * <p>Flat per game plus a win bonus, rather than scaled by score. Scaling
     * by score would let a single high-scoring game outweigh weeks of play and
     * would make XP a function of which games award large numbers, which is a
     * property of the game and not of the player.
     */
    private static final int XP_PER_GAME = 10;
    private static final int XP_WIN_BONUS = 15;

    /** Popularity increment per session, the input to the discovery ordering. */
    private static final int POPULARITY_PER_SESSION = 1;

    private final IdempotentConsumer idempotency;
    private final UserProfileRepository profiles;
    private final GameRepository games;

    @KafkaListener(
            topics = EventType.Topics.GAME_EVENTS,
            groupId = GROUP,
            containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void onGameEvent(GameHubEvent event) {
        // The topic carries both GameStarted and GameCompleted. Only the
        // latter changes statistics; started events are consumed and ignored
        // here rather than routed to a separate topic, because the two must
        // stay ordered relative to one another per player.
        if (!(event instanceof GameCompletedEvent completed)) {
            return;
        }

        idempotency.runOnce(completed.eventId(), GROUP, eventId -> apply(completed));
    }

    private void apply(GameCompletedEvent event) {
        // Atomic increments rather than load-modify-save. Two events for the
        // same player can be processed concurrently on different partitions,
        // and a read-then-write would lose one of them under exactly the load
        // that makes the numbers worth having.
        int xpGained = XP_PER_GAME + (event.isWin() ? XP_WIN_BONUS : 0);

        int updated = profiles.applyCompletedGame(
                event.userId(),
                event.isWin() ? 1 : 0,
                event.durationSeconds(),
                xpGained);

        if (updated == 0) {
            // The player was deleted between the event and its processing.
            // Not an error and not retryable: dropping it is correct, and
            // throwing would send a legitimate event to the dead-letter topic.
            log.warn("statistics event {} references a profile that no longer exists: {}",
                    event.eventId(), event.userId());
            return;
        }

        games.incrementPopularity(event.gameId(), POPULARITY_PER_SESSION);

        log.debug("applied statistics for user {} game {} ({} xp)",
                event.userId(), event.gameId(), xpGained);
    }
}
