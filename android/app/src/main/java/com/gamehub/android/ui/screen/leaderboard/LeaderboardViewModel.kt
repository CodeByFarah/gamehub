package com.gamehub.android.ui.screen.leaderboard

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamehub.android.data.remote.LeaderboardEntry
import com.gamehub.android.data.repository.LeaderboardRepository
import com.gamehub.android.domain.ApiResult
import com.gamehub.android.domain.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Leaderboard for one game, one region, or globally.
 *
 * <h3>Why the scope lives in the ViewModel</h3>
 * Switching between Game, Regional and Global is a filter over the same
 * screen, not navigation to a different one. Modelling it as three routes
 * would mean three back-stack entries for what the player experiences as
 * flipping a tab.
 */
@HiltViewModel
class LeaderboardViewModel @Inject constructor(
    private val leaderboards: LeaderboardRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /**
     * Read from the navigation argument.
     *
     * SavedStateHandle rather than a setter call from the composable: it
     * survives process death, so returning to the app after Android has
     * reclaimed memory restores the same leaderboard rather than a blank one.
     */
    private val gameId: String? = savedStateHandle["gameId"]

    private val _state = MutableStateFlow<UiState<LeaderboardUiModel>>(UiState.Loading)
    val state: StateFlow<UiState<LeaderboardUiModel>> = _state.asStateFlow()

    private var scope: LeaderboardScope =
        if (gameId != null) LeaderboardScope.GAME else LeaderboardScope.GLOBAL

    init {
        load()
    }

    fun selectScope(next: LeaderboardScope) {
        if (next == scope) return
        // A game scope with no game id would produce a request the server
        // rejects. Guarding here keeps the tab from being offered in a
        // context where it cannot work.
        if (next == LeaderboardScope.GAME && gameId == null) return

        scope = next
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = UiState.Loading

            val result = when (scope) {
                LeaderboardScope.GAME -> leaderboards.forGame(requireNotNull(gameId))
                LeaderboardScope.REGIONAL -> leaderboards.forRegion(DEFAULT_REGION)
                LeaderboardScope.GLOBAL -> leaderboards.global()
            }

            _state.value = when (result) {
                is ApiResult.Success -> UiState.Success(
                    LeaderboardUiModel(
                        scope = scope,
                        entries = result.data.entries,
                        viewerEntry = result.data.viewerEntry,
                        totalEntries = result.data.totalEntries,
                        // The API reports which layer answered. Carried
                        // through so the UI can say "cached standings" during
                        // a Redis outage rather than silently showing slower,
                        // identical-looking results.
                        servedFromCache = result.data.servedFrom == "POSTGRES",
                        gameScopeAvailable = gameId != null,
                    ),
                )

                is ApiResult.Failure -> UiState.Error(result.kind)
            }
        }
    }

    private companion object {
        /**
         * Placeholder until the profile region is threaded through.
         *
         * A real client reads it from the cached profile. Hardcoding one
         * region is visibly wrong rather than subtly wrong, which is the
         * better failure mode for an unfinished path.
         */
        const val DEFAULT_REGION = "EU_WEST"
    }
}

enum class LeaderboardScope { GAME, REGIONAL, GLOBAL }

data class LeaderboardUiModel(
    val scope: LeaderboardScope,
    val entries: List<LeaderboardEntry>,
    /** Resolved by the server even when outside the page, so it can be pinned. */
    val viewerEntry: LeaderboardEntry?,
    val totalEntries: Long,
    val servedFromCache: Boolean,
    val gameScopeAvailable: Boolean,
)
