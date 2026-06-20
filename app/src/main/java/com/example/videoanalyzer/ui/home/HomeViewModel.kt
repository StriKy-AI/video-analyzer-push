package com.example.videoanalyzer.ui.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.videoanalyzer.VideoAnalyzerApp
import com.example.videoanalyzer.data.PreferencesRepository
import com.example.videoanalyzer.data.VideoAnalyzerRepository
import com.example.videoanalyzer.util.UploadProgressBus
import com.example.videoanalyzer.util.VideoUtils.MaxResolution
import com.example.videoanalyzer.util.VideoUtils.WarnThreshold
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
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
        val videoSizeBytes: Long? = null,
        val question: String = "",
        val answer: String? = null,
        val isAnalyzing: Boolean = false,
        val useFrames: Boolean = false,
        val maxResolution: MaxResolution = MaxResolution.RES_720,
        val warnThreshold: WarnThreshold = WarnThreshold.OVER_20MB,
        val error: String? = null,
        /** Non-null when we're waiting for the user to confirm a large upload. */
        val pendingSizeBytes: Long? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Live upload progress driven by [UploadProgressBus] — read by the UI. */
    val uploadProgress: StateFlow<UploadProgressBus.Snapshot> =
        UploadProgressBus.state.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = UploadProgressBus.Snapshot(),
        )

    init {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                baseUrl = prefs.baseUrl.first(),
                apiKey = prefs.apiKey.first(),
                selectedModel = prefs.selectedModel.first(),
                models = prefs.cachedModels.first(),
                maxResolution = prefs.maxResolution.first(),
                warnThreshold = prefs.warnThreshold.first(),
            )
        }
    }

    fun onVideoPicked(uri: Uri?, displayName: String?) {
        _state.value = _state.value.copy(
            videoUri = uri,
            videoDisplayName = displayName,
            videoSizeBytes = uri?.let { repo.querySize(it) },
            answer = null,
            error = null,
            pendingSizeBytes = null,
        )
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

    fun onMaxResolutionChange(value: MaxResolution) {
        _state.value = _state.value.copy(maxResolution = value)
        viewModelScope.launch { prefs.setMaxResolution(value) }
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

    /**
     * Entry point when the user taps Analyze. If the file size is known and
     * exceeds the user's warning threshold, set [UiState.pendingSizeBytes] so
     * the UI shows the confirmation dialog. Otherwise go straight to upload.
     */
    fun onAnalyzeClicked() {
        val s = _state.value
        val uri = s.videoUri ?: run {
            _state.value = s.copy(error = "Pick a video first.")
            return
        }
        if (s.selectedModel.isBlank()) {
            _state.value = s.copy(error = "No model selected — go to Settings to pick one.")
            return
        }
        // If we don't know the size, fall back to reading it. If we still
        // don't know, just send — better to upload than to refuse.
        val size = s.videoSizeBytes ?: repo.querySize(uri)
        _state.value = _state.value.copy(videoSizeBytes = size)

        val threshold = s.warnThreshold
        if (size != null && threshold.minBytes != null && size >= threshold.minBytes) {
            _state.value = s.copy(pendingSizeBytes = size)
        } else {
            startUpload()
        }
    }

    /** Called when the user taps "Send anyway" in the confirmation dialog. */
    fun confirmUpload() {
        _state.value = _state.value.copy(pendingSizeBytes = null)
        startUpload()
    }

    /** Called when the user taps "Cancel" in the confirmation dialog. */
    fun cancelUpload() {
        _state.value = _state.value.copy(pendingSizeBytes = null)
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    private fun startUpload() {
        val s = _state.value
        val uri = s.videoUri ?: return
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
                        maxResolution = s.maxResolution,
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