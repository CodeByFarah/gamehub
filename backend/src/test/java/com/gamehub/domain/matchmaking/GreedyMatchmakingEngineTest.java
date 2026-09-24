package com.gamehub.domain.matchmaking;

import com.gamehub.domain.common.Region;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static com.gamehub.domain.matchmaking.MatchTickets.NOW;
import static com.gamehub.domain.matchmaking.MatchTickets.population;
import static com.gamehub.domain.matchmaking.MatchTickets.ticket;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Properties the engine must hold for every input, rather than examples of it
 * working on one input.
 *
 * <p>Three of these are safety properties a matchmaker cannot violate even once
 * without a visible product bug: a player in two matches, a match nobody
 * agreed to, and results that differ between identical runs. They are asserted
 * over seeded random populations rather than hand-picked cases, because the
 * interesting failures in a greedy algorithm come from the shape of the
 * population and not from any single pair.
 */
class GreedyMatchmakingEngineTest {

    private final MatchmakingPolicy policy = MatchmakingPolicy.balanced();
    private final MatchCandidateScorer scorer = new MatchCandidateScorer(policy);
    private final GreedyMatchmakingEngine engine = new GreedyMatchmakingEngine(scorer);

    @Nested
    @DisplayName("safety: a player is never placed in two matches")
    class Disjointness {

        @ParameterizedTest(name = "population of {0}")
        @ValueSource(ints = {2, 3, 17, 50, 500})
        @DisplayName("no ticket and no user appears in more than one proposal")
        void everyProposalIsDisjoint(int size) {
            List<ProposedMatch> proposals = engine.pair(population(size, 42L + size), NOW);

            Set<UUID> seenTickets = new HashSet<>();
            Set<UUID> seenUsers = new HashSet<>();

            for (ProposedMatch proposal : proposals) {
                for (UUID ticketId : proposal.ticketIds()) {
                    assertThat(seenTickets.add(ticketId))
                            .withFailMessage("ticket %s was matched twice", ticketId)
                            .isTrue();
                }
                for (UUID userId : proposal.userIds()) {
                    assertThat(seenUsers.add(userId))
                            .withFailMessage("user %s was matched twice", userId)
                            .isTrue();
                }
            }
        }

        @Test
        @DisplayName("an odd population leaves exactly one ticket behind, not zero and not two")
        void oddPopulationLeavesOneBehind() {
            // Identical tickets, so nothing can be rejected on cost and the
            // only constraint left is that a pair needs two members.
            List<MatchTicket> tickets = new ArrayList<>();
            for (int i = 0; i < 9; i++) {
                tickets.add(ticket(1200, Region.EU_WEST, 20, 30));
            }

            List<ProposedMatch> proposals = engine.pair(tickets, NOW);

            assertThat(proposals).hasSize(4);
            assertThat(proposals.size() * 2).isEqualTo(tickets.size() - 1);
        }
    }

    @Nested
    @DisplayName("safety: every committed pairing cleared its own threshold")
    class Acceptability {

        @Test
        @DisplayName("no proposal exceeds the acceptance cost of its longest waiter")
        void everyProposalIsAcceptable() {
            List<ProposedMatch> proposals = engine.pair(population(400, 7L), NOW);

            assertThat(proposals).isNotEmpty();
            for (ProposedMatch proposal : proposals) {
                MatchTicket a = proposal.tickets().get(0);
                MatchTicket b = proposal.tickets().get(1);

                assertThat(scorer.isAcceptable(a, b, NOW))
                        .withFailMessage(
                                "committed a pairing the scorer rejects, cost %s",
                                proposal.cost())
                        .isTrue();
            }
        }

        @Test
        @DisplayName("reported cost is the cost the scorer actually assigns")
        void reportedCostIsNotDecorative() {
            // match_quality is persisted and dashboarded. If it drifted from
            // the real cost, every quality alert would be measuring nothing.
            for (ProposedMatch proposal : engine.pair(population(120, 11L), NOW)) {
                double recomputed = scorer.cost(
                        proposal.tickets().get(0), proposal.tickets().get(1), NOW);
                assertThat(proposal.cost()).isEqualTo(recomputed);
            }
        }

        @Test
        @DisplayName("two players far apart in skill are left unmatched rather than paired badly")
        void refusesAHopelessPairing() {
            List<MatchTicket> tickets = List.of(
                    ticket(400, Region.EU_WEST, 20, 0),
                    ticket(3200, Region.AP_NORTHEAST, 350, 0));

            assertThat(engine.pair(tickets, NOW)).isEmpty();
        }
    }

    @Nested
    @DisplayName("determinism")
    class Determinism {

        @Test
        @DisplayName("the same population always yields the same proposals")
        void repeatedRunsAgree() {
            List<MatchTicket> tickets = population(300, 99L);

            List<ProposedMatch> first = engine.pair(tickets, NOW);
            List<ProposedMatch> second = engine.pair(tickets, NOW);

            assertThat(second).isEqualTo(first);
        }

        @Test
        @DisplayName("shuffling the input does not change the outcome")
        void inputOrderDoesNotMatter() {
            // The engine reads its input from Redis, whose iteration order is
            // not guaranteed. If order mattered, two instances could disagree
            // about the very same queue.
            List<MatchTicket> tickets = population(200, 123L);
            List<ProposedMatch> baseline = engine.pair(tickets, NOW);

            List<MatchTicket> shuffled = new ArrayList<>(tickets);
            Collections.shuffle(shuffled, new Random(5L));

            assertThat(engine.pair(shuffled, NOW)).isEqualTo(baseline);
        }
    }

    @Nested
    @DisplayName("liveness: nobody waits forever")
    class Starvation {

        @Test
        @DisplayName("the longest waiter is matched even when better pairings exist for others")
        void longestWaiterGetsPriority() {
            UUID starved = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

            List<MatchTicket> tickets = new ArrayList<>();
            // Waiting two minutes, and rated away from the pack.
            tickets.add(MatchTickets.ticket(starved, 1700, 120));
            // A tight cluster that would otherwise pair perfectly among itself.
            for (int i = 0; i < 6; i++) {
                tickets.add(ticket(1500 + i, Region.EU_WEST, 20, 1));
            }

            List<ProposedMatch> proposals = engine.pair(tickets, NOW);

            assertThat(proposals)
                    .withFailMessage("the longest-waiting ticket was left in the queue")
                    .anyMatch(p -> p.ticketIds().contains(starved));
        }

        @Test
        @DisplayName("waiting opens up a pairing the same players were refused at first")
        void patienceOpensUpMatchesThatWereRejected() {
            MatchTicket a = ticket(1200, Region.EU_WEST, 30, 0);
            MatchTicket b = ticket(1650, Region.EU_CENTRAL, 45, 0);

            assertThat(engine.pair(List.of(a, b), NOW))
                    .withFailMessage("this pairing should be refused at zero wait")
                    .isEmpty();

            MatchTicket patientA = ticket(1200, Region.EU_WEST, 30, 90);
            MatchTicket patientB = ticket(1650, Region.EU_CENTRAL, 45, 90);

            assertThat(engine.pair(List.of(patientA, patientB), NOW))
                    .withFailMessage("this pairing should be accepted after waiting")
                    .hasSize(1);
        }
    }

    @Nested
    @DisplayName("degenerate input")
    class Degenerate {

        @Test
        @DisplayName("an empty or single-ticket queue yields no proposals and does not throw")
        void handlesTooFewTickets() {
            assertThat(engine.pair(List.of(), NOW)).isEmpty();
            assertThat(engine.pair(null, NOW)).isEmpty();
            assertThat(engine.pair(List.of(ticket(1200, Region.EU_WEST, 20, 0)), NOW)).isEmpty();
        }

        @Test
        @DisplayName("a large single-rating band stays bounded by the candidate limit")
        void boundedByCandidateLimit() {
            // The O(N^2) degenerate case the candidate limit exists to contain.
            List<MatchTicket> tickets = new ArrayList<>();
            for (int i = 0; i < 1_000; i++) {
                tickets.add(ticket(1200, Region.EU_WEST, 20, 10));
            }

            long startedAt = System.nanoTime();
            List<ProposedMatch> proposals = engine.pair(tickets, NOW);
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

            assertThat(proposals).hasSize(500);
            // Generous on purpose. This asserts the absence of quadratic
            // blow-up, not a performance figure; a real regression here costs
            // seconds, not milliseconds.
            assertThat(elapsedMs).isLessThan(2_000L);
        }
    }
}
