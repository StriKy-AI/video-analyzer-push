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
import com.example.videoanalyzer.util.VideoUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Single entry point for talking to the configured provider.
 *
 * Strategy for "send a video to a chat model":
 *  1. Read the entire video as bytes from the user-picked Uri.
 *  2. Base64-encode and embed as a `data:video/...;base64,...` URL inside a
 *     `video_url` content part. Moonshot / Kimi accept this shape directly.
 *  3. POST to /v1/chat/completions using the standard OpenAI request envelope.
 *
 * If the chosen provider does not understand `video_url`, the user can switch
 * to a vision-capable model OR pick a smaller clip — see README "Troubleshooting".
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

    /**
     * Send [videoUri] + [question] to the configured provider and return the
     * model's text answer. Throws [ApiException] on transport / API errors.
     */
    suspend fun analyzeVideo(
        baseUrl: String,
        apiKey: String,
        model: String,
        videoUri: Uri,
        question: String,
    ): String = withContext(Dispatchers.IO) {
        val normalized = NetworkModule.normalizeBaseUrl(baseUrl)
        val url = "$normalized/v1/chat/completions"
        val auth = NetworkModule.bearerHeader(apiKey)

        val (bytes, mime) = VideoUtils.readBytesAndMime(context, videoUri)
        val dataUrl = "data:$mime;base64,${android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)}"

        val request = ChatCompletionRequest(
            model = model,
            messages = listOf(
                ChatMessage(
                    role = "user",
                    content = listOf(
                        ContentPart(
                            type = "text",
                            text = question.ifBlank { "Describe what's happening in this video." },
                        ),
                        ContentPart(type = "video_url", videoUrl = VideoUrl(dataUrl)),
                    ),
                ),
            ),
        )

        logRequest(
            op = "analyzeVideo",
            method = "POST",
            url = url,
            request = request,
            videoBytes = bytes.size,
            mime = mime,
            model = model,
        )

        val response: ChatCompletionResponse = try {
            chat.chatCompletion(url, auth, body = request)
        } catch (e: retrofit2.HttpException) {
            val errorBody = e.response()?.errorBody()?.string().orEmpty()
            Log.e(TAG, "[analyzeVideo] HTTP ${e.code()} ${e.message()} | body: $errorBody")
            throw ApiException(
                code = e.code(),
                message = "HTTP ${e.code()} ${e.message()}\n$errorBody",
            )
        } catch (e: IOException) {
            Log.e(TAG, "[analyzeVideo] network error: ${e.message}", e)
            throw ApiException(code = -1, message = "Network error: ${e.message}", cause = e)
        }

        response.error?.let {
            Log.e(TAG, "[analyzeVideo] API error: ${it.message}")
            throw ApiException(code = -2, message = it.message)
        }
        val text = response.extractText()
        Log.d(TAG, "[analyzeVideo] ✓ received ${text.length} chars of response text")
        text
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
            videoBytes = frames.size * 80_000, // rough estimate, not exact
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

    /**
     * Emits a single logcat block with everything needed to diagnose an API
     * request without dumping the multi-MB base64 payload. The body preview
     * keeps the JSON structure but replaces the encoded video / frame blobs
     * with `[<size> B redacted]` markers.
     */
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
        Log.d(TAG, "[$op] payload : $videoBytes bytes ($mime)")

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