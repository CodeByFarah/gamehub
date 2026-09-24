package com.gamehub.domain.leaderboard;

/**
 * The window a leaderboard covers.
 *
 * <p>Only ALL_TIME is populated today. The rest exist because the Redis key
 * scheme already encodes the period, and adding a value later without the key
 * having space for it would mean migrating every key.
 */
public enum LeaderboardPeriod {
    ALL_TIME,
    MONTHLY,
    WEEKLY,
    DAILY
}
