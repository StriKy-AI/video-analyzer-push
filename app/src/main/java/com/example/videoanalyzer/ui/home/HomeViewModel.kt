package com.example.videoanalyzer.ui.home

import android.net.Uri
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

class HomeViewModel(
    private val prefs: PreferencesRepository,
    private val repo: VideoAnalyzerRepository,
) : ViewModel() {

    data class UiState(
        val baseUrl: String = "",
        val apiKey: String = "",
        val models: List<String> = emptyList(),
        val selectedModel: String = "",
        val videoUri: Uri? = null,
        val videoDisplayName: String? = null,
        val question: String = "",
        val answer: String? = null,
        val isAnalyzing: Boolean = false,
        val error: String? = null,
        val useFrames: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                baseUrl = prefs.baseUrl.first(),
                apiKey = prefs.apiKey.first(),
                selectedModel = prefs.selectedModel.first(),
                models = prefs.cachedModels.first(),
            )
        }
    }

    fun onVideoPicked(uri: Uri?, displayName: String?) {
        _state.value = _state.value.copy(videoUri = uri, videoDisplayName = displayName, answer = null)
    }

    fun onQuestionChange(value: String) {
        _state.value = _state.value.copy(question = value)
    }

    fun onModelSelected(model: String) {
        _state.value = _state.value.copy(selectedModel = model)
        viewModelScope.launch { prefs.setSelectedModel(model) }
    }

    fun onUseFramesToggle(value: Boolean) {
        _state.value = _state.value.copy(useFrames = value)
    }

    fun refreshModels() {
        val url = _state.value.baseUrl
        val key = _state.value.apiKey
        if (url.isBlank() || key.isBlank()) return
        viewModelScope.launch {
            try {
                val models = repo.fetchModels(url, key)
                prefs.setCachedModels(models)
                _state.value = _state.value.copy(models = models)
            } catch (e: Throwable) {
                _state.value = _state.value.copy(error = "Refresh failed: ${e.message}")
            }
        }
    }

    fun analyze() {
        val s = _state.value
        val uri = s.videoUri
        if (uri == null) {
            _state.value = s.copy(error = "Pick a video first.")
            return
        }
        if (s.selectedModel.isBlank()) {
            _state.value = s.copy(error = "No model selected — go to Settings to pick one.")
            return
        }
        _state.value = s.copy(isAnalyzing = true, error = null, answer = null)
        viewModelScope.launch {
            try {
                val result = if (s.useFrames) {
                    repo.analyzeVideoByFrames(
                        baseUrl = s.baseUrl,
                        apiKey = s.apiKey,
                        model = s.selectedModel,
                        videoUri = uri,
                        question = s.question,
                    )
                } else {
                    repo.analyzeVideo(
                        baseUrl = s.baseUrl,
                        apiKey = s.apiKey,
                        model = s.selectedModel,
                        videoUri = uri,
                        question = s.question,
                    )
                }
                _state.value = _state.value.copy(isAnalyzing = false, answer = result)
            } catch (e: VideoAnalyzerRepository.ApiException) {
                _state.value = _state.value.copy(
                    isAnalyzing = false,
                    error = e.message ?: "Analysis failed.",
                )
            } catch (e: Throwable) {
                _state.value = _state.value.copy(
                    isAnalyzing = false,
                    error = e.message ?: e::class.simpleName ?: "Analysis failed.",
                )
            }
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    companion object {
        fun factory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = VideoAnalyzerApp.get()
                return HomeViewModel(app.preferences, app.repository) as T
            }
        }
    }
}