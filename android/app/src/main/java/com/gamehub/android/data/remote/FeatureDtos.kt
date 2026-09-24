package com.gamehub.android.data.remote

import kotlinx.serialization.Serializable

@Serializable
data class JoinQueueRequest(val gameId: String, val latencyMs: Int)

@Serializable
data class MatchmakingTicket(
    val ticketId: String,
    val status: String,
    val gameId: String,
    val region: String,
    val skillRating: Int,
    val enqueuedAt: String,
    val expiresAt: String,
    val waitedSeconds: Long,
    // A hint, not a promise. The server says so explicitly rather than
    // implying a guarantee it cannot make.
    val estimatedWaitSeconds: Long? = null,
    val matchId: String? = null,
    val opponentIds: List<String> = emptyList(),
)

@Serializable
data class StartSessionRequest(
    val gameId: String,
    val matchId: String? = null,
    val clientVersion: String? = null,
)

@Serializable
data class CompleteSessionRequest(
    val score: Int,
    val outcome: String,
    // Sent, and deliberately ignored by the server, which re-derives it.
    val perfect: Boolean = false,
)

@Serializable
data class SessionResponse(
    val id: String,
    val gameId: String,
    val matchId: String? = null,
    val startedAt: String,
    val endedAt: String? = null,
    val durationSeconds: Int? = null,
    val score: Int,
    val outcome: String? = null,
)

@Serializable
data class CloudSave(
    val id: String,
    val gameId: String,
    val slot: Int,
    val version: Long,
    val payloadSizeBytes: Int,
    val checksum: String,
    val deviceId: String? = null,
    val updatedAt: String,
    // Null in listings, which omit payloads so the screen does not transfer
    // megabytes to render a list of slots.
    val payloadBase64: String? = null,
)

@Serializable
data class CloudSaveUploadRequest(
    val gameId: String,
    val slot: Int,
    // Required, no default. Omitting it would silently get last-write-wins,
    // which is the data loss the server design exists to prevent.
    val expectedVersion: Long,
    val payloadBase64: String,
    val checksum: String,
    val deviceId: String? = null,
)

@Serializable
data class AiSearchRequest(val prompt: String)

@Serializable
data class AiSearchIntent(
    val genre: String? = null,
    val tags: List<String> = emptyList(),
    val multiplayer: Boolean? = null,
    val maxSessionMinutes: Int? = null,
    val minRating: Double? = null,
    val interpretation: String? = null,
    val confidence: Double = 0.0,
    val degraded: Boolean = false,
)

@Serializable
data class AiSearchResponse(
    val intent: AiSearchIntent,
    val results: List<GameSummary> = emptyList(),
    // True when the interpretation came from the deterministic fallback. The
    // UI says so rather than implying a model was involved.
    val degraded: Boolean = false,
)

@Serializable
data class AiAssistantRequest(val question: String, val gameId: String? = null)

@Serializable
data class AiAssistantResponse(
    val answer: String,
    val referencedGames: List<GameSummary> = emptyList(),
    // False when no catalogue context was supplied, meaning the answer may
    // describe games that do not exist here.
    val grounded: Boolean = false,
    val degraded: Boolean = false,
)
