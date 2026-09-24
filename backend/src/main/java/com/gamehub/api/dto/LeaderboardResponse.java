package com.gamehub.api.dto;

import java.util.List;

/**
 * A leaderboard page.
 *
 * @param servedFrom REDIS or POSTGRES. Surfaced on purpose: during a Redis
 *                   outage the data is still correct but the path is slower,
 *                   and making that visible in the response turns an invisible
 *                   degradation into something a dashboard can count.
 * @param viewerEntry the caller own standing, resolved even when it falls
 *                    outside the requested page, so the UI can pin it without
 *                    a second request
 */
public record LeaderboardResponse(
        String scope,
        String period,
        String gameId,
        String region,
        List<LeaderboardEntryResponse> entries,
        LeaderboardEntryResponse viewerEntry,
        long totalEntries,
        String servedFrom) {
}
