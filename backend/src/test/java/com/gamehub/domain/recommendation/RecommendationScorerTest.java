package com.gamehub.domain.recommendation;

import com.gamehub.domain.recommendation.RecommendationScorer.Candidate;
import com.gamehub.domain.recommendation.RecommendationScorer.PlayerTaste;
import com.gamehub.domain.recommendation.RecommendationScorer.ScoredCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RecommendationScorerTest {

    private static final UUID PLAYED = UUID.fromString("11111111-0000-0000-0000-000000000001");
    private static final UUID MATCHING = UUID.fromString("22222222-0000-0000-0000-000000000002");
    private static final UUID UNRELATED = UUID.fromString("33333333-0000-0000-0000-000000000003");

    private static Candidate candidate(UUID id, String genre, Set<String> tags,
                                       int popularity, int daysOld) {
        return new Candidate(id, "Game " + id, genre, tags, popularity, daysOld);
    }

    @Nested
    @DisplayName("score range")
    class ScoreRange {

        @Test
        @DisplayName("a score never leaves the closed interval 0 to 1")
        void scoreStaysInRange() {
            // The column is NUMERIC(8,6) with a CHECK constraint, so a score
            // outside this range is not merely odd, it fails to persist.
            PlayerTaste taste = new PlayerTaste(
                    Set.of("competitive", "fast-paced"), Set.of("Racing"), Set.of());

            List<Candidate> candidates = List.of(
                    candidate(MATCHING, "Racing", Set.of("competitive", "fast-paced"),
                            Integer.MAX_VALUE, 0),
                    candidate(UNRELATED, "Puzzle", Set.of("relaxing"), 0, 100_000));

            for (ScoredCandidate scored :
                    RecommendationScorer.rank(taste, candidates, Integer.MAX_VALUE, 10)) {
                assertThat(scored.score()).isBetween(0.0d, 1.0d);
            }
        }

        @Test
        @DisplayName("a perfect match on every dimension scores 1.0")
        void perfectMatchScoresOne() {
            // Weights sum to 1, so a candidate maximising every term must
            // reach exactly 1.0. If this drifts, the weights no longer sum
            // correctly and scores stop being comparable across users.
            PlayerTaste taste = new PlayerTaste(
                    Set.of("competitive"), Set.of("Racing"), Set.of());

            List<ScoredCandidate> ranked = RecommendationScorer.rank(
                    taste,
                    List.of(candidate(MATCHING, "Racing", Set.of("competitive"), 100, 0)),
                    100, 10);

            assertThat(ranked).hasSize(1);
            assertThat(ranked.get(0).score()).isCloseTo(1.0d, within(0.000_001d));
        }
    }

    @Nested
    @DisplayName("filtering")
    class Filtering {

        @Test
        @DisplayName("a game already played is never recommended")
        void excludesAlreadyPlayed() {
            // The point is discovery. A list of games you own reads as broken.
            PlayerTaste taste = new PlayerTaste(
                    Set.of("competitive"), Set.of("Racing"), Set.of(PLAYED));

            assertThat(RecommendationScorer.rank(
                    taste,
                    List.of(candidate(PLAYED, "Racing", Set.of("competitive"), 100, 0)),
                    100, 10)).isEmpty();
        }

        @Test
        @DisplayName("a candidate matching nothing is dropped rather than ranked last")
        void dropsZeroScores() {
            PlayerTaste taste = new PlayerTaste(
                    Set.of("competitive"), Set.of("Racing"), Set.of());

            assertThat(RecommendationScorer.rank(
                    taste,
                    List.of(candidate(UNRELATED, "Puzzle", Set.of("relaxing"), 0, 100_000)),
                    0, 10)).isEmpty();
        }
    }

    @Nested
    @DisplayName("ranking")
    class Ranking {

        @Test
        @DisplayName("a closer tag match outranks a merely popular game")
        void tasteBeatsPopularity() {
            // Popularity carries only 0.15 weight, deliberately, so it acts as
            // a tie-break rather than collapsing the list into a bestseller
            // chart.
            PlayerTaste taste = new PlayerTaste(
                    Set.of("roguelike", "story-rich"), Set.of("RPG"), Set.of());

            List<Candidate> candidates = List.of(
                    candidate(MATCHING, "RPG", Set.of("roguelike", "story-rich"), 1, 30),
                    candidate(UNRELATED, "Sports", Set.of("competitive"), 10_000, 30));

            assertThat(RecommendationScorer.rank(taste, candidates, 10_000, 10)
                    .get(0).gameId()).isEqualTo(MATCHING);
        }

        @Test
        @DisplayName("results are capped at the requested limit")
        void respectsLimit() {
            PlayerTaste taste = new PlayerTaste(
                    Set.of("competitive"), Set.of("Racing"), Set.of());

            List<Candidate> many = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                many.add(candidate(UUID.randomUUID(), "Racing",
                        Set.of("competitive"), 100 - i, i));
            }

            assertThat(RecommendationScorer.rank(taste, many, 100, 5)).hasSize(5);
        }

        @Test
        @DisplayName("the same input always produces the same order")
        void rankingIsDeterministic() {
            // Ties are common because the score has few distinct components.
            // Without a stable tie-break the Home screen would reshuffle on
            // every regeneration for no visible reason.
            PlayerTaste taste = new PlayerTaste(
                    Set.of("competitive"), Set.of("Racing"), Set.of());

            List<Candidate> identical = List.of(
                    candidate(MATCHING, "Racing", Set.of("competitive"), 100, 10),
                    candidate(UNRELATED, "Racing", Set.of("competitive"), 100, 10));

            assertThat(RecommendationScorer.rank(taste, identical, 100, 10))
                    .isEqualTo(RecommendationScorer.rank(taste, identical, 100, 10));
        }

        @Test
        @DisplayName("every result carries a non-empty reason")
        void everyResultIsExplained() {
            // An unexplained recommendation is one players do not act on, and
            // one nobody can debug when the ranking looks wrong.
            PlayerTaste taste = new PlayerTaste(
                    Set.of("competitive"), Set.of("Racing"), Set.of());

            assertThat(RecommendationScorer.rank(
                    taste,
                    List.of(candidate(MATCHING, "Racing", Set.of("competitive"), 50, 5)),
                    100, 10))
                    .allSatisfy(scored -> assertThat(scored.reason()).isNotBlank());
        }
    }

    @Nested
    @DisplayName("component functions")
    class Components {

        @Test
        @DisplayName("jaccard is intersection over union, and zero on an empty side")
        void jaccardBehaviour() {
            assertThat(RecommendationScorer.jaccard(Set.of("a", "b"), Set.of("a", "b")))
                    .isEqualTo(1.0d);
            // One shared tag out of three distinct.
            assertThat(RecommendationScorer.jaccard(Set.of("a", "b"), Set.of("b", "c")))
                    .isCloseTo(1.0d / 3.0d, within(0.000_001d));
            assertThat(RecommendationScorer.jaccard(Set.of(), Set.of("a"))).isZero();
            assertThat(RecommendationScorer.jaccard(Set.of("a"), Set.of("z"))).isZero();
        }

        @Test
        @DisplayName("recency decays linearly to zero and never goes negative")
        void recencyDecay() {
            assertThat(RecommendationScorer.recencyBoost(0)).isEqualTo(1.0d);
            assertThat(RecommendationScorer.recencyBoost(90)).isCloseTo(0.5d, within(0.000_001d));
            assertThat(RecommendationScorer.recencyBoost(180)).isZero();
            // Past the window, and for a game with no release date at all.
            assertThat(RecommendationScorer.recencyBoost(100_000)).isZero();
            assertThat(RecommendationScorer.recencyBoost(-5)).isZero();
        }
    }
}
