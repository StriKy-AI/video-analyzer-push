package com.example.videoanalyzer.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.videoanalyzer.VideoAnalyzerApp
import com.example.videoanalyzer.data.PreferencesRepository
import com.example.videoanalyzer.data.VideoAnalyzerRepository
import com.example.videoanalyzer.network.NetworkModule
import com.example.videoanalyzer.util.VideoUtils.MaxResolution
import com.example.videoanalyzer.util.VideoUtils.WarnThreshold
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val prefs: PreferencesRepository,
    private val repo: VideoAnalyzerRepository,
) : ViewModel() {

    data class UiState(
        val baseUrl: String = "",
        val apiKey: String = "",
        val selectedModel: String = "",
        val models: List<String> = emptyList(),
        val isRefreshing: Boolean = false,
        val error: String? = null,
        val notice: String? = null,
        // New settings
        val maxResolution: MaxResolution = MaxResolution.RES_720,
        val warnThreshold: WarnThreshold = WarnThreshold.OVER_20MB,
        val gzipEnabled: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val maxRes = prefs.maxResolution.first()
            val warnT = prefs.warnThreshold.first()
            val gzip = prefs.gzipEnabled.first()
            // Apply gzip to the OkHttp interceptor immediately
            NetworkModule.gzipInterceptor.enabled = gzip
            _state.value = _state.value.copy(
                baseUrl = prefs.baseUrl.first(),
                apiKey = prefs.apiKey.first(),
                selectedModel = prefs.selectedModel.first(),
                models = prefs.cachedModels.first(),
                maxResolution = maxRes,
                warnThreshold = warnT,
                gzipEnabled = gzip,
            )
        }
    }

    fun onBaseUrlChange(value: String) {
        _state.value = _state.value.copy(baseUrl = value, error = null, notice = null)
    }

    fun onApiKeyChange(value: String) {
        _state.value = _state.value.copy(apiKey = value, error = null, notice = null)
    }

    fun onModelSelected(value: String) {
        _state.value = _state.value.copy(selectedModel = value)
    }

    fun onMaxResolutionChange(value: MaxResolution) {
        _state.value = _state.value.copy(maxResolution = value)
        viewModelScope.launch { prefs.setMaxResolution(value) }
    }

    fun onWarnThresholdChange(value: WarnThreshold) {
        _state.value = _state.value.copy(warnThreshold = value)
        viewModelScope.launch { prefs.setWarnThreshold(value) }
    }

    fun onGzipEnabledChange(value: Boolean) {
        _state.value = _state.value.copy(gzipEnabled = value, notice = if (value) {
            "Gzip on. Note: not all providers accept Content-Encoding: gzip — if requests fail with 400, turn this off."
        } else null)
        NetworkModule.gzipInterceptor.enabled = value
        viewModelScope.launch { prefs.setGzipEnabled(value) }
    }

    fun refreshModels() {
        val url = _state.value.baseUrl.trim()
        val key = _state.value.apiKey.trim()
        if (url.isBlank() || key.isBlank()) {
            _state.value = _state.value.copy(error = "Need both base URL and API key.")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(isRefreshing = true, error = null, notice = null)
            try {
                val models = repo.fetchModels(url, key)
                prefs.setCachedModels(models)
                val sel = if (_state.value.selectedModel in models) _state.value.selectedModel else models.firstOrNull().orEmpty()
                _state.value = _state.value.copy(
                    isRefreshing = false,
                    models = models,
                    selectedModel = sel,
                    notice = "Fetched ${models.size} models.",
                )
            } catch (e: Throwable) {
                _state.value = _state.value.copy(
                    isRefreshing = false,
                    error = "Failed: ${e.message ?: e::class.simpleName}",
                )
            }
        }
    }

    fun save() {
        val s = _state.value
        if (s.baseUrl.isBlank() || s.apiKey.isBlank() || s.selectedModel.isBlank()) {
            _state.value = s.copy(error = "All three fields are required.")
            return
        }
        viewModelScope.launch {
            prefs.setBaseUrl(s.baseUrl)
            prefs.setApiKey(s.apiKey)
            prefs.setSelectedModel(s.selectedModel)
            _state.value = _state.value.copy(notice = "Saved.")
        }
    }

    fun wipeAll(onDone: () -> Unit) {
        viewModelScope.launch {
            prefs.clearAll()
            NetworkModule.gzipInterceptor.enabled = false
            onDone()
        }
    }

    companion object {
        fun factory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = VideoAnalyzerApp.get()
                return SettingsViewModel(app.preferences, app.repository) as T
            }
        }
    }
}