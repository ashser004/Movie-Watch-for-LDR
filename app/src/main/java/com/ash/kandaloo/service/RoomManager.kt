package com.ash.kandaloo.service

import com.ash.kandaloo.data.ChatMessage
import com.ash.kandaloo.data.MemberData
import com.ash.kandaloo.data.PlaybackState
import com.ash.kandaloo.data.ReactionEvent
import com.ash.kandaloo.data.RejoinInfo
import com.ash.kandaloo.data.VideoMetadata
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.random.Random
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class RoomManager {

    private val database = FirebaseDatabase.getInstance("https://kandaloo-default-rtdb.asia-southeast1.firebasedatabase.app")
    private val roomsRef = database.getReference("rooms")
    private val usersRef = database.getReference("users")
    private val auth = FirebaseAuth.getInstance()

    private val currentUser get() = auth.currentUser

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
                "hasMatchingFile" to false
            )
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
            val currentMembers = snapshot.child("members").childrenCount.toInt()

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
                "hasMatchingFile" to false
            )

            roomsRef.child(roomCode).child("members").child(user.uid)
                .setValue(memberData)
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

    fun startParty(roomCode: String, autoPlay: Boolean = true) {
        // Clear old chat and reactions from the waiting-room phase
        roomsRef.child(roomCode).child("chat").removeValue()
        roomsRef.child(roomCode).child("reactions").removeValue()

        roomsRef.child(roomCode).child("status").setValue("playing")
        updatePlaybackState(roomCode, PlaybackState(
            isPlaying = autoPlay,
            positionMs = 0L,
            speed = 1.0f,
            lastUpdatedBy = currentUser?.uid ?: "",
            lastUpdatedAt = System.currentTimeMillis()
        ))
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

    fun sendReaction(roomCode: String, emoji: String) {
        val user = currentUser ?: return
        val reaction = mapOf(
            "emoji" to emoji,
            "senderId" to user.uid,
            "senderName" to (user.displayName ?: ""),
            "timestamp" to ServerValue.TIMESTAMP
        )
        roomsRef.child(roomCode).child("reactions").push().setValue(reaction)
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

    fun leaveRoom(roomCode: String, videoUriString: String = "") {
        val user = currentUser ?: return

        // Read room state BEFORE removing ourselves to avoid race condition
        roomsRef.child(roomCode).get().addOnSuccessListener { snapshot ->
            val hostName = snapshot.child("hostName").value as? String ?: ""
            val hostId = snapshot.child("hostId").value as? String ?: ""
            val membersCount = snapshot.child("members").childrenCount.toInt()
            val status = snapshot.child("status").value as? String ?: "waiting"
            val isHost = user.uid == hostId

            // Now remove ourselves
            roomsRef.child(roomCode).child("members").child(user.uid).removeValue()
            sendSystemMessage(roomCode, "${user.displayName ?: "Someone"} left the room", "leave")

            // Remaining members = count - 1 (since we counted ourselves)
            val remainingMembers = membersCount - 1

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
                usersRef.child(user.uid).child("recentRooms").child(roomCode).setValue(rejoinData)
            }
        }
    }

    fun endRoom(roomCode: String) {
        roomsRef.child(roomCode).child("status").setValue("ended")
        // Clean up all users' rejoin entries for this room
        cleanupRejoinEntriesForRoom(roomCode)
    }

    private fun cleanupRejoinEntriesForRoom(roomCode: String) {
        // Scan all users and remove rejoin entries for this room
        // We also need to check the members who left before
        roomsRef.child(roomCode).child("members").get().addOnSuccessListener { snapshot ->
            snapshot.children.forEach { child ->
                val uid = child.key ?: return@forEach
                usersRef.child(uid).child("recentRooms").child(roomCode).removeValue()
            }
        }
    }

    fun deleteRoom(roomCode: String) {
        roomsRef.child(roomCode).removeValue()
    }

    // Chat methods
    fun sendChatMessage(roomCode: String, message: String) {
        val user = currentUser ?: return
        val chatMsg = mapOf(
            "senderId" to user.uid,
            "senderName" to (user.displayName ?: ""),
            "message" to message,
            "timestamp" to ServerValue.TIMESTAMP,
            "type" to "chat"
        )
        roomsRef.child(roomCode).child("chat").push().setValue(chatMsg)
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

    fun observeChat(roomCode: String): Flow<ChatMessage> = callbackFlow {
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
        roomsRef.child(roomCode).child("chat").addChildEventListener(listener)
        awaitClose { roomsRef.child(roomCode).child("chat").removeEventListener(listener) }
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
                rooms.add(RejoinInfo(roomCode, hostName, leftAt, isHost, videoUriString))
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
            val membersCount = snapshot.child("members").childrenCount.toInt()
            onResult(status != "ended" && membersCount > 0)
        }.addOnFailureListener {
            onResult(false)
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
            val currentMembers = snapshot.child("members").childrenCount.toInt()

            if (currentMembers >= maxMembers) {
                onFailure("Room is full")
                return@addOnSuccessListener
            }

            val memberData = mapOf(
                "uid" to user.uid,
                "displayName" to (user.displayName ?: "Member"),
                "photoUrl" to (user.photoUrl?.toString() ?: ""),
                "isReady" to true,
                "hasMatchingFile" to true
            )

            roomsRef.child(roomCode).child("members").child(user.uid)
                .setValue(memberData)
                .addOnSuccessListener {
                    // Remove rejoin entry
                    removeRejoinEntry(roomCode)
                    sendJoinNotification(roomCode)
                    // Pause all members for sync when someone rejoins a playing room
                    if (status == "playing") {
                        pauseForSync(roomCode, user.displayName ?: "Someone")
                    }
                    onSuccess(status)
                }
                .addOnFailureListener { onFailure(it.message ?: "Failed to rejoin room") }
        }.addOnFailureListener {
            onFailure(it.message ?: "Failed to check room")
        }
    }

    // ─── Heartbeat / Presence System ───

    private var heartbeatJob: Job? = null
    private var presenceListenerJob: Job? = null
    private val heartbeatScope = CoroutineScope(Dispatchers.IO)

    companion object {
        const val HEARTBEAT_INTERVAL_MS = 4000L
        const val OFFLINE_THRESHOLD_MS = 10000L
    }

    fun startHeartbeat(roomCode: String) {
        stopHeartbeat()
        val user = currentUser ?: return
        heartbeatJob = heartbeatScope.launch {
            while (true) {
                roomsRef.child(roomCode).child("members").child(user.uid)
                    .child("lastSeen").setValue(ServerValue.TIMESTAMP)
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    fun observePresence(roomCode: String, onMemberOffline: (String, String) -> Unit, onAllOffline: () -> Unit): Flow<Map<String, Long>> = callbackFlow {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val now = System.currentTimeMillis()
                val presenceMap = mutableMapOf<String, Long>()
                val currentUid = currentUser?.uid ?: ""
                var allOffline = true
                var memberCount = 0

                snapshot.children.forEach { child ->
                    val uid = child.key ?: return@forEach
                    val lastSeen = (child.child("lastSeen").value as? Long)
                        ?: (child.child("lastSeen").value as? Number)?.toLong() ?: 0L
                    val displayName = child.child("displayName").value as? String ?: "Someone"
                    presenceMap[uid] = lastSeen
                    memberCount++

                    if (lastSeen > 0 && (now - lastSeen) < OFFLINE_THRESHOLD_MS) {
                        allOffline = false
                    } else if (uid != currentUid && lastSeen > 0 && (now - lastSeen) >= OFFLINE_THRESHOLD_MS) {
                        onMemberOffline(uid, displayName)
                    }
                }

                if (memberCount > 0 && allOffline) {
                    onAllOffline()
                }

                trySend(presenceMap)
            }

            override fun onCancelled(error: DatabaseError) {
                close(error.toException())
            }
        }
        roomsRef.child(roomCode).child("members").addValueEventListener(listener)
        awaitClose { roomsRef.child(roomCode).child("members").removeEventListener(listener) }
    }

    // Pause all members for sync (used on rejoin)
    fun pauseForSync(roomCode: String, displayName: String) {
        roomsRef.child(roomCode).child("playbackState").get().addOnSuccessListener { snapshot ->
            @Suppress("UNCHECKED_CAST")
            val map = snapshot.value as? Map<String, Any?> ?: return@addOnSuccessListener
            val currentState = PlaybackState.fromMap(map)
            if (currentState.isPlaying) {
                updatePlaybackState(roomCode, PlaybackState(
                    isPlaying = false,
                    positionMs = currentState.positionMs,
                    speed = currentState.speed,
                    lastUpdatedBy = currentUser?.uid ?: "",
                    lastUpdatedAt = System.currentTimeMillis()
                ))
                sendSystemMessage(roomCode, "Paused for sync — $displayName rejoined", "system")
            }
        }
    }
}
