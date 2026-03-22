package com.ash.kandaloo.service

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Simple wrapper around MediaRecorder for Opus/OGG voice recording.
 * Uses OGG container + Opus codec natively available on API 29+.
 */
class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startTimeMs: Long = 0L

    val isRecording: Boolean get() = recorder != null

    fun startRecording(): Boolean {
        return try {
            val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.ogg")
            outputFile = file

            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.OGG)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            mr.setAudioSamplingRate(48000)
            mr.setAudioEncodingBitRate(64000) // 64kbps — good quality, small files
            mr.setOutputFile(file.absolutePath)

            mr.prepare()
            mr.start()

            recorder = mr
            startTimeMs = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            cleanup()
            false
        }
    }

    /**
     * Stop recording and return the recorded file + duration.
     * Returns null if recording was not active.
     */
    fun stopRecording(): RecordingResult? {
        val mr = recorder ?: return null
        val file = outputFile ?: return null

        return try {
            mr.stop()
            mr.release()
            recorder = null

            val durationMs = System.currentTimeMillis() - startTimeMs
            RecordingResult(file, durationMs)
        } catch (e: Exception) {
            e.printStackTrace()
            cleanup()
            null
        }
    }

    /** Cancel recording and delete the temp file. */
    fun cancelRecording() {
        try {
            recorder?.stop()
        } catch (_: Exception) {
            // May throw if stop is called too early
        }
        cleanup()
    }

    /** Get current recording amplitude for waveform visualization (0-32767) */
    fun getMaxAmplitude(): Int {
        return try {
            recorder?.maxAmplitude ?: 0
        } catch (_: Exception) {
            0
        }
    }

    fun getElapsedMs(): Long {
        return if (recorder != null) System.currentTimeMillis() - startTimeMs else 0L
    }

    private fun cleanup() {
        try {
            recorder?.release()
        } catch (_: Exception) {}
        recorder = null
        outputFile?.delete()
        outputFile = null
        startTimeMs = 0L
    }

    data class RecordingResult(
        val file: File,
        val durationMs: Long
    )
}
