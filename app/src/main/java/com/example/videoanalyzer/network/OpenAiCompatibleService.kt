package com.example.videoanalyzer.network

import com.example.videoanalyzer.data.ModelsResponse
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Url

interface OpenAiCompatibleService {

    /**
     * Hits the provider's model-list endpoint. Most providers expose it at
     * `/v1/models` — we let [url] override the path so users on custom setups
     * (MiniMax, Moonshot, OpenRouter, etc.) can adjust in advanced settings.
     */
    @GET
    suspend fun listModels(
        @Url url: String,
        @Header("Authorization") authorization: String,
    ): ModelsResponse

    @GET
    suspend fun listModelsWithExtraHeaders(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Header("Accept") accept: String = "application/json",
    ): ModelsResponse
}