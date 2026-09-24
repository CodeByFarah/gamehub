package com.gamehub.domain.leaderboard;

import com.gamehub.domain.common.Region;

import java.util.UUID;

/**
 * The single place that knows how a Redis leaderboard key is spelled.
 *
 * <p>Kept in the domain, and kept as the only source of the format, because
 * the key is written by one code path and read by another. If the two derived
 * it independently, a change to the scheme would split a leaderboard in half,
 * with writes going to one key and reads to another, and nothing would fail.
 *
 * <p>The resulting key is also persisted on the leaderboard row, so a change
 * to this format is a migration rather than a silent cache split.
 */
public final class LeaderboardKey {

    private LeaderboardKey() {
    }

    /**
     * @param gameId required for GAME scope, ignored otherwise
     * @param region required for REGIONAL scope, ignored otherwise
     */
    public static String build(LeaderboardScope scope, UUID gameId,
                               Region region, LeaderboardPeriod period) {
        String suffix = period.name().toLowerCase(java.util.Locale.ROOT);

        return switch (scope) {
            case GLOBAL -> "lb:global:" + suffix;
            case REGIONAL -> {
                if (region == null) {
                    throw new IllegalArgumentException("a regional leaderboard needs a region");
                }
                yield "lb:region:" + region.name().toLowerCase(java.util.Locale.ROOT)
                        + ":" + suffix;
            }
            case GAME -> {
                if (gameId == null) {
                    throw new IllegalArgumentException("a game leaderboard needs a game id");
                }
                yield "lb:game:" + gameId + ":" + suffix;
            }
        };
    }
}
