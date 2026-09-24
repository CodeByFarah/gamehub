package com.gamehub.android.ui.screen.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamehub.android.data.remote.GameSummary
import com.gamehub.android.data.repository.AiRepository
import com.gamehub.android.domain.ApiResult
import com.gamehub.android.domain.ErrorKind
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The game assistant.
 *
 * <h3>Honesty is the feature</h3>
 * Every answer carries two flags from the server, and both are shown:
 *
 * - `degraded` means the deterministic fallback answered, not a model.
 * - `grounded` means the answer was produced from real catalogue rows.
 *
 * An ungrounded answer may describe games that do not exist here, and saying
 * so is the difference between a useful assistant and a confident one.
 *
 * <h3>No local retry</h3>
 * The server already wraps the provider in a timeout, a jittered retry, a
 * circuit breaker and a fallback. A client-side retry would stack a second
 * policy on top of one that has already given up, and spend battery doing it.
 */
@HiltViewModel
class AssistantViewModel @Inject constructor(
    private val ai: AiRepository,
) : ViewModel() {

    private val _messages = MutableStateFlow<List<AssistantMessage>>(emptyList())
    val messages: StateFlow<List<AssistantMessage>> = _messages.asStateFlow()

    private val _thinking = MutableStateFlow(false)
    val thinking: StateFlow<Boolean> = _thinking.asStateFlow()

    fun ask(question: String, gameId: String? = null) {
        val trimmed = question.trim()
        if (trimmed.isEmpty() || _thinking.value) return

        // Echoed immediately, before the request. Waiting for the response to
        // show what was asked makes a slow answer feel like a dropped input.
        _messages.update { it + AssistantMessage.FromPlayer(trimmed) }

        viewModelScope.launch {
            _thinking.value = true

            val reply = when (val result = ai.ask(trimmed, gameId)) {
                is ApiResult.Success -> AssistantMessage.FromAssistant(
                    text = result.data.answer,
                    referencedGames = result.data.referencedGames,
                    degraded = result.data.degraded,
                    grounded = result.data.grounded,
                )

                is ApiResult.Failure -> AssistantMessage.Failed(
                    // Reaching here means both the provider and the fallback
                    // failed, which the server treats as a real outage rather
                    // than routine provider flakiness.
                    retryable = result.kind != ErrorKind.UNAUTHORIZED,
                )
            }

            _messages.update { it + reply }
            _thinking.value = false
        }
    }

    fun clear() {
        _messages.value = emptyList()
    }
}

sealed interface AssistantMessage {

    data class FromPlayer(val text: String) : AssistantMessage

    data class FromAssistant(
        val text: String,
        val referencedGames: List<GameSummary>,
        /** Answered by keyword matching, not a model. */
        val degraded: Boolean,
        /** Produced from real catalogue rows rather than training data. */
        val grounded: Boolean,
    ) : AssistantMessage

    data class Failed(val retryable: Boolean) : AssistantMessage
}
