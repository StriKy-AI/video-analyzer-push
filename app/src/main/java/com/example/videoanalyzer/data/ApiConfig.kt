package com.example.videoanalyzer.data

/**
 * Lightweight in-memory snapshot of the user's API configuration.
 * Built fresh on demand from [PreferencesRepository] for each network call,
 * so the user can change base URL / key without restarting the app.
 */
data class ApiConfig(
    val baseUrl: String,
    val apiKey: String,
    val selectedModel: String,
) {
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && selectedModel.isNotBlank()
}