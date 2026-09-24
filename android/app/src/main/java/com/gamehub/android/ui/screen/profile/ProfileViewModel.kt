package com.gamehub.android.ui.screen.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamehub.android.data.remote.UserProfile
import com.gamehub.android.data.repository.AuthRepository
import com.gamehub.android.data.repository.PlayerRepository
import com.gamehub.android.domain.ApiResult
import com.gamehub.android.domain.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The player profile.
 *
 * Reads from the Room cache, so it renders instantly and offline, and
 * refreshes in the background. A profile is the screen most likely to be
 * opened on a poor connection, which is exactly when a spinner is least
 * welcome.
 */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val players: PlayerRepository,
    private val auth: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<UserProfile>>(UiState.Loading)
    val state: StateFlow<UiState<UserProfile>> = _state.asStateFlow()

    init {
        players.observeProfile()
            .onEach { profile ->
                if (profile != null) {
                    val wasStale = (_state.value as? UiState.Success)?.isStale ?: false
                    _state.value = UiState.Success(profile, isStale = wasStale)
                }
            }
            .launchIn(viewModelScope)

        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            when (val result = players.refreshProfile()) {
                is ApiResult.Success ->
                    (_state.value as? UiState.Success)?.let {
                        _state.value = it.copy(isStale = false)
                    }

                is ApiResult.Failure -> {
                    val current = _state.value
                    // Cached profile on screen: banner, not an error page.
                    _state.value = if (current is UiState.Success) {
                        current.copy(isStale = true)
                    } else {
                        UiState.Error(result.kind)
                    }
                }
            }
        }
    }

    /**
     * Signs out.
     *
     * Clears the tokens and the cached profile. Navigation reacts to the
     * token store Flow rather than being driven from here, so a sign-out
     * triggered by a failed token refresh routes identically to one the
     * player asked for.
     */
    fun signOut() {
        viewModelScope.launch { auth.signOut() }
    }
}
