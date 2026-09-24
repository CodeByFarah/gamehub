package com.gamehub.android.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entities.
 *
 * Only the catalogue and the profile are cached. Leaderboards, matchmaking
 * tickets and cloud saves deliberately are not: a stale leaderboard is
 * misleading rather than useful, a stale ticket is actively wrong, and a
 * cached save would invite the client to write from a version it no longer
 * holds, which is exactly the conflict the server design guards against.
 *
 * `cachedAt` is on every row so the repository can decide whether to serve
 * the cache, refresh in the background, or both.
 */

@Entity(tableName = "games")
data class GameEntity(
    @PrimaryKey val id: String,
    val slug: String,
    val title: String,
    val shortDescription: String,
    val genre: String,
    // Room stores no lists. Joined with a separator rather than pulling in a
    // JSON converter for one field; the tag vocabulary contains no commas.
    val tags: String,
    val iconUrl: String?,
    val ratingAvg: Double,
    val ratingCount: Int,
    val popularityScore: Int,
    val supportsMultiplayer: Boolean,
    val supportsCloudSave: Boolean,
    val avgSessionMinutes: Int,
    val cachedAt: Long,
)

@Entity(tableName = "profile")
data class ProfileEntity(
    @PrimaryKey val userId: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String?,
    val region: String,
    val skillRating: Int,
    val level: Int,
    val xp: Int,
    val gamesPlayed: Int,
    val gamesWon: Int,
    val winRate: Double,
    val totalPlaytimeSeconds: Long,
    val cachedAt: Long,
)
