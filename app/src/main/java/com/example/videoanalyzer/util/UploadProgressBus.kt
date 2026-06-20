package com.example.videoanalyzer.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide progress reporter for the active outbound video upload.
 *
 * The OkHttp [UploadProgressInterceptor] publishes byte counts here as the
 * request body streams to the network. The HomeViewModel collects the
 * StateFlow and the UI binds it to a LinearProgressIndicator.
 *
 * Only one upload can be in flight at a time in this app, so a single
 * shared state holder is sufficient — no need for per-request tokens.
 */
object UploadProgressBus {

    data class Snapshot(
        val sentBytes: Long = 0L,
        val totalBytes: Long = 0L,
        val isActive: Boolean = false,
    ) {
        /** 0f..1f — never NaN, even when total is unknown. */
        val fraction: Float
            get() = when {
                !isActive -> 0f
                totalBytes <= 0L -> 0f
                else -> (sentBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
            }
    }

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    /** Called by the interceptor when a new upload begins. */
    fun start(totalBytes: Long) {
        _state.value = Snapshot(sentBytes = 0L, totalBytes = totalBytes, isActive = true)
    }

    /** Called periodically while the request body is being written. */
    fun update(sentBytes: Long, totalBytes: Long) {
        _state.value = _state.value.copy(sentBytes = sentBytes, totalBytes = totalBytes)
    }

    /** Called when the upload completes (success or failure). */
    fun finish() {
        val current = _state.value
        _state.value = current.copy(
            sentBytes = current.totalBytes.coerceAtLeast(current.sentBytes),
            isActive = false,
        )
    }

    /** Called when an upload is cancelled before any data was sent. */
    fun reset() {
        _state.value = Snapshot()
    }
}