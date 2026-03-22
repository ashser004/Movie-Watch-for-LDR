package com.ash.kandaloo.service

import android.content.Context
import android.media.MediaPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.security.MessageDigest

/**
 * Manages voice note playback with an LRU disk cache.
 * Uses MediaPlayer (separate from ExoPlayer which handles video).
 */
class VoicePlayerManager(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null
    private var currentUrl: String? = null
    private var progressJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _playingUrl = MutableStateFlow("")
    val playingUrl: StateFlow<String> = _playingUrl

    private val _progress = MutableStateFlow(0f) // 0.0 to 1.0
    val progress: StateFlow<Float> = _progress

    private val cacheDir: File by lazy {
        File(context.cacheDir, "voice_cache").also { it.mkdirs() }
    }

    companion object {
        private const val MAX_CACHE_SIZE_BYTES = 50L * 1024 * 1024 // 50MB
    }

    /**
     * Play a voice note from URL. Downloads + caches first if needed.
     * If the same URL is already playing, toggles pause/resume.
     */
    fun play(audioUrl: String, onError: ((String) -> Unit)? = null) {
        // Toggle if same URL
        if (audioUrl == currentUrl && mediaPlayer != null) {
            val mp = mediaPlayer!!
            if (mp.isPlaying) {
                mp.pause()
                _isPlaying.value = false
            } else {
                mp.start()
                _isPlaying.value = true
                startProgressTracking()
            }
            return
        }

        // Stop current playback
        stop()

        // Load from cache or download
        scope.launch(Dispatchers.IO) {
            try {
                val cachedFile = getCachedFile(audioUrl) ?: downloadAndCache(audioUrl)
                if (cachedFile == null) {
                    scope.launch(Dispatchers.Main) {
                        onError?.invoke("Failed to download voice note")
                    }
                    return@launch
                }

                scope.launch(Dispatchers.Main) {
                    try {
                        val mp = MediaPlayer()
                        mp.setDataSource(cachedFile.absolutePath)
                        mp.setOnCompletionListener {
                            _isPlaying.value = false
                            _progress.value = 0f
                            _playingUrl.value = ""
                            progressJob?.cancel()
                        }
                        mp.setOnErrorListener { _, _, _ ->
                            stop()
                            onError?.invoke("Playback error")
                            true
                        }
                        mp.prepare()
                        mp.start()

                        mediaPlayer = mp
                        currentUrl = audioUrl
                        _isPlaying.value = true
                        _playingUrl.value = audioUrl
                        startProgressTracking()
                    } catch (e: Exception) {
                        onError?.invoke(e.message ?: "Playback error")
                    }
                }
            } catch (e: Exception) {
                scope.launch(Dispatchers.Main) {
                    onError?.invoke(e.message ?: "Download failed")
                }
            }
        }
    }

    /** Play a local file (for preview before sending) */
    fun playLocal(file: File, onComplete: (() -> Unit)? = null) {
        stop()
        try {
            val mp = MediaPlayer()
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener {
                _isPlaying.value = false
                _progress.value = 0f
                _playingUrl.value = ""
                progressJob?.cancel()
                onComplete?.invoke()
            }
            mp.prepare()
            mp.start()

            mediaPlayer = mp
            currentUrl = file.absolutePath
            _isPlaying.value = true
            _playingUrl.value = file.absolutePath
            startProgressTracking()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stop() {
        progressJob?.cancel()
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
        currentUrl = null
        _isPlaying.value = false
        _progress.value = 0f
        _playingUrl.value = ""
    }

    fun getDuration(): Int {
        return try {
            mediaPlayer?.duration ?: 0
        } catch (_: Exception) {
            0
        }
    }

    private fun startProgressTracking() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (_isPlaying.value) {
                try {
                    val mp = mediaPlayer ?: break
                    val dur = mp.duration
                    if (dur > 0) {
                        _progress.value = mp.currentPosition.toFloat() / dur.toFloat()
                    }
                } catch (_: Exception) {
                    break
                }
                delay(100)
            }
        }
    }

    // ─── Cache Management ───

    private fun urlToFileName(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(url.toByteArray())
        return hash.joinToString("") { "%02x".format(it) } + ".ogg"
    }

    private fun getCachedFile(url: String): File? {
        val file = File(cacheDir, urlToFileName(url))
        return if (file.exists() && file.length() > 0) {
            // Touch for LRU
            file.setLastModified(System.currentTimeMillis())
            file
        } else null
    }

    private fun downloadAndCache(url: String): File? {
        return try {
            evictIfNeeded()
            val file = File(cacheDir, urlToFileName(url))
            val connection = URL(url).openConnection()
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.getInputStream().use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun evictIfNeeded() {
        val files = cacheDir.listFiles() ?: return
        var totalSize = files.sumOf { it.length() }

        if (totalSize <= MAX_CACHE_SIZE_BYTES) return

        // Sort oldest first (LRU)
        val sorted = files.sortedBy { it.lastModified() }
        for (f in sorted) {
            if (totalSize <= MAX_CACHE_SIZE_BYTES * 0.8) break // Evict to 80%
            totalSize -= f.length()
            f.delete()
        }
    }

    /** Clear entire voice cache — call on session end */
    fun clearCache() {
        stop()
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    fun release() {
        stop()
    }
}
