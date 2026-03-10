package com.ash.kandaloo.service

import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    companion object {
        var exoPlayer: ExoPlayer? = null
            private set

        fun setPlayer(player: ExoPlayer?) {
            exoPlayer = player
        }
    }

    override fun onCreate() {
        super.onCreate()
        val player = exoPlayer ?: return
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep playing in background - don't stop on task removal
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
