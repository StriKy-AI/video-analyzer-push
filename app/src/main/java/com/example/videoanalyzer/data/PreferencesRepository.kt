package com.example.videoanalyzer.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.videoanalyzer.util.VideoUtils.MaxResolution
import com.example.videoanalyzer.util.VideoUtils.WarnThreshold
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "video_analyzer_prefs")

/**
 * Holds persistent app settings:
 *   - API base URL
 *   - API key
 *   - Last-selected model id
 *   - Cached list of model ids (comma-separated) so the picker survives restarts
 *   - Max upload resolution (Off / 480p / 720p / 1080p)
 *   - Size-warning threshold (Never / >1 MB / >20 MB / >100 MB)
 *   - Gzip compression toggle
 *
 * Backed by Jetpack DataStore.
 */
class PreferencesRepository(private val context: Context) {

    private object Keys {
        val BASE_URL = stringPreferencesKey("base_url")
        val API_KEY = stringPreferencesKey("api_key")
        val SELECTED_MODEL = stringPreferencesKey("selected_model")
        val CACHED_MODELS = stringPreferencesKey("cached_models")
        val MAX_RESOLUTION = stringPreferencesKey("max_resolution")
        val WARN_THRESHOLD = stringPreferencesKey("warn_threshold")
        val GZIP_ENABLED = stringPreferencesKey("gzip_enabled")
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

    /** Stored as the enum's [MaxResolution.label] string. */
    val maxResolution: Flow<MaxResolution> = context.dataStore.data.map { prefs ->
        MaxResolution.fromLabel(prefs[Keys.MAX_RESOLUTION].orEmpty())
    }

    val warnThreshold: Flow<WarnThreshold> = context.dataStore.data.map { prefs ->
        WarnThreshold.fromLabel(prefs[Keys.WARN_THRESHOLD].orEmpty())
    }

    val gzipEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.GZIP_ENABLED]?.toBooleanStrictOrNull() ?: false
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

    suspend fun setMaxResolution(value: MaxResolution) {
        context.dataStore.edit { it[Keys.MAX_RESOLUTION] = value.label }
    }

    suspend fun setWarnThreshold(value: WarnThreshold) {
        context.dataStore.edit { it[Keys.WARN_THRESHOLD] = value.label }
    }

    suspend fun setGzipEnabled(value: Boolean) {
        context.dataStore.edit { it[Keys.GZIP_ENABLED] = value.toString() }
    }

    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }
}