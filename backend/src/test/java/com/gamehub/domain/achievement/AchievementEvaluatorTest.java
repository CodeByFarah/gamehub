package com.gamehub.domain.achievement;

import com.gamehub.domain.achievement.AchievementEvaluator.PlayerSnapshot;
import com.gamehub.domain.achievement.AchievementEvaluator.Rule;
import com.gamehub.domain.achievement.AchievementEvaluator.TriggerType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AchievementEvaluatorTest {

    private static Rule rule(String code, TriggerType type, int threshold) {
        return new Rule(code, code, type, threshold);
    }

    private static PlayerSnapshot snapshot(int played, int won, int level,
                                           int score, int duration, boolean perfect) {
        return new PlayerSnapshot(played, won, level, score, duration, perfect);
    }

    @Nested
    @DisplayName("threshold semantics")
    class Thresholds {

        @Test
        @DisplayName("a threshold is inclusive, so the tenth win unlocks TEN_WINS")
        void thresholdIsInclusive() {
            // Off-by-one here is the difference between unlocking on the game
            // that earned it and unlocking on the game after, which players
            // report as a bug.
            List<Rule> rules = List.of(rule("TEN_WINS", TriggerType.GAMES_WON, 10));

            assertThat(AchievementEvaluator.evaluate(
                    snapshot(20, 9, 1, 0, 0, false), rules)).isEmpty();
            assertThat(AchievementEvaluator.evaluate(
                    snapshot(20, 10, 1, 0, 0, false), rules)).hasSize(1);
        }

        @Test
        @DisplayName("cumulative rules stay satisfied once passed")
        void cumulativeRulesRemainSatisfied() {
            // The evaluator reports everything that currently holds, including
            // achievements already owned. Filtering those out is the caller
            // job, done with an insert that ignores conflicts.
            List<Rule> rules = List.of(rule("FIRST_WIN", TriggerType.GAMES_WON, 1));

            assertThat(AchievementEvaluator.evaluate(
                    snapshot(500, 400, 20, 0, 0, false), rules)).hasSize(1);
        }
    }

    @Nested
    @DisplayName("per-session rules are not cumulative")
    class PerSessionRules {

        @Test
        @DisplayName("SCORE_REACHED tests the game just finished, not a lifetime total")
        void scoreIsPerSession() {
            // A player with a huge lifetime total but a poor final game must
            // not unlock a high-score achievement.
            List<Rule> rules = List.of(rule("DRIFT_KING", TriggerType.SCORE_REACHED, 5_000));

            assertThat(AchievementEvaluator.evaluate(
                    snapshot(9_999, 9_999, 99, 12, 60, false), rules)).isEmpty();
            assertThat(AchievementEvaluator.evaluate(
                    snapshot(1, 1, 1, 5_000, 60, false), rules)).hasSize(1);
        }

        @Test
        @DisplayName("SESSION_DURATION tests the last session length")
        void durationIsPerSession() {
            List<Rule> rules = List.of(rule("MARATHON", TriggerType.SESSION_DURATION, 7_200));

            assertThat(AchievementEvaluator.evaluate(
                    snapshot(1_000, 500, 40, 100, 3_600, false), rules)).isEmpty();
            assertThat(AchievementEvaluator.evaluate(
                    snapshot(1, 0, 1, 0, 7_200, false), rules)).hasSize(1);
        }

        @Test
        @DisplayName("PERFECT_MATCH ignores its threshold and reads the flag")
        void perfectMatchUsesTheFlag() {
            List<Rule> rules = List.of(rule("PERFECT", TriggerType.PERFECT_MATCH, 1));

            assertThat(AchievementEvaluator.evaluate(
                    snapshot(1, 1, 1, 9_999, 9_999, false), rules)).isEmpty();
            assertThat(AchievementEvaluator.evaluate(
                    snapshot(1, 1, 1, 0, 0, true), rules)).hasSize(1);
        }
    }

    @Test
    @DisplayName("every satisfied rule is returned, not just the first")
    void returnsAllSatisfiedRules() {
        // One game can cross several thresholds at once. Returning only the
        // first would silently withhold the others until the next game.
        List<Rule> rules = List.of(
                rule("FIRST_GAME", TriggerType.GAMES_PLAYED, 1),
                rule("FIRST_WIN", TriggerType.GAMES_WON, 1),
                rule("PERFECT", TriggerType.PERFECT_MATCH, 1),
                rule("TEN_WINS", TriggerType.GAMES_WON, 10));

        List<Rule> satisfied = AchievementEvaluator.evaluate(
                snapshot(1, 1, 1, 100, 120, true), rules);

        assertThat(satisfied).extracting(Rule::code)
                .containsExactlyInAnyOrder("FIRST_GAME", "FIRST_WIN", "PERFECT");
    }

    @Test
    @DisplayName("degenerate input yields an empty list and never throws")
    void handlesDegenerateInput() {
        assertThat(AchievementEvaluator.evaluate(null, List.of())).isEmpty();
        assertThat(AchievementEvaluator.evaluate(
                snapshot(1, 1, 1, 1, 1, true), null)).isEmpty();
        assertThat(AchievementEvaluator.evaluate(
                snapshot(1, 1, 1, 1, 1, true), List.of())).isEmpty();
    }

    @Test
    @DisplayName("the returned list is immutable")
    void resultIsImmutable() {
        // The caller iterates this while inserting rows. A mutable result
        // invites an accidental modification mid-iteration.
        List<Rule> satisfied = AchievementEvaluator.evaluate(
                snapshot(1, 0, 1, 0, 0, false),
                List.of(rule("FIRST_GAME", TriggerType.GAMES_PLAYED, 1)));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> satisfied.add(rule("X", TriggerType.GAMES_PLAYED, 1)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
