package com.gamehub.domain.matchmaking;

import java.time.Instant;

/**
 * Scores how bad a proposed pairing is. Lower is better; zero is perfect.
 *
 * <h2>The cost function</h2>
 * <pre>
 *   cost(a, b) = skillWeight   * skillTerm
 *              + latencyWeight * latencyTerm
 *              + regionWeight  * regionTerm
 *              - patienceWeight * patienceBonus
 * </pre>
 *
 * Every term is normalised into the closed interval 0 to 1 before weighting, so
 * the weights are the only thing expressing priority. Mixing a raw Elo gap
 * (hundreds) with a raw latency (tens) and a region flag (0 or 1) in one sum
 * would let whichever quantity happens to have the largest units silently
 * dominate; normalising first is what makes the weights mean what they say.
 *
 * <h3>skillTerm</h3>
 * {@code min(1, |ratingA - ratingB| / skillNormaliserElo)}. Clamped rather than
 * left unbounded: past a 400-point gap the match is already a write-off, and
 * letting the term grow to 5.0 would make a hopeless pairing dominate the sum
 * so completely that latency and region stopped mattering at all.
 *
 * <h3>latencyTerm</h3>
 * Uses the <em>worse</em> of the two players, not the mean. A 20ms player
 * paired with a 300ms player does not experience a 160ms match; both
 * experience the 300ms one. Averaging would hide exactly the pairing players
 * complain about.
 *
 * <h3>regionTerm</h3>
 * {@link com.gamehub.domain.common.Region#affinityPenaltyTo}, already
 * normalised.
 *
 * <h3>patienceBonus</h3>
 * Subtracted, not added, and driven by the <em>longer</em>-waiting of the two.
 * This is what stops a thin queue from starving: as a ticket ages, the cost of
 * every pairing involving it falls, so it climbs the global ordering and gets
 * matched ahead of fresher tickets. Combined with the rising acceptance
 * threshold in {@link MatchmakingPolicy#acceptanceCostAt}, it gives the
 * liveness guarantee that the algorithm relies on.
 *
 * <p>Cost may go negative for a very patient pair. That is intentional and
 * harmless: only the <em>ordering</em> of costs and the comparison against the
 * acceptance threshold matter, and both are well defined over negatives.
 *
 * <h2>Properties</h2>
 * The function is <strong>symmetric</strong>, {@code cost(a, b) == cost(b, a)},
 * because every term is built from an absolute difference, a max, or a
 * symmetric region table. Symmetry is not cosmetic: the engine evaluates each
 * unordered pair once, so an asymmetric cost would make results depend on
 * iteration order, and therefore on Redis key ordering. {@code
 * MatchCandidateScorerTest} asserts it as a property over random input.
 *
 * <p>Complexity: O(1) time, O(1) space, no allocation.
 *
 * <p>Stateless and therefore safe to share across matchmaker threads.
 */
public final class MatchCandidateScorer {

    private final MatchmakingPolicy policy;

    public MatchCandidateScorer(MatchmakingPolicy policy) {
        if (policy == null) {
            throw new IllegalArgumentException("policy is required");
        }
        this.policy = policy;
    }

    /**
     * Cost of pairing {@code a} with {@code b} at instant {@code now}.
     *
     * @throws IllegalArgumentException if the two tickets belong to the same
     *                                  player. That would be a bug upstream in
     *                                  queue management rather than a merely
     *                                  bad match, so it fails loudly instead of
     *                                  scoring 0.0 and looking like the best
     *                                  possible pairing.
     */
    public double cost(MatchTicket a, MatchTicket b, Instant now) {
        if (a.userId().equals(b.userId())) {
            throw new IllegalArgumentException(
                    "cannot score a player against themselves: " + a.userId());
        }

        double skillTerm = Math.min(
                1.0d,
                Math.abs(a.skillRating() - b.skillRating()) / policy.skillNormaliserElo());

        double worstLatency = Math.max(a.latencyMs(), b.latencyMs());
        double latencyTerm = Math.min(1.0d, worstLatency / policy.latencyBudgetMs());

        double regionTerm = a.region().affinityPenaltyTo(b.region());

        long longestWait = Math.max(a.waitSeconds(now), b.waitSeconds(now));
        double patienceBonus = Math.min(1.0d, longestWait / policy.patienceSeconds());

        return policy.skillWeight() * skillTerm
                + policy.latencyWeight() * latencyTerm
                + policy.regionWeight() * regionTerm
                - policy.patienceWeight() * patienceBonus;
    }

    /**
     * Whether this pairing is good enough to commit to.
     *
     * <p>Evaluated against the threshold of the <em>longer</em>-waiting ticket.
     * Using the fresher ticket instead would let an impatient newcomer veto a
     * match that a long-suffering player is more than willing to accept, which
     * is the wrong trade: the newcomer loses a few seconds, the veteran loses
     * another full wait cycle.
     */
    public boolean isAcceptable(MatchTicket a, MatchTicket b, Instant now) {
        long longestWait = Math.max(a.waitSeconds(now), b.waitSeconds(now));
        return cost(a, b, now) <= policy.acceptanceCostAt(longestWait);
    }

    public MatchmakingPolicy policy() {
        return policy;
    }
}
