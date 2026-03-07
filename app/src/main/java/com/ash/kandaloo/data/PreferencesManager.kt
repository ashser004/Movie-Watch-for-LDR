package com.ash.kandaloo.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "kandaloo_settings")

class PreferencesManager(private val context: Context) {

    companion object {
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val AUTO_PLAY = booleanPreferencesKey("auto_play")
    }

    val isDarkTheme: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[DARK_THEME] ?: true // Default to dark theme for movie night vibe
    }

    val isAutoPlay: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[AUTO_PLAY] ?: true
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
}
