package com.gamehub.domain.leaderboard;

/**
 * What population a leaderboard ranks.
 *
 * <p>A domain concept, not a persistence detail. It lives here rather than
 * nested in the JPA entity because the API speaks in these terms: an endpoint
 * signature that referenced a type owned by the persistence layer would couple
 * the public contract to the schema, and renaming a column would become a
 * breaking API change.
 *
 * <p>That coupling was caught by {@code ArchitectureTest}, not by review.
 */
public enum LeaderboardScope {

    /** Every player, every game. */
    GLOBAL,

    /** Every player in one region, across all games. */
    REGIONAL,

    /** Every player of one game. */
    GAME
}
