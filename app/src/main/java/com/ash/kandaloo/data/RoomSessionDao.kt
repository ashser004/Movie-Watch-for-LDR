package com.ash.kandaloo.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Data Access Object for room_sessions table.
 * Provides all the queries needed for smart rejoin functionality.
 */
@Dao
interface RoomSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: RoomSessionEntity)

    /** Get all sessions where the user has left (for showing rejoin options) */
    @Query("SELECT * FROM room_sessions WHERE leftAt > 0 ORDER BY leftAt DESC")
    suspend fun getLeftSessions(): List<RoomSessionEntity>

    /** Get a specific session by room code */
    @Query("SELECT * FROM room_sessions WHERE roomCode = :roomCode")
    suspend fun getSession(roomCode: String): RoomSessionEntity?

    /** Delete a specific session */
    @Query("DELETE FROM room_sessions WHERE roomCode = :roomCode")
    suspend fun delete(roomCode: String)

    /** Delete sessions older than a given timestamp (for 24-hour cleanup) */
    @Query("DELETE FROM room_sessions WHERE joinedAt < :cutoffTimestamp")
    suspend fun deleteOlderThan(cutoffTimestamp: Long)

    /** Get all sessions (for debugging / cleanup) */
    @Query("SELECT * FROM room_sessions")
    suspend fun getAll(): List<RoomSessionEntity>
}
