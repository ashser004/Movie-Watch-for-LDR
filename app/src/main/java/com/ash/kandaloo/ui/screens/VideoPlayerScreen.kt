package com.ash.kandaloo.ui.screens

import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.ash.kandaloo.data.PlaybackState
import com.ash.kandaloo.data.ReactionEvent
import com.ash.kandaloo.service.RoomManager
import com.ash.kandaloo.ui.components.ReactionOverlay
import com.ash.kandaloo.ui.components.ReactionPicker
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(
    videoUri: Uri,
    roomCode: String,
    roomManager: RoomManager,
    isHost: Boolean,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: ""

    var showControls by remember { mutableStateOf(true) }
    var showReactions by remember { mutableStateOf(false) }
    var showSpeedMenu by remember { mutableStateOf(false) }
    var showExitDialog by remember { mutableStateOf(false) }
    var isFullscreen by remember { mutableStateOf(false) }
    var isUserSeeking by remember { mutableStateOf(false) }
    var seekPosition by remember { mutableLongStateOf(0L) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentSpeed by remember { mutableFloatStateOf(1.0f) }
    var isSyncUpdate by remember { mutableStateOf(false) }
    val visibleReactions = remember { mutableStateListOf<ReactionEvent>() }

    val exoPlayer = remember {
        ExoPlayer.Builder(context)
            .build()
            .apply {
                val mediaItem = MediaItem.fromUri(videoUri)
                setMediaItem(mediaItem)
                prepare()
                playWhenReady = false
            }
    }

    // Position update loop
    LaunchedEffect(exoPlayer) {
        while (true) {
            if (!isUserSeeking) {
                currentPosition = exoPlayer.currentPosition
                duration = exoPlayer.duration.coerceAtLeast(0L)
                isPlaying = exoPlayer.isPlaying
            }
            delay(500)
        }
    }

    // Auto-hide controls
    LaunchedEffect(showControls) {
        if (showControls && !isUserSeeking) {
            delay(4000)
            showControls = false
        }
    }

    // Listen for remote playback state changes
    val playbackFlow = remember { roomManager.observePlaybackState(roomCode) }
    val remoteState by playbackFlow.collectAsState(initial = PlaybackState())

    LaunchedEffect(remoteState) {
        // Skip if this update originated from us
        if (remoteState.lastUpdatedBy == currentUserId) return@LaunchedEffect
        if (remoteState.lastUpdatedAt == 0L) return@LaunchedEffect

        isSyncUpdate = true

        // Apply remote state
        val positionDiff = kotlin.math.abs(exoPlayer.currentPosition - remoteState.positionMs)
        // Only seek if position differs by more than 2 seconds
        if (positionDiff > 2000) {
            exoPlayer.seekTo(remoteState.positionMs)
        }

        exoPlayer.playWhenReady = remoteState.isPlaying
        if (exoPlayer.playbackParameters.speed != remoteState.speed) {
            exoPlayer.playbackParameters = PlaybackParameters(remoteState.speed)
            currentSpeed = remoteState.speed
        }

        delay(300)
        isSyncUpdate = false
    }

    // Listen for reactions
    val reactionsFlow = remember { roomManager.observeReactions(roomCode) }
    val latestReaction by reactionsFlow.collectAsState(initial = null)

    LaunchedEffect(latestReaction) {
        val reaction = latestReaction ?: return@LaunchedEffect
        if (reaction.senderId == currentUserId) return@LaunchedEffect
        visibleReactions.add(reaction)
        delay(3000)
        visibleReactions.remove(reaction)
    }

    // Player listener for local state changes -> push to Firebase
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                if (isSyncUpdate) return
                roomManager.updatePlaybackState(roomCode, PlaybackState(
                    isPlaying = playing,
                    positionMs = exoPlayer.currentPosition,
                    speed = exoPlayer.playbackParameters.speed,
                    lastUpdatedBy = currentUserId,
                    lastUpdatedAt = System.currentTimeMillis()
                ))
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    BackHandler {
        showExitDialog = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                showControls = !showControls
                showReactions = false
            }
    ) {
        // Video Player
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Reactions overlay (right side)
        if (!isFullscreen) {
            ReactionOverlay(
                reactions = visibleReactions.toList(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 140.dp)
            )
        }

        // Controls overlay
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
            ) {
                // Top bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { showExitDialog = true }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Exit",
                            tint = Color.White
                        )
                    }

                    Text(
                        text = "Room: $roomCode",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )

                    // Member count indicator
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color.White.copy(alpha = 0.15f))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            Icons.Default.People,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "Live",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }

                // Center controls
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Play/Pause
                    IconButton(
                        onClick = {
                            exoPlayer.playWhenReady = !exoPlayer.playWhenReady
                        },
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.2f))
                    ) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }

                // Bottom controls
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    // Progress bar
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = formatTime(if (isUserSeeking) seekPosition else currentPosition),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 12.sp
                        )

                        Slider(
                            value = if (duration > 0) {
                                (if (isUserSeeking) seekPosition else currentPosition).toFloat() / duration.toFloat()
                            } else 0f,
                            onValueChange = { fraction ->
                                isUserSeeking = true
                                seekPosition = (fraction * duration).toLong()
                            },
                            onValueChangeFinished = {
                                exoPlayer.seekTo(seekPosition)
                                isUserSeeking = false
                                // Send seek update to Firebase
                                roomManager.updatePlaybackState(roomCode, PlaybackState(
                                    isPlaying = exoPlayer.playWhenReady,
                                    positionMs = seekPosition,
                                    speed = currentSpeed,
                                    lastUpdatedBy = currentUserId,
                                    lastUpdatedAt = System.currentTimeMillis()
                                ))
                            },
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp),
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                            )
                        )

                        Text(
                            text = formatTime(duration),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 12.sp
                        )
                    }

                    // Bottom action row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Speed selector
                        Box {
                            IconButton(onClick = { showSpeedMenu = true }) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Speed,
                                        contentDescription = "Speed",
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        "${currentSpeed}x",
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }

                            DropdownMenu(
                                expanded = showSpeedMenu,
                                onDismissRequest = { showSpeedMenu = false }
                            ) {
                                listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { speed ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "${speed}x",
                                                fontWeight = if (speed == currentSpeed) FontWeight.Bold else FontWeight.Normal
                                            )
                                        },
                                        onClick = {
                                            currentSpeed = speed
                                            exoPlayer.playbackParameters = PlaybackParameters(speed)
                                            showSpeedMenu = false
                                            roomManager.updatePlaybackState(roomCode, PlaybackState(
                                                isPlaying = exoPlayer.isPlaying,
                                                positionMs = exoPlayer.currentPosition,
                                                speed = speed,
                                                lastUpdatedBy = currentUserId,
                                                lastUpdatedAt = System.currentTimeMillis()
                                            ))
                                        }
                                    )
                                }
                            }
                        }

                        // Reactions button
                        if (!isFullscreen) {
                            IconButton(onClick = {
                                showReactions = !showReactions
                                showControls = true
                            }) {
                                Icon(
                                    Icons.Default.Mood,
                                    contentDescription = "Reactions",
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        // Fullscreen toggle
                        IconButton(onClick = { isFullscreen = !isFullscreen }) {
                            Icon(
                                if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                contentDescription = "Fullscreen",
                                tint = Color.White
                            )
                        }
                    }
                }
            }
        }

        // Reaction picker (bottom)
        AnimatedVisibility(
            visible = showReactions && !isFullscreen,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
        ) {
            ReactionPicker(
                onReactionSelected = { emoji ->
                    roomManager.sendReaction(roomCode, emoji)
                    // Show locally too
                    val localReaction = ReactionEvent(
                        emoji = emoji,
                        senderId = currentUserId,
                        senderName = "You",
                        timestamp = System.currentTimeMillis()
                    )
                    visibleReactions.add(localReaction)
                    scope.launch {
                        delay(3000)
                        visibleReactions.remove(localReaction)
                    }
                }
            )
        }
    }

    // Exit confirmation dialog
    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            shape = RoundedCornerShape(24.dp),
            title = { Text("Leave Party?") },
            text = {
                Text(
                    if (isHost)
                        "As the host, leaving will end the party for everyone."
                    else
                        "Are you sure you want to leave the watch party?"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showExitDialog = false
                    if (isHost) {
                        roomManager.endRoom(roomCode)
                    } else {
                        roomManager.leaveRoom(roomCode)
                    }
                    onExit()
                }) {
                    Text("Leave", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) {
                    Text("Stay")
                }
            }
        )
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
