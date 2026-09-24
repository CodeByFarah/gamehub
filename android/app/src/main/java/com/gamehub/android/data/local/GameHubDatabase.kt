package com.gamehub.android.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The local cache.
 *
 * `exportSchema = true` writes the schema to a JSON file that is committed.
 * That file is what makes a future migration reviewable and what lets Room
 * verify a migration actually produces the expected schema. Turning it off
 * silences a warning and removes the only record of what the schema was.
 */
@Database(
    entities = [GameEntity::class, ProfileEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class GameHubDatabase : RoomDatabase() {
    abstract fun gameDao(): GameDao
    abstract fun profileDao(): ProfileDao
}
