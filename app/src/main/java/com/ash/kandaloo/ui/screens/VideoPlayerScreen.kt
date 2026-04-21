package com.ash.kandaloo.ui.screens

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay10
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.ui.PlayerView
import com.ash.kandaloo.data.ChatMessage
import com.ash.kandaloo.data.PlaybackState
import com.ash.kandaloo.data.ReactionEvent
import com.ash.kandaloo.service.PlaybackService
import com.ash.kandaloo.service.RoomManager
import com.ash.kandaloo.service.VoicePlayerManager
import com.ash.kandaloo.service.VoiceRecorder
import com.ash.kandaloo.ui.components.ChatSection
import com.ash.kandaloo.ui.components.FloatingMessageOverlay
import com.ash.kandaloo.ui.components.ReactionOverlay
import com.ash.kandaloo.ui.components.ReactionPicker
import com.ash.kandaloo.ui.components.RecordingWaveform
import com.ash.kandaloo.ui.components.formatVoiceDuration
import com.google.firebase.auth.FirebaseAuth
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(
    videoUri: Uri,
    roomCode: String,
    roomManager: RoomManager,
    isHost: Boolean,
    isRejoin: Boolean = false,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
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
    var isVideoEnded by remember { mutableStateOf(false) }
    var currentSpeed by remember { mutableFloatStateOf(1.0f) }
    var isSyncUpdate by remember { mutableStateOf(false) }
    val visibleReactions = remember { mutableStateListOf<ReactionEvent>() }
    val chatMessages = remember { mutableStateListOf<ChatMessage>() }
    val floatingMessages = remember { mutableStateListOf<ChatMessage>() }

    // Voice player for voice notes (separate from ExoPlayer)
    val voicePlayerManager = remember { VoicePlayerManager(context) }
    var isUploadingVoice by remember { mutableStateOf(false) }

    // Play lock for rejoined users — prevents playing at wrong position
    var isPlayLocked by remember { mutableStateOf(isRejoin) }
    var audioIssueWarningShown by remember { mutableStateOf(false) }

    // Skip lock state (for ±10s skip with 5s cooldown)
    var skipLockBy by remember { mutableStateOf("") }
    var skipLockAt by remember { mutableLongStateOf(0L) }

    // Observe skip lock from RTDB
    LaunchedEffect(roomCode) {
        roomManager.observeSkipLock(roomCode).collect { (lockedBy, lockedAt) ->
            skipLockBy = lockedBy
            skipLockAt = lockedAt
        }
    }

    // Voice upload helper
    val voiceUploadAndSend: (File, Long) -> Unit = { file, durationMs ->
        if (!isUploadingVoice) {
            isUploadingVoice = true
            Toast.makeText(context, "Sending voice note...", Toast.LENGTH_SHORT).show()
            roomManager.getCloudinarySignature(
                onSuccess = { signature, timestamp, apiKey, cloudName, folder ->
                    roomManager.uploadVoiceToCloudinary(
                        file = file,
                        signature = signature,
                        timestamp = timestamp,
                        apiKey = apiKey,
                        cloudName = cloudName,
                        folder = folder,
                        onSuccess = { audioUrl ->
                            roomManager.sendVoiceMessage(roomCode, audioUrl, durationMs)
                            file.delete()
                            isUploadingVoice = false
                        },
                        onFailure = { err ->
                            Toast.makeText(context, "Upload failed: $err", Toast.LENGTH_LONG).show()
                            isUploadingVoice = false
                        }
                    )
                },
                onFailure = { err ->
                    Toast.makeText(context, "Auth failed: $err", Toast.LENGTH_LONG).show()
                    isUploadingVoice = false
                }
            )
        }
    }

    val exoPlayer = remember {
        // Custom RenderersFactory: overrides buildVideoRenderers to inject a MediaCodecSelector
        // that ALWAYS includes software decoders alongside hardware ones.
        // Why: Redmi 14C has a hardware HEVC decoder that doesn't support Main 10 profile.
        //      The hardware decoder is returned by DEFAULT selector, gets picked, but fails at init.
        //      By including software decoders (c2.android.hevc.decoder) in the list,
        //      setEnableDecoderFallback(true) can fall back to them when hardware init fails.
        //      On devices WITH hardware Main 10 support (like iQOO), hardware is tried first
        //      and succeeds — software is never used. No performance impact.
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildVideoRenderers(
                context: android.content.Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                eventHandler: android.os.Handler,
                eventListener: VideoRendererEventListener,
                allowedVideoJoiningTimeMs: Long,
                out: ArrayList<Renderer>
            ) {
                // Always return ALL available decoders (hardware + software)
                // ExoPlayer tries them in order; setEnableDecoderFallback(true) ensures
                // it moves to the next if one fails during init
                val allDecodersSelector = MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                    // Get the default list (usually hardware-only when secure/tunneling flags match)
                    val defaultList = MediaCodecSelector.DEFAULT.getDecoderInfos(
                        mimeType, requiresSecureDecoder, requiresTunnelingDecoder
                    )
                    // Also get the full unrestricted list (includes software decoders)
                    val fullList = MediaCodecSelector.DEFAULT.getDecoderInfos(
                        mimeType, false, false
                    )
                    // Merge: default list first (hardware priority), then any extras from full list
                    val merged = defaultList.toMutableList()
                    for (info in fullList) {
                        if (!merged.contains(info)) {
                            merged.add(info)
                        }
                    }
                    merged
                }
                super.buildVideoRenderers(
                    context, extensionRendererMode, allDecodersSelector,
                    true, // force enableDecoderFallback regardless of outer setting
                    eventHandler, eventListener,
                    allowedVideoJoiningTimeMs, out
                )
            }
        }.setEnableDecoderFallback(true)
         .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        // Configure HTTP data source with proper timeouts for large file streaming
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("KanDaloo/1.0")
            .setConnectTimeoutMs(30_000)
            .setReadTimeoutMs(60_000)
            .setAllowCrossProtocolRedirects(true)

        // Wrap HTTP factory so local content:// URIs still work
        val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)

        // Create media source factory with our configured data source
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
                // Ensure the stream is routed and treated as media playback audio.
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    true
                )
                volume = 1.0f
            }
    }

    // Skip helper: ±10s seek with lock enforcement
    val handleSkip: (Boolean) -> Unit = { forward ->
        val now = System.currentTimeMillis()
        val lockAge = now - skipLockAt
        val isLockedByOther = skipLockBy.isNotEmpty() && skipLockBy != currentUserId && lockAge < 5000

        if (isLockedByOther) {
            Toast.makeText(context, "Another user is skipping, wait a moment", Toast.LENGTH_SHORT).show()
        } else {
            // Set skip lock (resets timer for same user)
            roomManager.setSkipLock(roomCode, currentUserId)

            val delta = if (forward) 10_000L else -10_000L
            val newPos = (exoPlayer.currentPosition + delta).coerceIn(0L, duration)
            exoPlayer.seekTo(newPos)
            exoPlayer.playWhenReady = false // Pause for sync

            roomManager.updatePlaybackState(roomCode, PlaybackState(
                isPlaying = false,
                positionMs = newPos,
                speed = currentSpeed,
                lastUpdatedBy = currentUserId,
                lastUpdatedAt = System.currentTimeMillis()
            ))
        }
    }

    // Audio ducking: mute during recording, near-mute during voice playback
    val voicePlaying by voicePlayerManager.isPlaying.collectAsState()
    var isPortraitRecording by remember { mutableStateOf(false) }
    LaunchedEffect(voicePlaying, isPortraitRecording) {
        exoPlayer.volume = when {
            isPortraitRecording -> 0.0f   // Mute while recording
            voicePlaying -> 0.05f         // 5% while listening to voice
            else -> 1.0f                  // Full volume
        }
    }

    // Prepare player after composition (non-blocking to avoid startup lag)
    LaunchedEffect(exoPlayer) {
        // Detect MIME type from URI to help ExoPlayer pick the right extractor
        val uriString = videoUri.toString().lowercase()
        val mimeType = when {
            uriString.contains(".mkv") || uriString.contains("matroska") -> MimeTypes.APPLICATION_MATROSKA
            uriString.contains(".mp4") || uriString.contains(".m4v") -> MimeTypes.VIDEO_MP4
            uriString.contains(".webm") -> MimeTypes.APPLICATION_WEBM
            uriString.contains(".ts") -> "video/mp2t"
            uriString.contains(".avi") -> "video/x-msvideo"
            uriString.contains(".flv") -> "video/x-flv"
            else -> null  // Let ExoPlayer auto-detect
        }

        val mediaItem = if (mimeType != null) {
            MediaItem.Builder()
                .setUri(videoUri)
                .setMimeType(mimeType)
                .build()
        } else {
            MediaItem.fromUri(videoUri)
        }

        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
    }

    // Keep screen on while watching
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Start heartbeat, onDisconnect handlers, and PlaybackService
    DisposableEffect(exoPlayer) {
        roomManager.startHeartbeat(roomCode)
        roomManager.setupOnDisconnect(roomCode, videoUri.toString())
        // Start media session service for notification + background play + bluetooth
        PlaybackService.setPlayer(exoPlayer)
        val serviceIntent = Intent(context, PlaybackService::class.java)
        context.startService(serviceIntent)
        onDispose {
            roomManager.stopHeartbeat()
            PlaybackService.setPlayer(null)
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }

    // Handle orientation based on fullscreen state
    LaunchedEffect(isFullscreen) {
        activity?.requestedOrientation = if (isFullscreen) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    // Reset orientation on dispose
    DisposableEffect(Unit) {
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
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
        if (remoteState.lastUpdatedBy == currentUserId) return@LaunchedEffect
        if (remoteState.lastUpdatedAt == 0L) return@LaunchedEffect

        isSyncUpdate = true
        val positionDiff = kotlin.math.abs(exoPlayer.currentPosition - remoteState.positionMs)
        if (positionDiff > 1500) {
            exoPlayer.seekTo(remoteState.positionMs)
        }
        exoPlayer.playWhenReady = remoteState.isPlaying
        if (exoPlayer.playbackParameters.speed != remoteState.speed) {
            exoPlayer.playbackParameters = PlaybackParameters(remoteState.speed)
            currentSpeed = remoteState.speed
        }
        // Unlock play for rejoin user when another member starts playing
        if (isPlayLocked && remoteState.isPlaying) {
            isPlayLocked = false
        }
        delay(500)
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

    // Listen for chat messages
    val chatFlow = remember { roomManager.observeChat(roomCode) }
    val latestChat by chatFlow.collectAsState(initial = null)

    LaunchedEffect(latestChat) {
        val msg = latestChat ?: return@LaunchedEffect
        chatMessages.add(msg)

        // If in fullscreen, also show as floating message
        if (isFullscreen && msg.senderId != currentUserId) {
            floatingMessages.add(msg)
            delay(4000)
            floatingMessages.remove(msg)
        }
    }

    // Auto-unlock play after timeout (safety net if no other member presses play)
    if (isPlayLocked) {
        LaunchedEffect(Unit) {
            delay(15_000)
            if (isPlayLocked) {
                isPlayLocked = false
                Toast.makeText(context, "Auto-unlocked \u2014 you can play now", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Player listener for local state changes -> push to Firebase
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                val anyAudioSupported = tracks.groups.any { group ->
                    group.type == C.TRACK_TYPE_AUDIO &&
                        (0 until group.length).any { trackIndex -> group.isTrackSupported(trackIndex) }
                }

                val anyAudioSelected = tracks.groups.any { group ->
                    group.type == C.TRACK_TYPE_AUDIO &&
                        (0 until group.length).any { trackIndex -> group.isTrackSelected(trackIndex) }
                }

                if (!anyAudioSupported && !audioIssueWarningShown) {
                    audioIssueWarningShown = true
                    Toast.makeText(
                        context,
                        "This video uses AC3 audio, which this device may not decode.",
                        Toast.LENGTH_LONG
                    ).show()
                    return
                }

                if (anyAudioSelected) return

                val firstSupportedAudioGroup = tracks.groups.firstOrNull { group ->
                    group.type == C.TRACK_TYPE_AUDIO &&
                        (0 until group.length).any { trackIndex -> group.isTrackSupported(trackIndex) }
                } ?: return

                val firstSupportedTrackIndex = (0 until firstSupportedAudioGroup.length)
                    .firstOrNull { trackIndex -> firstSupportedAudioGroup.isTrackSupported(trackIndex) }
                    ?: return

                exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                    .buildUpon()
                    .setOverrideForType(
                        TrackSelectionOverride(
                            firstSupportedAudioGroup.mediaTrackGroup,
                            listOf(firstSupportedTrackIndex)
                        )
                    )
                    .build()
            }

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

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    isVideoEnded = true
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val errorMsg = when (error.errorCode) {
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                        "Network error — check your connection"
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                        "Server error — file may be unavailable"
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                        "File not found"
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FAILED ->
                        "Codec not supported on this device"
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
                        "Unsupported file format"
                    else ->
                        "Playback error: ${error.localizedMessage}"
                }
                Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
            voicePlayerManager.clearCache()
            voicePlayerManager.release()
        }
    }

    BackHandler {
        if (isFullscreen) {
            isFullscreen = false
        } else {
            showExitDialog = true
        }
    }

    if (isFullscreen) {
        // ──────────── FULLSCREEN / LANDSCAPE MODE ────────────
        FullscreenPlayer(
            exoPlayer = exoPlayer,
            roomCode = roomCode,
            roomManager = roomManager,
            currentUserId = currentUserId,
            showControls = showControls,
            showReactions = showReactions,
            showSpeedMenu = showSpeedMenu,
            isPlaying = isPlaying,
            isVideoEnded = isVideoEnded,
            isUserSeeking = isUserSeeking,
            seekPosition = seekPosition,
            currentPosition = currentPosition,
            duration = duration,
            currentSpeed = currentSpeed,
            visibleReactions = visibleReactions.toList(),
            floatingMessages = floatingMessages.toList(),
            voicePlayerManager = voicePlayerManager,
            onSendVoice = voiceUploadAndSend,
            onToggleControls = {
                showControls = !showControls
                showReactions = false
            },
            onToggleReactions = {
                showReactions = !showReactions
                showControls = true
            },
            onToggleSpeedMenu = { showSpeedMenu = !showSpeedMenu },
            onDismissSpeedMenu = { showSpeedMenu = false },
            onSpeedChange = { speed ->
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
            },
            onSeekStart = { fraction ->
                isUserSeeking = true
                seekPosition = (fraction * duration).toLong()
            },
            onSeekEnd = {
                exoPlayer.seekTo(seekPosition)
                exoPlayer.playWhenReady = false  // Pause after seek for sync
                isUserSeeking = false
                roomManager.updatePlaybackState(roomCode, PlaybackState(
                    isPlaying = false,
                    positionMs = seekPosition,
                    speed = currentSpeed,
                    lastUpdatedBy = currentUserId,
                    lastUpdatedAt = System.currentTimeMillis()
                ))
            },
            onPlayPause = {
                if (isPlayLocked) {
                    Toast.makeText(context, "Ask another member to play", Toast.LENGTH_SHORT).show()
                } else if (isVideoEnded) {
                    exoPlayer.seekTo(0)
                    exoPlayer.playWhenReady = true
                    isVideoEnded = false
                } else {
                    exoPlayer.playWhenReady = !exoPlayer.playWhenReady
                }
            },
            onSkipBackward = { handleSkip(false) },
            onSkipForward = { handleSkip(true) },
            onExitFullscreen = { isFullscreen = false },
            onExit = { showExitDialog = true },
            onReaction = { emoji ->
                roomManager.sendReaction(roomCode, emoji)
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
    } else {
        // ──────────── PORTRAIT MODE ────────────
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Video player (35% of screen height with top padding for status bar)
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
                        showControls = !showControls
                        showReactions = false
                    }
            ) {
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

                // Reactions overlay
                ReactionOverlay(
                    reactions = visibleReactions.toList(),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 8.dp, bottom = 8.dp)
                )

                // Controls overlay (without seekbar — seekbar is outside)
                androidx.compose.animation.AnimatedVisibility(
                    visible = showControls,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    PortraitVideoOverlay(
                        roomCode = roomCode,
                        isPlaying = isPlaying,
                        isVideoEnded = isVideoEnded,
                        currentSpeed = currentSpeed,
                        showSpeedMenu = showSpeedMenu,
                        showReactions = showReactions,
                        onPlayPause = {
                            if (isPlayLocked) {
                                Toast.makeText(context, "Ask another member to play", Toast.LENGTH_SHORT).show()
                            } else if (isVideoEnded) {
                                exoPlayer.seekTo(0)
                                exoPlayer.playWhenReady = true
                                isVideoEnded = false
                            } else {
                                exoPlayer.playWhenReady = !exoPlayer.playWhenReady
                            }
                        },
                        onSkipBackward = { handleSkip(false) },
                        onSkipForward = { handleSkip(true) },
                        onToggleSpeedMenu = { showSpeedMenu = !showSpeedMenu },
                        onDismissSpeedMenu = { showSpeedMenu = false },
                        onSpeedChange = { speed ->
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
                        },
                        onToggleReactions = {
                            showReactions = !showReactions
                            showControls = true
                        },
                        onFullscreen = { isFullscreen = true },
                        onExit = { showExitDialog = true }
                    )
                }

                // Reaction picker (mini, attached to video area)
                androidx.compose.animation.AnimatedVisibility(
                    visible = showReactions,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    ReactionPicker(
                        onReactionSelected = { emoji ->
                            roomManager.sendReaction(roomCode, emoji)
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

            // Seekbar outside video frame
            PortraitSeekBar(
                isUserSeeking = isUserSeeking,
                seekPosition = seekPosition,
                currentPosition = currentPosition,
                duration = duration,
                onSeekStart = { fraction ->
                    isUserSeeking = true
                    seekPosition = (fraction * duration).toLong()
                },
                onSeekEnd = {
                    exoPlayer.seekTo(seekPosition)
                    exoPlayer.playWhenReady = false  // Pause after seek for sync
                    isUserSeeking = false
                    roomManager.updatePlaybackState(roomCode, PlaybackState(
                        isPlaying = false,
                        positionMs = seekPosition,
                        speed = currentSpeed,
                        lastUpdatedBy = currentUserId,
                        lastUpdatedAt = System.currentTimeMillis()
                    ))
                }
            )

            // Chat section below video (65% remaining)
            ChatSection(
                messages = chatMessages.toList(),
                currentUserId = currentUserId,
                onSendMessage = { message ->
                    roomManager.sendChatMessage(roomCode, message)
                },
                voicePlayerManager = voicePlayerManager,
                onSendVoice = voiceUploadAndSend,
                onRecordingStateChanged = { isPortraitRecording = it },
                modifier = Modifier.weight(1f)
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
                Text("Are you sure you want to leave the watch party?")
            },
            confirmButton = {
                TextButton(onClick = {
                    showExitDialog = false
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

// ─────────── PORTRAIT VIDEO OVERLAY (inside video, no seekbar) ───────────

@Composable
private fun PortraitVideoOverlay(
    roomCode: String,
    isPlaying: Boolean,
    isVideoEnded: Boolean,
    currentSpeed: Float,
    showSpeedMenu: Boolean,
    showReactions: Boolean,
    onPlayPause: () -> Unit,
    onSkipBackward: () -> Unit,
    onSkipForward: () -> Unit,
    onToggleSpeedMenu: () -> Unit,
    onDismissSpeedMenu: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onToggleReactions: () -> Unit,
    onFullscreen: () -> Unit,
    onExit: () -> Unit
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White.copy(alpha = 0.15f))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Icon(
                    Icons.Default.People,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text("Live", color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
        }

        // Center controls: skip back, play/pause, skip forward
        Row(
            modifier = Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Skip backward 10s
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

            // Play/Pause
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

            // Skip forward 10s
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

        // Bottom controls (speed, reactions, fullscreen — no seekbar)
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Speed
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

            // Reactions
            IconButton(onClick = onToggleReactions) {
                Icon(
                    Icons.Default.Mood,
                    contentDescription = "Reactions",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }

            // Fullscreen
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

// ─────────── PORTRAIT SEEKBAR (outside video frame) ───────────

@Composable
private fun PortraitSeekBar(
    isUserSeeking: Boolean,
    seekPosition: Long,
    currentPosition: Long,
    duration: Long,
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

// ─────────── FULLSCREEN PLAYER ───────────

@OptIn(UnstableApi::class)
@Composable
private fun FullscreenPlayer(
    exoPlayer: ExoPlayer,
    roomCode: String,
    roomManager: RoomManager,
    currentUserId: String,
    showControls: Boolean,
    showReactions: Boolean,
    showSpeedMenu: Boolean,
    isPlaying: Boolean,
    isVideoEnded: Boolean,
    isUserSeeking: Boolean,
    seekPosition: Long,
    currentPosition: Long,
    duration: Long,
    currentSpeed: Float,
    visibleReactions: List<ReactionEvent>,
    floatingMessages: List<ChatMessage>,
    voicePlayerManager: VoicePlayerManager,
    onSendVoice: (File, Long) -> Unit,
    onToggleControls: () -> Unit,
    onToggleReactions: () -> Unit,
    onToggleSpeedMenu: () -> Unit,
    onDismissSpeedMenu: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onSeekStart: (Float) -> Unit,
    onSeekEnd: () -> Unit,
    onPlayPause: () -> Unit,
    onSkipBackward: () -> Unit,
    onSkipForward: () -> Unit,
    onExitFullscreen: () -> Unit,
    onExit: () -> Unit,
    onReaction: (String) -> Unit
) {
    val context = LocalContext.current
    // Fullscreen voice recording state
    var isFullscreenRecording by remember { mutableStateOf(false) }
    val fullscreenRecorder = remember { VoiceRecorder(context) }
    var fsRecordingElapsedMs by remember { mutableLongStateOf(0L) }

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
            ) { onToggleControls() }
    ) {
        // Video
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

        // Floating chat messages (left side) — with voice auto-play
        FloatingMessageOverlay(
            messages = floatingMessages,
            voicePlayerManager = voicePlayerManager,
            currentUserId = currentUserId,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 80.dp)
        )

        // Reactions overlay (right side)
        ReactionOverlay(
            reactions = visibleReactions,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 80.dp)
        )

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
                        .padding(horizontal = 8.dp, vertical = 4.dp),
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
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
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
                        Text("Live", color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                }

                // Center controls: skip back, play/pause, skip forward
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    // Skip backward 10s
                    IconButton(
                        onClick = onSkipBackward,
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

                    // Play/Pause
                    IconButton(
                        onClick = onPlayPause,
                        modifier = Modifier
                            .size(72.dp)
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
                            modifier = Modifier.size(40.dp)
                        )
                    }

                    // Skip forward 10s
                    IconButton(
                        onClick = onSkipForward,
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
                            text = formatTime(if (isUserSeeking) seekPosition else currentPosition),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 12.sp
                        )
                        Slider(
                            value = if (duration > 0) {
                                (if (isUserSeeking) seekPosition else currentPosition).toFloat() / duration.toFloat()
                            } else 0f,
                            onValueChange = { onSeekStart(it) },
                            onValueChangeFinished = onSeekEnd,
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
                        // Speed
                        Box {
                            IconButton(onClick = onToggleSpeedMenu) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Speed,
                                        contentDescription = "Speed",
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("${currentSpeed}x", color = Color.White, style = MaterialTheme.typography.labelSmall)
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

                        // Reactions button
                        IconButton(onClick = onToggleReactions) {
                            Icon(
                                Icons.Default.Mood,
                                contentDescription = "Reactions",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        // Mic button (fullscreen only)
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
                        IconButton(onClick = onExitFullscreen) {
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

        // Reaction picker (bottom, fullscreen)
        AnimatedVisibility(
            visible = showReactions,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            ReactionPicker(
                onReactionSelected = { emoji -> onReaction(emoji) }
            )
        }

        // ─── Fullscreen inline recording overlay ───
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
                // Red recording dot
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(Color.Red)
                )

                Spacer(modifier = Modifier.width(8.dp))

                // Animated waveform
                RecordingWaveform(
                    color = Color.Red,
                    modifier = Modifier.height(20.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                // Timer
                Text(
                    text = formatVoiceDuration(fsRecordingElapsedMs),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.weight(1f))

                // Cancel/Delete button
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

                // Send button
                IconButton(
                    onClick = {
                        val result = fullscreenRecorder.stopRecording()
                        isFullscreenRecording = false
                        fsRecordingElapsedMs = 0L
                        if (result != null) {
                            Toast.makeText(context, "Sending voice note...", Toast.LENGTH_SHORT).show()
                            onSendVoice(result.file, result.durationMs)
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
