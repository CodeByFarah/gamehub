package com.gamehub.domain.achievement;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides which achievements a completed game unlocks.
 *
 * <p>Pure. Takes the player statistics after the game and the rules, and
 * returns the rules that now hold. No database, no events, no Spring, so the
 * whole rule set is testable as a table of inputs and expected outputs.
 *
 * <h2>Why the rules are data</h2>
 * Each rule is a trigger type plus a threshold, so adding "win 50 games" is an
 * INSERT rather than a deployment. The alternative, a class per achievement,
 * makes every new achievement a release and makes the rule set impossible to
 * see in one place.
 *
 * <p>Complexity: O(R) for R applicable rules, each check O(1). R is small,
 * bounded by the achievements defined for one game plus the platform-wide
 * ones, so this runs comfortably inside a Kafka consumer.
 */
public final class AchievementEvaluator {

    private AchievementEvaluator() {
    }

    /**
     * Player state as of immediately after the completed game.
     *
     * <p>Thresholds are compared against post-game totals, so finishing the
     * tenth win unlocks TEN_WINS in the same pass. Comparing against pre-game
     * totals would delay every unlock by one game, which reads as a bug.
     */
    public record PlayerSnapshot(
            int gamesPlayed,
            int gamesWon,
            int level,
            int lastScore,
            int lastDurationSeconds,
            boolean lastWasPerfect) {
    }

    /** One rule, projected from an achievements row. */
    public record Rule(Object achievementId, String code, TriggerType triggerType, int threshold) {
    }

    public enum TriggerType {
        GAMES_PLAYED, GAMES_WON, LEVEL_REACHED, SCORE_REACHED, PERFECT_MATCH, SESSION_DURATION
    }

    /**
     * Rules satisfied by this snapshot.
     *
     * <p>Returns everything that now holds, including achievements the player
     * already has. Filtering those out is the callers job, because only the
     * caller knows what has already been unlocked, and it does that with an
     * insert that ignores conflicts rather than with a read-then-check that
     * would race.
     */
    public static List<Rule> evaluate(PlayerSnapshot snapshot, List<Rule> rules) {
        if (snapshot == null || rules == null || rules.isEmpty()) {
            return List.of();
        }

        List<Rule> satisfied = new ArrayList<>();
        for (Rule rule : rules) {
            if (isSatisfied(rule, snapshot)) {
                satisfied.add(rule);
            }
        }
        return List.copyOf(satisfied);
    }

    private static boolean isSatisfied(Rule rule, PlayerSnapshot s) {
        return switch (rule.triggerType()) {
            case GAMES_PLAYED -> s.gamesPlayed() >= rule.threshold();
            case GAMES_WON -> s.gamesWon() >= rule.threshold();
            case LEVEL_REACHED -> s.level() >= rule.threshold();
            // Score and duration are properties of the game just finished, not
            // cumulative totals. Comparing a lifetime total against them would
            // unlock a high-score achievement for a player who had merely
            // played a lot.
            case SCORE_REACHED -> s.lastScore() >= rule.threshold();
            case SESSION_DURATION -> s.lastDurationSeconds() >= rule.threshold();
            case PERFECT_MATCH -> s.lastWasPerfect();
        };
    }
}
