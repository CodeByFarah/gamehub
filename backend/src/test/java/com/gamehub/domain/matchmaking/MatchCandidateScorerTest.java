package com.gamehub.domain.matchmaking;

import com.gamehub.domain.common.Region;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static com.gamehub.domain.matchmaking.MatchTickets.NOW;
import static com.gamehub.domain.matchmaking.MatchTickets.population;
import static com.gamehub.domain.matchmaking.MatchTickets.ticket;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MatchCandidateScorerTest {

    private final MatchmakingPolicy policy = MatchmakingPolicy.balanced();
    private final MatchCandidateScorer scorer = new MatchCandidateScorer(policy);

    @Test
    @DisplayName("cost is symmetric, so results cannot depend on iteration order")
    void costIsSymmetric() {
        // The engine evaluates each unordered pair once. An asymmetric cost
        // would make the outcome depend on which ticket Redis returned first.
        List<MatchTicket> tickets = population(120, 2026L);

        for (int i = 0; i < tickets.size(); i++) {
            for (int j = i + 1; j < tickets.size(); j++) {
                MatchTicket a = tickets.get(i);
                MatchTicket b = tickets.get(j);

                assertThat(scorer.cost(a, b, NOW)).isEqualTo(scorer.cost(b, a, NOW));
            }
        }
    }

    @Test
    @DisplayName("an identical pair in the same region costs nothing")
    void perfectPairCostsZero() {
        MatchTicket a = ticket(1200, Region.EU_WEST, 0, 0);
        MatchTicket b = ticket(1200, Region.EU_WEST, 0, 0);

        assertThat(scorer.cost(a, b, NOW)).isZero();
    }

    @Test
    @DisplayName("a wider skill gap never costs less than a narrower one")
    void costRisesMonotonicallyWithSkillGap() {
        MatchTicket base = ticket(1200, Region.EU_WEST, 20, 0);
        double previous = -1.0d;

        for (int gap = 0; gap <= 600; gap += 50) {
            MatchTicket other = ticket(1200 + gap, Region.EU_WEST, 20, 0);
            double cost = scorer.cost(base, other, NOW);

            assertThat(cost).isGreaterThanOrEqualTo(previous);
            previous = cost;
        }
    }

    @Test
    @DisplayName("latency is judged by the worse player, not by the average")
    void latencyUsesTheWorseOfThePair() {
        // Both pairs have a mean latency of 80ms, so an averaging cost function
        // would score them identically. They are not equivalent: in the
        // lopsided pair both players experience the 140ms connection, and it is
        // that player who complains. Costing the worse of the two separates
        // them.
        //
        // Both values sit below latencyBudgetMs (150) deliberately. Past the
        // budget the term clamps to 1.0 and every pairing looks alike, which is
        // correct behaviour but makes this particular property untestable.
        MatchTicket lopsidedGood = ticket(1200, Region.EU_WEST, 20, 0);
        MatchTicket lopsidedBad = ticket(1200, Region.EU_WEST, 140, 0);
        MatchTicket evenA = ticket(1200, Region.EU_WEST, 80, 0);
        MatchTicket evenB = ticket(1200, Region.EU_WEST, 80, 0);

        assertThat(scorer.cost(lopsidedGood, lopsidedBad, NOW))
                .isGreaterThan(scorer.cost(evenA, evenB, NOW));
    }

    @Test
    @DisplayName("latency past the budget clamps, so one terrible link cannot swamp the score")
    void latencyTermClampsAtTheBudget() {
        // Without the clamp a 2000ms outlier would contribute a term of 13.3,
        // dwarfing skill and region and making every other dimension
        // irrelevant for that ticket.
        MatchTicket atBudget = ticket(1200, Region.EU_WEST, 150, 0);
        MatchTicket farPastBudget = ticket(1200, Region.EU_WEST, 2_000, 0);
        MatchTicket reference = ticket(1200, Region.EU_WEST, 10, 0);

        assertThat(scorer.cost(reference, farPastBudget, NOW))
                .isEqualTo(scorer.cost(reference, atBudget, NOW));
    }

    @Test
    @DisplayName("a cross-continent pairing costs more than a neighbouring one")
    void regionAffinityIsApplied() {
        MatchTicket home = ticket(1200, Region.EU_WEST, 20, 0);
        MatchTicket neighbour = ticket(1200, Region.EU_CENTRAL, 20, 0);
        MatchTicket distant = ticket(1200, Region.AP_NORTHEAST, 20, 0);

        double sameRegion = scorer.cost(home, ticket(1200, Region.EU_WEST, 20, 0), NOW);
        double neighbouring = scorer.cost(home, neighbour, NOW);
        double crossContinent = scorer.cost(home, distant, NOW);

        assertThat(sameRegion).isLessThan(neighbouring);
        assertThat(neighbouring).isLessThan(crossContinent);
    }

    @Test
    @DisplayName("waiting lowers the cost of the same pairing")
    void patienceDiscountsCost() {
        double fresh = scorer.cost(
                ticket(1200, Region.EU_WEST, 20, 0),
                ticket(1400, Region.EU_WEST, 20, 0), NOW);
        double patient = scorer.cost(
                ticket(1200, Region.EU_WEST, 20, 60),
                ticket(1400, Region.EU_WEST, 20, 60), NOW);

        assertThat(patient).isLessThan(fresh);
    }

    @Test
    @DisplayName("the acceptance threshold widens with wait, never narrows")
    void acceptanceThresholdOnlyRelaxes() {
        double previous = -1.0d;

        for (long wait = 0; wait <= 200; wait += 5) {
            double threshold = policy.acceptanceCostAt(wait);
            assertThat(threshold).isGreaterThanOrEqualTo(previous);
            previous = threshold;
        }

        assertThat(policy.acceptanceCostAt(0)).isEqualTo(policy.baseAcceptanceCost());
        assertThat(policy.acceptanceCostAt(100_000)).isEqualTo(policy.maxAcceptanceCost());
    }

    @Test
    @DisplayName("the skill window widens with wait and stops at the cap")
    void skillWindowExpandsThenPlateaus() {
        assertThat(policy.skillWindowAt(0)).isEqualTo(policy.baseSkillWindowElo());
        assertThat(policy.skillWindowAt(10)).isGreaterThan(policy.skillWindowAt(0));
        assertThat(policy.skillWindowAt(100_000)).isEqualTo(policy.maxSkillWindowElo());
    }

    @Test
    @DisplayName("scoring a player against themselves is a bug and fails loudly")
    void rejectsSelfPairing() {
        MatchTicket a = ticket(1200, Region.EU_WEST, 20, 0);
        MatchTicket sameUser = new MatchTicket(
                java.util.UUID.randomUUID(), a.userId(), a.gameId(),
                a.region(), a.skillRating(), a.latencyMs(), a.enqueuedAt());

        assertThatThrownBy(() -> scorer.cost(a, sameUser, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("against themselves");
    }

    @Test
    @DisplayName("clock skew cannot produce a negative wait")
    void clockSkewIsClamped() {
        // A ticket enqueued in the future by a skewed instance would otherwise
        // report a negative wait and invert the priority ordering.
        MatchTicket fromTheFuture = ticket(1200, Region.EU_WEST, 20, -300);

        assertThat(fromTheFuture.waitSeconds(NOW)).isZero();
    }

    @Test
    @DisplayName("a policy with no positive weight is rejected at construction")
    void rejectsDegeneratePolicy() {
        assertThatThrownBy(() -> new MatchmakingPolicy(
                0, 0, 0, 0.4, 400, 150, 100, 25, 1200, 0.25, 0.9, 45))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be");
    }

    @Test
    @DisplayName("region affinity is symmetric in both directions")
    void regionAffinityIsSymmetric() {
        Random random = new Random(3L);
        Region[] regions = Region.values();

        for (int i = 0; i < 200; i++) {
            Region a = regions[random.nextInt(regions.length)];
            Region b = regions[random.nextInt(regions.length)];

            assertThat(a.affinityPenaltyTo(b)).isEqualTo(b.affinityPenaltyTo(a));
        }
    }
}
