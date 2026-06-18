package com.example.videoanalyzer.network

import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Url

interface ChatCompletionsService {

    /**
     * Sends the video + question to whatever endpoint the user has configured
     * (default: {baseUrl}/v1/chat/completions). The path is overridable via
     * the [url] parameter so providers with non-standard layouts work too.
     */
    @POST
    suspend fun chatCompletion(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Header("Content-Type") contentType: String = "application/json",
        @Body body: ChatCompletionRequest,
    ): ChatCompletionResponse
}