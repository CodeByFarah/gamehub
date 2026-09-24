package com.gamehub.domain.leaderboard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The ranking rule this codec exists to enforce is: higher score wins, and on
 * equal scores the player who got there first wins.
 *
 * <p>These tests assert that rule directly, by sorting encoded values the way
 * Redis would and checking the resulting order. They are not tests of the
 * arithmetic for its own sake.
 */
class LeaderboardScoreCodecTest {

    private static final Instant T0 = LeaderboardScoreCodec.EPOCH.plusSeconds(1_000_000);

    @Nested
    @DisplayName("round trip")
    class RoundTrip {

        @Test
        @DisplayName("recovers the exact score and second it was given")
        void recoversInputExactly() {
            double encoded = LeaderboardScoreCodec.encode(4_237, T0);

            assertThat(LeaderboardScoreCodec.decodeScore(encoded)).isEqualTo(4_237L);
            assertThat(LeaderboardScoreCodec.decodeAchievedAt(encoded)).isEqualTo(T0);
        }

        @Test
        @DisplayName("is lossless at the extremes, where a double would be expected to drift")
        void isLosslessAtExtremes() {
            // The whole design rests on staying under 2^53. If that ever stops
            // being true this is the test that catches it, rather than a player
            // reporting that two ranks swapped for no reason.
            double maxEncoded = LeaderboardScoreCodec.encode(
                    LeaderboardScoreCodec.MAX_SCORE, LeaderboardScoreCodec.EPOCH);

            assertThat(maxEncoded).isLessThan(Math.pow(2, 53));
            assertThat(LeaderboardScoreCodec.decodeScore(maxEncoded))
                    .isEqualTo(LeaderboardScoreCodec.MAX_SCORE);
            assertThat(LeaderboardScoreCodec.decodeAchievedAt(maxEncoded))
                    .isEqualTo(LeaderboardScoreCodec.EPOCH);

            double minEncoded = LeaderboardScoreCodec.encode(0, LeaderboardScoreCodec.MAX_INSTANT);
            assertThat(LeaderboardScoreCodec.decodeScore(minEncoded)).isZero();
            assertThat(LeaderboardScoreCodec.decodeAchievedAt(minEncoded))
                    .isEqualTo(LeaderboardScoreCodec.MAX_INSTANT);
        }

        @Test
        @DisplayName("round trips a thousand random inputs without drift")
        void roundTripsRandomInputs() {
            Random random = new Random(20260921L);

            for (int i = 0; i < 1_000; i++) {
                long score = (long) random.nextInt((int) LeaderboardScoreCodec.MAX_SCORE);
                Instant at = LeaderboardScoreCodec.EPOCH.plusSeconds(random.nextInt(500_000_000));

                double encoded = LeaderboardScoreCodec.encode(score, at);

                assertThat(LeaderboardScoreCodec.decodeScore(encoded)).isEqualTo(score);
                assertThat(LeaderboardScoreCodec.decodeAchievedAt(encoded)).isEqualTo(at);
            }
        }
    }

    @Nested
    @DisplayName("ordering, as Redis ZREVRANGE would apply it")
    class Ordering {

        @Test
        @DisplayName("a higher score always outranks a lower one, however old")
        void higherScoreWins() {
            // The low scorer got there first, which under a naive timestamp
            // tie-break would wrongly promote them.
            double lowButEarly = LeaderboardScoreCodec.encode(100, LeaderboardScoreCodec.EPOCH);
            double highButLate = LeaderboardScoreCodec.encode(101, LeaderboardScoreCodec.MAX_INSTANT);

            assertThat(highButLate).isGreaterThan(lowButEarly);
        }

        @Test
        @DisplayName("on an equal score the earlier player outranks the later one")
        void earlierWinsTies() {
            double early = LeaderboardScoreCodec.encode(5_000, T0);
            double late = LeaderboardScoreCodec.encode(5_000, T0.plusSeconds(1));

            assertThat(early).isGreaterThan(late);
        }

        @Test
        @DisplayName("sorting encoded values descending reproduces the product ranking rule")
        void sortingReproducesTheProductRule() {
            record Entry(String player, long score, Instant at) {
            }

            List<Entry> entries = List.of(
                    new Entry("carol", 5_000, T0.plusSeconds(30)),
                    new Entry("alice", 5_000, T0),
                    new Entry("dave", 9_100, T0.plusSeconds(900)),
                    new Entry("bob", 5_000, T0.plusSeconds(10)),
                    new Entry("erin", 120, T0.minusSeconds(5_000)));

            List<String> ranked = new ArrayList<>(entries).stream()
                    .sorted(Comparator
                            .comparingDouble((Entry e) ->
                                    LeaderboardScoreCodec.encode(e.score(), e.at()))
                            .reversed())
                    .map(Entry::player)
                    .toList();

            // dave leads on score. alice, bob and carol are tied on 5000 and
            // are separated only by who arrived first. erin is last.
            assertThat(ranked).containsExactly("dave", "alice", "bob", "carol", "erin");
        }
    }

    @Nested
    @DisplayName("range enforcement")
    class RangeEnforcement {

        @Test
        @DisplayName("rejects a score above the representable range rather than wrapping it")
        void rejectsOversizedScore() {
            assertThatThrownBy(() ->
                    LeaderboardScoreCodec.encode(LeaderboardScoreCodec.MAX_SCORE + 1, T0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("outside representable range");
        }

        @Test
        @DisplayName("rejects a negative score")
        void rejectsNegativeScore() {
            assertThatThrownBy(() -> LeaderboardScoreCodec.encode(-1, T0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects an instant before the fixed epoch")
        void rejectsInstantBeforeEpoch() {
            assertThatThrownBy(() -> LeaderboardScoreCodec.encode(
                    10, LeaderboardScoreCodec.EPOCH.minusSeconds(1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("outside representable range");
        }

        @Test
        @DisplayName("rejects an instant past the end of the time budget")
        void rejectsInstantAfterMax() {
            assertThatThrownBy(() -> LeaderboardScoreCodec.encode(
                    10, LeaderboardScoreCodec.MAX_INSTANT.plusSeconds(1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects a value it did not produce")
        void rejectsForeignValue() {
            assertThatThrownBy(() -> LeaderboardScoreCodec.decodeScore(-4.0d))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> LeaderboardScoreCodec.decodeScore(1.5d))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
