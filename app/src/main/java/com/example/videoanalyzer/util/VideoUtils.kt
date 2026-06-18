package com.example.videoanalyzer.util

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.InputStream

object VideoUtils {

    /**
     * Read the entire video file into memory. For clips under ~50 MB this is
     * fine. Larger files should be streamed — we cap with a soft warning
     * rather than failing.
     */
    fun readBytesAndMime(context: Context, uri: Uri): Pair<ByteArray, String> {
        val mime = context.contentResolver.getType(uri) ?: "video/mp4"
        val bytes = context.contentResolver.openInputStream(uri)?.use(InputStream::readBytes)
            ?: throw IllegalStateException("Could not open video at $uri")
        if (bytes.size > 60L * 1024 * 1024) {
            // soft warn — most providers reject payloads >50MB anyway
        }
        return bytes to mime
    }

    /**
     * Extract [maxFrames] JPEG frames evenly spaced through the video using
     * MediaMetadataRetriever. Returns pairs of (dataUrl, timestampMillis).
     */
    fun extractFrames(
        context: Context,
        uri: Uri,
        maxFrames: Int,
    ): List<Pair<String, Long>> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L

            if (durationMs <= 0L) return emptyList()

            val results = mutableListOf<Pair<String, Long>>()
            for (i in 0 until maxFrames) {
                val ts = ((i + 1L) * durationMs) / (maxFrames + 1L)
                val bmp: Bitmap? = try {
                    retriever.getFrameAtTime(ts * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } catch (_: Throwable) {
                    null
                }
                if (bmp != null) {
                    val baos = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                    val b64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
                    results += "data:image/jpeg;base64,$b64" to ts
                    bmp.recycle()
                }
            }
            results
        } finally {
            try { retriever.release() } catch (_: Throwable) { /* ignore */ }
        }
    }

    fun formatDurationMs(ms: Long): String {
        val totalSec = ms / 1000
        val mm = totalSec / 60
        val ss = totalSec % 60
        return "%d:%02d".format(mm, ss)
    }
}