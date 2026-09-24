package com.gamehub.android.data.repository

import com.gamehub.android.data.remote.GameHubApi
import com.gamehub.android.data.remote.JoinQueueRequest
import com.gamehub.android.data.remote.MatchmakingTicket
import com.gamehub.android.domain.ApiResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Matchmaking queue, from the client side.
 */
@Singleton
class MatchmakingRepository @Inject constructor(
    private val api: GameHubApi,
    private val json: Json,
) {

    /** Matches the server ticket TTL, so polling stops when the ticket dies. */
    private val maxPollDuration = 5 * 60 * 1000L

    suspend fun join(gameId: String, latencyMs: Int): ApiResult<MatchmakingTicket> =
        apiCall(json) { api.joinQueue(JoinQueueRequest(gameId, latencyMs)) }

    /**
     * Idempotent, and treated as such.
     *
     * The server returns 204 whether or not a ticket existed, so a client that
     * has already been matched, or that retries a cancel, is not shown an
     * error for reaching the state it wanted.
     */
    suspend fun leave(): ApiResult<Unit> = apiCall(json) { api.leaveQueue() }

    /**
     * Polls a ticket until it resolves.
     *
     * <h3>Why polling and not a socket</h3>
     * Matching is asynchronous and typically resolves within seconds. A
     * WebSocket would be lower latency, but it means connection lifecycle
     * management, reconnection on network change, and a stateful server
     * component in a system that is otherwise entirely stateless. Polling a
     * single indexed lookup is the cheaper correct answer at this scale.
     *
     * <h3>Backoff</h3>
     * Starts at one second and widens to five. A player who has waited two
     * minutes is not served by polling every second: it burns battery and
     * adds load exactly when the queue is already struggling.
     *
     * Emits each state so the UI can show elapsed time, rather than sitting on
     * an indeterminate spinner.
     */
    fun pollTicket(ticketId: String): Flow<ApiResult<MatchmakingTicket>> = flow {
        val startedAt = System.currentTimeMillis()
        var intervalMillis = 1_000L

        while (System.currentTimeMillis() - startedAt < maxPollDuration) {
            val result = apiCall(json) { api.ticket(ticketId) }
            emit(result)

            if (result is ApiResult.Success) {
                // MATCHED, CANCELLED or EXPIRED are all terminal. Continuing
                // to poll a resolved ticket is pure waste.
                if (result.data.status != "WAITING") {
                    return@flow
                }
            }

            if (result is ApiResult.Failure) {
                // Stop on a client error: a 404 means the ticket does not
                // exist, and no amount of polling will change that.
                if (result.kind == com.gamehub.android.domain.ErrorKind.CLIENT) {
                    return@flow
                }
            }

            delay(intervalMillis)
            intervalMillis = (intervalMillis * 3 / 2).coerceAtMost(5_000L)
        }
    }
}
