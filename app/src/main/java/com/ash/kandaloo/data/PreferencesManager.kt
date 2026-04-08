package com.ash.kandaloo.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "kandaloo_settings")

class PreferencesManager(private val context: Context) {

    companion object {
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val AUTO_PLAY = booleanPreferencesKey("auto_play")
        val PENDING_DOWNLOAD_ID = longPreferencesKey("pending_download_id")
        val PENDING_UPDATE_TAG = stringPreferencesKey("pending_update_tag")
    }

    val isDarkTheme: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[DARK_THEME] ?: true // Default to dark theme for movie night vibe
    }

    val isAutoPlay: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[AUTO_PLAY] ?: false
    }

    suspend fun setDarkTheme(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[DARK_THEME] = enabled
        }
    }

    suspend fun setAutoPlay(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[AUTO_PLAY] = enabled
        }
    }

    // ─── Update download tracking ───

    suspend fun setPendingDownload(downloadId: Long, tag: String) {
        context.dataStore.edit { prefs ->
            prefs[PENDING_DOWNLOAD_ID] = downloadId
            prefs[PENDING_UPDATE_TAG] = tag
        }
    }

    suspend fun clearPendingDownload() {
        context.dataStore.edit { prefs ->
            prefs.remove(PENDING_DOWNLOAD_ID)
            prefs.remove(PENDING_UPDATE_TAG)
        }
    }

    suspend fun getPendingDownloadId(): Long {
        return context.dataStore.data.first()[PENDING_DOWNLOAD_ID] ?: -1L
    }

    suspend fun getPendingUpdateTag(): String {
        return context.dataStore.data.first()[PENDING_UPDATE_TAG] ?: ""
    }
}
