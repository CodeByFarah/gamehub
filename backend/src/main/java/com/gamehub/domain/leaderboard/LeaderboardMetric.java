package com.gamehub.domain.leaderboard;

/**
 * What a leaderboard ranks by.
 *
 * <p>Only SCORE is wired up. WINS and PLAYTIME are declared because the
 * database CHECK constraint already permits them, and an enum narrower than
 * its own constraint is a latent deserialisation failure.
 */
public enum LeaderboardMetric {
    SCORE,
    WINS,
    PLAYTIME
}
