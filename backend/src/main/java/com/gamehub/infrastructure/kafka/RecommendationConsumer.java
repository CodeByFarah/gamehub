package com.gamehub.infrastructure.kafka;

import com.gamehub.application.service.LeaderboardService;
import com.gamehub.domain.event.EventType;
import com.gamehub.domain.event.GameCompletedEvent;
import com.gamehub.domain.event.GameHubEvent;
import com.gamehub.domain.recommendation.RecommendationScorer;
import com.gamehub.domain.recommendation.RecommendationScorer.Candidate;
import com.gamehub.domain.recommendation.RecommendationScorer.PlayerTaste;
import com.gamehub.domain.recommendation.RecommendationScorer.ScoredCandidate;
import com.gamehub.infrastructure.persistence.entity.GameEntity;
import com.gamehub.infrastructure.persistence.entity.GameEntity.GameStatus;
import com.gamehub.infrastructure.persistence.entity.GameSessionEntity;
import com.gamehub.infrastructure.persistence.entity.UserProfileEntity;
import com.gamehub.infrastructure.persistence.repository.GameRepository;
import com.gamehub.infrastructure.persistence.repository.GameSessionRepository;
import com.gamehub.infrastructure.persistence.repository.RecommendationRepository;
import com.gamehub.infrastructure.persistence.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Regenerates a player recommendations after each completed game, and submits
 * the score to the leaderboards.
 *
 * <h2>Why regenerate here rather than at read time</h2>
 * The Home screen must render from one indexed lookup. Scoring the catalogue
 * on every request would put an O(catalogue) computation on the hottest read
 * in the product, and would repeat identical work for a player whose taste has
 * not changed.
 *
 * <p>A completed game is the natural trigger: it is the only moment a player
 * taste actually changes.
 *
 * <h2>Why leaderboard submission lives here too</h2>
 * It could be its own consumer. It is not, because it needs exactly the same
 * event and no extra state, and a fourth consumer group would triple the
 * ledger writes for one ZADD. This is the pragmatic boundary rather than the
 * theoretically pure one, and it can be split later without changing the
 * event.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecommendationConsumer {

    static final String GROUP = "gamehub-recommendations";

    /** Recent sessions that define current taste. */
    private static final int TASTE_WINDOW = 20;

    /** Candidates scored per regeneration. */
    private static final int CANDIDATE_POOL = 200;

    /** Recommendations persisted per player. */
    private static final int KEEP = 20;

    private final IdempotentConsumer idempotency;
    private final GameSessionRepository sessions;
    private final GameRepository games;
    private final RecommendationRepository recommendations;
    private final UserProfileRepository profiles;
    private final LeaderboardService leaderboards;

    @KafkaListener(
            topics = EventType.Topics.GAME_EVENTS,
            groupId = GROUP,
            containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void onGameEvent(GameHubEvent event) {
        if (!(event instanceof GameCompletedEvent completed)) {
            return;
        }
        idempotency.runOnce(completed.eventId(), GROUP, eventId -> handle(completed));
    }

    private void handle(GameCompletedEvent event) {
        submitToLeaderboards(event);
        regenerate(event.userId());
    }

    /**
     * Submits the score to the game, regional and global leaderboards.
     *
     * <p>The underlying write keeps only a personal best and is therefore
     * idempotent, so a replay cannot demote a player even if the ledger were
     * bypassed.
     */
    private void submitToLeaderboards(GameCompletedEvent event) {
        UserProfileEntity profile = profiles.findById(event.userId()).orElse(null);
        if (profile == null) {
            log.warn("cannot submit score for missing profile {}", event.userId());
            return;
        }
        if (event.score() <= 0) {
            // A zero score is not a standing. Writing it would fill the
            // leaderboard with players who have never scored.
            return;
        }

        leaderboards.submitScore(event.userId(), event.gameId(),
                profile.getRegion(), event.score(), event.occurredAt());
    }

    private void regenerate(UUID userId) {
        List<GameSessionEntity> recent = sessions
                .findByUserIdOrderByStartedAtDesc(userId, PageRequest.of(0, TASTE_WINDOW))
                .getContent();

        if (recent.isEmpty()) {
            return;
        }

        Set<UUID> playedIds = recent.stream()
                .map(GameSessionEntity::getGameId)
                .collect(Collectors.toCollection(HashSet::new));

        List<GameEntity> playedGames = games.findByIdInAndStatus(
                List.copyOf(playedIds), GameStatus.PUBLISHED);

        Set<String> tags = new HashSet<>();
        Set<String> genres = new HashSet<>();
        for (GameEntity game : playedGames) {
            tags.addAll(game.getTags());
            genres.add(game.getGenre());
        }

        PlayerTaste taste = new PlayerTaste(tags, genres, playedIds);

        // Candidate pool ordered by popularity. Scoring the entire catalogue
        // would be correct but wasteful: a game nobody plays is unlikely to
        // beat 200 popular ones on a score that already includes popularity.
        List<GameEntity> pool = games.findByStatusOrderByPopularityScoreDesc(
                GameStatus.PUBLISHED, PageRequest.of(0, CANDIDATE_POOL)).getContent();

        if (pool.isEmpty()) {
            return;
        }

        int maxPopularity = pool.stream()
                .mapToInt(GameEntity::getPopularityScore)
                .max()
                .orElse(0);

        List<Candidate> candidates = pool.stream()
                .map(RecommendationConsumer::toCandidate)
                .toList();

        List<ScoredCandidate> ranked =
                RecommendationScorer.rank(taste, candidates, maxPopularity, KEEP);

        if (ranked.isEmpty()) {
            return;
        }

        // Clear then rewrite, so a game that has dropped out of the top N does
        // not linger from an earlier run. Both statements share one
        // transaction, so a reader never observes an empty list mid-update.
        recommendations.deleteAllForUser(userId);

        for (ScoredCandidate scored : ranked) {
            recommendations.upsert(userId, scored.gameId(),
                    BigDecimal.valueOf(scored.score()), scored.reason(), "CONTENT_BASED_V1");
        }

        log.debug("regenerated {} recommendations for user {}", ranked.size(), userId);
    }

    private static Candidate toCandidate(GameEntity game) {
        // A game with no release date is treated as maximally old, so the
        // recency term contributes nothing rather than defaulting to a boost
        // it has not earned.
        int daysSinceRelease = game.getReleasedAt() == null
                ? Integer.MAX_VALUE
                : (int) Math.min(Integer.MAX_VALUE,
                        ChronoUnit.DAYS.between(game.getReleasedAt(), LocalDate.now()));

        return new Candidate(
                game.getId(),
                game.getTitle(),
                game.getGenre(),
                Set.copyOf(game.getTags()),
                game.getPopularityScore(),
                Math.max(daysSinceRelease, 0));
    }
}
