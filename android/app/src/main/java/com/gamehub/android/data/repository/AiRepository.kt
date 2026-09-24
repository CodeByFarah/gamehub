package com.gamehub.android.data.repository

import com.gamehub.android.data.remote.AiAssistantRequest
import com.gamehub.android.data.remote.AiAssistantResponse
import com.gamehub.android.data.remote.AiSearchRequest
import com.gamehub.android.data.remote.AiSearchResponse
import com.gamehub.android.data.remote.GameHubApi
import com.gamehub.android.domain.ApiResult
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI search and the assistant.
 *
 * Nothing here is cached and nothing is retried locally. The server already
 * wraps the provider in a timeout, a retry with jitter, a circuit breaker and
 * a deterministic fallback, so a client-side retry would stack a second retry
 * policy on top of one that has already given up, and would spend the user
 * battery doing it.
 *
 * Both responses carry `degraded`, which the UI surfaces rather than hides.
 */
@Singleton
class AiRepository @Inject constructor(
    private val api: GameHubApi,
    private val json: Json,
) {

    suspend fun search(prompt: String): ApiResult<AiSearchResponse> =
        apiCall(json) { api.aiSearch(AiSearchRequest(prompt.trim())) }

    suspend fun ask(question: String, gameId: String? = null): ApiResult<AiAssistantResponse> =
        apiCall(json) { api.aiAssistant(AiAssistantRequest(question.trim(), gameId)) }
}
