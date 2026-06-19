package com.ash.kandaloo.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Local database entity representing a watch party room session.
 * Stores all the info needed for smart rejoin — survives app kills and restarts.
 * Stored in internal app storage (NOT affected by "Clear Cache").
 */
@Entity(tableName = "room_sessions")
data class RoomSessionEntity(
    @PrimaryKey val roomCode: String,
    val hostName: String,
    val hostId: String,
    val isHost: Boolean,
    val videoUriString: String = "",
    val joinedAt: Long,          // Timestamp when user first joined
    val leftAt: Long = 0L,      // Timestamp when user left (0 = still active / in room)
    val maxMembers: Int = 2
)
