package com.gamehub.android.ui.screen.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamehub.android.data.remote.AiSearchIntent
import com.gamehub.android.data.remote.GameSummary
import com.gamehub.android.data.repository.AiRepository
import com.gamehub.android.data.repository.GameRepository
import com.gamehub.android.domain.ApiResult
import com.gamehub.android.domain.ErrorKind
import com.gamehub.android.domain.UiState
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
 * Discover: browse, search, and natural-language search.
 *
 * <h3>Why the cache is the source of truth for the UI</h3>
 * The screen observes Room and never the network response. A refresh writes to
 * Room, the Flow re-emits, and the screen updates with no coordination here.
 *
 * That inversion is what makes an offline launch show content instead of a
 * spinner, and a failed refresh keep content on screen with a banner rather
 * than replacing it with an error page.
 */
@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val games: GameRepository,
    private val ai: AiRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<DiscoverUiModel>>(UiState.Loading)
    val state: StateFlow<UiState<DiscoverUiModel>> = _state.asStateFlow()

    /** Cancelled and replaced on each new query, so a slow earlier search
     *  cannot overwrite the results of a later one. */
    private var searchJob: Job? = null

    init {
        observeCache()
        refresh()
    }

    private fun observeCache() {
        games.observeCatalogue()
            .onEach { catalogue ->
                val current = _state.value
                // An empty cache before the first refresh completes is not an
                // empty catalogue, it is a cache that has not been filled.
                // Rendering the empty state here would flash "no games" on
                // every cold start.
                if (catalogue.isEmpty() && current is UiState.Loading) {
                    return@onEach
                }
                _state.value = UiState.Success(
                    DiscoverUiModel(games = catalogue),
                    isStale = (current as? UiState.Success)?.isStale ?: false,
                )
            }
            .launchIn(viewModelScope)
    }

    fun refresh() {
        viewModelScope.launch {
            when (val result = games.refreshCatalogue()) {
                is ApiResult.Success -> {
                    // The Flow delivers the data. Only the staleness flag is
                    // cleared here.
                    (_state.value as? UiState.Success)?.let {
                        _state.value = it.copy(isStale = false)
                    }
                }

                is ApiResult.Failure -> {
                    val current = _state.value
                    _state.value = if (current is UiState.Success && current.data.games.isNotEmpty()) {
                        // Keep showing real data. Replacing it with an error
                        // screen would throw away content the user can still
                        // use.
                        current.copy(isStale = true)
                    } else {
                        UiState.Error(result.kind, canRetry = result.kind != ErrorKind.UNAUTHORIZED)
                    }
                }
            }
        }
    }

    /**
     * Plain search. Server-side when possible, local substring otherwise.
     *
     * A blank query returns to the full catalogue rather than searching for
     * nothing, which would render the empty state.
     */
    fun search(query: String) {
        searchJob?.cancel()

        if (query.isBlank()) {
            searchJob = games.observeCatalogue()
                .onEach { _state.value = UiState.Success(DiscoverUiModel(games = it)) }
                .launchIn(viewModelScope)
            return
        }

        searchJob = viewModelScope.launch {
            when (val result = games.search(query)) {
                is ApiResult.Success ->
                    _state.value = UiState.Success(DiscoverUiModel(games = result.data))

                is ApiResult.Failure -> {
                    // Offline: fall back to the local cache and say so, rather
                    // than showing an error for a query the device can
                    // partially answer.
                    games.searchLocally(query)
                        .onEach {
                            _state.value = UiState.Success(
                                DiscoverUiModel(games = it),
                                isStale = true,
                            )
                        }
                        .launchIn(viewModelScope)
                }
            }
        }
    }

    /**
     * Natural-language search.
     *
     * The `degraded` flag from the response is carried into the UI model
     * unchanged. The server reports it honestly and the screen says so, rather
     * than implying a model was involved when the deterministic fallback
     * answered.
     */
    fun aiSearch(prompt: String) {
        if (prompt.isBlank()) return

        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.value = UiState.Loading

            when (val result = ai.search(prompt)) {
                is ApiResult.Success -> _state.value = UiState.Success(
                    DiscoverUiModel(
                        games = result.data.results,
                        aiIntent = result.data.intent,
                        aiDegraded = result.data.degraded,
                    ),
                )

                is ApiResult.Failure -> _state.value =
                    UiState.Error(result.kind, canRetry = result.kind != ErrorKind.UNAUTHORIZED)
            }
        }
    }
}

/**
 * @param aiIntent the interpretation, shown back to the user so they can tell
 *        whether the system understood them. A search that silently applies
 *        filters the user did not ask for is one they cannot correct.
 */
data class DiscoverUiModel(
    val games: List<GameSummary>,
    val aiIntent: AiSearchIntent? = null,
    val aiDegraded: Boolean = false,
)
