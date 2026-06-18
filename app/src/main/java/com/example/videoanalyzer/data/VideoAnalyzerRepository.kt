package com.example.videoanalyzer.data

import android.content.Context
import android.net.Uri
import com.example.videoanalyzer.network.ChatCompletionRequest
import com.example.videoanalyzer.network.ChatCompletionsService
import com.example.videoanalyzer.network.ChatCompletionResponse
import com.example.videoanalyzer.network.ChatMessage
import com.example.videoanalyzer.network.ImageUrl
import com.example.videoanalyzer.network.ImageUrlPart
import com.example.videoanalyzer.network.NetworkModule
import com.example.videoanalyzer.network.OpenAiCompatibleService
import com.example.videoanalyzer.network.TextPart
import com.example.videoanalyzer.network.VideoUrl
import com.example.videoanalyzer.network.VideoUrlPart
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

    /** Hit the provider's /v1/models endpoint and return the model ids. */
    suspend fun fetchModels(baseUrl: String, apiKey: String): List<String> =
        withContext(Dispatchers.IO) {
            val normalized = NetworkModule.normalizeBaseUrl(baseUrl)
            val url = "$normalized/v1/models"
            val auth = NetworkModule.bearerHeader(apiKey)
            val resp = openAi.listModels(url, auth)
            resp.allIds()
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
                        TextPart(question.ifBlank { "Describe what's happening in this video." }),
                        VideoUrlPart(VideoUrl(dataUrl)),
                    ),
                ),
            ),
        )

        val response: ChatCompletionResponse = try {
            chat.chatCompletion(url, auth, body = request)
        } catch (e: retrofit2.HttpException) {
            val errorBody = e.response()?.errorBody()?.string().orEmpty()
            throw ApiException(
                code = e.code(),
                message = "HTTP ${e.code()} ${e.message()}\n$errorBody",
            )
        } catch (e: IOException) {
            throw ApiException(code = -1, message = "Network error: ${e.message}", cause = e)
        }

        response.error?.let { throw ApiException(code = -2, message = it.message) }
        response.extractText()
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
            throw ApiException(code = -3, message = "Could not extract any frames from this video.")
        }

        val parts = mutableListOf<com.example.videoanalyzer.network.ContentPart>()
        parts += TextPart(question.ifBlank { "Describe what's happening in this video." })
        frames.forEach { (dataUrl, _) ->
            parts += ImageUrlPart(ImageUrl(dataUrl))
        }

        val request = ChatCompletionRequest(
            model = model,
            messages = listOf(ChatMessage("user", parts)),
        )

        val response = chat.chatCompletion(url, auth, body = request)
        response.error?.let { throw ApiException(code = -2, message = it.message) }
        response.extractText()
    }

    class ApiException(
        val code: Int,
        message: String,
        cause: Throwable? = null,
    ) : RuntimeException(message, cause)
}