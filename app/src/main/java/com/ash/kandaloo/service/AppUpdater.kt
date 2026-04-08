package com.ash.kandaloo.service

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import com.ash.kandaloo.data.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manages APK download via DownloadManager and installation.
 * State persists across app restarts through PreferencesManager.
 */
class AppUpdater(
    private val context: Context,
    private val preferencesManager: PreferencesManager
) {
    private val downloadManager =
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    // Download progress 0f..1f, -1f if not downloading
    private val _progress = MutableStateFlow(-1f)
    val progress: StateFlow<Float> = _progress

    // Current state
    private val _state = MutableStateFlow(UpdateState.IDLE)
    val state: StateFlow<UpdateState> = _state

    // Tag of the pending update
    private val _pendingTag = MutableStateFlow("")
    val pendingTag: StateFlow<String> = _pendingTag

    enum class UpdateState {
        IDLE,           // No update activity
        DOWNLOADING,    // Download in progress
        DOWNLOADED,     // APK ready to install
        INSTALLING      // User triggered install
    }

    /**
     * Check for a previously started download on app restart.
     */
    suspend fun restorePendingDownload() {
        val downloadId = preferencesManager.getPendingDownloadId()
        val tag = preferencesManager.getPendingUpdateTag()

        if (downloadId == -1L || tag.isEmpty()) return

        _pendingTag.value = tag

        // Check download status
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor: Cursor? = downloadManager.query(query)

        if (cursor != null && cursor.moveToFirst()) {
            val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val status = if (statusIdx >= 0) cursor.getInt(statusIdx) else -1

            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    _state.value = UpdateState.DOWNLOADED
                    _progress.value = 1f
                }
                DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING -> {
                    _state.value = UpdateState.DOWNLOADING
                    trackProgress(downloadId)
                }
                else -> {
                    // Failed or unknown — clean up
                    preferencesManager.clearPendingDownload()
                    _state.value = UpdateState.IDLE
                }
            }
            cursor.close()
        } else {
            // Download gone — clean up
            cursor?.close()
            preferencesManager.clearPendingDownload()
            _state.value = UpdateState.IDLE
        }
    }

    /**
     * Start downloading the APK for [tag] from [url].
     */
    suspend fun startDownload(url: String, tag: String) {
        // Clean up any old APK files
        cleanupOldApks()

        val fileName = "KanDaloo-$tag.apk"
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle("KanDaloo Update $tag")
            setDescription("Downloading update...")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
            setMimeType("application/vnd.android.package-archive")
        }

        val downloadId = downloadManager.enqueue(request)
        preferencesManager.setPendingDownload(downloadId, tag)

        _pendingTag.value = tag
        _state.value = UpdateState.DOWNLOADING
        _progress.value = 0f

        trackProgress(downloadId)
    }

    /**
     * Polls DownloadManager for progress updates.
     */
    private suspend fun trackProgress(downloadId: Long) = withContext(Dispatchers.IO) {
        while (true) {
            val query = DownloadManager.Query().setFilterById(downloadId)
            val cursor = downloadManager.query(query)

            if (cursor == null || !cursor.moveToFirst()) {
                cursor?.close()
                _state.value = UpdateState.IDLE
                _progress.value = -1f
                preferencesManager.clearPendingDownload()
                break
            }

            val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val bytesIdx = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val totalIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)

            val status = if (statusIdx >= 0) cursor.getInt(statusIdx) else -1
            val bytesDownloaded = if (bytesIdx >= 0) cursor.getLong(bytesIdx) else 0L
            val totalBytes = if (totalIdx >= 0) cursor.getLong(totalIdx) else -1L

            cursor.close()

            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    _progress.value = 1f
                    _state.value = UpdateState.DOWNLOADED
                    break
                }
                DownloadManager.STATUS_FAILED -> {
                    _progress.value = -1f
                    _state.value = UpdateState.IDLE
                    preferencesManager.clearPendingDownload()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Download failed, try again", Toast.LENGTH_SHORT).show()
                    }
                    break
                }
                DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING -> {
                    _progress.value = if (totalBytes > 0) {
                        bytesDownloaded.toFloat() / totalBytes.toFloat()
                    } else 0f
                }
            }

            delay(500) // Poll every 500ms
        }
    }

    /**
     * Launch the APK installer for the downloaded file.
     */
    suspend fun installUpdate() {
        val downloadId = preferencesManager.getPendingDownloadId()
        if (downloadId == -1L) return

        _state.value = UpdateState.INSTALLING

        val tag = preferencesManager.getPendingUpdateTag()
        val fileName = "KanDaloo-$tag.apk"
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)

        if (!file.exists()) {
            Toast.makeText(context, "APK file not found, please re-download", Toast.LENGTH_SHORT).show()
            preferencesManager.clearPendingDownload()
            _state.value = UpdateState.IDLE
            return
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(intent)
    }

    /**
     * Remove old downloaded APKs.
     */
    private fun cleanupOldApks() {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        dir?.listFiles()?.forEach { file ->
            if (file.name.startsWith("KanDaloo-") && file.name.endsWith(".apk")) {
                file.delete()
            }
        }
    }

    /**
     * Cleanup after successful install (call on app open if version changed).
     */
    suspend fun cleanupIfUpdated(currentVersion: String) {
        val pendingTag = preferencesManager.getPendingUpdateTag()
        if (pendingTag.isEmpty()) return

        // If current version is >= pending tag, install was successful
        val currentParts = currentVersion.removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }
        val pendingParts = pendingTag.removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }

        var currentIsNewer = false
        val maxLen = maxOf(currentParts.size, pendingParts.size)
        for (i in 0 until maxLen) {
            val c = currentParts.getOrElse(i) { 0 }
            val p = pendingParts.getOrElse(i) { 0 }
            if (c > p) { currentIsNewer = true; break }
            if (c < p) break
            if (i == maxLen - 1 && c == p) currentIsNewer = true
        }

        if (currentIsNewer) {
            cleanupOldApks()
            preferencesManager.clearPendingDownload()
        }
    }
}
