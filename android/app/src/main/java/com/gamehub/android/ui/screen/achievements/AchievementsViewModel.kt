package com.gamehub.android.ui.screen.achievements

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamehub.android.data.remote.Achievement
import com.gamehub.android.data.repository.PlayerRepository
import com.gamehub.android.domain.ApiResult
import com.gamehub.android.domain.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Achievements, unlocked and locked.
 *
 * Locked ones are shown deliberately. A list of only what you already have is
 * a trophy cabinet; the goals are the part that drives play. The server
 * returns both when a gameId is supplied, which is why that call differs from
 * the unfiltered one.
 */
@HiltViewModel
class AchievementsViewModel @Inject constructor(
    private val players: PlayerRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val gameId: String? = savedStateHandle["gameId"]

    private val _state = MutableStateFlow<UiState<AchievementsUiModel>>(UiState.Loading)
    val state: StateFlow<UiState<AchievementsUiModel>> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = UiState.Loading

            _state.value = when (val result = players.achievements(gameId)) {
                is ApiResult.Success -> UiState.Success(model(result.data))
                is ApiResult.Failure -> UiState.Error(result.kind)
            }
        }
    }

    private fun model(all: List<Achievement>): AchievementsUiModel {
        val (unlocked, locked) = all.partition { it.unlocked }

        return AchievementsUiModel(
            // Most recent first, so the thing just earned is at the top.
            unlocked = unlocked.sortedByDescending { it.unlockedAt },
            // Cheapest first, so the next achievable goal is visible without
            // scrolling past the legendary ones.
            locked = locked.sortedBy { it.points },
            earnedPoints = unlocked.sumOf { it.points },
            totalPoints = all.sumOf { it.points },
            // Only meaningful for a single game. Across the whole catalogue
            // the denominator is every achievement that exists, which is not
            // a target anyone is working towards.
            showsProgress = gameId != null,
        )
    }
}

data class AchievementsUiModel(
    val unlocked: List<Achievement>,
    val locked: List<Achievement>,
    val earnedPoints: Int,
    val totalPoints: Int,
    val showsProgress: Boolean,
) {
    val isEmpty: Boolean get() = unlocked.isEmpty() && locked.isEmpty()

    val progress: Float
        get() = if (totalPoints == 0) 0f else earnedPoints / totalPoints.toFloat()
}
