package com.ash.kandaloo.data

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The local Room database for KanDaloo.
 * Stores room session data for smart rejoin functionality.
 * Database file lives at /data/data/com.ash.kandaloo/databases/kandaloo_db
 * — NOT affected by "Clear Cache", only "Clear Data" or uninstall.
 */
@Database(entities = [RoomSessionEntity::class], version = 2, exportSchema = false)
abstract class KanDalooDatabase : RoomDatabase() {
    abstract fun roomSessionDao(): RoomSessionDao
}
