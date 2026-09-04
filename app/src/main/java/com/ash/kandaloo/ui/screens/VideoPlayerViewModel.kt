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
import com.ash.kandaloo.data.LiveMemberInfo
import com.ash.kandaloo.data.PlaybackState
import com.ash.kandaloo.data.ReactionEvent
import com.ash.kandaloo.service.BatteryMonitor
import com.ash.kandaloo.service.RoomManager
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.File

class VideoPlayerViewModel(
    val context: Context,
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

    private val batteryMonitor = BatteryMonitor(context)
    val batteryLevel = batteryMonitor.batteryLevel
    var currentScreenState = "watching"

    fun setScreenState(screen: String) {
        currentScreenState = screen
        roomManager.setScreenState(roomCode, screen)
    }

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
    val playLockStartTime = mutableLongStateOf(if (isRejoin) System.currentTimeMillis() else 0L)
    val audioIssueWarningShown = mutableStateOf(false)
    val isUploadingVoice = mutableStateOf(false)

    fun getPlayLockRemainingSeconds(): Int {
        if (!isPlayLocked.value || playLockStartTime.longValue == 0L) return 0
        val elapsedMs = System.currentTimeMillis() - playLockStartTime.longValue
        val remainingMs = 15_000L - elapsedMs
        return (remainingMs / 1000L).toInt().coerceIn(1, 15)
    }

    val skipLockBy = mutableStateOf("")
    val skipLockAt = mutableLongStateOf(0L)

    val visibleReactions = mutableStateListOf<ReactionEvent>()
    val chatMessages = mutableStateListOf<ChatMessage>()
    val floatingMessages = mutableStateListOf<ChatMessage>()

    val shouldExit = mutableStateOf(false)
    val typingNames = mutableStateListOf<String>()
    val memberCount = mutableStateOf(1)
    val memberNames = mutableStateListOf<String>()
    val liveMembers = mutableStateListOf<LiveMemberInfo>()

    private val _errorEvent = Channel<String>(Channel.BUFFERED)
    val errorEvent = _errorEvent.receiveAsFlow()

    // Flag for sync update to suppress double-sync updates back to DB
    var isSyncUpdate = false

    init {
        // Battery alerts debounced
        batteryMonitor.start(
            onLowBattery = { pct ->
                val name = FirebaseAuth.getInstance().currentUser?.displayName ?: "Someone"
                roomManager.sendSystemMessage(roomCode, "$name's battery is low ($pct%)", "system")
            },
            onCriticalBattery = { pct ->
                val name = FirebaseAuth.getInstance().currentUser?.displayName ?: "Someone"
                roomManager.sendSystemMessage(roomCode, "$name's battery is low ($pct%)", "system")
            }
        )

        // Heartbeats & disconnect tracking
        roomManager.startHeartbeat(
            roomCode = roomCode,
            batteryProvider = { batteryMonitor.batteryLevel.value },
            screenProvider = { currentScreenState }
        )
        roomManager.setupOnDisconnect(roomCode, videoUriString, videoFileName)

        // Observe room members (for LiveBadge)
        viewModelScope.launch {
            roomManager.observeRoom(roomCode).collect { roomData ->
                @Suppress("UNCHECKED_CAST")
                val membersMap = roomData["members"] as? Map<String, Any> ?: emptyMap()
                val now = System.currentTimeMillis()
                val activeMembers = mutableListOf<String>()
                val liveInfoList = mutableListOf<LiveMemberInfo>()

                membersMap.values.forEach { memberObj ->
                    val map = memberObj as? Map<String, Any> ?: return@forEach
                    val uid = map["uid"] as? String ?: ""
                    val displayName = map["displayName"] as? String ?: "Someone"
                    val lastSeen = (map["lastSeen"] as? Long) ?: (map["lastSeen"] as? Number)?.toLong() ?: 0L
                    val state = map["state"] as? String ?: "active"
                    val screen = map["screen"] as? String ?: "watching"
                    val battery = (map["battery"] as? Long)?.toInt() ?: (map["battery"] as? Number)?.toInt() ?: 100

                    val timeSinceLastSeen = if (lastSeen > 0) now - lastSeen else 0L
                    val isOnline = uid == currentUserId || (state == "active" && lastSeen > 0 && timeSinceLastSeen < RoomManager.OFFLINE_THRESHOLD_MS)
                    val isUnstable = uid != currentUserId && state == "active" && lastSeen > 0 && timeSinceLastSeen in RoomManager.UNSTABLE_THRESHOLD_MS..RoomManager.OFFLINE_THRESHOLD_MS
                    val isLeft = state == "left"
                    val isOffline = state == "offline" || (state != "left" && !isOnline && lastSeen > 0)
                    val isMinimized = screen == "minimized"

                    if (isOnline && state != "left") {
                        activeMembers.add(displayName)
                    }

                    liveInfoList.add(
                        LiveMemberInfo(
                            uid = uid,
                            name = displayName,
                            isOnline = isOnline,
                            isUnstable = isUnstable,
                            isMinimized = isMinimized,
                            isLeft = isLeft,
                            isOffline = isOffline,
                            battery = battery
                        )
                    )
                }

                memberCount.value = activeMembers.size
                memberNames.clear()
                memberNames.addAll(activeMembers)
                liveMembers.clear()
                liveMembers.addAll(liveInfoList)

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
                    chatMessages.removeAll { it.id == "presence_left_${msg.senderId}" }
                    floatingMessages.removeAll { it.id == "presence_left_${msg.senderId}" }
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

        // Observe presence
        viewModelScope.launch {
            roomManager.observePresence(
                roomCode = roomCode,
                onMemberMinimized = { uid, displayName ->
                    val sysMsg = ChatMessage(
                        id = "presence_min_${uid}_${System.currentTimeMillis()}",
                        senderId = "system",
                        senderName = "System",
                        message = "$displayName left the watch screen",
                        timestamp = System.currentTimeMillis(),
                        type = "system"
                    )
                    addSystemChatAndFloating(sysMsg)
                },
                onMemberReturned = { uid, displayName ->
                    val sysMsg = ChatMessage(
                        id = "presence_ret_${uid}_${System.currentTimeMillis()}",
                        senderId = "system",
                        senderName = "System",
                        message = "$displayName returned to the watch screen",
                        timestamp = System.currentTimeMillis(),
                        type = "system"
                    )
                    addSystemChatAndFloating(sysMsg)
                },
                onMemberLeft = { uid, displayName ->
                    if (uid !in explicitlyLeftUsers) {
                        val alreadyHasLeaveChat = chatMessages.any { it.senderId == uid && it.type == "leave" }
                        if (!alreadyHasLeaveChat) {
                            val sysMsg = ChatMessage(
                                id = "presence_left_${uid}_${System.currentTimeMillis()}",
                                senderId = "system",
                                senderName = "System",
                                message = "$displayName left the room",
                                timestamp = System.currentTimeMillis(),
                                type = "system"
                            )
                            addSystemChatAndFloating(sysMsg)
                        }
                    }
                },
                onMemberOffline = { uid, displayName ->
                    val sysMsg = ChatMessage(
                        id = "presence_off_${uid}_${System.currentTimeMillis()}",
                        senderId = "system",
                        senderName = "System",
                        message = "$displayName went offline",
                        timestamp = System.currentTimeMillis(),
                        type = "system"
                    )
                    addSystemChatAndFloating(sysMsg)
                },
                onConnectionUnstable = { uid, displayName ->
                    val sysMsg = ChatMessage(
                        id = "presence_unst_${uid}_${System.currentTimeMillis()}",
                        senderId = "system",
                        senderName = "System",
                        message = "$displayName's connection is unstable...",
                        timestamp = System.currentTimeMillis(),
                        type = "system"
                    )
                    addSystemChatAndFloating(sysMsg)
                },
                onMemberReconnected = { uid, displayName ->
                    val sysMsg = ChatMessage(
                        id = "presence_rec_${uid}_${System.currentTimeMillis()}",
                        senderId = "system",
                        senderName = "System",
                        message = "$displayName reconnected",
                        timestamp = System.currentTimeMillis(),
                        type = "system"
                    )
                    addSystemChatAndFloating(sysMsg)
                }
            ).collect {
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

    private fun addSystemChatAndFloating(sysMsg: ChatMessage) {
        chatMessages.add(sysMsg)
        if (isFullscreen.value) {
            floatingMessages.add(sysMsg)
            viewModelScope.launch {
                delay(4000)
                floatingMessages.remove(sysMsg)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        batteryMonitor.stop()
        roomManager.stopHeartbeat()
        roomManager.cancelOnDisconnect(roomCode)
    }
}

class VideoPlayerViewModelFactory(
    private val context: Context,
    private val roomManager: RoomManager,
    private val roomCode: String,
    private val isHost: Boolean,
    private val isRejoin: Boolean,
    private val videoUriString: String,
    private val videoFileName: String
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return VideoPlayerViewModel(context.applicationContext, roomManager, roomCode, isHost, isRejoin, videoUriString, videoFileName) as T
    }
}
