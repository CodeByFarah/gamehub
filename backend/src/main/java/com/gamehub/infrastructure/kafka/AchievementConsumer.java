package com.gamehub.infrastructure.kafka;

import com.gamehub.application.event.OutboxEventPublisher;
import com.gamehub.domain.achievement.AchievementEvaluator;
import com.gamehub.domain.achievement.AchievementEvaluator.PlayerSnapshot;
import com.gamehub.domain.achievement.AchievementEvaluator.Rule;
import com.gamehub.domain.achievement.AchievementEvaluator.TriggerType;
import com.gamehub.domain.event.AchievementUnlockedEvent;
import com.gamehub.domain.event.EventType;
import com.gamehub.domain.event.GameCompletedEvent;
import com.gamehub.domain.event.GameHubEvent;
import com.gamehub.infrastructure.persistence.entity.AchievementEntity;
import com.gamehub.infrastructure.persistence.entity.UserProfileEntity;
import com.gamehub.infrastructure.persistence.repository.AchievementRepository;
import com.gamehub.infrastructure.persistence.repository.UserAchievementRepository;
import com.gamehub.infrastructure.persistence.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Unlocks achievements from completed games.
 *
 * <h2>Two independent idempotency layers</h2>
 * Awarding the same achievement twice is visible to the player and awkward to
 * undo, so it is guarded twice over:
 *
 * <ol>
 *   <li>The {@code processed_events} ledger stops the event being handled a
 *       second time at all.</li>
 *   <li>The composite primary key on {@code user_achievements}, reached
 *       through an insert that ignores conflicts, stops a duplicate unlock
 *       even if the first layer were somehow bypassed.</li>
 * </ol>
 *
 * <p>The second layer is also what makes concurrency safe within one run: two
 * threads evaluating the same player cannot both insert.
 *
 * <h2>Ordering relative to statistics</h2>
 * This consumer reads the profile to evaluate cumulative rules such as
 * TEN_WINS, and the statistics consumer is what updates that profile. Both
 * consume the same partition independently, so this one may run first and see
 * a count one behind.
 *
 * <p>That is accepted rather than coordinated: the achievement then unlocks on
 * the next qualifying game, at most one game late. The alternative, chaining
 * this consumer behind statistics, would couple two subsystems and make each
 * one an availability dependency of the other, which is the thing the event
 * bus exists to avoid.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AchievementConsumer {

    static final String GROUP = "gamehub-achievements";

    private final IdempotentConsumer idempotency;
    private final AchievementRepository achievements;
    private final UserAchievementRepository unlocked;
    private final UserProfileRepository profiles;
    private final OutboxEventPublisher events;

    @KafkaListener(
            topics = EventType.Topics.GAME_EVENTS,
            groupId = GROUP,
            containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void onGameEvent(GameHubEvent event) {
        if (!(event instanceof GameCompletedEvent completed)) {
            return;
        }
        idempotency.runOnce(completed.eventId(), GROUP, eventId -> evaluate(completed));
    }

    private void evaluate(GameCompletedEvent event) {
        UserProfileEntity profile = profiles.findById(event.userId()).orElse(null);
        if (profile == null) {
            log.warn("achievement event {} references a missing profile {}",
                    event.eventId(), event.userId());
            return;
        }

        // Everything that could apply, in one query: the achievements defined
        // for this game plus the platform-wide ones.
        List<AchievementEntity> applicable = achievements.findApplicableTo(event.gameId());
        if (applicable.isEmpty()) {
            return;
        }

        Map<UUID, AchievementEntity> byId = applicable.stream()
                .collect(java.util.stream.Collectors.toMap(AchievementEntity::getId, a -> a));

        PlayerSnapshot snapshot = new PlayerSnapshot(
                profile.getGamesPlayed(),
                profile.getGamesWon(),
                profile.getLevel(),
                event.score(),
                event.durationSeconds(),
                event.perfect());

        List<Rule> rules = applicable.stream()
                .map(a -> new Rule(a.getId(), a.getCode(),
                        TriggerType.valueOf(a.getTriggerType().name()), a.getTriggerThreshold()))
                .toList();

        for (Rule satisfied : AchievementEvaluator.evaluate(snapshot, rules)) {
            UUID achievementId = (UUID) satisfied.achievementId();

            // Insert-if-absent rather than exists-then-insert. The latter
            // races, and its failure mode is a primary key violation that
            // aborts the whole transaction, losing the other unlocks in the
            // same batch.
            int inserted = unlocked.unlockIfAbsent(
                    event.userId(), achievementId, event.eventId());

            if (inserted == 0) {
                // Already held. Not an error, and crucially not an event: a
                // second AchievementUnlocked would reach downstream consumers
                // as a genuine new unlock.
                continue;
            }

            AchievementEntity achievement = byId.get(achievementId);

            events.publish(new AchievementUnlockedEvent(
                            UUID.randomUUID(), Instant.now(), event.userId(), achievementId,
                            achievement.getCode(), achievement.getPoints(),
                            achievement.getRarity().name(),
                            // Links back to the event that caused this, so a
                            // replay is traceable rather than mysterious.
                            event.eventId()),
                    achievementId, event.userId(), event.gameId());

            log.info("player {} unlocked {}", event.userId(), achievement.getCode());
        }
    }
}
