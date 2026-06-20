package com.example.videoanalyzer.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.videoanalyzer.network.ChatCompletionRequest
import com.example.videoanalyzer.network.ChatCompletionsService
import com.example.videoanalyzer.network.ChatCompletionResponse
import com.example.videoanalyzer.network.ChatMessage
import com.example.videoanalyzer.network.ContentPart
import com.example.videoanalyzer.network.ImageUrl
import com.example.videoanalyzer.network.NetworkModule
import com.example.videoanalyzer.network.OpenAiCompatibleService
import com.example.videoanalyzer.network.VideoUrl
import com.example.videoanalyzer.util.UploadProgressBus
import com.example.videoanalyzer.util.VideoUtils
import com.example.videoanalyzer.util.VideoUtils.MaxResolution
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Single entry point for talking to the configured provider.
 *
 * The chat flow uses two low-level primitives:
 *   - [readVideoBytes] — read the video, optionally downscale, return bytes
 *     (cleans up any temp downscaled file)
 *   - [sendChat] — POST a multi-turn conversation to /v1/chat/completions
 *
 * The ViewModel caches the video bytes after the first message so follow-up
 * questions don't re-upload. The full message history (with the video only on
 * the first user message) is sent on every request, letting the model maintain
 * conversational context.
 */
class VideoAnalyzerRepository(
    private val context: Context,
    private val prefs: PreferencesRepository,
) {

    private val openAi: OpenAiCompatibleService = NetworkModule.openAiService
    private val chat: ChatCompletionsService = NetworkModule.chatService

    companion object {
        private const val TAG = "VideoAnalyzer"
    }

    // ---------------------------------------------------------------------
    // Lightweight helpers
    // ---------------------------------------------------------------------

    /** Hit the provider's /v1/models endpoint and return the model ids. */
    suspend fun fetchModels(baseUrl: String, apiKey: String): List<String> =
        withContext(Dispatchers.IO) {
            val normalized = NetworkModule.normalizeBaseUrl(baseUrl)
            val url = "$normalized/v1/models"
            val auth = NetworkModule.bearerHeader(apiKey)
            Log.d(TAG, "[fetchModels] GET $url")
            val resp = openAi.listModels(url, auth)
            val ids = resp.allIds()
            Log.d(TAG, "[fetchModels] returned ${ids.size} model ids")
            ids
        }

    /** Fast metadata-only size query — does NOT read the file. */
    fun querySize(uri: Uri): Long? = VideoUtils.querySizeBytes(context, uri)

    // ---------------------------------------------------------------------
    // Video bytes preparation (used by chat for the first message)
    // ---------------------------------------------------------------------

    /**
     * Read the video at [uri] into memory, optionally re-encoding to
     * [maxResolution]. The downscaled file is written to the cache dir and
     * deleted before this function returns — the returned ByteArray is the
     * canonical artifact, callers should cache it (don't re-call this on
     * every chat message).
     */
    suspend fun readVideoBytes(
        uri: Uri,
        maxResolution: MaxResolution = MaxResolution.OFF,
    ): Pair<ByteArray, String> = withContext(Dispatchers.IO) {
        var downscaledFile: File? = null
        try {
            if (maxResolution != MaxResolution.OFF) {
                Log.d(TAG, "[readVideoBytes] downscaling → ${maxResolution.label}")
                downscaledFile = VideoUtils.downscaleVideo(
                    context = context,
                    src = uri,
                    targetHeight = maxResolution.heightPx,
                )
                if (downscaledFile != null && downscaledFile.exists()) {
                    return@withContext downscaledFile.readBytes() to "video/mp4"
                }
                Log.w(TAG, "[readVideoBytes] downscale returned null — using original")
            }
            VideoUtils.readBytesAndMime(context, uri)
        } catch (e: Throwable) {
            Log.e(TAG, "[readVideoBytes] failed: ${e.message}", e)
            downscaledFile?.delete()
            throw e
        } finally {
            // Bytes are now in memory; the temp file is no longer needed.
            downscaledFile?.delete()
        }
    }

    // ---------------------------------------------------------------------
    // Main chat path
    // ---------------------------------------------------------------------

    /**
     * POST a multi-turn conversation to /v1/chat/completions. The caller is
     * responsible for constructing the message list — in the chat flow,
     * the ViewModel embeds the video only on the first user message and
     * subsequent messages are pure text.
     */
    suspend fun sendChat(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
    ): String = withContext(Dispatchers.IO) {
        val normalized = NetworkModule.normalizeBaseUrl(baseUrl)
        val url = "$normalized/v1/chat/completions"
        val auth = NetworkModule.bearerHeader(apiKey)

        val request = ChatCompletionRequest(
            model = model,
            messages = messages,
            temperature = 0.4,
            stream = false,
        )

        // Count the video bytes being shipped in this request for logging.
        val totalPayloadBytes = messages.sumOf { msg ->
            msg.content.sumOf { part ->
                when {
                    part.videoUrl != null -> part.videoUrl.url.length
                    part.imageUrl != null -> part.imageUrl.url.length
                    else -> part.text?.length ?: 0
                }
            }
        }

        logRequest(
            op = "sendChat",
            method = "POST",
            url = url,
            request = request,
            videoBytes = totalPayloadBytes,
            mime = "application/json",
            model = model,
        )

        val response: ChatCompletionResponse = try {
            chat.chatCompletion(url, auth, body = request)
        } catch (e: retrofit2.HttpException) {
            val errorBody = e.response()?.errorBody()?.string().orEmpty()
            Log.e(TAG, "[sendChat] HTTP ${e.code()} ${e.message()} | body: $errorBody")
            throw ApiException(
                code = e.code(),
                message = "HTTP ${e.code()} ${e.message()}\n$errorBody",
            )
        } catch (e: IOException) {
            Log.e(TAG, "[sendChat] network error: ${e.message}", e)
            throw ApiException(code = -1, message = "Network error: ${e.message}", cause = e)
        }

        response.error?.let {
            Log.e(TAG, "[sendChat] API error: ${it.message}")
            throw ApiException(code = -2, message = it.message)
        }
        val text = response.extractText()
        Log.d(TAG, "[sendChat] ✓ received ${text.length} chars of response text")
        text
    }

    // ---------------------------------------------------------------------
    // Single-shot convenience methods (kept for back-compat / debug)
    // ---------------------------------------------------------------------

    /** One-shot: read video (with optional downscale) + send + return text. */
    suspend fun analyzeVideo(
        baseUrl: String,
        apiKey: String,
        model: String,
        videoUri: Uri,
        question: String,
        maxResolution: MaxResolution = MaxResolution.OFF,
    ): String {
        val (bytes, mime) = readVideoBytes(videoUri, maxResolution)
        val dataUrl = "data:$mime;base64,${android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)}"
        return sendChat(
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            messages = listOf(
                ChatMessage(
                    role = "user",
                    content = listOf(
                        ContentPart(type = "text", text = question),
                        ContentPart(type = "video_url", videoUrl = VideoUrl(dataUrl)),
                    ),
                ),
            ),
        )
    }

    /**
     * Frame-based fallback for providers that don't accept video_url — extracts
     * up to [maxFrames] evenly-spaced frames and sends them as image_url parts.
     */
    suspend fun analyzeVideoByFrames(
        baseUrl: String,
        apiKey: String,
        model: String,
        videoUri: Uri,
        question: String,
        maxFrames: Int = 8,
    ): String = withContext(Dispatchers.IO) {
        val normalized = NetworkModule.normalizeBaseUrl(baseUrl)
        val url = "$normalized/v1/chat/completions"
        val auth = NetworkModule.bearerHeader(apiKey)

        val frames = VideoUtils.extractFrames(context, videoUri, maxFrames)
        if (frames.isEmpty()) {
            Log.w(TAG, "[analyzeVideoByFrames] no frames extracted from $videoUri")
            throw ApiException(code = -3, message = "Could not extract any frames from this video.")
        }

        val parts = mutableListOf<ContentPart>()
        parts += ContentPart(
            type = "text",
            text = question.ifBlank { "Describe what's happening in this video." },
        )
        frames.forEach { (dataUrl, _) ->
            parts += ContentPart(type = "image_url", imageUrl = ImageUrl(dataUrl))
        }

        val request = ChatCompletionRequest(
            model = model,
            messages = listOf(ChatMessage("user", parts)),
        )

        logRequest(
            op = "analyzeVideoByFrames",
            method = "POST",
            url = url,
            request = request,
            videoBytes = frames.size * 80_000,
            mime = "image/jpeg",
            model = model,
        )

        val response = try {
            chat.chatCompletion(url, auth, body = request)
        } catch (e: retrofit2.HttpException) {
            val errorBody = e.response()?.errorBody()?.string().orEmpty()
            Log.e(TAG, "[analyzeVideoByFrames] HTTP ${e.code()} | body: $errorBody")
            throw ApiException(
                code = e.code(),
                message = "HTTP ${e.code()} ${e.message()}\n$errorBody",
            )
        } catch (e: IOException) {
            Log.e(TAG, "[analyzeVideoByFrames] network error: ${e.message}", e)
            throw ApiException(code = -1, message = "Network error: ${e.message}", cause = e)
        }
        response.error?.let {
            Log.e(TAG, "[analyzeVideoByFrames] API error: ${it.message}")
            throw ApiException(code = -2, message = it.message)
        }
        response.extractText()
    }

    // ---------------------------------------------------------------------
    // Logging
    // ---------------------------------------------------------------------

    private fun logRequest(
        op: String,
        method: String,
        url: String,
        request: ChatCompletionRequest,
        videoBytes: Int,
        mime: String,
        model: String,
    ) {
        Log.d(TAG, "[$op] ─────────── REQUEST ───────────")
        Log.d(TAG, "[$op] method  : $method")
        Log.d(TAG, "[$op] url     : $url")
        Log.d(TAG, "[$op] model   : $model")
        Log.d(TAG, "[$op] payload : ${VideoUtils.formatBytes(videoBytes.toLong())} ($mime)")
        Log.d(TAG, "[$op] gzip    : ${NetworkModule.gzipInterceptor.enabled}")
        Log.d(TAG, "[$op] msgs    : ${request.messages.size}")

        // Redact base64 in the JSON preview — the structure matters for debugging,
        // not the actual byte content.
        val redacted = request.copy(
            messages = request.messages.map { msg ->
                msg.copy(
                    content = msg.content.map { part ->
                        when (part.type) {
                            "video_url" -> part.copy(
                                videoUrl = VideoUrl("data:$mime;base64,[${videoBytes}B redacted]"),
                            )
                            "image_url" -> part.copy(
                                imageUrl = ImageUrl("data:image/jpeg;base64,[frame redacted]"),
                            )
                            else -> part
                        }
                    },
                )
            },
        )
        val adapter = NetworkModule.moshi.adapter(ChatCompletionRequest::class.java).lenient()
        Log.d(TAG, "[$op] body    : ${adapter.toJson(redacted)}")
        Log.d(TAG, "[$op] ──────────────────────────────")
    }

    class ApiException(
        val code: Int,
        message: String,
        cause: Throwable? = null,
    ) : RuntimeException(message, cause)
}