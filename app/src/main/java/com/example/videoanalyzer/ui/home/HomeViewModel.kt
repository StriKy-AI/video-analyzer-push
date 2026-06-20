package com.example.videoanalyzer.ui.home

import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.videoanalyzer.VideoAnalyzerApp
import com.example.videoanalyzer.data.PreferencesRepository
import com.example.videoanalyzer.data.VideoAnalyzerRepository
import com.example.videoanalyzer.network.ChatMessage
import com.example.videoanalyzer.network.ContentPart
import com.example.videoanalyzer.network.VideoUrl
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
        val maxResolution: MaxResolution = MaxResolution.RES_720,
        val warnThreshold: WarnThreshold = WarnThreshold.OVER_20MB,
        val chatHistory: List<ChatItem> = emptyList(),
        val pendingMessage: String = "",
        val isSending: Boolean = false,
        val error: String? = null,
        /** Non-null when waiting for user to confirm a large upload. */
        val pendingSizeBytes: Long? = null,
        /** Non-null when user typed a message but hasn't pressed send yet. */
        val pendingFirstSendText: String? = null,
    )

    companion object {
        private const val TAG = "VideoAnalyzer"

        fun factory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = VideoAnalyzerApp.get()
                return HomeViewModel(app.preferences, app.repository) as T
            }
        }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Live upload progress driven by [UploadProgressBus]. */
    val uploadProgress: StateFlow<UploadProgressBus.Snapshot> =
        UploadProgressBus.state.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = UploadProgressBus.Snapshot(),
        )

    /**
     * In-memory cache of the prepared video bytes for the current chat session.
     * Cleared when the user picks a different video. Never persisted to disk.
     */
    private var cachedVideoBytes: ByteArray? = null
    private var cachedVideoMime: String? = null

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

    // ---------------------------------------------------------------------
    // Video lifecycle
    // ---------------------------------------------------------------------

    fun onVideoPicked(uri: Uri?, displayName: String?) {
        // Picking a new video invalidates the cache and clears the conversation.
        cachedVideoBytes = null
        cachedVideoMime = null
        _state.value = _state.value.copy(
            videoUri = uri,
            videoDisplayName = displayName,
            videoSizeBytes = uri?.let { repo.querySize(it) },
            chatHistory = emptyList(),
            pendingMessage = "",
            error = null,
            pendingSizeBytes = null,
            pendingFirstSendText = null,
        )
    }

    fun onMaxResolutionChange(value: MaxResolution) {
        // Resolution changed — invalidate the cached bytes; next send will re-prepare.
        cachedVideoBytes = null
        cachedVideoMime = null
        _state.value = _state.value.copy(maxResolution = value)
        viewModelScope.launch { prefs.setMaxResolution(value) }
    }

    // ---------------------------------------------------------------------
    // Model / settings
    // ---------------------------------------------------------------------

    fun onModelSelected(model: String) {
        _state.value = _state.value.copy(selectedModel = model)
        viewModelScope.launch { prefs.setSelectedModel(model) }
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

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    fun clearChat() {
        _state.value = _state.value.copy(chatHistory = emptyList())
    }

    // ---------------------------------------------------------------------
    // Chat send flow
    // ---------------------------------------------------------------------

    fun onPendingMessageChange(text: String) {
        _state.value = _state.value.copy(pendingMessage = text)
    }

    /**
     * Entry point for the chat input. If this is the first message in the
     * conversation, the video will be uploaded — which means we may need to
     * trigger the size confirmation dialog. Subsequent messages skip that
     * step and just text-chat.
     */
    fun onSendClicked() {
        val s = _state.value
        val text = s.pendingMessage.trim()
        if (text.isEmpty()) return
        val uri = s.videoUri ?: run {
            _state.value = s.copy(error = "Pick a video first.")
            return
        }
        if (s.selectedModel.isBlank()) {
            _state.value = s.copy(error = "No model selected — open Settings.")
            return
        }

        val isFirstMessage = s.chatHistory.isEmpty()
        if (isFirstMessage) {
            val size = s.videoSizeBytes ?: repo.querySize(uri)
            _state.value = _state.value.copy(videoSizeBytes = size)
            val threshold = s.warnThreshold
            if (size != null && threshold.minBytes != null && size >= threshold.minBytes) {
                // Park the message text and trigger the confirmation dialog.
                _state.value = s.copy(pendingFirstSendText = text)
                return
            }
        }

        executeSend(text)
    }

    /** Called when the user taps "Send anyway" in the size confirmation dialog. */
    fun confirmSend() {
        val text = _state.value.pendingFirstSendText
        _state.value = _state.value.copy(pendingFirstSendText = null)
        if (!text.isNullOrBlank()) executeSend(text)
    }

    /** Called when the user taps "Cancel" in the size confirmation dialog. */
    fun cancelSend() {
        _state.value = _state.value.copy(pendingFirstSendText = null)
    }

    /**
     * Build the API message list and POST it. The video is attached only to
     * the very first user message; everything else rides on conversational
     * context.
     */
    private fun executeSend(text: String) {
        val s = _state.value
        val uri = s.videoUri ?: return
        val userItem = ChatItem(role = ChatRole.USER, text = text)
        val newHistory = s.chatHistory + userItem
        _state.value = s.copy(
            chatHistory = newHistory,
            pendingMessage = "",
            isSending = true,
            error = null,
        )

        viewModelScope.launch {
            try {
                val messages = buildApiMessages(uri, newHistory)
                val response = repo.sendChat(
                    baseUrl = s.baseUrl,
                    apiKey = s.apiKey,
                    model = s.selectedModel,
                    messages = messages,
                )
                val assistantItem = ChatItem(role = ChatRole.ASSISTANT, text = response)
                _state.value = _state.value.copy(
                    chatHistory = newHistory + assistantItem,
                    isSending = false,
                )
            } catch (e: VideoAnalyzerRepository.ApiException) {
                Log.e(TAG, "[chat] API error: ${e.message}")
                _state.value = _state.value.copy(
                    isSending = false,
                    error = e.message ?: "API error.",
                )
            } catch (e: Throwable) {
                Log.e(TAG, "[chat] error: ${e.message}", e)
                _state.value = _state.value.copy(
                    isSending = false,
                    error = e.message ?: e::class.simpleName ?: "Send failed.",
                )
            }
        }
    }

    /**
     * Translate the chat history into the wire-format list of [ChatMessage]s.
     * The first user message gets the video attached as a `video_url` part;
     * everything else is plain text.
     */
    private suspend fun buildApiMessages(
        uri: Uri,
        history: List<ChatItem>,
    ): List<ChatMessage> {
        val isFirstUserMessage = history.indexOfFirst { it.role == ChatRole.USER }
        return history.mapIndexed { idx, item ->
            when (item.role) {
                ChatRole.ASSISTANT -> ChatMessage(
                    role = "assistant",
                    content = listOf(ContentPart(type = "text", text = item.text)),
                )
                ChatRole.USER -> {
                    if (idx == isFirstUserMessage) {
                        val (bytes, mime) = prepareVideoBytes(uri)
                        val dataUrl = "data:$mime;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
                        ChatMessage(
                            role = "user",
                            content = listOf(
                                ContentPart(type = "text", text = item.text),
                                ContentPart(type = "video_url", videoUrl = VideoUrl(dataUrl)),
                            ),
                        )
                    } else {
                        ChatMessage(
                            role = "user",
                            content = listOf(ContentPart(type = "text", text = item.text)),
                        )
                    }
                }
            }
        }
    }

    /**
     * Read + downscale the video once per session and cache the bytes. Skips
     * the heavy work for follow-up messages.
     */
    private suspend fun prepareVideoBytes(uri: Uri): Pair<ByteArray, String> {
        cachedVideoBytes?.let { bytes ->
            cachedVideoMime?.let { mime -> return bytes to mime }
        }
        val maxRes = _state.value.maxResolution
        Log.d(TAG, "[chat] preparing video bytes (maxRes=${maxRes.label})")
        val (bytes, mime) = repo.readVideoBytes(uri, maxRes)
        cachedVideoBytes = bytes
        cachedVideoMime = mime
        return bytes to mime
    }
}
