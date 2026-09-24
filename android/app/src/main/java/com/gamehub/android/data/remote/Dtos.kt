package com.gamehub.android.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire types, mirroring the backend DTOs exactly.
 *
 * Separate from the domain and Room models on purpose. A single shared class
 * would mean a change to the API schema silently changes the database schema
 * and the UI model, and it would force the wire format to carry fields only
 * one layer needs.
 */

@Serializable
data class PageResponse<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    // -1 when the server could not cheaply compute a total, which it reports
    // rather than guessing. The UI uses hasNext for paging instead.
    val totalItems: Long,
    val totalPages: Int,
    val hasNext: Boolean,
)

@Serializable
data class GameSummary(
    val id: String,
    val slug: String,
    val title: String,
    val shortDescription: String,
    val genre: String,
    val tags: List<String> = emptyList(),
    val iconUrl: String? = null,
    val ratingAvg: Double = 0.0,
    val ratingCount: Int = 0,
    val popularityScore: Int = 0,
    val supportsMultiplayer: Boolean = false,
    val supportsCloudSave: Boolean = false,
    val avgSessionMinutes: Int = 0,
)

@Serializable
data class GameDetail(
    val id: String,
    val slug: String,
    val title: String,
    val shortDescription: String,
    val description: String? = null,
    val genre: String,
    val tags: List<String> = emptyList(),
    val minPlayers: Int = 1,
    val maxPlayers: Int = 1,
    val avgSessionMinutes: Int = 0,
    val supportsMultiplayer: Boolean = false,
    val supportsCloudSave: Boolean = false,
    val iconUrl: String? = null,
    val bannerUrl: String? = null,
    val ratingAvg: Double = 0.0,
    val ratingCount: Int = 0,
    val popularityScore: Int = 0,
    val releasedAt: String? = null,
)

@Serializable
data class UserProfile(
    val userId: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val region: String,
    val skillRating: Int,
    val level: Int,
    val xp: Int,
    val gamesPlayed: Int,
    val gamesWon: Int,
    val winRate: Double,
    val totalPlaytimeSeconds: Long,
    val createdAt: String,
)

@Serializable
data class LeaderboardEntry(
    val rank: Long,
    val userId: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val region: String? = null,
    val score: Long,
    val achievedAt: String,
)

@Serializable
data class LeaderboardResponse(
    val scope: String,
    val period: String,
    val gameId: String? = null,
    val region: String? = null,
    val entries: List<LeaderboardEntry> = emptyList(),
    // Resolved even when outside the requested page, so the UI can pin the
    // viewer without a second request.
    val viewerEntry: LeaderboardEntry? = null,
    val totalEntries: Long = 0,
    // REDIS or POSTGRES. Surfaced by the API so a degraded read path is
    // observable rather than merely slower.
    val servedFrom: String? = null,
)

@Serializable
data class Achievement(
    val id: String,
    val code: String,
    val name: String,
    val description: String,
    val iconUrl: String? = null,
    val points: Int,
    val rarity: String,
    val gameId: String? = null,
    val unlocked: Boolean,
    val unlockedAt: String? = null,
)

@Serializable
data class Recommendation(
    val game: GameSummary,
    val score: Double,
    // Always populated by the server. An unexplained recommendation is one
    // players do not act on.
    val reason: String,
    val algorithm: String,
)
