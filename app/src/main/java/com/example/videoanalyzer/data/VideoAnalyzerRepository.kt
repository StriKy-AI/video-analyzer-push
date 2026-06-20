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
 * The ViewModel is responsible for orchestration — querying file size for
 * the warning dialog, calling [analyzeVideo] after user confirmation, etc.
 * This class is concerned only with: read bytes, optionally downscale, POST.
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

    // ---------------------------------------------------------------------
    // Lightweight helpers used by the ViewModel for orchestration
    // ---------------------------------------------------------------------

    /**
     * Fast metadata-only size query — does NOT read the file. Returns null
     * if the provider doesn't expose a size (e.g. some cloud-backed URIs).
     */
    fun querySize(uri: Uri): Long? = VideoUtils.querySizeBytes(context, uri)

    // ---------------------------------------------------------------------
    // Main upload path
    // ---------------------------------------------------------------------

    /**
     * Send [videoUri] + [question] to the configured provider and return
     * the model's text answer.
     *
     * If [maxResolution] is anything other than [MaxResolution.OFF], the
     * video is re-encoded locally via Media3 Transformer to that height
     * before upload. The downscaled file lives in the app's cache dir and
     * is deleted in a finally block after upload.
     *
     * Throws [ApiException] on transport / API errors, [IOException] on
     * network failures.
     */
    suspend fun analyzeVideo(
        baseUrl: String,
        apiKey: String,
        model: String,
        videoUri: Uri,
        question: String,
        maxResolution: MaxResolution = MaxResolution.OFF,
    ): String = withContext(Dispatchers.IO) {
        val normalized = NetworkModule.normalizeBaseUrl(baseUrl)
        val url = "$normalized/v1/chat/completions"
        val auth = NetworkModule.bearerHeader(apiKey)

        // ---- prep: optional downscale ----
        var downscaledFile: File? = null
        val (bytes, mime) = try {
            if (maxResolution != MaxResolution.OFF) {
                Log.d(TAG, "[analyzeVideo] downscaling → ${maxResolution.label}")
                downscaledFile = VideoUtils.downscaleVideo(
                    context = context,
                    src = videoUri,
                    targetHeight = maxResolution.heightPx,
                )
                if (downscaledFile != null && downscaledFile.exists()) {
                    downscaledFile.readBytes() to "video/mp4"
                } else {
                    Log.w(TAG, "[analyzeVideo] downscale returned null — falling back to original")
                    VideoUtils.readBytesAndMime(context, videoUri)
                }
            } else {
                VideoUtils.readBytesAndMime(context, videoUri)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "[analyzeVideo] downscale failed: ${e.message} — falling back to original", e)
            downscaledFile?.delete()
            downscaledFile = null
            VideoUtils.readBytesAndMime(context, videoUri)
        }

        try {
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
        } finally {
            downscaledFile?.delete()
        }
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

    /**
     * Emits a single logcat block with everything needed to diagnose an API
     * request without dumping the multi-MB base64 payload.
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
        Log.d(TAG, "[$op] payload : ${VideoUtils.formatBytes(videoBytes.toLong())} ($mime)")
        Log.d(TAG, "[$op] gzip    : ${NetworkModule.gzipInterceptor.enabled}")

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