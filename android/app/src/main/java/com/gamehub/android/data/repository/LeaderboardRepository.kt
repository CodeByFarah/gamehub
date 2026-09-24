package com.gamehub.android.data.repository

import com.gamehub.android.data.remote.GameHubApi
import com.gamehub.android.data.remote.LeaderboardResponse
import com.gamehub.android.domain.ApiResult
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Leaderboards.
 *
 * Deliberately not cached. A stale rank shown next to a fresh board reads as a
 * bug, and a leaderboard is the one screen where being slightly out of date is
 * worse than being slightly slow. The server already serves these from a Redis
 * sorted set, so the round trip is cheap.
 *
 * The response carries `servedFrom`, which the UI surfaces during a Redis
 * outage rather than hiding a degraded path behind identical-looking results.
 */
@Singleton
class LeaderboardRepository @Inject constructor(
    private val api: GameHubApi,
    private val json: Json,
) {

    suspend fun global(page: Int = 0, size: Int = 20): ApiResult<LeaderboardResponse> =
        apiCall(json) { api.globalLeaderboard(page, size) }

    suspend fun forGame(
        gameId: String,
        page: Int = 0,
        size: Int = 20,
    ): ApiResult<LeaderboardResponse> =
        apiCall(json) { api.gameLeaderboard(gameId, page, size) }

    suspend fun forRegion(
        region: String,
        page: Int = 0,
        size: Int = 20,
    ): ApiResult<LeaderboardResponse> =
        apiCall(json) { api.regionalLeaderboard(region, page, size) }
}
