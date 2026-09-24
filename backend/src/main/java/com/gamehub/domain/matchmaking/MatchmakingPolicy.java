package com.gamehub.domain.matchmaking;

/**
 * Every tunable number the matchmaker uses, in one immutable object.
 *
 * <p>These are policy, not physics. They belong in configuration so they can be
 * tuned per game without a code change, and they are grouped here so a test can
 * construct a deliberately extreme policy (zero patience, unbounded skill
 * window) and assert the algorithm still terminates and still produces a legal
 * result.
 *
 * <p>The weights are relative, not absolute: each cost term is already
 * normalised into the closed interval 0 to 1, so the weights express nothing
 * but relative importance. That is what lets a reviewer read skillWeight 0.5,
 * latencyWeight 0.3, regionWeight 0.2 and immediately know that skill matters
 * about as much as latency and region combined.
 *
 * @param skillWeight           relative importance of rating difference
 * @param latencyWeight         relative importance of connection quality
 * @param regionWeight          relative importance of geographic affinity
 * @param patienceWeight        how strongly a long wait discounts a poor match
 * @param skillNormaliserElo    rating gap treated as completely mismatched.
 *                              400 is the Elo convention: a 400-point gap is
 *                              roughly 10:1 expected odds, the point at which
 *                              a match stops being a game.
 * @param latencyBudgetMs       average RTT treated as completely unplayable
 * @param baseSkillWindowElo    half-width of the initial candidate window
 * @param windowExpansionPerSec how fast that window widens per second waited
 * @param maxSkillWindowElo     hard ceiling on window width
 * @param baseAcceptanceCost    worst cost accepted from a player who just joined
 * @param maxAcceptanceCost     worst cost accepted from a maximally patient one
 * @param patienceSeconds       wait at which a player is maximally patient
 */
public record MatchmakingPolicy(
        double skillWeight,
        double latencyWeight,
        double regionWeight,
        double patienceWeight,
        double skillNormaliserElo,
        double latencyBudgetMs,
        int baseSkillWindowElo,
        double windowExpansionPerSec,
        int maxSkillWindowElo,
        double baseAcceptanceCost,
        double maxAcceptanceCost,
        double patienceSeconds) {

    public MatchmakingPolicy {
        requirePositive(skillNormaliserElo, "skillNormaliserElo");
        requirePositive(latencyBudgetMs, "latencyBudgetMs");
        requirePositive(patienceSeconds, "patienceSeconds");
        requireNonNegative(skillWeight, "skillWeight");
        requireNonNegative(latencyWeight, "latencyWeight");
        requireNonNegative(regionWeight, "regionWeight");
        requireNonNegative(patienceWeight, "patienceWeight");
        requireNonNegative(windowExpansionPerSec, "windowExpansionPerSec");

        if (baseSkillWindowElo <= 0 || maxSkillWindowElo < baseSkillWindowElo) {
            throw new IllegalArgumentException(
                    "require positive baseSkillWindowElo no greater than maxSkillWindowElo, got "
                            + baseSkillWindowElo + " and " + maxSkillWindowElo);
        }
        if (maxAcceptanceCost < baseAcceptanceCost) {
            throw new IllegalArgumentException(
                    "maxAcceptanceCost must not be below baseAcceptanceCost, got "
                            + maxAcceptanceCost + " below " + baseAcceptanceCost);
        }
        if (skillWeight + latencyWeight + regionWeight <= 0) {
            throw new IllegalArgumentException(
                    "at least one of skillWeight, latencyWeight and regionWeight must be "
                            + "positive, otherwise every pairing scores identically and "
                            + "matchmaking degenerates into arbitrary selection");
        }
    }

    /**
     * Balanced default, and the policy the load tests under tests/load exercise.
     *
     * <p>Skill is weighted highest because an unfair match is the complaint
     * players actually voice. Latency is next because it is felt every second.
     * Region is lowest because it is largely a proxy for latency and would
     * otherwise be double-counted.
     */
    public static MatchmakingPolicy balanced() {
        return new MatchmakingPolicy(
                0.50d,   // skillWeight
                0.30d,   // latencyWeight
                0.20d,   // regionWeight
                0.40d,   // patienceWeight
                400.0d,  // skillNormaliserElo
                150.0d,  // latencyBudgetMs
                100,     // baseSkillWindowElo
                25.0d,   // windowExpansionPerSec
                1200,    // maxSkillWindowElo
                0.25d,   // baseAcceptanceCost
                0.90d,   // maxAcceptanceCost
                45.0d);  // patienceSeconds
    }

    /**
     * Half-width, in Elo points, of the candidate window for a ticket that has
     * waited {@code waitSeconds}.
     *
     * <p>Linear expansion, capped. Linear rather than exponential because this
     * window is the bound on a Redis ZRANGEBYSCORE: doubling it doubles the
     * candidates scanned, and exponential growth would turn a queue that is
     * merely slow into one that is also expensive, at exactly the moment the
     * system is already under pressure.
     */
    public int skillWindowAt(long waitSeconds) {
        double widened = baseSkillWindowElo + windowExpansionPerSec * waitSeconds;
        return (int) Math.min(widened, maxSkillWindowElo);
    }

    /**
     * Worst match cost tolerated for a ticket that has waited
     * {@code waitSeconds}.
     *
     * <p>This is the mechanism that guarantees the queue drains. Without it a
     * player in a thin population could wait forever for a perfect opponent who
     * never arrives. Tolerance rises with wait, so every ticket eventually
     * accepts an imperfect match rather than no match at all.
     */
    public double acceptanceCostAt(long waitSeconds) {
        double patience = Math.min(1.0d, waitSeconds / patienceSeconds);
        return baseAcceptanceCost + (maxAcceptanceCost - baseAcceptanceCost) * patience;
    }

    private static void requirePositive(double value, String name) {
        if (!(value > 0)) {
            throw new IllegalArgumentException(name + " must be positive, got " + value);
        }
    }

    private static void requireNonNegative(double value, String name) {
        if (!(value >= 0)) {
            throw new IllegalArgumentException(name + " must not be negative, got " + value);
        }
    }
}
