package com.feiying.basketball.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.feiying.basketball.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "basketball_settings",
)

/** Developer-only overrides. The server URL is not exposed to regular users. */
class SettingsStore(private val context: Context) {
    val baseUrl: Flow<String> = context.settingsDataStore.data.map { preferences ->
        preferences[BASE_URL]?.takeIf { it.isNotBlank() } ?: BuildConfig.SERVER_BASE_URL
    }

    suspend fun setBaseUrl(url: String) {
        context.settingsDataStore.edit { preferences ->
            val trimmed = url.trim().trimEnd('/')
            if (trimmed.isBlank() || trimmed == BuildConfig.SERVER_BASE_URL) {
                preferences.remove(BASE_URL)
            } else {
                preferences[BASE_URL] = trimmed
            }
        }
    }

    companion object {
        private val BASE_URL = stringPreferencesKey("server_base_url")
    }
}
