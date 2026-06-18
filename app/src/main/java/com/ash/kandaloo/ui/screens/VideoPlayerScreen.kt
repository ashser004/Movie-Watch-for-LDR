package com.ash.kandaloo.ui.screens

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.ash.kandaloo.data.PlaybackState
import com.ash.kandaloo.service.PlaybackService
import com.ash.kandaloo.service.RoomManager
import com.ash.kandaloo.service.TrackHelper
import com.ash.kandaloo.service.TrackInfo
import com.ash.kandaloo.service.VoicePlayerManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
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

    val viewModel: VideoPlayerViewModel = viewModel(
        factory = VideoPlayerViewModelFactory(roomManager, roomCode, isHost, isRejoin, videoUri.toString())
    )

    // Voice player for voice notes (separate from ExoPlayer)
    val voicePlayerManager = remember { VoicePlayerManager(context) }

    // Initialize ExoPlayer
    val exoPlayer = remember {
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildVideoRenderers(
                context: android.content.Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                eventHandler: android.os.Handler,
                eventListener: androidx.media3.exoplayer.video.VideoRendererEventListener,
                allowedVideoJoiningTimeMs: Long,
                out: ArrayList<Renderer>
            ) {
                val allDecodersSelector = MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                    val defaultList = MediaCodecSelector.DEFAULT.getDecoderInfos(
                        mimeType, requiresSecureDecoder, requiresTunnelingDecoder
                    )
                    val fullList = MediaCodecSelector.DEFAULT.getDecoderInfos(
                        mimeType, false, false
                    )
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
                    true,
                    eventHandler, eventListener,
                    allowedVideoJoiningTimeMs, out
                )
            }
        }.setEnableDecoderFallback(true)
         .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("KanDaloo/1.0")
            .setConnectTimeoutMs(30_000)
            .setReadTimeoutMs(60_000)
            .setAllowCrossProtocolRedirects(true)

        val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
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

    var audioTracks by remember { mutableStateOf<List<TrackInfo>>(emptyList()) }
    var subtitleTracks by remember { mutableStateOf<List<TrackInfo>>(emptyList()) }

    // Observe player listeners & manage release lifecycle
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                audioTracks = TrackHelper.getAudioTracks(tracks)
                subtitleTracks = TrackHelper.getSubtitleTracks(tracks)

                val anyAudioSupported = tracks.groups.any { group ->
                    group.type == C.TRACK_TYPE_AUDIO &&
                        (0 until group.length).any { trackIndex -> group.isTrackSupported(trackIndex) }
                }

                if (!anyAudioSupported && !viewModel.audioIssueWarningShown.value) {
                    viewModel.audioIssueWarningShown.value = true
                    Toast.makeText(
                        context,
                        "This video uses AC3 audio, which this device may not decode.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                viewModel.updatePlaybackStateFromLocal(playing, exoPlayer.currentPosition, exoPlayer.playbackParameters.speed)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    viewModel.isVideoEnded.value = true
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

    // Track state updates from ViewModel
    LaunchedEffect(Unit) {
        viewModel.errorEvent.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(viewModel.shouldExit.value) {
        if (viewModel.shouldExit.value) {
            onExit()
        }
    }

    // Connect remote playback changes to ExoPlayer
    LaunchedEffect(Unit) {
        viewModel.remotePlaybackEvent.collect { remoteState ->
            val positionDiff = kotlin.math.abs(exoPlayer.currentPosition - remoteState.positionMs)
            if (positionDiff > 1500) {
                exoPlayer.seekTo(remoteState.positionMs)
            }
            exoPlayer.playWhenReady = remoteState.isPlaying
            if (exoPlayer.playbackParameters.speed != remoteState.speed) {
                exoPlayer.playbackParameters = PlaybackParameters(remoteState.speed)
            }
        }
    }

    // Connect local ViewModel playback override requests to ExoPlayer
    LaunchedEffect(Unit) {
        viewModel.localPlaybackRequest.collect { request ->
            request.seekPositionMs?.let {
                exoPlayer.seekTo(it)
            }
            exoPlayer.playWhenReady = request.isPlaying
        }
    }

    // Position progress update loop
    LaunchedEffect(exoPlayer) {
        while (true) {
            if (!viewModel.isUserSeeking.value) {
                viewModel.currentPosition.longValue = exoPlayer.currentPosition
                viewModel.duration.longValue = exoPlayer.duration.coerceAtLeast(0L)
                viewModel.isPlaying.value = exoPlayer.isPlaying
            }
            delay(500)
        }
    }

    // Prepare player
    LaunchedEffect(exoPlayer) {
        val uriString = videoUri.toString().lowercase()
        val mimeType = when {
            uriString.contains(".mkv") || uriString.contains("matroska") -> MimeTypes.APPLICATION_MATROSKA
            uriString.contains(".mp4") || uriString.contains(".m4v") -> MimeTypes.VIDEO_MP4
            uriString.contains(".webm") -> MimeTypes.APPLICATION_WEBM
            uriString.contains(".ts") -> "video/mp2t"
            uriString.contains(".avi") -> "video/x-msvideo"
            uriString.contains(".flv") -> "video/x-flv"
            else -> null
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

    // Screen Keep On
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Service session lifecycle link
    DisposableEffect(exoPlayer) {
        PlaybackService.setPlayer(exoPlayer)
        val serviceIntent = Intent(context, PlaybackService::class.java)
        context.startService(serviceIntent)
        onDispose {
            PlaybackService.setPlayer(null)
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }

    // Fullscreen landscape locking
    LaunchedEffect(viewModel.isFullscreen.value) {
        activity?.requestedOrientation = if (viewModel.isFullscreen.value) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    BackHandler {
        if (viewModel.isFullscreen.value) {
            viewModel.isFullscreen.value = false
        } else {
            viewModel.showExitDialog.value = true
        }
    }

    if (viewModel.isFullscreen.value) {
        FullscreenPlayerLayout(
            exoPlayer = exoPlayer,
            viewModel = viewModel,
            voicePlayerManager = voicePlayerManager,
            audioTracks = audioTracks,
            subtitleTracks = subtitleTracks,
            onAudioSelected = { TrackHelper.selectAudioTrack(exoPlayer, it) },
            onSubtitleSelected = {
                if (it == null) {
                    TrackHelper.disableSubtitles(exoPlayer)
                } else {
                    TrackHelper.selectSubtitleTrack(exoPlayer, it)
                }
            }
        )
    } else {
        PortraitPlayerLayout(
            exoPlayer = exoPlayer,
            viewModel = viewModel,
            videoUri = videoUri,
            voicePlayerManager = voicePlayerManager,
            audioTracks = audioTracks,
            subtitleTracks = subtitleTracks,
            onAudioSelected = { TrackHelper.selectAudioTrack(exoPlayer, it) },
            onSubtitleSelected = {
                if (it == null) {
                    TrackHelper.disableSubtitles(exoPlayer)
                } else {
                    TrackHelper.selectSubtitleTrack(exoPlayer, it)
                }
            }
        )
    }

    if (viewModel.showExitDialog.value) {
        AlertDialog(
            onDismissRequest = { viewModel.showExitDialog.value = false },
            shape = RoundedCornerShape(24.dp),
            title = { Text("Leave Party?") },
            text = { Text("Are you sure you want to leave the watch party?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.showExitDialog.value = false
                    viewModel.roomManager.leaveRoom(roomCode)
                    onExit()
                }) {
                    Text("Leave", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.showExitDialog.value = false }) {
                    Text("Stay")
                }
            }
        )
    }
}
