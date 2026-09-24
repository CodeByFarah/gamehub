package com.gamehub.api.dto;

/**
 * A recommended game with its justification.
 *
 * @param reason human-readable explanation. Always populated. An unexplained
 *               recommendation is one players do not act on, and it is also
 *               impossible to debug when the ranking looks wrong.
 */
public record RecommendationResponse(
        GameSummaryResponse game,
        double score,
        String reason,
        String algorithm) {
}
