package com.gamehub.domain.matchmaking;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

/**
 * Selects pairings from a snapshot of one queue bucket.
 *
 * <h2>Problem</h2>
 * Given N waiting tickets for one game, choose disjoint pairs that minimise
 * total cost, while respecting the acceptance threshold of each ticket and
 * never placing a player in two matches.
 *
 * <h2>Why greedy and not optimal</h2>
 * Minimum-weight perfect matching on a general graph is solvable exactly by
 * Blossom in O(N^3). That is the right algorithm for a different problem. Here:
 *
 * <ul>
 *   <li>The input is a <em>snapshot</em>. By the time an optimal solution is
 *       computed, tickets have arrived and left, so the extra precision is
 *       spent on a queue that no longer exists.</li>
 *   <li>The tick runs on a one-second budget. At N = 10,000 an O(N^3) pass is
 *       around 10^12 operations. It would not finish.</li>
 *   <li>Players judge their own match, not the global optimum. Greedy gives
 *       each ticket the best partner still available, which is the property
 *       that is actually visible to a player.</li>
 * </ul>
 *
 * Greedy here is therefore not a shortcut around the hard algorithm. It is the
 * better fit for a queue that mutates faster than an exact solver can run.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Sort tickets by wait descending, so the longest-waiting player gets
 *       first pick of partners. This is the starvation guard.</li>
 *   <li>For each still-unmatched ticket, scan the candidates inside its skill
 *       window, which widens with wait, and score each pairing.</li>
 *   <li>Push every acceptable pairing into a priority queue keyed by cost.</li>
 *   <li>Drain that queue cheapest-first, committing a pairing only if
 *       <em>both</em> of its tickets are still unclaimed.</li>
 * </ol>
 *
 * Step 4 is what makes this globally greedy rather than merely locally greedy:
 * pairings are committed in order of quality across the whole bucket, not in
 * the order tickets happened to be visited.
 *
 * <h2>Complexity</h2>
 * Let N be tickets in the bucket and K the average number of candidates inside
 * a skill window.
 *
 * <ul>
 *   <li>Sort: O(N log N)</li>
 *   <li>Candidate generation and scoring: O(N * K), each score O(1)</li>
 *   <li>Priority queue inserts: O(N * K * log(N * K))</li>
 *   <li>Drain: O(N * K), with O(1) claim checks against a hash set</li>
 * </ul>
 *
 * Overall <strong>O(N * K * log(N * K))</strong> time and
 * <strong>O(N * K)</strong> space. K is bounded in practice by
 * {@code maxSkillWindowElo} and by {@code candidateLimit}, so this is
 * near-linear in N for realistic queues. The degenerate case is every player
 * sitting in one narrow rating band, where K approaches N and the pass becomes
 * O(N^2 log N); {@code candidateLimit} is the hard stop that keeps that case
 * from taking the tick down.
 *
 * <h2>Failure cases</h2>
 * <ul>
 *   <li><em>Thin queue.</em> Fewer than two tickets, or none within any
 *       acceptance threshold, yields an empty list. Callers must treat "no
 *       proposals" as normal, not as an error.</li>
 *   <li><em>Starvation.</em> Prevented by two independent mechanisms: the
 *       wait-descending scan order here, and the rising acceptance threshold
 *       in {@link MatchmakingPolicy#acceptanceCostAt}. Either alone would
 *       leave a hole. The first decides who picks first, the second guarantees
 *       there is eventually something acceptable to pick.</li>
 *   <li><em>Stale snapshot.</em> A proposal can name a ticket that has since
 *       been cancelled or claimed elsewhere. This class cannot detect that and
 *       does not try; the atomic claim at commit time is what rejects it.</li>
 *   <li><em>Clock skew.</em> Handled upstream by clamping in
 *       {@link MatchTicket#waitSeconds}, so a skewed instance cannot inject a
 *       negative wait that would invert the priority ordering.</li>
 * </ul>
 *
 * <h2>What this class deliberately does not do</h2>
 * It does not touch Redis, Postgres or Kafka, and it does not mutate the queue.
 * It is a pure function from a ticket list to a proposal list, which is what
 * makes the properties asserted in {@code GreedyMatchmakingEngineTest} (no
 * player twice, every match acceptable, same input yields same output)
 * testable with no infrastructure at all. Turning proposals into committed
 * matches is a separate, concurrency-sensitive step described in
 * docs/matchmaking.md.
 */
public final class GreedyMatchmakingEngine {

    /**
     * Upper bound on candidates examined per ticket.
     *
     * <p>Without it, a bucket where every player sits in the same rating band
     * degrades into a full O(N^2) pass. Truncating costs a little match quality
     * in that case, which is the right trade: a slightly worse match is a
     * complaint, a matchmaker tick that overruns its budget is an outage.
     */
    private static final int DEFAULT_CANDIDATE_LIMIT = 50;

    private final MatchCandidateScorer scorer;
    private final int candidateLimit;

    public GreedyMatchmakingEngine(MatchCandidateScorer scorer) {
        this(scorer, DEFAULT_CANDIDATE_LIMIT);
    }

    public GreedyMatchmakingEngine(MatchCandidateScorer scorer, int candidateLimit) {
        if (scorer == null) {
            throw new IllegalArgumentException("scorer is required");
        }
        if (candidateLimit < 1) {
            throw new IllegalArgumentException(
                    "candidateLimit must be at least 1, got " + candidateLimit);
        }
        this.scorer = scorer;
        this.candidateLimit = candidateLimit;
    }

    /**
     * Pairs up as many tickets as it can.
     *
     * @param tickets waiting tickets for a single game, in any order. Not
     *                mutated by this call.
     * @param now     the tick instant. Passed in rather than read from the
     *                clock so that tests are deterministic, and so that every
     *                ticket in one tick is aged against the same instant.
     * @return disjoint proposals, best first. Never null; empty when nothing
     *         acceptable was found.
     */
    public List<ProposedMatch> pair(List<MatchTicket> tickets, Instant now) {
        if (tickets == null || tickets.size() < 2) {
            return List.of();
        }

        // Longest wait first. Ordering the outer scan this way is what gives
        // the most starved tickets first refusal on the best partners. Ties
        // are broken by ticketId so that one input always yields one output,
        // which is what makes the engine reproducible and testable.
        List<MatchTicket> ordered = new ArrayList<>(tickets);
        ordered.sort(Comparator
                .comparingLong((MatchTicket t) -> t.waitSeconds(now)).reversed()
                .thenComparing(MatchTicket::ticketId));

        PriorityQueue<ScoredPair> byCost = new PriorityQueue<>(Comparator
                .comparingDouble(ScoredPair::cost)
                // Deterministic ordering for equal costs, which are common
                // when many tickets share a rating and a region.
                .thenComparing(p -> p.a().ticketId())
                .thenComparing(p -> p.b().ticketId()));

        for (int i = 0; i < ordered.size(); i++) {
            MatchTicket a = ordered.get(i);
            int window = scorer.policy().skillWindowAt(a.waitSeconds(now));
            int examined = 0;

            for (int j = i + 1; j < ordered.size() && examined < candidateLimit; j++) {
                MatchTicket b = ordered.get(j);

                // Cheap rejections first. Both are O(1) and skip the full cost
                // evaluation for the overwhelming majority of pairs in a large
                // bucket, which is what keeps the constant factor on O(N * K)
                // small enough to matter.
                if (a.userId().equals(b.userId())) {
                    continue;
                }
                if (Math.abs(a.skillRating() - b.skillRating()) > window) {
                    continue;
                }

                examined++;
                if (scorer.isAcceptable(a, b, now)) {
                    byCost.add(new ScoredPair(a, b, scorer.cost(a, b, now)));
                }
            }
        }

        Set<UUID> claimed = new HashSet<>();
        List<ProposedMatch> proposals = new ArrayList<>();

        while (!byCost.isEmpty()) {
            ScoredPair pair = byCost.poll();
            // A ticket can appear in many pairs. The first pairing drained wins
            // it, because the queue is ordered by cost and this is therefore
            // the best remaining use of that ticket.
            if (claimed.contains(pair.a().ticketId()) || claimed.contains(pair.b().ticketId())) {
                continue;
            }
            claimed.add(pair.a().ticketId());
            claimed.add(pair.b().ticketId());
            proposals.add(new ProposedMatch(
                    pair.a().gameId(),
                    List.of(pair.a(), pair.b()),
                    pair.cost()));
        }

        return List.copyOf(proposals);
    }

    private record ScoredPair(MatchTicket a, MatchTicket b, double cost) {
    }
}
