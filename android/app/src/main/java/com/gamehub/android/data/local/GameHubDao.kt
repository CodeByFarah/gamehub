package com.gamehub.android.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface GameDao {

    /**
     * Returns a Flow, so the UI re-renders when the cache is refreshed without
     * the ViewModel having to coordinate anything. This is the whole reason
     * for a database rather than an in-memory cache.
     */
    @Query("SELECT * FROM games ORDER BY popularityScore DESC")
    fun observeAll(): Flow<List<GameEntity>>

    @Query("SELECT * FROM games WHERE id = :id")
    fun observeById(id: String): Flow<GameEntity?>

    /**
     * Local search over the cache, so Discover still works offline.
     *
     * Deliberately not an attempt to reproduce Postgres full-text ranking. It
     * is a substring match, and the UI labels results as offline so nobody
     * mistakes it for the real search.
     */
    @Query(
        """
        SELECT * FROM games
        WHERE title LIKE '%' || :query || '%'
           OR genre LIKE '%' || :query || '%'
           OR tags  LIKE '%' || :query || '%'
        ORDER BY popularityScore DESC
        """,
    )
    fun search(query: String): Flow<List<GameEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(games: List<GameEntity>)

    @Query("DELETE FROM games")
    suspend fun clear()

    /**
     * Replaces the cached catalogue atomically.
     *
     * Without the transaction, a reader between the delete and the insert
     * observes an empty catalogue and renders the empty state, which looks
     * exactly like a bug.
     */
    @Transaction
    suspend fun replaceAll(games: List<GameEntity>) {
        clear()
        upsertAll(games)
    }

    @Query("SELECT MIN(cachedAt) FROM games")
    suspend fun oldestCacheTimestamp(): Long?
}

@Dao
interface ProfileDao {

    @Query("SELECT * FROM profile LIMIT 1")
    fun observe(): Flow<ProfileEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: ProfileEntity)

    @Query("DELETE FROM profile")
    suspend fun clear()
}
