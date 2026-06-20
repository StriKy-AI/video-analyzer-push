package com.example.videoanalyzer.network

import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.GzipSink
import okio.Sink
import okio.buffer

/**
 * OkHttp interceptor that gzip-compresses request bodies when enabled.
 *
 * Gated by a [Boolean] flag read at request time — flip the flag via the
 * Settings toggle and the next request will or won't be compressed. NOT
 * every provider accepts `Content-Encoding: gzip`, so this defaults to OFF.
 *
 * Only intercepts requests whose body has `Content-Type: application/json`
 * (i.e. our chat-completions POSTs). Multipart bodies, images, etc. pass
 * through untouched.
 */
class GzipRequestInterceptor(
    @Volatile var enabled: Boolean = false,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (!enabled) return chain.proceed(original)
        val body = original.body ?: return chain.proceed(original)
        if (original.header("Content-Encoding") != null) return chain.proceed(original)

        val contentType = body.contentType()?.toString().orEmpty()
        if (!contentType.startsWith("application/json", ignoreCase = true)) {
            return chain.proceed(original)
        }

        val gzipped = GzippingRequestBody(body)
        val compressed = original.newBuilder()
            .header("Content-Encoding", "gzip")
            .removeHeader("Content-Length")
            .method(original.method, gzipped)
            .build()
        return chain.proceed(compressed)
    }

    private class GzippingRequestBody(private val delegate: RequestBody) : RequestBody() {
        override fun contentType(): MediaType? = delegate.contentType()
        override fun contentLength(): Long = -1L // unknown after compression

        override fun writeTo(sink: BufferedSink) {
            val gzipSink: Sink = GzipSink(sink)
            val buffered = gzipSink.buffer()
            delegate.writeTo(buffered)
            buffered.close()
        }

        // Best-effort equality for OkHttp's call deduplication.
        override fun isOneShot(): Boolean = true
    }
}

/**
 * Wraps an existing request body so the upload progress is observable.
 *
 * Reports byte counts to [UploadProgressBus] which the HomeViewModel reads
 * to drive a LinearProgressIndicator in the UI.
 *
 * If the body has no `Content-Length` (chunked transfer), the progress
 * fraction stays indeterminate (0f).
 */
class UploadProgressInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val body = original.body ?: return chain.proceed(original)

        // Only count bodies for POST/PUT/PATCH. GET /v1/models etc. have no body.
        if (original.method !in setOf("POST", "PUT", "PATCH")) {
            return chain.proceed(original)
        }

        return try {
            val total = body.contentLength()
            com.example.videoanalyzer.util.UploadProgressBus.start(total)
            val wrapped = CountingRequestBody(body) { sent, t ->
                com.example.videoanalyzer.util.UploadProgressBus.update(sent, t)
            }
            val req = original.newBuilder().method(original.method, wrapped).build()
            chain.proceed(req)
        } finally {
            com.example.videoanalyzer.util.UploadProgressBus.finish()
        }
    }

    private class CountingRequestBody(
        private val delegate: RequestBody,
        private val onProgress: (sent: Long, total: Long) -> Unit,
    ) : RequestBody() {
        override fun contentType(): MediaType? = delegate.contentType()
        override fun contentLength(): Long = delegate.contentLength()

        override fun writeTo(sink: BufferedSink) {
            val total = contentLength()
            val countingSink = object : ForwardingSink(sink) {
                private var sent = 0L
                override fun write(source: Buffer, byteCount: Long) {
                    super.write(source, byteCount)
                    sent += byteCount
                    onProgress(sent, total)
                }
            }.buffer()
            delegate.writeTo(countingSink)
            countingSink.flush()
        }

        override fun isOneShot(): Boolean = delegate.isOneShot()
    }
}