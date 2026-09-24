package com.gamehub.android.ui.screen.matchmaking

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamehub.android.data.remote.MatchmakingTicket
import com.gamehub.android.data.repository.MatchmakingRepository
import com.gamehub.android.domain.ApiResult
import com.gamehub.android.domain.ErrorKind
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Matchmaking queue state.
 *
 * The states are a sealed hierarchy rather than a ticket plus a set of
 * booleans. With independent flags the compiler permits "searching and
 * matched", which is meaningless, and every composable has to decide what that
 * renders as.
 */
@HiltViewModel
class MatchmakingViewModel @Inject constructor(
    private val matchmaking: MatchmakingRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<MatchmakingState>(MatchmakingState.Idle)
    val state: StateFlow<MatchmakingState> = _state.asStateFlow()

    private var pollJob: Job? = null

    fun join(gameId: String, latencyMs: Int = DEFAULT_LATENCY_MS) {
        // Guards the double-tap locally as well as server-side. The partial
        // unique index is what actually prevents a duplicate ticket; this just
        // avoids making a request that is certain to be rejected.
        if (_state.value is MatchmakingState.Searching) return

        viewModelScope.launch {
            _state.value = MatchmakingState.Joining

            when (val result = matchmaking.join(gameId, latencyMs)) {
                is ApiResult.Success -> {
                    _state.value = MatchmakingState.Searching(result.data)
                    startPolling(result.data.ticketId)
                }

                is ApiResult.Failure -> {
                    _state.value = if (result.code == ALREADY_QUEUED) {
                        // Not an error worth showing. The player is in the
                        // queue, which is what they asked for. A 409 here
                        // usually means a retried request that succeeded.
                        MatchmakingState.AlreadyQueued
                    } else {
                        MatchmakingState.Failed(result.kind)
                    }
                }
            }
        }
    }

    private fun startPolling(ticketId: String) {
        pollJob?.cancel()
        pollJob = matchmaking.pollTicket(ticketId)
            .onEach { result ->
                when (result) {
                    is ApiResult.Success -> _state.value = when (result.data.status) {
                        "MATCHED" -> MatchmakingState.Matched(result.data)
                        "EXPIRED" -> MatchmakingState.Expired
                        "CANCELLED" -> MatchmakingState.Idle
                        else -> MatchmakingState.Searching(result.data)
                    }

                    is ApiResult.Failure -> {
                        // A transient poll failure does not cancel the search.
                        // The ticket still exists server-side, and dropping
                        // the player out of the queue because one poll timed
                        // out would be wrong.
                        if (result.kind == ErrorKind.CLIENT) {
                            _state.value = MatchmakingState.Failed(result.kind)
                        }
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    /**
     * Leaves the queue.
     *
     * The local state is cleared regardless of the server response, because
     * leaving is idempotent: if the request failed, the ticket expires on its
     * own TTL, and stranding the player on a searching screen would be worse.
     */
    fun leave() {
        pollJob?.cancel()
        viewModelScope.launch {
            matchmaking.leave()
            _state.value = MatchmakingState.Idle
        }
    }

    /**
     * Polling is tied to the ViewModel scope, so it stops when the screen is
     * destroyed. Without this, a backgrounded app keeps polling and drains
     * battery for a match nobody is waiting on.
     */
    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val ALREADY_QUEUED = "ALREADY_QUEUED"

        /**
         * Placeholder until a real measurement exists.
         *
         * A production client would ping the regional edge and report the
         * measured RTT. Sending an honest default is better than sending
         * zero, which would tell the matchmaker every player has a perfect
         * connection and make the latency term useless.
         */
        const val DEFAULT_LATENCY_MS = 50
    }
}

sealed interface MatchmakingState {
    data object Idle : MatchmakingState
    data object Joining : MatchmakingState

    /** Holds the ticket so the UI can show elapsed time rather than an
     *  indeterminate spinner, which is what makes queueing tolerable. */
    data class Searching(val ticket: MatchmakingTicket) : MatchmakingState

    data class Matched(val ticket: MatchmakingTicket) : MatchmakingState

    /** The bucket had too few players. Distinct from a failure, because the
     *  right action is to try a different game rather than to retry. */
    data object Expired : MatchmakingState

    data object AlreadyQueued : MatchmakingState
    data class Failed(val kind: ErrorKind) : MatchmakingState
}
