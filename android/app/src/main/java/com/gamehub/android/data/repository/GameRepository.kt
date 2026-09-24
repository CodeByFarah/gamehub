package com.gamehub.android.data.repository

import com.gamehub.android.data.local.GameDao
import com.gamehub.android.data.local.GameEntity
import com.gamehub.android.data.remote.GameHubApi
import com.gamehub.android.data.remote.GameSummary
import com.gamehub.android.domain.ApiResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The catalogue, cache-first.
 *
 * <h3>Why cache-first and not network-first</h3>
 * The screen renders immediately from Room while the network refresh runs, so
 * a slow connection shows content rather than a spinner. On a failed refresh
 * the cached data stays on screen and the UI adds an offline banner, rather
 * than replacing real content with an error page.
 *
 * Network-first would mean every launch shows a spinner for the duration of
 * the request, and an offline launch shows nothing at all.
 *
 * <h3>Flow of truth</h3>
 * The UI observes Room, never the network response. That inversion is what
 * makes the refresh transparent: a successful write to the cache re-emits the
 * Flow and the screen updates, with no coordination in the ViewModel.
 */
@Singleton
class GameRepository @Inject constructor(
    private val api: GameHubApi,
    private val dao: GameDao,
    private val json: Json,
) {

    /** Cache freshness horizon. Matches the server catalogue TTL. */
    private val staleAfterMillis = 5 * 60 * 1000L

    fun observeCatalogue(): Flow<List<GameSummary>> =
        dao.observeAll().map { entities -> entities.map { it.toSummary() } }

    fun observeGame(id: String): Flow<GameSummary?> =
        dao.observeById(id).map { it?.toSummary() }

    /**
     * Local substring search, so Discover works offline.
     *
     * Deliberately not an attempt to reproduce Postgres full-text ranking.
     * The UI labels these results as offline so nobody mistakes them for the
     * real search.
     */
    fun searchLocally(query: String): Flow<List<GameSummary>> =
        dao.search(query).map { entities -> entities.map { it.toSummary() } }

    /**
     * Refreshes the cache from the network.
     *
     * @return the outcome, so the caller can decide whether to show an
     *         offline banner. It does **not** return the data: the data
     *         arrives through the Flow.
     */
    suspend fun refreshCatalogue(): ApiResult<Unit> {
        val result = apiCall(json) { api.games(page = 0, size = 50) }

        return when (result) {
            is ApiResult.Success -> {
                val now = System.currentTimeMillis()
                // Replaced atomically. Without the transaction a reader
                // between the delete and the insert sees an empty catalogue
                // and renders the empty state, which looks like a bug.
                dao.replaceAll(result.data.items.map { it.toEntity(now) })
                ApiResult.Success(Unit)
            }
            is ApiResult.Failure -> result
        }
    }

    /**
     * Server-side search, which is the real one: stemming, phrase queries and
     * a trigram fallback for misspellings.
     *
     * Results are **not** written to the cache. Search results are a slice
     * ordered by relevance, and persisting them would corrupt the
     * popularity-ordered catalogue the Home screen reads.
     */
    suspend fun search(query: String): ApiResult<List<GameSummary>> =
        apiCall(json) { api.games(query = query, size = 20) }.let { result ->
            when (result) {
                is ApiResult.Success -> ApiResult.Success(result.data.items)
                is ApiResult.Failure -> result
            }
        }

    suspend fun isCacheStale(): Boolean {
        val oldest = dao.oldestCacheTimestamp() ?: return true
        return System.currentTimeMillis() - oldest > staleAfterMillis
    }
}

private fun GameEntity.toSummary() = GameSummary(
    id = id,
    slug = slug,
    title = title,
    shortDescription = shortDescription,
    genre = genre,
    tags = if (tags.isBlank()) emptyList() else tags.split(","),
    iconUrl = iconUrl,
    ratingAvg = ratingAvg,
    ratingCount = ratingCount,
    popularityScore = popularityScore,
    supportsMultiplayer = supportsMultiplayer,
    supportsCloudSave = supportsCloudSave,
    avgSessionMinutes = avgSessionMinutes,
)

private fun GameSummary.toEntity(cachedAt: Long) = GameEntity(
    id = id,
    slug = slug,
    title = title,
    shortDescription = shortDescription,
    genre = genre,
    tags = tags.joinToString(","),
    iconUrl = iconUrl,
    ratingAvg = ratingAvg,
    ratingCount = ratingCount,
    popularityScore = popularityScore,
    supportsMultiplayer = supportsMultiplayer,
    supportsCloudSave = supportsCloudSave,
    avgSessionMinutes = avgSessionMinutes,
    cachedAt = cachedAt,
)
