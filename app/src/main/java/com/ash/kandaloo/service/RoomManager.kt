package com.ash.kandaloo.service

import com.ash.kandaloo.data.ChatMessage
import com.ash.kandaloo.data.MemberData
import com.ash.kandaloo.data.PlaybackState
import com.ash.kandaloo.data.ReactionEvent
import com.ash.kandaloo.data.RejoinInfo
import com.ash.kandaloo.data.VideoMetadata
import com.ash.kandaloo.data.CloudinarySignatureResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.random.Random
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.google.android.gms.tasks.Task

class RoomManager {

    private val database = FirebaseDatabase.getInstance("https://kandaloo-default-rtdb.asia-southeast1.firebasedatabase.app")
    private val roomsRef = database.getReference("rooms")
    private val usersRef = database.getReference("users")
    private val auth = FirebaseAuth.getInstance()

    private val _isConnected = MutableStateFlow(true)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    init {
        database.getReference(".info/connected").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val connected = snapshot.getValue(Boolean::class.java) ?: false
                _isConnected.value = connected
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    val currentUser get() = auth.currentUser

    fun generateRoomCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..6).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }

    fun createRoom(
        roomCode: String,
        maxMembers: Int,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        val user = currentUser ?: run { onFailure("Not logged in"); return }

        val roomData = mapOf(
            "roomCode" to roomCode,
            "hostId" to user.uid,
            "hostName" to (user.displayName ?: "Host"),
            "maxMembers" to maxMembers,
            "createdAt" to ServerValue.TIMESTAMP,
            "status" to "waiting",
            "members/${user.uid}" to mapOf(
                "uid" to user.uid,
                "displayName" to (user.displayName ?: "Host"),
                "photoUrl" to (user.photoUrl?.toString() ?: ""),
                "isReady" to true,
                "hasMatchingFile" to false,
                "state" to "active"
            ),
            "memberHistory/${user.uid}" to true
        )

        roomsRef.child(roomCode).updateChildren(roomData)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { onFailure(it.message ?: "Failed to create room") }
    }

    fun joinRoom(
        roomCode: String,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        val user = currentUser ?: run { onFailure("Not logged in"); return }

        roomsRef.child(roomCode).get().addOnSuccessListener { snapshot ->
            if (!snapshot.exists()) {
                onFailure("Room not found")
                return@addOnSuccessListener
            }

            val maxMembers = (snapshot.child("maxMembers").value as? Long)?.toInt() ?: 2
            val currentMembers = snapshot.child("members").children.count {
                val state = it.child("state").value as? String
                state != "left"
            }

            if (currentMembers >= maxMembers) {
                onFailure("Room is full")
                return@addOnSuccessListener
            }

            val status = snapshot.child("status").value as? String ?: "waiting"
            if (status == "ended") {
                onFailure("Room has ended")
                return@addOnSuccessListener
            }

            val memberData = mapOf(
                "uid" to user.uid,
                "displayName" to (user.displayName ?: "Member"),
                "photoUrl" to (user.photoUrl?.toString() ?: ""),
                "isReady" to false,
                "hasMatchingFile" to false,
                "state" to "active"
            )

            val updates = mapOf(
                "members/${user.uid}" to memberData,
                "memberHistory/${user.uid}" to true
            )

            roomsRef.child(roomCode).updateChildren(updates)
                .addOnSuccessListener {
                    sendJoinNotification(roomCode)
                    onSuccess()
                }
                .addOnFailureListener { onFailure(it.message ?: "Failed to join room") }
        }.addOnFailureListener {
            onFailure(it.message ?: "Failed to check room")
        }
    }

    fun setVideoMetadata(
        roomCode: String,
        metadata: VideoMetadata,
        isHost: Boolean
    ) {
        val user = currentUser ?: return

        val updates = mutableMapOf<String, Any>(
            "members/${user.uid}/videoMetadata" to metadata.toMap(),
        )

        if (isHost) {
            updates["videoMetadata"] = metadata.toMap()
        }

        roomsRef.child(roomCode).updateChildren(updates)
    }

    fun setMemberFileMatch(roomCode: String, matches: Boolean) {
        val user = currentUser ?: return
        roomsRef.child(roomCode)
            .child("members").child(user.uid)
            .child("hasMatchingFile").setValue(matches)
    }

    fun setMemberReady(roomCode: String, ready: Boolean) {
        val user = currentUser ?: return
        roomsRef.child(roomCode)
            .child("members").child(user.uid)
            .child("isReady").setValue(ready)
    }

    fun startParty(roomCode: String, autoPlay: Boolean = true, onSuccess: (() -> Unit)? = null, onFailure: ((String) -> Unit)? = null) {
        // Single atomic write to prevent multiple onDataChange callbacks and reduce startup lag
        val playbackState = PlaybackState(
            isPlaying = autoPlay,
            positionMs = 0L,
            speed = 1.0f,
            lastUpdatedBy = currentUser?.uid ?: "",
            lastUpdatedAt = System.currentTimeMillis()
        )
        val updates = mapOf<String, Any?>(
            "chat" to null,
            "reactions" to null,
            "status" to "playing",
            "playbackState" to playbackState.toMap()
        )
        roomsRef.child(roomCode).updateChildren(updates)
            .addOnSuccessListener { onSuccess?.invoke() }
            .addOnFailureListener { e -> onFailure?.invoke(e.message ?: "Failed to start party") }
    }

    fun setMemberAutoPlay(roomCode: String, autoPlay: Boolean) {
        val user = currentUser ?: return
        roomsRef.child(roomCode)
            .child("members").child(user.uid)
            .child("autoPlay").setValue(autoPlay)
    }

    fun updatePlaybackState(roomCode: String, state: PlaybackState) {
        roomsRef.child(roomCode).child("playbackState").setValue(state.toMap())
    }

    // ─── Skip Lock (prevents multiple users skipping simultaneously) ───

    fun setSkipLock(roomCode: String, userId: String) {
        roomsRef.child(roomCode).child("skipLock").setValue(
            mapOf(
                "lockedBy" to userId,
                "lockedAt" to ServerValue.TIMESTAMP
            )
        )
    }

    fun observeSkipLock(roomCode: String): Flow<Pair<String, Long>> = callbackFlow {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val lockedBy = snapshot.child("lockedBy").getValue(String::class.java) ?: ""
                val lockedAt = snapshot.child("lockedAt").getValue(Long::class.java) ?: 0L
                trySend(Pair(lockedBy, lockedAt))
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        roomsRef.child(roomCode).child("skipLock").addValueEventListener(listener)
        awaitClose { roomsRef.child(roomCode).child("skipLock").removeEventListener(listener) }
    }

    fun sendReaction(roomCode: String, emoji: String) {
        val user = currentUser ?: return
        val reactionKey = roomsRef.child(roomCode).child("reactions").push().key ?: return
        val reaction = mapOf(
            "emoji" to emoji,
            "senderId" to user.uid,
            "senderName" to (user.displayName ?: ""),
            "timestamp" to ServerValue.TIMESTAMP
        )
        val updates = mapOf(
            "reactions/$reactionKey" to reaction,
            "lastReactionWrite/${user.uid}" to ServerValue.TIMESTAMP
        )
        roomsRef.child(roomCode).updateChildren(updates)
    }

    fun observeRoom(roomCode: String): Flow<Map<String, Any?>> = callbackFlow {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                @Suppress("UNCHECKED_CAST")
                val data = snapshot.value as? Map<String, Any?> ?: emptyMap()
                trySend(data)
            }

            override fun onCancelled(error: DatabaseError) {
                close(error.toException())
            }
        }
        roomsRef.child(roomCode).addValueEventListener(listener)
        awaitClose { roomsRef.child(roomCode).removeEventListener(listener) }
    }

    fun observePlaybackState(roomCode: String): Flow<PlaybackState> = callbackFlow {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                @Suppress("UNCHECKED_CAST")
                val map = snapshot.value as? Map<String, Any?> ?: return
                trySend(PlaybackState.fromMap(map))
            }

            override fun onCancelled(error: DatabaseError) {
                close(error.toException())
            }
        }
        roomsRef.child(roomCode).child("playbackState").addValueEventListener(listener)
        awaitClose { roomsRef.child(roomCode).child("playbackState").removeEventListener(listener) }
    }

    fun observeReactions(roomCode: String): Flow<ReactionEvent> = callbackFlow {
        val listener = object : com.google.firebase.database.ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                @Suppress("UNCHECKED_CAST")
                val map = snapshot.value as? Map<String, Any?> ?: return
                val event = ReactionEvent(
                    emoji = map["emoji"] as? String ?: "",
                    senderId = map["senderId"] as? String ?: "",
                    senderName = map["senderName"] as? String ?: "",
                    timestamp = (map["timestamp"] as? Long) ?: (map["timestamp"] as? Number)?.toLong() ?: 0L
                )
                trySend(event)
            }

            override fun onChildChanged(s: DataSnapshot, p: String?) {}
            override fun onChildRemoved(s: DataSnapshot) {}
            override fun onChildMoved(s: DataSnapshot, p: String?) {}
            override fun onCancelled(error: DatabaseError) {
                close(error.toException())
            }
        }
        roomsRef.child(roomCode).child("reactions").addChildEventListener(listener)
        awaitClose { roomsRef.child(roomCode).child("reactions").removeEventListener(listener) }
    }

    fun leaveRoom(roomCode: String, videoUriString: String = "", videoFileName: String = "") {
        stopHeartbeat() // Stop the heartbeat immediately to prevent node recreation in RTDB
        val user = currentUser ?: return
        // Cancel onDisconnect since we're leaving explicitly
        cancelOnDisconnect(roomCode)

        // Read room state BEFORE removing ourselves to avoid race condition
        roomsRef.child(roomCode).get().addOnSuccessListener { snapshot ->
            val hostName = snapshot.child("hostName").value as? String ?: ""
            val hostId = snapshot.child("hostId").value as? String ?: ""
            val activeMembersCount = snapshot.child("members").children.count {
                val state = it.child("state").value as? String
                state != "left"
            }
            val status = snapshot.child("status").value as? String ?: "waiting"
            val isHost = user.uid == hostId

            // Now mark ourselves as left
            roomsRef.child(roomCode).child("members").child(user.uid).child("state").setValue("left")
            sendSystemMessage(roomCode, "${user.displayName ?: "Someone"} left the room", "leave")

            // Remaining members = count - 1 (since we counted ourselves)
            val remainingMembers = activeMembersCount - 1

            if (remainingMembers <= 0) {
                // Last person left — mark room as ended and clean up
                roomsRef.child(roomCode).child("status").setValue("ended")
                usersRef.child(user.uid).child("recentRooms").child(roomCode).removeValue()
                cleanupRejoinEntriesForRoom(roomCode)
            } else if (status == "ended") {
                usersRef.child(user.uid).child("recentRooms").child(roomCode).removeValue()
            } else {
                // Room still active — save rejoin info
                val rejoinData = mutableMapOf<String, Any>(
                    "roomCode" to roomCode,
                    "hostName" to hostName,
                    "leftAt" to ServerValue.TIMESTAMP,
                    "isHost" to isHost
                )
                if (videoUriString.isNotEmpty()) {
                    rejoinData["videoUriString"] = videoUriString
                }
                if (videoFileName.isNotEmpty()) {
                    rejoinData["videoFileName"] = videoFileName
                }
                usersRef.child(user.uid).child("recentRooms").child(roomCode).setValue(rejoinData)
            }
        }
    }

    fun endRoom(roomCode: String) {
        roomsRef.child(roomCode).child("status").setValue("ended")
    }

    private fun cleanupRejoinEntriesForRoom(roomCode: String) {
        // Scan all users and remove rejoin entries for this room
        // Use memberHistory — contains ALL users who ever joined, not just current
        roomsRef.child(roomCode).child("memberHistory").get().addOnSuccessListener { snapshot ->
            snapshot.children.forEach { child ->
                val uid = child.key ?: return@forEach
                usersRef.child(uid).child("recentRooms").child(roomCode).removeValue()
            }
        }
    }

    // Chat methods
    fun sendChatMessage(roomCode: String, message: String, replyTo: ChatMessage? = null) {
        val user = currentUser ?: return
        val msgKey = roomsRef.child(roomCode).child("chat").push().key ?: return
        val chatMsg = buildMap<String, Any> {
            put("senderId", user.uid)
            put("senderName", user.displayName ?: "")
            put("message", message)
            put("timestamp", ServerValue.TIMESTAMP)
            put("type", "chat")
            if (replyTo != null) {
                put("replyToId", replyTo.id)
                put("replyToSenderName", replyTo.senderName)
                put("replyToMessage", if (replyTo.type == "voice") "\uD83C\uDFA4 Voice message" else replyTo.message)
            }
        }
        val updates = mapOf(
            "chat/$msgKey" to chatMsg,
            "lastChatWrite/${user.uid}" to ServerValue.TIMESTAMP
        )
        roomsRef.child(roomCode).updateChildren(updates)
    }

    fun sendSystemMessage(roomCode: String, message: String, type: String) {
        val chatMsg = mapOf(
            "senderId" to "system",
            "senderName" to "System",
            "message" to message,
            "timestamp" to ServerValue.TIMESTAMP,
            "type" to type
        )
        roomsRef.child(roomCode).child("chat").push().setValue(chatMsg)
    }

    fun observeChat(roomCode: String, sinceTimestamp: Long = 0L): Flow<ChatMessage> = callbackFlow {
        val query = if (sinceTimestamp > 0L) {
            roomsRef.child(roomCode).child("chat")
                .orderByChild("timestamp")
                .startAfter(sinceTimestamp.toDouble())
        } else {
            roomsRef.child(roomCode).child("chat")
        }
        val listener = object : com.google.firebase.database.ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                @Suppress("UNCHECKED_CAST")
                val map = snapshot.value as? Map<String, Any?> ?: return
                val msg = ChatMessage.fromMap(snapshot.key ?: "", map)
                trySend(msg)
            }
            override fun onChildChanged(s: DataSnapshot, p: String?) {}
            override fun onChildRemoved(s: DataSnapshot) {}
            override fun onChildMoved(s: DataSnapshot, p: String?) {}
            override fun onCancelled(error: DatabaseError) {
                close(error.toException())
            }
        }
        query.addChildEventListener(listener)
        awaitClose { query.removeEventListener(listener) }
    }

    // Rejoin methods
    fun getRecentRooms(onResult: (List<RejoinInfo>) -> Unit) {
        val user = currentUser ?: run { onResult(emptyList()); return }
        usersRef.child(user.uid).child("recentRooms").get().addOnSuccessListener { snapshot ->
            val rooms = mutableListOf<RejoinInfo>()
            val roomCodes = mutableListOf<String>()
            snapshot.children.forEach { child ->
                val roomCode = child.child("roomCode").value as? String ?: return@forEach
                val hostName = child.child("hostName").value as? String ?: ""
                val leftAt = (child.child("leftAt").value as? Long)
                    ?: (child.child("leftAt").value as? Number)?.toLong() ?: 0L
                val isHost = child.child("isHost").value as? Boolean ?: false
                val videoUriString = child.child("videoUriString").value as? String ?: ""
                val videoFileName = child.child("videoFileName").value as? String ?: ""
                rooms.add(RejoinInfo(roomCode, hostName, leftAt, isHost, videoUriString, videoFileName))
                roomCodes.add(roomCode)
            }
            // Sort by latest left first
            rooms.sortByDescending { it.leftAt }

            // Validate rooms still exist and are not ended
            if (rooms.isEmpty()) {
                onResult(emptyList())
                return@addOnSuccessListener
            }

            val validRooms = mutableListOf<RejoinInfo>()
            var checkedCount = 0
            rooms.forEach { info ->
                checkRoomStillActive(info.roomCode) { active ->
                    if (active) {
                        validRooms.add(info)
                    } else {
                        // Clean up stale rejoin entry
                        removeRejoinEntry(info.roomCode)
                    }
                    checkedCount++
                    if (checkedCount == rooms.size) {
                        validRooms.sortByDescending { it.leftAt }
                        onResult(validRooms)
                    }
                }
            }
        }.addOnFailureListener {
            onResult(emptyList())
        }
    }

    fun checkRoomStillActive(roomCode: String, onResult: (Boolean) -> Unit) {
        roomsRef.child(roomCode).get().addOnSuccessListener { snapshot ->
            val status = snapshot.child("status").value as? String ?: "ended"
            val activeMembersCount = snapshot.child("members").children.count {
                val state = it.child("state").value as? String
                state != "left"
            }
            onResult(status != "ended" && activeMembersCount > 0)
        }.addOnFailureListener {
            onResult(false)
        }
    }

    /**
     * Suspend version of checkRoomStillActive that also checks heartbeat timestamps.
     * Returns true only if the room exists, status is not "ended", AND at least one
     * member has a lastSeen timestamp within OFFLINE_THRESHOLD_MS.
     */
    suspend fun checkRoomAlive(roomCode: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val snapshot = roomsRef.child(roomCode).get().await()
            if (!snapshot.exists()) return@withContext false
            val status = snapshot.child("status").value as? String ?: "ended"
            if (status == "ended") return@withContext false
            val membersSnap = snapshot.child("members")
            if (membersSnap.childrenCount == 0L) return@withContext false
            val now = System.currentTimeMillis()
            val hasActiveHeartbeat = membersSnap.children.any { child ->
                val state = child.child("state").value as? String
                val lastSeen = (child.child("lastSeen").value as? Long)
                    ?: (child.child("lastSeen").value as? Number)?.toLong() ?: 0L
                state != "left" && lastSeen > 0 && (now - lastSeen) < ROOM_INACTIVE_THRESHOLD_MS
            }
            hasActiveHeartbeat
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Probes a room up to [attempts] times with a delay between each attempt.
     * Returns true as soon as the room is found alive. Returns false only after
     * all attempts fail — at which point the room is considered dead.
     */
    suspend fun probeRoom(roomCode: String, attempts: Int = 4, delayMs: Long = 2500L): Boolean {
        repeat(attempts) { attempt ->
            if (checkRoomAlive(roomCode)) return true
            if (attempt < attempts - 1) delay(delayMs)
        }
        return false
    }

    /**
     * If the room has 0 members, mark it as "ended" so no one else tries to rejoin.
     * This is called when probes detect an abandoned room.
     */
    fun markRoomEndedIfEmpty(roomCode: String) {
        roomsRef.child(roomCode).child("members").get().addOnSuccessListener { snapshot ->
            val activeCount = snapshot.children.count {
                val state = it.child("state").value as? String
                state != "left"
            }
            if (activeCount == 0) {
                roomsRef.child(roomCode).child("status").setValue("ended")
                cleanupRejoinEntriesForRoom(roomCode)
            }
        }
    }

    fun removeRejoinEntry(roomCode: String) {
        val user = currentUser ?: return
        usersRef.child(user.uid).child("recentRooms").child(roomCode).removeValue()
    }

    fun sendJoinNotification(roomCode: String) {
        val user = currentUser ?: return
        sendSystemMessage(roomCode, "${user.displayName ?: "Someone"} joined the room", "join")
    }

    fun rejoinPlayingRoom(
        roomCode: String,
        onSuccess: (String) -> Unit, // returns room status
        onFailure: (String) -> Unit
    ) {
        val user = currentUser ?: run { onFailure("Not logged in"); return }

        roomsRef.child(roomCode).get().addOnSuccessListener { snapshot ->
            if (!snapshot.exists()) {
                onFailure("Room not found")
                return@addOnSuccessListener
            }

            val status = snapshot.child("status").value as? String ?: "waiting"
            if (status == "ended") {
                onFailure("Room has ended")
                return@addOnSuccessListener
            }

            val maxMembers = (snapshot.child("maxMembers").value as? Long)?.toInt() ?: 2
            val currentMembers = snapshot.child("members").children.count {
                val state = it.child("state").value as? String
                state != "left"
            }

            if (currentMembers >= maxMembers) {
                onFailure("Room is full")
                return@addOnSuccessListener
            }

            val memberData = mapOf(
                "uid" to user.uid,
                "displayName" to (user.displayName ?: "Member"),
                "photoUrl" to (user.photoUrl?.toString() ?: ""),
                "isReady" to true,
                "hasMatchingFile" to true,
                "state" to "active"
            )

            val updates = mapOf(
                "members/${user.uid}" to memberData,
                "memberHistory/${user.uid}" to true
            )

            roomsRef.child(roomCode).updateChildren(updates)
                .addOnSuccessListener {
                    // Remove rejoin entry
                    removeRejoinEntry(roomCode)
                    sendJoinNotification(roomCode)
                    onSuccess(status)
                }
                .addOnFailureListener { onFailure(it.message ?: "Failed to rejoin room") }
        }.addOnFailureListener {
            onFailure(it.message ?: "Failed to check room")
        }
    }

    // ─── Voice Note Methods ───

    /** The Cloudflare Worker URL — set this to your deployed worker endpoint */
    companion object {
        const val HEARTBEAT_INTERVAL_MS = 4000L
        const val OFFLINE_THRESHOLD_MS = 25000L
        const val UNSTABLE_THRESHOLD_MS = 15000L
        const val ROOM_INACTIVE_THRESHOLD_MS = 60000L
        const val WORKER_URL = "https://kandaloo.ashmithb796.workers.dev/sign"
    }

    fun sendVoiceMessage(roomCode: String, audioUrl: String, durationMs: Long, replyTo: ChatMessage? = null) {
        val user = currentUser ?: return
        val voiceMsg = buildMap<String, Any> {
            put("senderId", user.uid)
            put("senderName", user.displayName ?: "")
            put("message", "\uD83C\uDFA4 Voice message")
            put("timestamp", ServerValue.TIMESTAMP)
            put("type", "voice")
            put("audioUrl", audioUrl)
            put("audioDurationMs", durationMs)
            if (replyTo != null) {
                put("replyToId", replyTo.id)
                put("replyToSenderName", replyTo.senderName)
                put("replyToMessage", if (replyTo.type == "voice") "\uD83C\uDFA4 Voice message" else replyTo.message)
            }
        }
        val msgKey = roomsRef.child(roomCode).child("chat").push().key ?: return
        val updates = mapOf(
            "chat/$msgKey" to voiceMsg,
            "lastChatWrite/${user.uid}" to ServerValue.TIMESTAMP
        )
        roomsRef.child(roomCode).updateChildren(updates)
    }

    suspend fun getCloudinarySignature(): CloudinarySignatureResult = withContext(Dispatchers.IO) {
        val user = currentUser ?: throw Exception("Not logged in")
        val tokenResult = user.getIdToken(false).await()
        val idToken = tokenResult.token ?: throw Exception("No ID token")

        val url = java.net.URL(WORKER_URL)
        val conn = url.openConnection() as java.net.HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $idToken")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000

            val responseCode = conn.responseCode
            val body = if (responseCode in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else {
                conn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
            }

            if (responseCode != 200) {
                throw Exception("Server error: $body")
            }

            val json = org.json.JSONObject(body)
            CloudinarySignatureResult(
                signature = json.getString("signature"),
                timestamp = json.getLong("timestamp"),
                apiKey = json.getString("apiKey"),
                cloudName = json.getString("cloudName"),
                folder = json.getString("folder")
            )
        } finally {
            conn.disconnect()
        }
    }

    suspend fun uploadVoiceToCloudinary(
        file: java.io.File,
        signature: String,
        timestamp: Long,
        apiKey: String,
        cloudName: String,
        folder: String
    ): String = withContext(Dispatchers.IO) {
        val boundary = "----KanDaloo${System.currentTimeMillis()}"
        val url = java.net.URL("https://api.cloudinary.com/v1_1/$cloudName/video/upload")
        val conn = url.openConnection() as java.net.HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000

            val output = conn.outputStream

            fun writeField(name: String, value: String) {
                output.write("--$boundary\r\n".toByteArray())
                output.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
                output.write("$value\r\n".toByteArray())
            }

            writeField("api_key", apiKey)
            writeField("timestamp", timestamp.toString())
            writeField("signature", signature)
            writeField("folder", folder)

            // File part
            output.write("--$boundary\r\n".toByteArray())
            output.write("Content-Disposition: form-data; name=\"file\"; filename=\"${file.name}\"\r\n".toByteArray())
            output.write("Content-Type: audio/ogg\r\n\r\n".toByteArray())
            file.inputStream().use { it.copyTo(output) }
            output.write("\r\n".toByteArray())

            output.write("--$boundary--\r\n".toByteArray())
            output.flush()
            output.close()

            val responseCode = conn.responseCode
            val body = if (responseCode in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else {
                conn.errorStream?.bufferedReader()?.readText() ?: "Upload failed"
            }

            if (responseCode != 200) {
                throw Exception("Upload error: $body")
            }

            val json = org.json.JSONObject(body)
            json.getString("secure_url")
        } finally {
            conn.disconnect()
        }
    }

    // ─── Heartbeat / Presence System ───

    private var heartbeatJob: Job? = null
    private val heartbeatScope = CoroutineScope(Dispatchers.IO)

    fun startHeartbeat(
        roomCode: String,
        batteryProvider: () -> Int = { 100 },
        screenProvider: () -> String = { "watching" }
    ) {
        stopHeartbeat()
        val user = currentUser ?: return
        heartbeatJob = heartbeatScope.launch {
            while (true) {
                val updates = mapOf(
                    "lastSeen" to ServerValue.TIMESTAMP,
                    "displayName" to (user.displayName ?: "Member"),
                    "photoUrl" to (user.photoUrl?.toString() ?: ""),
                    "uid" to user.uid,
                    "state" to "active",
                    "screen" to screenProvider(),
                    "battery" to batteryProvider()
                )
                roomsRef.child(roomCode).child("members").child(user.uid)
                    .updateChildren(updates)
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    fun setScreenState(roomCode: String, screen: String) {
        val user = currentUser ?: return
        roomsRef.child(roomCode).child("members").child(user.uid).child("screen").setValue(screen)
    }

    fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private var lobbyHeartbeatJob: Job? = null

    fun startLobbyHeartbeat(roomCodes: List<String>) {
        stopLobbyHeartbeat()
        val user = currentUser ?: return
        if (roomCodes.isEmpty()) return
        lobbyHeartbeatJob = heartbeatScope.launch {
            while (true) {
                roomCodes.forEach { roomCode ->
                    val updates = mapOf(
                        "lastSeen" to ServerValue.TIMESTAMP,
                        "displayName" to (user.displayName ?: "Member"),
                        "photoUrl" to (user.photoUrl?.toString() ?: ""),
                        "uid" to user.uid,
                        "state" to "left"
                    )
                    roomsRef.child(roomCode).child("members").child(user.uid)
                        .updateChildren(updates)
                }
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    fun stopLobbyHeartbeat() {
        lobbyHeartbeatJob?.cancel()
        lobbyHeartbeatJob = null
    }

    fun setupOnDisconnect(roomCode: String, videoUriString: String, videoFileName: String = "") {
        val user = currentUser ?: return
        val uid = user.uid
        roomsRef.child(roomCode).get().addOnSuccessListener { snapshot ->
            val hostName = snapshot.child("hostName").value as? String ?: ""
            val hostId = snapshot.child("hostId").value as? String ?: ""
            val isHost = uid == hostId
            // When disconnected, mark state as offline instead of deleting the node
            roomsRef.child(roomCode).child("members").child(uid).child("state")
                .onDisconnect().setValue("offline")
            // When disconnected, save rejoin entry so user can rejoin later
            val rejoinData = mapOf<String, Any>(
                "roomCode" to roomCode,
                "hostName" to hostName,
                "leftAt" to ServerValue.TIMESTAMP,
                "isHost" to isHost,
                "videoUriString" to videoUriString,
                "videoFileName" to videoFileName
            )
            usersRef.child(uid).child("recentRooms").child(roomCode)
                .onDisconnect().setValue(rejoinData)
        }
    }

    fun cancelOnDisconnect(roomCode: String) {
        val user = currentUser ?: return
        roomsRef.child(roomCode).child("members").child(user.uid)
            .onDisconnect().cancel()
        usersRef.child(user.uid).child("recentRooms").child(roomCode)
            .onDisconnect().cancel()
    }

    fun observePresence(
        roomCode: String,
        onMemberMinimized: (String, String) -> Unit,
        onMemberReturned: (String, String) -> Unit,
        onMemberLeft: (String, String) -> Unit,
        onMemberOffline: (String, String) -> Unit,
        onConnectionUnstable: (String, String) -> Unit,
        onMemberReconnected: (String, String) -> Unit
    ): Flow<Map<String, Long>> = callbackFlow {
        val notifiedOffline = mutableSetOf<String>()
        val notifiedUnstable = mutableSetOf<String>()
        val notifiedLeft = mutableSetOf<String>()
        val wasOfflineOrUnstable = mutableSetOf<String>()
        val previousScreens = mutableMapOf<String, String>()
        val previousMembers = mutableMapOf<String, String>() // Local cache: uid -> displayName to preserve names of removed/offline users

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val now = System.currentTimeMillis()
                val presenceMap = mutableMapOf<String, Long>()
                val currentUid = currentUser?.uid ?: ""
                val currentMembers = mutableMapOf<String, String>()

                snapshot.children.forEach { child ->
                    val uid = child.key ?: return@forEach
                    val lastSeen = (child.child("lastSeen").value as? Long)
                        ?: (child.child("lastSeen").value as? Number)?.toLong() ?: 0L
                    val displayName = child.child("displayName").value as? String ?: "Someone"
                    val state = child.child("state").value as? String ?: "active"
                    val screen = child.child("screen").value as? String ?: "watching"
                    presenceMap[uid] = lastSeen
                    currentMembers[uid] = displayName

                    val timeSinceLastSeen = if (lastSeen > 0) now - lastSeen else 0L
                    val isOnline = lastSeen > 0 && timeSinceLastSeen < OFFLINE_THRESHOLD_MS
                    val isUnstable = lastSeen > 0 && timeSinceLastSeen in UNSTABLE_THRESHOLD_MS..OFFLINE_THRESHOLD_MS

                    when (state) {
                        "left" -> {
                            if (notifiedLeft.add(uid)) {
                                onMemberLeft(uid, displayName)
                            }
                            notifiedOffline.remove(uid)
                            notifiedUnstable.remove(uid)
                            wasOfflineOrUnstable.remove(uid)
                            previousScreens.remove(uid)
                        }
                        "offline" -> {
                            if (uid != currentUid && notifiedOffline.add(uid)) {
                                wasOfflineOrUnstable.add(uid)
                                onMemberOffline(uid, displayName)
                            }
                            notifiedUnstable.remove(uid)
                            previousScreens.remove(uid)
                        }
                        else -> { // "active"
                            if (isOnline && !isUnstable) {
                                if (wasOfflineOrUnstable.remove(uid)) {
                                    notifiedOffline.remove(uid)
                                    notifiedUnstable.remove(uid)
                                    if (uid != currentUid) {
                                        onMemberReconnected(uid, displayName)
                                    }
                                }
                                notifiedOffline.remove(uid)
                                notifiedUnstable.remove(uid)
                                notifiedLeft.remove(uid)

                                // Screen state transitions
                                val prevScreen = previousScreens[uid]
                                if (uid != currentUid && prevScreen != null && prevScreen != screen) {
                                    if (screen == "minimized" && prevScreen == "watching") {
                                        onMemberMinimized(uid, displayName)
                                    } else if (screen == "watching" && prevScreen == "minimized") {
                                        onMemberReturned(uid, displayName)
                                    }
                                }
                                previousScreens[uid] = screen
                            } else if (isUnstable) {
                                if (uid != currentUid && notifiedUnstable.add(uid)) {
                                    wasOfflineOrUnstable.add(uid)
                                    onConnectionUnstable(uid, displayName)
                                }
                            } else if (lastSeen > 0 && timeSinceLastSeen >= OFFLINE_THRESHOLD_MS) {
                                if (uid != currentUid && notifiedOffline.add(uid)) {
                                    wasOfflineOrUnstable.add(uid)
                                    onMemberOffline(uid, displayName)
                                }
                            }
                        }
                    }
                }

                // Detect members whose nodes were REMOVED from the database.
                // This means they explicitly left (leaveRoom removes the node).
                previousMembers.forEach { (uid, displayName) ->
                    if (uid != currentUid && !currentMembers.containsKey(uid)) {
                        if (notifiedLeft.add(uid)) {
                            onMemberLeft(uid, displayName)
                        }
                        notifiedOffline.remove(uid)
                        notifiedUnstable.remove(uid)
                        wasOfflineOrUnstable.remove(uid)
                        previousScreens.remove(uid)
                    }
                }

                previousMembers.clear()
                previousMembers.putAll(currentMembers)

                trySend(presenceMap)
            }

            override fun onCancelled(error: DatabaseError) {
                close(error.toException())
            }
        }
        roomsRef.child(roomCode).child("members").addValueEventListener(listener)
        awaitClose { roomsRef.child(roomCode).child("members").removeEventListener(listener) }
    }



    fun setTyping(roomCode: String, isTyping: Boolean) {
        val uid = currentUser?.uid ?: return
        if (isTyping) {
            roomsRef.child(roomCode).child("typing").child(uid).setValue(
                mapOf("name" to (currentUser?.displayName ?: "Someone"), "at" to ServerValue.TIMESTAMP)
            )
        } else {
            roomsRef.child(roomCode).child("typing").child(uid).removeValue()
        }
    }

    fun observeTyping(roomCode: String): Flow<List<String>> = callbackFlow {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val currentUid = currentUser?.uid ?: ""
                val now = System.currentTimeMillis()
                val typingNames = snapshot.children
                    .filter { it.key != currentUid }
                    .filter {
                        val at = (it.child("at").value as? Long)
                            ?: (it.child("at").value as? Number)?.toLong() ?: 0L
                        (now - at) < 10_000 // Only show if typing within last 10s
                    }
                    .mapNotNull { it.child("name").value as? String }
                trySend(typingNames)
            }
            override fun onCancelled(error: DatabaseError) { close(error.toException()) }
        }
        roomsRef.child(roomCode).child("typing").addValueEventListener(listener)
        awaitClose { roomsRef.child(roomCode).child("typing").removeEventListener(listener) }
    }
}

suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (task.isSuccessful) {
            continuation.resume(task.result)
        } else {
            continuation.resumeWithException(task.exception ?: Exception("Task failed"))
        }
    }
}
