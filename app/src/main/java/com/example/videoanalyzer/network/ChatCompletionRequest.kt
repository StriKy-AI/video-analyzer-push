package com.example.videoanalyzer.network

import com.squareup.moshi.JsonClass

/**
 * Request body for the OpenAI-compatible chat completions endpoint, extended
 * with a `video_url` content part. Providers like Moonshot Kimi accept this
 * shape directly; others that only support image input can be supported by
 * extracting frames instead.
 *
 * Use [VideoContentPart] to attach the video as a base64 data URL.
 */
@JsonClass(generateAdapter = false)
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.4,
    val stream: Boolean = false,
)

@JsonClass(generateAdapter = false)
data class ChatMessage(
    val role: String, // "user" | "system" | "assistant"
    val content: List<ContentPart>,
)

sealed class ContentPart
data class TextPart(val text: String) : ContentPart() {
    val type: String get() = "text"
}

data class ImageUrlPart(
    val image_url: ImageUrl,
) : ContentPart() {
    val type: String get() = "image_url"
}

data class VideoUrlPart(
    val video_url: VideoUrl,
) : ContentPart() {
    val type: String get() = "video_url"
}

data class ImageUrl(val url: String)
data class VideoUrl(val url: String)

@JsonClass(generateAdapter = false)
data class ChatCompletionResponse(
    val id: String? = null,
    val model: String? = null,
    val choices: List<Choice>? = null,
    val error: ApiError? = null,
) {
    data class Choice(
        val index: Int? = null,
        val message: ResponseMessage? = null,
        val finish_reason: String? = null,
    )

    data class ResponseMessage(
        val role: String? = null,
        val content: String? = null,
    )

    data class ApiError(
        val message: String,
        val type: String? = null,
        val code: String? = null,
    )

    fun extractText(): String {
        error?.let { return "API error: ${it.message}" }
        val content = choices
            ?.mapNotNull { it.message?.content }
            ?.joinToString("\n")
            .orEmpty()
        return content.ifBlank { "(empty response)" }
    }
}