package com.ash.kandaloo.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class UpdateResult(
    val hasUpdate: Boolean,
    val latestTag: String = "",
    val downloadUrl: String = ""
)

object UpdateChecker {

    private const val API_URL =
        "https://api.github.com/repos/ashser004/Movie-Watch-for-LDR/releases/latest"

    /**
     * Checks GitHub Releases API for a newer version.
     * Returns [UpdateResult] with download URL if a newer version exists.
     */
    suspend fun check(currentVersionName: String): UpdateResult = withContext(Dispatchers.IO) {
        try {
            val connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                connectTimeout = 10_000
                readTimeout = 10_000
            }

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                return@withContext UpdateResult(hasUpdate = false)
            }

            val body = connection.inputStream.bufferedReader().readText()
            connection.disconnect()

            val json = JSONObject(body)
            val tagName = json.getString("tag_name") // e.g. "v2.5"

            val latestVersion = parseVersion(tagName)
            val currentVersion = parseVersion(currentVersionName)

            if (compareVersions(latestVersion, currentVersion) > 0) {
                // Construct download URL from tag
                val downloadUrl =
                    "https://github.com/ashser004/Movie-Watch-for-LDR/releases/download/$tagName/KanDaloo-$tagName.apk"
                UpdateResult(
                    hasUpdate = true,
                    latestTag = tagName,
                    downloadUrl = downloadUrl
                )
            } else {
                UpdateResult(hasUpdate = false)
            }
        } catch (_: Exception) {
            UpdateResult(hasUpdate = false)
        }
    }

    /**
     * Parse version string like "v2.4" or "2.4" into list of ints [2, 4].
     */
    private fun parseVersion(version: String): List<Int> {
        return version.removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }
    }

    /**
     * Compare two version lists segment by segment.
     * Returns positive if a > b, negative if a < b, 0 if equal.
     */
    private fun compareVersions(a: List<Int>, b: List<Int>): Int {
        val maxLen = maxOf(a.size, b.size)
        for (i in 0 until maxLen) {
            val segA = a.getOrElse(i) { 0 }
            val segB = b.getOrElse(i) { 0 }
            if (segA != segB) return segA - segB
        }
        return 0
    }
}
