package com.gamehub.android.ui.screen.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamehub.android.data.remote.GameSummary
import com.gamehub.android.data.remote.Recommendation
import com.gamehub.android.data.remote.UserProfile
import com.gamehub.android.data.repository.GameRepository
import com.gamehub.android.data.repository.PlayerRepository
import com.gamehub.android.domain.ApiResult
import com.gamehub.android.domain.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Home: recommendations, profile summary, and popular games.
 *
 * <h3>Why the two network calls run concurrently</h3>
 * Recommendations and the profile refresh are independent. Awaiting them in
 * sequence would make Home as slow as the sum of both for no reason. `async`
 * plus `await` starts them together and still lets each failure be handled
 * separately.
 *
 * <h3>Partial failure is a first-class outcome</h3>
 * If recommendations fail but the cached profile and catalogue are present,
 * Home renders what it has. Showing an error page because one of three
 * sections failed would throw away two that work.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val players: PlayerRepository,
    private val games: GameRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<HomeUiModel>>(UiState.Loading)
    val state: StateFlow<UiState<HomeUiModel>> = _state.asStateFlow()

    private val recommendations = MutableStateFlow<List<Recommendation>>(emptyList())
    private val recommendationsFailed = MutableStateFlow(false)

    init {
        observeCachedData()
        refresh()
    }

    /**
     * Profile and catalogue both come from Room, so Home renders instantly on
     * a cold start and works offline. `combine` re-emits whenever either
     * changes, which is what makes a background refresh appear without any
     * coordination here.
     */
    private fun observeCachedData() {
        combine(
            players.observeProfile(),
            games.observeCatalogue(),
            recommendations,
            recommendationsFailed,
        ) { profile, catalogue, recs, recsFailed ->
            HomeUiModel(
                profile = profile,
                recommendations = recs,
                popular = catalogue.take(POPULAR_COUNT),
                recommendationsUnavailable = recsFailed,
            )
        }
            .onEach { model ->
                // Nothing cached yet and the first refresh is still running.
                // Emitting here would flash an empty Home on every cold start.
                if (model.isEmpty && _state.value is UiState.Loading) {
                    return@onEach
                }
                val wasStale = (_state.value as? UiState.Success)?.isStale ?: false
                _state.value = UiState.Success(model, isStale = wasStale)
            }
            .launchIn(viewModelScope)
    }

    fun refresh() {
        viewModelScope.launch {
            // Started together rather than awaited in turn.
            val profileJob = async { players.refreshProfile() }
            val catalogueJob = async { games.refreshCatalogue() }
            val recommendationJob = async { players.recommendations(RECOMMENDATION_COUNT) }

            val profileResult = profileJob.await()
            val catalogueResult = catalogueJob.await()

            when (val recs = recommendationJob.await()) {
                is ApiResult.Success -> {
                    recommendations.value = recs.data
                    recommendationsFailed.value = false
                }
                is ApiResult.Failure -> {
                    // A new player legitimately has none, and the server
                    // returns an empty list for that rather than an error.
                    // Reaching here means the call itself failed, so the
                    // section is hidden rather than shown empty and wrong.
                    recommendationsFailed.value = true
                }
            }

            val anySucceeded = profileResult is ApiResult.Success ||
                catalogueResult is ApiResult.Success

            val current = _state.value
            when {
                // Everything failed and there is nothing cached to show.
                !anySucceeded && current !is UiState.Success ->
                    _state.value = UiState.Error(
                        (catalogueResult as? ApiResult.Failure)?.kind
                            ?: (profileResult as ApiResult.Failure).kind,
                    )

                // Refresh failed but cached data is on screen. Banner, not
                // an error page.
                !anySucceeded && current is UiState.Success ->
                    _state.value = current.copy(isStale = true)

                current is UiState.Success ->
                    _state.value = current.copy(isStale = false)
            }
        }
    }

    private companion object {
        const val POPULAR_COUNT = 10
        const val RECOMMENDATION_COUNT = 10
    }
}

data class HomeUiModel(
    val profile: UserProfile?,
    val recommendations: List<Recommendation>,
    val popular: List<GameSummary>,
    /** Hides the section entirely rather than rendering a misleading empty state. */
    val recommendationsUnavailable: Boolean = false,
) {
    val isEmpty: Boolean
        get() = profile == null && recommendations.isEmpty() && popular.isEmpty()
}
