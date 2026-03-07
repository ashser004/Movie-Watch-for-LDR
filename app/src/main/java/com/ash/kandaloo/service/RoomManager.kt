package com.ash.kandaloo.service

import com.ash.kandaloo.data.MemberData
import com.ash.kandaloo.data.PlaybackState
import com.ash.kandaloo.data.ReactionEvent
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

class RoomManager {

    private val database = FirebaseDatabase.getInstance("https://kandaloo-default-rtdb.asia-southeast1.firebasedatabase.app")
    private val roomsRef = database.getReference("rooms")
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
                .addOnSuccessListener { onSuccess() }
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

    fun startParty(roomCode: String) {
        roomsRef.child(roomCode).child("status").setValue("playing")
        updatePlaybackState(roomCode, PlaybackState(
            isPlaying = true,
            positionMs = 0L,
            speed = 1.0f,
            lastUpdatedBy = currentUser?.uid ?: "",
            lastUpdatedAt = System.currentTimeMillis()
        ))
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

    fun leaveRoom(roomCode: String) {
        val user = currentUser ?: return
        roomsRef.child(roomCode).child("members").child(user.uid).removeValue()
    }

    fun endRoom(roomCode: String) {
        roomsRef.child(roomCode).child("status").setValue("ended")
    }

    fun deleteRoom(roomCode: String) {
        roomsRef.child(roomCode).removeValue()
    }
}
