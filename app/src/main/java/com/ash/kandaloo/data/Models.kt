package com.ash.kandaloo.data

data class VideoMetadata(
    val durationMs: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val mimeType: String = "",
    val bitrate: Long = 0L,
    val fileSize: Long = 0L
) {
    fun matches(other: VideoMetadata): Boolean {
        // Duration must match within 1 second tolerance
        val durationMatch = kotlin.math.abs(durationMs - other.durationMs) < 1000
        // Resolution must match
        val resolutionMatch = width == other.width && height == other.height
        // File size must match within 1MB tolerance (accounts for container differences)
        val sizeMatch = kotlin.math.abs(fileSize - other.fileSize) < 1_048_576
        return durationMatch && resolutionMatch && sizeMatch
    }

    fun toMap(): Map<String, Any> = mapOf(
        "durationMs" to durationMs,
        "width" to width,
        "height" to height,
        "mimeType" to mimeType,
        "bitrate" to bitrate,
        "fileSize" to fileSize
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): VideoMetadata = VideoMetadata(
            durationMs = (map["durationMs"] as? Long) ?: (map["durationMs"] as? Number)?.toLong() ?: 0L,
            width = (map["width"] as? Int) ?: (map["width"] as? Number)?.toInt() ?: 0,
            height = (map["height"] as? Int) ?: (map["height"] as? Number)?.toInt() ?: 0,
            mimeType = (map["mimeType"] as? String) ?: "",
            bitrate = (map["bitrate"] as? Long) ?: (map["bitrate"] as? Number)?.toLong() ?: 0L,
            fileSize = (map["fileSize"] as? Long) ?: (map["fileSize"] as? Number)?.toLong() ?: 0L
        )
    }
}

data class RoomData(
    val roomCode: String = "",
    val hostId: String = "",
    val hostName: String = "",
    val maxMembers: Int = 2,
    val createdAt: Long = 0L,
    val status: String = "waiting", // waiting, ready, playing, ended
    val videoMetadata: VideoMetadata? = null,
    val members: Map<String, MemberData> = emptyMap(),
    val playbackState: PlaybackState = PlaybackState()
)

data class MemberData(
    val uid: String = "",
    val displayName: String = "",
    val photoUrl: String = "",
    val isReady: Boolean = false,
    val hasMatchingFile: Boolean = false,
    val autoPlay: Boolean = false,
    val videoMetadata: VideoMetadata? = null
)

data class PlaybackState(
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val speed: Float = 1.0f,
    val lastUpdatedBy: String = "",
    val lastUpdatedAt: Long = 0L
) {
    fun toMap(): Map<String, Any> = mapOf(
        "isPlaying" to isPlaying,
        "positionMs" to positionMs,
        "speed" to speed.toDouble(),
        "lastUpdatedBy" to lastUpdatedBy,
        "lastUpdatedAt" to lastUpdatedAt
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): PlaybackState = PlaybackState(
            isPlaying = (map["isPlaying"] as? Boolean) ?: false,
            positionMs = (map["positionMs"] as? Long) ?: (map["positionMs"] as? Number)?.toLong() ?: 0L,
            speed = (map["speed"] as? Double)?.toFloat() ?: (map["speed"] as? Number)?.toFloat() ?: 1.0f,
            lastUpdatedBy = (map["lastUpdatedBy"] as? String) ?: "",
            lastUpdatedAt = (map["lastUpdatedAt"] as? Long) ?: (map["lastUpdatedAt"] as? Number)?.toLong() ?: 0L
        )
    }
}

data class ReactionEvent(
    val emoji: String = "",
    val senderId: String = "",
    val senderName: String = "",
    val timestamp: Long = 0L
)

data class ChatMessage(
    val id: String = "",
    val senderId: String = "",
    val senderName: String = "",
    val message: String = "",
    val timestamp: Long = 0L,
    val type: String = "chat", // "chat", "voice", "join", "leave", "system"
    val audioUrl: String = "",
    val audioDurationMs: Long = 0L
) {
    fun toMap(): Map<String, Any> = buildMap {
        put("senderId", senderId)
        put("senderName", senderName)
        put("message", message)
        put("timestamp", timestamp)
        put("type", type)
        if (audioUrl.isNotEmpty()) put("audioUrl", audioUrl)
        if (audioDurationMs > 0) put("audioDurationMs", audioDurationMs)
    }

    companion object {
        fun fromMap(id: String, map: Map<String, Any?>): ChatMessage = ChatMessage(
            id = id,
            senderId = (map["senderId"] as? String) ?: "",
            senderName = (map["senderName"] as? String) ?: "",
            message = (map["message"] as? String) ?: "",
            timestamp = (map["timestamp"] as? Long) ?: (map["timestamp"] as? Number)?.toLong() ?: 0L,
            type = (map["type"] as? String) ?: "chat",
            audioUrl = (map["audioUrl"] as? String) ?: "",
            audioDurationMs = (map["audioDurationMs"] as? Long) ?: (map["audioDurationMs"] as? Number)?.toLong() ?: 0L
        )
    }
}

data class RejoinInfo(
    val roomCode: String = "",
    val hostName: String = "",
    val leftAt: Long = 0L,
    val isHost: Boolean = false,
    val videoUriString: String = ""
)
