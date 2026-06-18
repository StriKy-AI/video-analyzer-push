package com.example.videoanalyzer.network

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Request body for the OpenAI-compatible chat completions endpoint, extended
 * with a `video_url` content part for providers like Moonshot Kimi that accept
 * native video input.
 *
 * Note: [ContentPart] is intentionally a flat data class (not a sealed class)
 * because Moshi's reflection adapter cannot serialize sealed-class hierarchies
 * out of the box — and `MoshiConverterFactory` would fail at request-build time
 * with "Unable to create @Body converter". Using a flat shape also matches the
 * wire format used by the providers we're targeting.
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

/**
 * Single content-part shape used by OpenAI-style chat completions. Use [type]
 * as the discriminator and fill exactly one of the payload fields:
 *
 *   - `type = "text"`      → fill [text]
 *   - `type = "image_url"` → fill [imageUrl]
 *   - `type = "video_url"` → fill [videoUrl]
 *
 * Moshi's default serialization omits null fields, so the JSON for a text part
 * is exactly `{"type": "text", "text": "..."}` — no trailing nulls.
 */
@JsonClass(generateAdapter = false)
data class ContentPart(
    val type: String,
    val text: String? = null,
    @Json(name = "image_url") val imageUrl: ImageUrl? = null,
    @Json(name = "video_url") val videoUrl: VideoUrl? = null,
)

@JsonClass(generateAdapter = false)
data class ImageUrl(val url: String)

@JsonClass(generateAdapter = false)
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