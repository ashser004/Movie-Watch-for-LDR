package com.ash.kandaloo.ui.screens

import android.content.pm.PackageManager
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.ash.kandaloo.data.ChatMessage
import com.ash.kandaloo.data.PlaybackState
import com.ash.kandaloo.data.ReactionEvent
import com.ash.kandaloo.service.RoomManager
import com.ash.kandaloo.service.TrackInfo
import com.ash.kandaloo.service.VoicePlayerManager
import com.ash.kandaloo.service.VoiceRecorder
import com.ash.kandaloo.ui.components.FloatingMessageOverlay
import com.ash.kandaloo.ui.components.LiveBadge
import com.ash.kandaloo.ui.components.ReactionOverlay
import com.ash.kandaloo.ui.components.ReactionPicker
import com.ash.kandaloo.ui.components.RecordingWaveform
import com.ash.kandaloo.ui.components.StreamSelectorDialog
import com.ash.kandaloo.ui.components.formatVoiceDuration
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@OptIn(UnstableApi::class)
@Composable
fun FullscreenPlayerLayout(
    exoPlayer: ExoPlayer,
    viewModel: VideoPlayerViewModel,
    voicePlayerManager: VoicePlayerManager,
    audioTracks: List<TrackInfo>,
    subtitleTracks: List<TrackInfo>,
    onAudioSelected: (TrackInfo) -> Unit,
    onSubtitleSelected: (TrackInfo?) -> Unit
) {
    val context = LocalContext.current
    val currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: ""
    val coroutineScope = rememberCoroutineScope()

    var isFullscreenRecording by remember { mutableStateOf(false) }
    val fullscreenRecorder = remember { VoiceRecorder(context) }
    var fsRecordingElapsedMs by remember { mutableLongStateOf(0L) }
    var showStreamSelector by remember { mutableStateOf(false) }

    // Mute video audio while recording in fullscreen
    LaunchedEffect(isFullscreenRecording) {
        exoPlayer.volume = if (isFullscreenRecording) 0.0f else 1.0f
    }

    // Recording timer
    LaunchedEffect(isFullscreenRecording) {
        if (isFullscreenRecording) {
            while (isFullscreenRecording) {
                fsRecordingElapsedMs = fullscreenRecorder.getElapsedMs()
                delay(200)
            }
        }
    }

    // Permission launcher for fullscreen mic
    val fsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (fullscreenRecorder.startRecording()) {
                isFullscreenRecording = true
            }
        } else {
            Toast.makeText(context, "Microphone permission required", Toast.LENGTH_SHORT).show()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                viewModel.showControls.value = !viewModel.showControls.value
                viewModel.showReactions.value = false
            }
    ) {
        // Video
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

        // Floating chat messages
        FloatingMessageOverlay(
            messages = viewModel.floatingMessages.toList(),
            voicePlayerManager = voicePlayerManager,
            currentUserId = currentUserId,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 80.dp)
        )

        // Reactions overlay
        ReactionOverlay(
            reactions = viewModel.visibleReactions.toList(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 80.dp)
        )

        // Controls overlay
        AnimatedVisibility(
            visible = viewModel.showControls.value,
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
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { viewModel.showExitDialog.value = true }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Exit",
                            tint = Color.White
                        )
                    }
                    Text(
                        text = "Room: ${viewModel.roomCode}",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )

                    // Streams Button
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color.White.copy(alpha = 0.15f))
                            .clickable { showStreamSelector = true }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Tune,
                            contentDescription = "Streams",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Streams", color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Live Badge
                    LiveBadge(
                        memberCount = viewModel.memberCount.value,
                        memberNames = viewModel.memberNames.toList(),
                        liveMembers = viewModel.liveMembers
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }

                // Center controls
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    IconButton(
                        onClick = { viewModel.handleRewind(exoPlayer.currentPosition) },
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.15f))
                    ) {
                        Icon(
                            Icons.Default.Replay10,
                            contentDescription = "Skip back 10s",
                            tint = Color.White,
                            modifier = Modifier.size(30.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            if (viewModel.isPlayLocked.value) {
                                val remaining = viewModel.getPlayLockRemainingSeconds()
                                Toast.makeText(context, "Ask another member (in ${remaining}s)", Toast.LENGTH_SHORT).show()
                            } else if (viewModel.isVideoEnded.value) {
                                exoPlayer.seekTo(0)
                                exoPlayer.playWhenReady = true
                                viewModel.isVideoEnded.value = false
                            } else {
                                exoPlayer.playWhenReady = !exoPlayer.playWhenReady
                            }
                        },
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.2f))
                    ) {
                        Icon(
                            when {
                                viewModel.isVideoEnded.value -> Icons.Default.Replay
                                viewModel.isPlaying.value -> Icons.Default.Pause
                                else -> Icons.Default.PlayArrow
                            },
                            contentDescription = when {
                                viewModel.isVideoEnded.value -> "Restart"
                                viewModel.isPlaying.value -> "Pause"
                                else -> "Play"
                            },
                            tint = Color.White,
                            modifier = Modifier.size(40.dp)
                        )
                    }

                    IconButton(
                        onClick = { viewModel.handleSkip(exoPlayer.currentPosition) },
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.15f))
                    ) {
                        Icon(
                            Icons.Default.Forward10,
                            contentDescription = "Skip forward 10s",
                            tint = Color.White,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }

                // Bottom controls
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                ) {
                    // Progress bar
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = formatTime(if (viewModel.isUserSeeking.value) viewModel.seekPosition.longValue else viewModel.currentPosition.longValue),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 12.sp
                        )
                        Slider(
                            value = if (viewModel.duration.longValue > 0) {
                                (if (viewModel.isUserSeeking.value) viewModel.seekPosition.longValue else viewModel.currentPosition.longValue).toFloat() / viewModel.duration.longValue.toFloat()
                            } else 0f,
                            enabled = !viewModel.isPlayLocked.value,
                            onValueChange = { fraction ->
                                viewModel.isUserSeeking.value = true
                                viewModel.seekPosition.longValue = (fraction * viewModel.duration.longValue).toLong()
                            },
                            onValueChangeFinished = {
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
                            },
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 12.dp)
                                .graphicsLayer(scaleY = 0.8f),
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                            )
                        )
                        Text(
                            text = formatTime(viewModel.duration.longValue),
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
                        Box {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { viewModel.showSpeedMenu.value = !viewModel.showSpeedMenu.value }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    Icons.Default.Speed,
                                    contentDescription = "Speed",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    "${viewModel.currentSpeed.floatValue}x",
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                            DropdownMenu(expanded = viewModel.showSpeedMenu.value, onDismissRequest = { viewModel.showSpeedMenu.value = false }) {
                                listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { speed ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "${speed}x",
                                                fontWeight = if (speed == viewModel.currentSpeed.floatValue) FontWeight.Bold else FontWeight.Normal
                                            )
                                        },
                                        onClick = {
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
                                        }
                                    )
                                }
                            }
                        }

                        // Reactions button
                        IconButton(onClick = {
                            viewModel.showReactions.value = !viewModel.showReactions.value
                            viewModel.showControls.value = true
                        }) {
                            Icon(
                                Icons.Default.Mood,
                                contentDescription = "Reactions",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        // Mic button
                        IconButton(
                            onClick = {
                                val hasPermission = ContextCompat.checkSelfPermission(
                                    context, android.Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED

                                if (hasPermission) {
                                    if (fullscreenRecorder.startRecording()) {
                                        isFullscreenRecording = true
                                    }
                                } else {
                                    fsPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        ) {
                            Icon(
                                Icons.Default.Mic,
                                contentDescription = "Voice note",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        // Exit fullscreen
                        IconButton(onClick = { viewModel.isFullscreen.value = false }) {
                            Icon(
                                Icons.Default.FullscreenExit,
                                contentDescription = "Exit Fullscreen",
                                tint = Color.White
                            )
                        }
                    }
                }
            }
        }

        // Reaction picker
        AnimatedVisibility(
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

        // Fullscreen inline recording overlay
        AnimatedVisibility(
            visible = isFullscreenRecording,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color(0xCC1A1A2E))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(Color.Red)
                )

                Spacer(modifier = Modifier.width(8.dp))

                RecordingWaveform(
                    color = Color.Red,
                    modifier = Modifier.height(20.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = formatVoiceDuration(fsRecordingElapsedMs),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.weight(1f))

                IconButton(
                    onClick = {
                        fullscreenRecorder.cancelRecording()
                        isFullscreenRecording = false
                        fsRecordingElapsedMs = 0L
                    },
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.15f))
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Cancel",
                        tint = Color.Red.copy(alpha = 0.9f),
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                IconButton(
                    onClick = {
                        val result = fullscreenRecorder.stopRecording()
                        isFullscreenRecording = false
                        fsRecordingElapsedMs = 0L
                        if (result != null) {
                            viewModel.uploadVoiceNote(result.file, result.durationMs, null)
                        }
                    },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFF6C63FF),
                                    Color(0xFF3F51B5)
                                )
                            )
                        )
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send voice",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }

    if (showStreamSelector) {
        StreamSelectorDialog(
            audioTracks = audioTracks,
            subtitleTracks = subtitleTracks,
            onAudioSelected = onAudioSelected,
            onSubtitleSelected = onSubtitleSelected,
            onDismiss = { showStreamSelector = false }
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
