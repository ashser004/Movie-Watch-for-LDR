package com.ash.kandaloo.ui.screens

import android.app.Activity
import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.ui.CaptionStyleCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.ash.kandaloo.data.ChatMessage
import com.ash.kandaloo.data.PlaybackState
import com.ash.kandaloo.data.ReactionEvent
import com.ash.kandaloo.service.RoomManager
import com.ash.kandaloo.service.TrackInfo
import com.ash.kandaloo.service.VoicePlayerManager
import com.ash.kandaloo.ui.components.ChatSection
import com.ash.kandaloo.ui.components.LiveBadge
import com.ash.kandaloo.ui.components.ReactionOverlay
import com.ash.kandaloo.ui.components.ReactionPicker
import com.ash.kandaloo.ui.components.StreamSelectorDialog
import com.google.firebase.auth.FirebaseAuth

@OptIn(UnstableApi::class)
@Composable
fun PortraitPlayerLayout(
    exoPlayer: ExoPlayer,
    viewModel: VideoPlayerViewModel,
    videoUri: Uri,
    voicePlayerManager: VoicePlayerManager,
    audioTracks: List<TrackInfo>,
    subtitleTracks: List<TrackInfo>,
    onAudioSelected: (TrackInfo) -> Unit,
    onSubtitleSelected: (TrackInfo?) -> Unit
) {
    val context = LocalContext.current
    val currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: ""
    val coroutineScope = rememberCoroutineScope()

    var showStreamSelector by remember { mutableStateOf(false) }
    var isPortraitRecording by remember { mutableStateOf(false) }

    // Audio ducking: mute during recording, near-mute during voice playback
    val voicePlaying by voicePlayerManager.isPlaying.collectAsState()
    LaunchedEffect(voicePlaying, isPortraitRecording) {
        exoPlayer.volume = when {
            isPortraitRecording -> 0.0f
            voicePlaying -> 0.05f
            else -> 1.0f
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Video player box
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.35f)
                .background(Color.Black)
                .statusBarsPadding()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    viewModel.showControls.value = !viewModel.showControls.value
                    viewModel.showReactions.value = false
                }
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = false
                        subtitleView?.setStyle(CaptionStyleCompat(
                            android.graphics.Color.WHITE,
                            android.graphics.Color.argb(128, 0, 0, 0),  // Semi-transparent black background
                            android.graphics.Color.TRANSPARENT,
                            CaptionStyleCompat.EDGE_TYPE_NONE,
                            android.graphics.Color.WHITE,
                            android.graphics.Typeface.DEFAULT
                        ))
                        subtitleView?.setBottomPaddingFraction(0.08f)
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Reactions overlay
            ReactionOverlay(
                reactions = viewModel.visibleReactions.toList(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 8.dp, bottom = 8.dp)
            )

            // Controls overlay
            androidx.compose.animation.AnimatedVisibility(
                visible = viewModel.showControls.value,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                PortraitVideoOverlay(
                    roomCode = viewModel.roomCode,
                    isPlaying = viewModel.isPlaying.value,
                    isVideoEnded = viewModel.isVideoEnded.value,
                    currentSpeed = viewModel.currentSpeed.floatValue,
                    showSpeedMenu = viewModel.showSpeedMenu.value,
                    showReactions = viewModel.showReactions.value,
                    memberCount = viewModel.memberCount.value,
                    memberNames = viewModel.memberNames.toList(),
                    onPlayPause = {
                        if (viewModel.isPlayLocked.value) {
                            Toast.makeText(context, "Ask another member to play", Toast.LENGTH_SHORT).show()
                        } else if (viewModel.isVideoEnded.value) {
                            exoPlayer.seekTo(0)
                            exoPlayer.playWhenReady = true
                            viewModel.isVideoEnded.value = false
                        } else {
                            exoPlayer.playWhenReady = !exoPlayer.playWhenReady
                        }
                    },
                    onSkipBackward = { viewModel.handleRewind(exoPlayer.currentPosition) },
                    onSkipForward = { viewModel.handleSkip(exoPlayer.currentPosition) },
                    onToggleSpeedMenu = { viewModel.showSpeedMenu.value = !viewModel.showSpeedMenu.value },
                    onDismissSpeedMenu = { viewModel.showSpeedMenu.value = false },
                    onSpeedChange = { speed ->
                        viewModel.isSyncUpdate = true
                        viewModel.currentSpeed.floatValue = speed
                        exoPlayer.playbackParameters = PlaybackParameters(speed)
                        viewModel.showSpeedMenu.value = false
                        viewModel.roomManager.updatePlaybackState(viewModel.roomCode, PlaybackState(
                            isPlaying = exoPlayer.isPlaying,
                            positionMs = exoPlayer.currentPosition,
                            speed = speed,
                            lastUpdatedBy = currentUserId,
                            lastUpdatedAt = System.currentTimeMillis()
                        ))
                        coroutineScope.launch {
                            delay(800)
                            viewModel.isSyncUpdate = false
                        }
                    },
                    onToggleReactions = {
                        viewModel.showReactions.value = !viewModel.showReactions.value
                        viewModel.showControls.value = true
                    },
                    onFullscreen = { viewModel.isFullscreen.value = true },
                    onExit = { viewModel.showExitDialog.value = true },
                    onToggleStreamSelector = { showStreamSelector = true }
                )
            }

            // Reaction picker
            androidx.compose.animation.AnimatedVisibility(
                visible = viewModel.showReactions.value,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                ReactionPicker(
                    onReactionSelected = { emoji ->
                        viewModel.sendReaction(emoji)
                        val localReaction = ReactionEvent(
                            emoji = emoji,
                            senderId = currentUserId,
                            senderName = "You",
                            timestamp = System.currentTimeMillis()
                        )
                        viewModel.visibleReactions.add(localReaction)
                        coroutineScope.launch {
                            delay(3000)
                            viewModel.visibleReactions.remove(localReaction)
                        }
                    }
                )
            }
        }

        // SeekBar
        PortraitSeekBar(
            isUserSeeking = viewModel.isUserSeeking.value,
            seekPosition = viewModel.seekPosition.longValue,
            currentPosition = viewModel.currentPosition.longValue,
            duration = viewModel.duration.longValue,
            enabled = !viewModel.isPlayLocked.value,
            onSeekStart = { fraction ->
                viewModel.isUserSeeking.value = true
                viewModel.seekPosition.longValue = (fraction * viewModel.duration.longValue).toLong()
            },
            onSeekEnd = {
                viewModel.isSyncUpdate = true
                exoPlayer.seekTo(viewModel.seekPosition.longValue)
                exoPlayer.playWhenReady = false
                viewModel.isUserSeeking.value = false
                viewModel.roomManager.updatePlaybackState(viewModel.roomCode, PlaybackState(
                    isPlaying = false,
                    positionMs = viewModel.seekPosition.longValue,
                    speed = viewModel.currentSpeed.floatValue,
                    lastUpdatedBy = currentUserId,
                    lastUpdatedAt = System.currentTimeMillis()
                ))
                coroutineScope.launch {
                    delay(800)
                    viewModel.isSyncUpdate = false
                }
            }
        )

        // Chat section
        ChatSection(
            messages = viewModel.chatMessages.toList(),
            currentUserId = currentUserId,
            onSendMessage = { message, replyTo -> viewModel.sendChatMessage(message, replyTo) },
            voicePlayerManager = voicePlayerManager,
            onSendVoice = { file, durationMs, replyTo -> viewModel.uploadVoiceNote(file, durationMs, replyTo) },
            onRecordingStateChanged = { isPortraitRecording = it },
            typingNames = viewModel.typingNames.toList(),
            onUserTypingStateChanged = { viewModel.setTyping(it) },
            modifier = Modifier.weight(1f)
        )
    }

    if (showStreamSelector) {
        StreamSelectorDialog(
            audioTracks = audioTracks,
            subtitleTracks = subtitleTracks,
            onAudioSelected = {
                onAudioSelected(it)
            },
            onSubtitleSelected = {
                onSubtitleSelected(it)
            },
            onDismiss = { showStreamSelector = false }
        )
    }
}

@Composable
private fun PortraitVideoOverlay(
    roomCode: String,
    isPlaying: Boolean,
    isVideoEnded: Boolean,
    currentSpeed: Float,
    showSpeedMenu: Boolean,
    showReactions: Boolean,
    memberCount: Int,
    memberNames: List<String>,
    onPlayPause: () -> Unit,
    onSkipBackward: () -> Unit,
    onSkipForward: () -> Unit,
    onToggleSpeedMenu: () -> Unit,
    onDismissSpeedMenu: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onToggleReactions: () -> Unit,
    onFullscreen: () -> Unit,
    onExit: () -> Unit,
    onToggleStreamSelector: () -> Unit
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
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onExit) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Exit",
                    tint = Color.White
                )
            }
            Text(
                text = "Room: $roomCode",
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )

            // Streams Button
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White.copy(alpha = 0.15f))
                    .clickable { onToggleStreamSelector() }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Tune,
                    contentDescription = "Streams",
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text("Streams", color = Color.White, style = MaterialTheme.typography.labelSmall)
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Live Badge
            LiveBadge(
                memberCount = memberCount,
                memberNames = memberNames
            )
            Spacer(modifier = Modifier.width(4.dp))
        }

        // Center controls
        Row(
            modifier = Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            IconButton(
                onClick = onSkipBackward,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.15f))
            ) {
                Icon(
                    Icons.Default.Replay10,
                    contentDescription = "Skip back 10s",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }

            IconButton(
                onClick = onPlayPause,
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.2f))
            ) {
                Icon(
                    when {
                        isVideoEnded -> Icons.Default.Replay
                        isPlaying -> Icons.Default.Pause
                        else -> Icons.Default.PlayArrow
                    },
                    contentDescription = when {
                        isVideoEnded -> "Restart"
                        isPlaying -> "Pause"
                        else -> "Play"
                    },
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }

            IconButton(
                onClick = onSkipForward,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.15f))
            ) {
                Icon(
                    Icons.Default.Forward10,
                    contentDescription = "Skip forward 10s",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }
        }

        // Bottom controls
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                IconButton(onClick = onToggleSpeedMenu) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Speed,
                            contentDescription = "Speed",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("${currentSpeed}x", color = Color.White, fontSize = 11.sp)
                    }
                }
                DropdownMenu(expanded = showSpeedMenu, onDismissRequest = onDismissSpeedMenu) {
                    listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { speed ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "${speed}x",
                                    fontWeight = if (speed == currentSpeed) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            onClick = { onSpeedChange(speed) }
                        )
                    }
                }
            }

            IconButton(onClick = onToggleReactions) {
                Icon(
                    Icons.Default.Mood,
                    contentDescription = "Reactions",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }

            IconButton(onClick = onFullscreen) {
                Icon(
                    Icons.Default.Fullscreen,
                    contentDescription = "Fullscreen",
                    tint = Color.White
                )
            }
        }
    }
}

@Composable
private fun PortraitSeekBar(
    isUserSeeking: Boolean,
    seekPosition: Long,
    currentPosition: Long,
    duration: Long,
    enabled: Boolean,
    onSeekStart: (Float) -> Unit,
    onSeekEnd: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 12.dp, vertical = 2.dp)
    ) {
        Text(
            text = formatTime(if (isUserSeeking) seekPosition else currentPosition),
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 11.sp
        )
        Slider(
            value = if (duration > 0) {
                (if (isUserSeeking) seekPosition else currentPosition).toFloat() / duration.toFloat()
            } else 0f,
            enabled = enabled,
            onValueChange = { onSeekStart(it) },
            onValueChangeFinished = onSeekEnd,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp)
                .graphicsLayer(scaleY = 0.8f),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.2f)
            )
        )
        Text(
            text = formatTime(duration),
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 11.sp
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
