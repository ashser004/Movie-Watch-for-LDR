package com.ash.kandaloo.service

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.ash.kandaloo.data.VideoMetadata

object VideoMetadataExtractor {

    fun extract(context: Context, uri: Uri): VideoMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)

            val duration = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L

            val width = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull() ?: 0

            val height = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull() ?: 0

            val mimeType = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_MIMETYPE
            ) ?: ""

            val bitrate = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_BITRATE
            )?.toLongOrNull() ?: 0L

            val fileSize = getFileSize(context, uri)

            VideoMetadata(
                durationMs = duration,
                width = width,
                height = height,
                mimeType = mimeType,
                bitrate = bitrate,
                fileSize = fileSize
            )
        } catch (e: Exception) {
            VideoMetadata()
        } finally {
            retriever.release()
        }
    }

    private fun getFileSize(context: Context, uri: Uri): Long {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                cursor.moveToFirst()
                if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0L
            } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}
