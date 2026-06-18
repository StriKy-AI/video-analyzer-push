package com.example.videoanalyzer.data

import com.squareup.moshi.JsonClass

/**
 * Wire format for OpenAI-compatible /v1/models endpoints.
 * Most providers (Moonshot, OpenRouter, MiniMax-compatible, etc.) return
 * either a bare string id or a model object with an `id` field. We support both.
 */
@JsonClass(generateAdapter = false)
data class ModelsResponse(
    val data: List<ModelEntry>? = null,
    val models: List<ModelEntry>? = null, // some providers use this
) {
    fun allIds(): List<String> = (data ?: models).orEmpty().mapNotNull { entry ->
        entry.id?.takeIf { it.isNotBlank() }
    }
}

@JsonClass(generateAdapter = false)
data class ModelEntry(
    val id: String? = null,
    val name: String? = null,
    val owned_by: String? = null,
)

/** Display model shown in the picker. */
data class ModelInfo(
    val id: String,
    val displayName: String = id,
)