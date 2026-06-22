package com.ash.kandaloo.ui.screens

import android.content.Context
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ash.kandaloo.data.ChatMessage
import com.ash.kandaloo.data.PlaybackState
import com.ash.kandaloo.data.ReactionEvent
import com.ash.kandaloo.service.RoomManager
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.File

class VideoPlayerViewModel(
    val roomManager: RoomManager,
    val roomCode: String,
    val isHost: Boolean,
    val isRejoin: Boolean,
    val videoUriString: String,
    val videoFileName: String
) : ViewModel() {

    private val currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: ""
    private val currentUserDisplayName = FirebaseAuth.getInstance().currentUser?.displayName ?: "Someone"
    private val explicitlyLeftUsers = mutableSetOf<String>()

    // === Composable States ===
    val showControls = mutableStateOf(true)
    val showReactions = mutableStateOf(false)
    val showSpeedMenu = mutableStateOf(false)
    val showExitDialog = mutableStateOf(false)
    val isFullscreen = mutableStateOf(false)
    val isUserSeeking = mutableStateOf(false)
    val seekPosition = mutableLongStateOf(0L)
    val currentPosition = mutableLongStateOf(0L)
    val duration = mutableLongStateOf(0L)
    val isPlaying = mutableStateOf(false)
    val isVideoEnded = mutableStateOf(false)
    val currentSpeed = mutableFloatStateOf(1.0f)
    val isPlayLocked = mutableStateOf(isRejoin)
    val audioIssueWarningShown = mutableStateOf(false)
    val isUploadingVoice = mutableStateOf(false)

    val skipLockBy = mutableStateOf("")
    val skipLockAt = mutableLongStateOf(0L)

    val visibleReactions = mutableStateListOf<ReactionEvent>()
    val chatMessages = mutableStateListOf<ChatMessage>()
    val floatingMessages = mutableStateListOf<ChatMessage>()

    val shouldExit = mutableStateOf(false)
    val typingNames = mutableStateListOf<String>()
    val memberCount = mutableStateOf(1)
    val memberNames = mutableStateListOf<String>()

    private val _errorEvent = Channel<String>(Channel.BUFFERED)
    val errorEvent = _errorEvent.receiveAsFlow()

    // Flag for sync update to suppress double-sync updates back to DB
    var isSyncUpdate = false

    init {
        // Heartbeats & disconnect tracking
        roomManager.startHeartbeat(roomCode)
        roomManager.setupOnDisconnect(roomCode, videoUriString, videoFileName)

        // Observe room members (for LiveBadge)
        viewModelScope.launch {
            roomManager.observeRoom(roomCode).collect { roomData ->
                @Suppress("UNCHECKED_CAST")
                val membersMap = roomData["members"] as? Map<String, Any> ?: emptyMap()
                val now = System.currentTimeMillis()
                val activeMembers = membersMap.values.mapNotNull {
                    val map = it as? Map<String, Any> ?: return@mapNotNull null
                    val uid = map["uid"] as? String ?: ""
                    val displayName = map["displayName"] as? String ?: "Someone"
                    val lastSeen = (map["lastSeen"] as? Long) ?: (map["lastSeen"] as? Number)?.toLong() ?: 0L
                    val state = map["state"] as? String ?: "active"
                    
                    val isActive = uid == currentUserId || (state == "active" && lastSeen > 0 && (now - lastSeen) < RoomManager.OFFLINE_THRESHOLD_MS)
                    if (isActive) displayName else null
                }
                memberCount.value = activeMembers.size
                memberNames.clear()
                memberNames.addAll(activeMembers)

                // If the user is the only active member, automatically release play lock
                if (activeMembers.size <= 1) {
                    isPlayLocked.value = false
                }
            }
        }

        // Observe playback state changes
        viewModelScope.launch {
            roomManager.observePlaybackState(roomCode).collect { remoteState ->
                if (remoteState.lastUpdatedBy == currentUserId) return@collect
                if (remoteState.lastUpdatedAt == 0L) return@collect

                isSyncUpdate = true
                currentSpeed.floatValue = remoteState.speed
                // Let the UI observe this speed and position changes via LaunchedEffects
                // Unlock play for rejoin user when another member starts playing
                if (isPlayLocked.value && remoteState.isPlaying) {
                    isPlayLocked.value = false
                }
                
                // Calculate catch-up position to handle network drops and late rejoins
                val targetPosition = if (remoteState.isPlaying) {
                    val now = System.currentTimeMillis()
                    val elapsed = (now - remoteState.lastUpdatedAt).coerceAtLeast(0L)
                    remoteState.positionMs + (elapsed * remoteState.speed).toLong()
                } else {
                    remoteState.positionMs
                }
                
                _remotePlaybackEvent.trySend(remoteState.copy(positionMs = targetPosition))

                // Auto-reset sync update flag after a delay to allow local player state to update
                launch {
                    delay(800)
                    isSyncUpdate = false
                }
            }
        }

        // Observe skip locks
        viewModelScope.launch {
            roomManager.observeSkipLock(roomCode).collect { (lockedBy, lockedAt) ->
                skipLockBy.value = lockedBy
                skipLockAt.longValue = lockedAt
            }
        }

        // Observe reactions
        viewModelScope.launch {
            roomManager.observeReactions(roomCode).collect { reaction ->
                if (reaction.senderId == currentUserId) return@collect
                visibleReactions.add(reaction)
                viewModelScope.launch {
                    delay(3000)
                    visibleReactions.remove(reaction)
                }
            }
        }

        // Observe chat messages
        viewModelScope.launch {
            // Rejoining users only see new messages. First-time users see all messages.
            val sinceTimestamp = if (isRejoin) System.currentTimeMillis() else 0L
            roomManager.observeChat(roomCode, sinceTimestamp).collect { msg ->
                if (msg.type == "leave") {
                    explicitlyLeftUsers.add(msg.senderId)
                }
                chatMessages.add(msg)

                // Auto-pause if someone else joins the player while we are playing
                if (msg.type == "join" && msg.senderId != currentUserId && isPlaying.value) {
                    _localPlaybackRequest.trySend(LocalPlaybackRequest(isPlaying = false))
                }

                // If in fullscreen, also show as floating message overlay
                if (isFullscreen.value && msg.senderId != currentUserId) {
                    floatingMessages.add(msg)
                    viewModelScope.launch {
                        delay(4000)
                        floatingMessages.remove(msg)
                    }
                }
            }
        }

        // Observe presence (went offline / back online system messages + auto-exit)
        viewModelScope.launch {
            roomManager.observePresence(
                roomCode = roomCode,
                onMemberOffline = { uid, displayName ->
                    // Fires ONLY when the member's heartbeat is stale
                    // (they are still in the database but not responding)
                    val sysMsg = ChatMessage(
                        id = "presence_off_$uid",
                        senderId = "system",
                        senderName = "System",
                        message = "$displayName went offline",
                        timestamp = System.currentTimeMillis(),
                        type = "system"
                    )
                    chatMessages.add(sysMsg)
                    if (isFullscreen.value) {
                        floatingMessages.add(sysMsg)
                        viewModelScope.launch {
                            delay(4000)
                            floatingMessages.remove(sysMsg)
                        }
                    }
                },
                onMemberLeft = { uid, displayName ->
                    if (uid !in explicitlyLeftUsers) {
                        val sysMsg = ChatMessage(
                            id = "presence_left_$uid",
                            senderId = "system",
                            senderName = "System",
                            message = "$displayName Left the room",
                            timestamp = System.currentTimeMillis(),
                            type = "system"
                        )
                        chatMessages.add(sysMsg)
                        if (isFullscreen.value) {
                            floatingMessages.add(sysMsg)
                            viewModelScope.launch {
                                delay(4000)
                                floatingMessages.remove(sysMsg)
                            }
                        }
                    }
                },
                onAllOffline = {
                    roomManager.endRoom(roomCode)
                    shouldExit.value = true
                }
            ).collect { presenceMap ->
                // Handle went back online detection if needed (UI can just show standard rejoin messages from Chat join notification)
            }
        }

        // Observe typing names
        viewModelScope.launch {
            roomManager.observeTyping(roomCode).collect { names ->
                typingNames.clear()
                typingNames.addAll(names)
            }
        }

        // Auto-hide controls
        viewModelScope.launch {
            while (true) {
                if (showControls.value && !isUserSeeking.value) {
                    delay(4000)
                    if (!isUserSeeking.value) {
                        showControls.value = false
                    }
                }
                delay(500)
            }
        }

        // Auto-unlock play after timeout (safety net)
        if (isPlayLocked.value) {
            viewModelScope.launch {
                delay(15_000)
                if (isPlayLocked.value) {
                    isPlayLocked.value = false
                    _errorEvent.trySend("Auto-unlocked \u2014 you can play now")
                }
            }
        }
    }

    private val _remotePlaybackEvent = Channel<PlaybackState>(Channel.BUFFERED)
    val remotePlaybackEvent = _remotePlaybackEvent.receiveAsFlow()

    fun handleSkip(currentPos: Long) {
        if (isPlayLocked.value) return
        val now = System.currentTimeMillis()
        val lockAge = now - skipLockAt.longValue
        val isLockedByOther = skipLockBy.value.isNotEmpty() && skipLockBy.value != currentUserId && lockAge < 5000

        if (isLockedByOther) {
            _errorEvent.trySend("Another user is skipping, wait a moment")
        } else {
            roomManager.setSkipLock(roomCode, currentUserId)
            val delta = 10_000L
            val newPos = (currentPos + delta).coerceIn(0L, duration.longValue)
            isSyncUpdate = true
            _localPlaybackRequest.trySend(LocalPlaybackRequest(isPlaying = false, seekPositionMs = newPos))
            roomManager.updatePlaybackState(roomCode, PlaybackState(
                isPlaying = false,
                positionMs = newPos,
                speed = currentSpeed.floatValue,
                lastUpdatedBy = currentUserId,
                lastUpdatedAt = System.currentTimeMillis()
            ))
            viewModelScope.launch {
                delay(800)
                isSyncUpdate = false
            }
        }
    }

    fun handleRewind(currentPos: Long) {
        if (isPlayLocked.value) return
        val now = System.currentTimeMillis()
        val lockAge = now - skipLockAt.longValue
        val isLockedByOther = skipLockBy.value.isNotEmpty() && skipLockBy.value != currentUserId && lockAge < 5000

        if (isLockedByOther) {
            _errorEvent.trySend("Another user is skipping, wait a moment")
        } else {
            roomManager.setSkipLock(roomCode, currentUserId)
            val delta = -10_000L
            val newPos = (currentPos + delta).coerceIn(0L, duration.longValue)
            isSyncUpdate = true
            _localPlaybackRequest.trySend(LocalPlaybackRequest(isPlaying = false, seekPositionMs = newPos))
            roomManager.updatePlaybackState(roomCode, PlaybackState(
                isPlaying = false,
                positionMs = newPos,
                speed = currentSpeed.floatValue,
                lastUpdatedBy = currentUserId,
                lastUpdatedAt = System.currentTimeMillis()
            ))
            viewModelScope.launch {
                delay(800)
                isSyncUpdate = false
            }
        }
    }

    private val _localPlaybackRequest = Channel<LocalPlaybackRequest>(Channel.BUFFERED)
    val localPlaybackRequest = _localPlaybackRequest.receiveAsFlow()

    data class LocalPlaybackRequest(val isPlaying: Boolean, val seekPositionMs: Long? = null)

    fun updatePlaybackStateFromLocal(playing: Boolean, currentPos: Long, speed: Float) {
        if (isSyncUpdate) return
        roomManager.updatePlaybackState(roomCode, PlaybackState(
            isPlaying = playing,
            positionMs = currentPos,
            speed = speed,
            lastUpdatedBy = currentUserId,
            lastUpdatedAt = System.currentTimeMillis()
        ))
    }

    fun sendReaction(emoji: String) {
        roomManager.sendReaction(roomCode, emoji)
    }

    fun sendChatMessage(message: String, replyTo: ChatMessage?) {
        roomManager.sendChatMessage(roomCode, message, replyTo)
    }

    fun setTyping(isTyping: Boolean) {
        roomManager.setTyping(roomCode, isTyping)
    }

    fun uploadVoiceNote(file: File, durationMs: Long, replyTo: ChatMessage?) {
        if (isUploadingVoice.value) return
        isUploadingVoice.value = true
        _errorEvent.trySend("Sending voice note...")
        viewModelScope.launch {
            try {
                val sig = roomManager.getCloudinarySignature()
                val audioUrl = roomManager.uploadVoiceToCloudinary(
                    file = file,
                    signature = sig.signature,
                    timestamp = sig.timestamp,
                    apiKey = sig.apiKey,
                    cloudName = sig.cloudName,
                    folder = sig.folder
                )
                roomManager.sendVoiceMessage(roomCode, audioUrl, durationMs, replyTo)
                file.delete()
            } catch (e: Exception) {
                _errorEvent.trySend("Upload failed: ${e.message}")
            } finally {
                isUploadingVoice.value = false
            }
        }
    }

    override fun onCleared() {
        roomManager.stopHeartbeat()
        roomManager.cancelOnDisconnect(roomCode)
    }
}

class VideoPlayerViewModelFactory(
    private val roomManager: RoomManager,
    private val roomCode: String,
    private val isHost: Boolean,
    private val isRejoin: Boolean,
    private val videoUriString: String,
    private val videoFileName: String
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return VideoPlayerViewModel(roomManager, roomCode, isHost, isRejoin, videoUriString, videoFileName) as T
    }
}
