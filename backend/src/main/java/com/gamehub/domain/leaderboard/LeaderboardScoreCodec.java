package com.gamehub.domain.leaderboard;

import java.time.Duration;
import java.time.Instant;

/**
 * Packs a player's score and the instant they achieved it into the single
 * {@code double} that a Redis sorted set uses as its score.
 *
 * <h2>The problem</h2>
 * Redis ZSETs rank by one numeric score. If two players finish on 5000 points,
 * Redis breaks the tie lexicographically by member, which here is a UUID: the
 * winner is decided by the random bytes of a primary key. That is not a
 * tie-break, it is a coin flip, and it is not stable across a cache rebuild
 * either, because the rebuild re-inserts the same members and gets the same
 * arbitrary order for a different reason.
 *
 * <p>The product rule we actually want is: <em>higher score wins; on equal
 * scores, whoever got there first wins.</em>
 *
 * <h2>The approach</h2>
 * Pack both dimensions into one integer, score in the high bits and an inverted
 * timestamp in the low bits:
 *
 * <pre>
 *   encoded = score * 2^29 + (TIME_MAX - secondsSinceEpoch)
 *             \__________/   \__________________________/
 *              high 20 bits          low 29 bits
 * </pre>
 *
 * Ordering by {@code encoded} descending gives score descending, then
 * {@code secondsSinceEpoch} ascending, which is exactly the rule above.
 * Inverting the time component is what turns "earlier is better" into
 * "numerically larger", so a single {@code ZREVRANGE} answers the whole query.
 *
 * <h2>Why this is exact, not approximate</h2>
 * An IEEE-754 double represents every integer below 2^53 exactly. The largest
 * value this codec can produce is
 * {@code (2^20 - 1) * 2^29 + (2^29 - 1) = 2^49 - 1}, which is comfortably under
 * that bound. So the packing is lossless and {@link #decodeScore} recovers the
 * original score bit for bit: no floating point drift, no ranks that shuffle
 * when a leaderboard is rebuilt.
 *
 * <p>The 2^49 ceiling is the whole reason for the range limits below. Widening
 * {@link #MAX_SCORE} means narrowing the time resolution, and vice versa; the
 * two budgets cannot both grow. {@link #encode} therefore rejects out-of-range
 * input loudly rather than letting it wrap and silently corrupt a ranking.
 *
 * <h2>Residual ties</h2>
 * Two players with the same score in the same one-second bucket still collide,
 * and Redis falls back to member ordering. That is deliberate: sub-second
 * ordering between two independent gameplay clients is not information we
 * actually have, so inventing a winner from it would be false precision. The
 * outcome is at least deterministic and survives a rebuild, because it depends
 * only on the member id.
 *
 * <p>This class is a pure function with no dependencies. It is the single place
 * that knows the encoding, so the Redis writer and the Postgres rebuild cannot
 * drift apart.
 */
public final class LeaderboardScoreCodec {

    /**
     * Bits reserved for the inverted timestamp. 2^29 seconds is about 17 years
     * from {@link #EPOCH}, which outlives any realistic life of this schema.
     */
    private static final int TIME_BITS = 29;

    /** Bits reserved for the score. 2^20 - 1 = 1,048,575. */
    private static final int SCORE_BITS = 20;

    private static final long TIME_SPAN = 1L << TIME_BITS;      // 536,870,912
    private static final long TIME_MAX = TIME_SPAN - 1;

    /** Highest score this codec can represent. */
    public static final long MAX_SCORE = (1L << SCORE_BITS) - 1;

    /**
     * Fixed origin for the time component. Pinned as a constant, never derived
     * from "now": if this value moved, every previously encoded score would
     * decode to a different instant and every stored ranking would silently
     * change meaning.
     */
    public static final Instant EPOCH = Instant.parse("2024-01-01T00:00:00Z");

    /** Last instant representable before the time component overflows. */
    public static final Instant MAX_INSTANT = EPOCH.plusSeconds(TIME_MAX);

    private LeaderboardScoreCodec() {
    }

    /**
     * Packs a score and achievement instant into a ZSET score.
     *
     * @param score      points, in {@code [0, MAX_SCORE]}
     * @param achievedAt when the score was reached; truncated to whole seconds
     * @return the packed value, exactly representable as a {@code double}
     * @throws IllegalArgumentException if either input is outside the range the
     *                                  encoding can represent. Failing here is
     *                                  deliberate: a wrapped value would
     *                                  corrupt a ranking silently, and a
     *                                  corrupted ranking is far more expensive
     *                                  to notice than a rejected write.
     */
    public static double encode(long score, Instant achievedAt) {
        if (achievedAt == null) {
            throw new IllegalArgumentException("achievedAt must not be null");
        }
        if (score < 0 || score > MAX_SCORE) {
            throw new IllegalArgumentException(
                    "score " + score + " outside representable range [0, " + MAX_SCORE + "]");
        }
        if (achievedAt.isBefore(EPOCH) || achievedAt.isAfter(MAX_INSTANT)) {
            throw new IllegalArgumentException(
                    "achievedAt " + achievedAt + " outside representable range ["
                            + EPOCH + ", " + MAX_INSTANT + "]");
        }

        long seconds = Duration.between(EPOCH, achievedAt).getSeconds();
        long invertedTime = TIME_MAX - seconds;
        return (double) ((score << TIME_BITS) | invertedTime);
    }

    /** Recovers the score from a value produced by {@link #encode}. */
    public static long decodeScore(double encoded) {
        return toLong(encoded) >>> TIME_BITS;
    }

    /**
     * Recovers the achievement instant, truncated to the second, from a value
     * produced by {@link #encode}.
     */
    public static Instant decodeAchievedAt(double encoded) {
        long invertedTime = toLong(encoded) & TIME_MAX;
        return EPOCH.plusSeconds(TIME_MAX - invertedTime);
    }

    private static long toLong(double encoded) {
        long value = (long) encoded;
        if (value < 0 || (double) value != encoded) {
            throw new IllegalArgumentException(
                    "not a value produced by this codec: " + encoded);
        }
        return value;
    }
}
