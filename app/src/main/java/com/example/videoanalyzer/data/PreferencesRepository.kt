package com.example.videoanalyzer.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "video_analyzer_prefs")

/**
 * Holds persistent app settings:
 *   - API base URL
 *   - API key
 *   - Last-selected model id
 *   - Cached list of model ids (comma-separated) so the picker survives restarts
 *
 * Backed by Jetpack DataStore. Everything except the cached model list is also
 * kept in EncryptedSharedPreferences-style memory while the app is running.
 */
class PreferencesRepository(private val context: Context) {

    private object Keys {
        val BASE_URL = stringPreferencesKey("base_url")
        val API_KEY = stringPreferencesKey("api_key")
        val SELECTED_MODEL = stringPreferencesKey("selected_model")
        val CACHED_MODELS = stringPreferencesKey("cached_models")
    }

    val baseUrl: Flow<String> = context.dataStore.data.map { it[Keys.BASE_URL].orEmpty() }
    val apiKey: Flow<String> = context.dataStore.data.map { it[Keys.API_KEY].orEmpty() }
    val selectedModel: Flow<String> = context.dataStore.data.map { it[Keys.SELECTED_MODEL].orEmpty() }

    val cachedModels: Flow<List<String>> = context.dataStore.data.map { prefs ->
        prefs[Keys.CACHED_MODELS]
            ?.split("|")
            ?.filter { it.isNotBlank() }
            .orEmpty()
    }

    suspend fun setBaseUrl(value: String) {
        context.dataStore.edit { it[Keys.BASE_URL] = value.trim() }
    }

    suspend fun setApiKey(value: String) {
        context.dataStore.edit { it[Keys.API_KEY] = value.trim() }
    }

    suspend fun setSelectedModel(value: String) {
        context.dataStore.edit { it[Keys.SELECTED_MODEL] = value }
    }

    suspend fun setCachedModels(models: List<String>) {
        context.dataStore.edit { it[Keys.CACHED_MODELS] = models.joinToString("|") }
    }

    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }

    /** Snapshot of current values, useful when we need a one-shot read for a request. */
    data class Snapshot(
        val baseUrl: String,
        val apiKey: String,
        val selectedModel: String,
    )
}