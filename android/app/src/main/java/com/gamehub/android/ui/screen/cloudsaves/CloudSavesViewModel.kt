package com.gamehub.android.ui.screen.cloudsaves

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamehub.android.data.remote.CloudSave
import com.gamehub.android.data.repository.CloudSaveRepository
import com.gamehub.android.domain.ApiResult
import com.gamehub.android.domain.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Cloud saves, including the conflict path.
 *
 * <h3>The conflict is a state, not an error</h3>
 * A 409 here is a documented outcome carrying the data needed to recover. It
 * gets its own state in [ConflictPrompt] rather than being routed through the
 * generic error handling, because the right response is a choice presented to
 * the player, not a retry button.
 *
 * Treating it as an error is the bug that loses player progress.
 */
@HiltViewModel
class CloudSavesViewModel @Inject constructor(
    private val cloudSaves: CloudSaveRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<List<CloudSave>>>(UiState.Loading)
    val state: StateFlow<UiState<List<CloudSave>>> = _state.asStateFlow()

    private val _conflict = MutableStateFlow<ConflictPrompt?>(null)
    val conflict: StateFlow<ConflictPrompt?> = _conflict.asStateFlow()

    private val _uploading = MutableStateFlow(false)
    val uploading: StateFlow<Boolean> = _uploading.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = UiState.Loading

            _state.value = when (val result = cloudSaves.list()) {
                is ApiResult.Success -> UiState.Success(result.data)
                is ApiResult.Failure -> UiState.Error(result.kind)
            }
        }
    }

    /**
     * Uploads a save.
     *
     * @param expectedVersion the version this device holds. There is no
     *        default: omitting it would mean silently accepting
     *        last-write-wins, which is the data loss the server design exists
     *        to prevent.
     */
    fun upload(gameId: String, payload: ByteArray, expectedVersion: Long, slot: Int = 0) {
        viewModelScope.launch {
            _uploading.value = true

            when (val outcome = cloudSaves.upload(gameId, payload, expectedVersion, slot)) {
                is CloudSaveRepository.SaveOutcome.Saved -> {
                    _conflict.value = null
                    refresh()
                }

                is CloudSaveRepository.SaveOutcome.Conflict -> {
                    // The local payload is kept so "keep this device" can
                    // retry immediately at the server version, without asking
                    // the game to regenerate it.
                    _conflict.value = ConflictPrompt(
                        gameId = gameId,
                        slot = slot,
                        localPayload = payload,
                        serverVersion = outcome.serverVersion,
                        serverChecksum = outcome.serverChecksum,
                    )
                }

                is CloudSaveRepository.SaveOutcome.Failed ->
                    _state.value = UiState.Error(outcome.failure.kind)
            }

            _uploading.value = false
        }
    }

    /**
     * Retries the local payload at the version the server reported.
     *
     * This can conflict again if a third write landed in between, which is
     * correct: the loop terminates because each round advances the version.
     */
    fun keepLocal() {
        val prompt = _conflict.value ?: return
        upload(prompt.gameId, prompt.localPayload, prompt.serverVersion, prompt.slot)
    }

    /**
     * Abandons the local write and takes the server copy.
     *
     * Nothing is uploaded. The next download brings the server version down,
     * and the local progress is discarded because the player chose to.
     */
    fun keepServer() {
        _conflict.value = null
        refresh()
    }

    fun dismissConflict() {
        // Dismissing is not the same as resolving. The local save stays
        // unsynced, and the next upload attempt will conflict again, which is
        // the honest outcome of deferring the decision.
        _conflict.value = null
    }
}

/**
 * Everything needed to resolve a conflict without another request.
 *
 * The server sends `serverVersion` and `serverChecksum` with the 409 for
 * exactly this reason: a bare conflict would force a second round trip, during
 * which the version could change again.
 */
data class ConflictPrompt(
    val gameId: String,
    val slot: Int,
    val localPayload: ByteArray,
    val serverVersion: Long,
    val serverChecksum: String,
) {
    // ByteArray uses identity equality, so a data class holding one needs
    // these written out or two prompts with identical content compare unequal.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ConflictPrompt) return false
        return gameId == other.gameId &&
            slot == other.slot &&
            serverVersion == other.serverVersion &&
            serverChecksum == other.serverChecksum &&
            localPayload.contentEquals(other.localPayload)
    }

    override fun hashCode(): Int {
        var result = gameId.hashCode()
        result = 31 * result + slot
        result = 31 * result + serverVersion.hashCode()
        result = 31 * result + serverChecksum.hashCode()
        result = 31 * result + localPayload.contentHashCode()
        return result
    }
}
