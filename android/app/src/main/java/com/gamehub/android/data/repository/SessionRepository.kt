package com.gamehub.android.data.repository

import com.gamehub.android.data.remote.CompleteSessionRequest
import com.gamehub.android.data.remote.GameHubApi
import com.gamehub.android.data.remote.SessionResponse
import com.gamehub.android.data.remote.StartSessionRequest
import com.gamehub.android.domain.ApiResult
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gameplay sessions.
 *
 * Completing a session is the highest-fan-out write in the product: it is what
 * drives statistics, achievements, leaderboards and recommendations. From the
 * client side it is still one request, because the fan-out happens
 * asynchronously server-side through the outbox.
 *
 * The consequence for the UI is that derived state lags briefly. The
 * completion response carries the finished session directly, so the screen
 * that just ended a game never has to re-read a counter that may not have
 * caught up yet.
 */
@Singleton
class SessionRepository @Inject constructor(
    private val api: GameHubApi,
    private val json: Json,
) {

    suspend fun start(gameId: String, matchId: String? = null): ApiResult<SessionResponse> =
        apiCall(json) {
            api.startSession(StartSessionRequest(gameId, matchId, CLIENT_VERSION))
        }

    /**
     * @param perfect sent, and deliberately ignored by the server, which
     *        re-derives it from outcome and score. A modified client cannot
     *        award itself the achievement by flipping this.
     */
    suspend fun complete(
        sessionId: String,
        score: Int,
        outcome: String,
        perfect: Boolean = false,
    ): ApiResult<SessionResponse> =
        apiCall(json) {
            api.completeSession(sessionId, CompleteSessionRequest(score, outcome, perfect))
        }

    private companion object {
        const val CLIENT_VERSION = "android-0.1.0"
    }
}
