package com.gamehub.android.data.repository

import com.gamehub.android.data.local.ProfileDao
import com.gamehub.android.data.local.ProfileEntity
import com.gamehub.android.data.remote.Achievement
import com.gamehub.android.data.remote.GameHubApi
import com.gamehub.android.data.remote.Recommendation
import com.gamehub.android.data.remote.UserProfile
import com.gamehub.android.domain.ApiResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlayerRepository @Inject constructor(
    private val api: GameHubApi,
    private val profileDao: ProfileDao,
    private val json: Json,
) {

    /** Cached, so the Profile screen renders instantly and offline. */
    fun observeProfile(): Flow<UserProfile?> =
        profileDao.observe().map { it?.toProfile() }

    suspend fun refreshProfile(): ApiResult<Unit> =
        when (val result = apiCall(json) { api.me() }) {
            is ApiResult.Success -> {
                profileDao.upsert(result.data.toEntity(System.currentTimeMillis()))
                ApiResult.Success(Unit)
            }
            is ApiResult.Failure -> result
        }

    /**
     * Achievements are not cached.
     *
     * They are small, they change as a direct result of something the player
     * just did, and showing a stale unlock state is worse than showing a
     * spinner: a player who just earned something and does not see it assumes
     * the feature is broken.
     */
    suspend fun achievements(gameId: String? = null): ApiResult<List<Achievement>> =
        apiCall(json) { api.achievements(gameId) }

    /**
     * Recommendations are not cached either.
     *
     * They are regenerated server-side after every completed game, so a cached
     * copy would frequently be one game behind the reason it changed.
     */
    suspend fun recommendations(limit: Int = 10): ApiResult<List<Recommendation>> =
        apiCall(json) { api.recommendations(limit) }
}

private fun ProfileEntity.toProfile() = UserProfile(
    userId = userId,
    username = username,
    displayName = displayName,
    avatarUrl = avatarUrl,
    region = region,
    skillRating = skillRating,
    level = level,
    xp = xp,
    gamesPlayed = gamesPlayed,
    gamesWon = gamesWon,
    winRate = winRate,
    totalPlaytimeSeconds = totalPlaytimeSeconds,
    createdAt = "",
)

private fun UserProfile.toEntity(cachedAt: Long) = ProfileEntity(
    userId = userId,
    username = username,
    displayName = displayName,
    avatarUrl = avatarUrl,
    region = region,
    skillRating = skillRating,
    level = level,
    xp = xp,
    gamesPlayed = gamesPlayed,
    gamesWon = gamesWon,
    winRate = winRate,
    totalPlaytimeSeconds = totalPlaytimeSeconds,
    cachedAt = cachedAt,
)
