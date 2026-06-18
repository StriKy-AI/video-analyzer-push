package com.example.videoanalyzer.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.videoanalyzer.VideoAnalyzerApp
import com.example.videoanalyzer.data.PreferencesRepository
import com.example.videoanalyzer.data.VideoAnalyzerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SetupViewModel(
    private val prefs: PreferencesRepository,
    private val repo: VideoAnalyzerRepository,
) : ViewModel() {

    data class UiState(
        val baseUrl: String = "",
        val apiKey: String = "",
        val isFetching: Boolean = false,
        val models: List<String> = emptyList(),
        val selectedModel: String = "",
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val baseUrl = prefs.baseUrl.first()
            val apiKey = prefs.apiKey.first()
            val cached = prefs.cachedModels.first()
            val selected = prefs.selectedModel.first()
            _state.value = _state.value.copy(
                baseUrl = baseUrl,
                apiKey = apiKey,
                models = cached,
                selectedModel = selected,
            )
        }
    }

    fun onBaseUrlChange(value: String) {
        _state.value = _state.value.copy(baseUrl = value, error = null)
    }

    fun onApiKeyChange(value: String) {
        _state.value = _state.value.copy(apiKey = value, error = null)
    }

    fun onModelSelected(model: String) {
        _state.value = _state.value.copy(selectedModel = model)
    }

    fun fetchModels() {
        val url = _state.value.baseUrl.trim()
        val key = _state.value.apiKey.trim()
        if (url.isBlank() || key.isBlank()) {
            _state.value = _state.value.copy(error = "Please enter both base URL and API key first.")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(isFetching = true, error = null, models = emptyList())
            try {
                val models = repo.fetchModels(url, key)
                if (models.isEmpty()) {
                    _state.value = _state.value.copy(
                        isFetching = false,
                        error = "Connected, but the provider returned zero models. Check that your key has access.",
                    )
                } else {
                    prefs.setCachedModels(models)
                    val preselect = models.first()
                    _state.value = _state.value.copy(
                        isFetching = false,
                        models = models,
                        selectedModel = if (_state.value.selectedModel in models) _state.value.selectedModel else preselect,
                    )
                }
            } catch (e: Throwable) {
                _state.value = _state.value.copy(
                    isFetching = false,
                    error = "Could not fetch models: ${e.message ?: e::class.simpleName}",
                )
            }
        }
    }

    fun save(onSaved: () -> Unit) {
        val s = _state.value
        if (s.baseUrl.isBlank() || s.apiKey.isBlank() || s.selectedModel.isBlank()) {
            _state.value = s.copy(error = "Base URL, API key, and a selected model are all required.")
            return
        }
        viewModelScope.launch {
            prefs.setBaseUrl(s.baseUrl)
            prefs.setApiKey(s.apiKey)
            prefs.setSelectedModel(s.selectedModel)
            onSaved()
        }
    }

    companion object {
        fun factory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = VideoAnalyzerApp.get()
                return SetupViewModel(app.preferences, app.repository) as T
            }
        }
    }
}