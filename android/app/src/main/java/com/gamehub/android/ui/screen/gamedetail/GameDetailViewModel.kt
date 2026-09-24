package com.gamehub.android.ui.screen.gamedetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamehub.android.data.remote.GameSummary
import com.gamehub.android.data.repository.GameRepository
import com.gamehub.android.domain.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

/**
 * One game.
 *
 * Served from the Room cache, because arriving here always means the player
 * just tapped a row that was rendered from that same cache. Re-fetching would
 * show a spinner over data already on screen a moment ago.
 */
@HiltViewModel
class GameDetailViewModel @Inject constructor(
    games: GameRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val gameId: String = requireNotNull(savedStateHandle["gameId"]) {
        "GameDetail requires a gameId navigation argument"
    }

    private val _state = MutableStateFlow<UiState<GameSummary>>(UiState.Loading)
    val state: StateFlow<UiState<GameSummary>> = _state.asStateFlow()

    init {
        games.observeGame(gameId)
            .onEach { game ->
                _state.value = if (game != null) {
                    UiState.Success(game)
                } else {
                    // Cached and then delisted, or a stale deep link. Not
                    // retryable: retrying cannot make a removed game exist.
                    UiState.Error(com.gamehub.android.domain.ErrorKind.CLIENT, canRetry = false)
                }
            }
            .launchIn(viewModelScope)
    }

    fun gameId(): String = gameId
}
