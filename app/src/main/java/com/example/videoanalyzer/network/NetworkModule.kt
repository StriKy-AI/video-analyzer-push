package com.example.videoanalyzer.network

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Centralised HTTP client / Retrofit factory. We deliberately do NOT pin
 * baseUrl on the Retrofit instance because users can change the endpoint at
 * runtime — every call passes the absolute URL via the `@Url` parameter on
 * the service methods.
 *
 * Two custom interceptors are installed:
 *  - [UploadProgressInterceptor] — always on; reports byte counts to the UI
 *  - [GzipRequestInterceptor]    — gated by [gzipInterceptor.enabled], which
 *                                  the SettingsViewModel flips when the user
 *                                  toggles "Gzip compression" in Settings
 */
object NetworkModule {

    val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    /** Exposed so SettingsViewModel can flip it when the user toggles gzip. */
    val gzipInterceptor = GzipRequestInterceptor(enabled = false)

    private val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS) // longer — video upload + inference can take minutes
        .writeTimeout(180, TimeUnit.SECONDS)
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
        )
        // Order matters: gzip wraps the body, progress wraps the (possibly-gzipped) body.
        .addInterceptor(gzipInterceptor)
        .addInterceptor(UploadProgressInterceptor())
        .build()

    private val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl("http://localhost/") // dummy; real URLs supplied per-call via @Url
        .client(okHttp)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    val openAiService: OpenAiCompatibleService =
        retrofit.create(OpenAiCompatibleService::class.java)

    val chatService: ChatCompletionsService =
        retrofit.create(ChatCompletionsService::class.java)

    /** Build the Authorization header value (e.g. "Bearer sk-..."). */
    fun bearerHeader(apiKey: String): String = "Bearer $apiKey"

    /** Trim a user-supplied base URL — strip trailing slash, drop `/v1` if present. */
    fun normalizeBaseUrl(raw: String): String {
        var s = raw.trim().trimEnd('/')
        // Strip /v1, /v1/, etc. so we can re-append canonical paths consistently.
        s = s.replace(Regex("/v\\d+/?$"), "")
        return s
    }
}