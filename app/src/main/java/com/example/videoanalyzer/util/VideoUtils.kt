package com.example.videoanalyzer.util

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object VideoUtils {

    /** Pixel resolution to downscale to, or null = leave the source alone. */
    enum class MaxResolution(val label: String, val heightPx: Int?) {
        OFF("Off (upload original)", null),
        RES_480("480p", 480),
        RES_720("720p", 720),
        RES_1080("1080p", 1080),
        ;

        companion object {
            fun fromLabel(label: String): MaxResolution =
                values().firstOrNull { it.label == label } ?: RES_720
        }
    }

    /** Size-warning thresholds, in bytes. */
    enum class WarnThreshold(val label: String, val minBytes: Long?) {
        NEVER("Never ask", null),
        OVER_1MB("Above 1 MB", 1L * 1024 * 1024),
        OVER_20MB("Above 20 MB", 20L * 1024 * 1024),
        OVER_100MB("Above 100 MB", 100L * 1024 * 1024),
        ;

        companion object {
            fun fromLabel(label: String): WarnThreshold =
                values().firstOrNull { it.label == label } ?: OVER_20MB
        }
    }

    /**
     * Look up the size of the file at [uri] using ContentProvider metadata.
     * This does NOT read the file — it's a metadata query, so it's fast even
     * for multi-GB clips. Returns null if the provider doesn't expose a size
     * (some don't, e.g. certain cloud-backed providers).
     */
    fun querySizeBytes(context: Context, uri: Uri): Long? {
        return try {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (idx >= 0 && !cursor.isNull(idx)) cursor.getLong(idx) else null
                    } else null
                }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Read the entire video file into memory. For clips under ~50 MB this is
     * fine. Larger files should be streamed — we cap with a soft warning
     * rather than failing.
     */
    fun readBytesAndMime(context: Context, uri: Uri): Pair<ByteArray, String> {
        val mime = context.contentResolver.getType(uri) ?: "video/mp4"
        val bytes = context.contentResolver.openInputStream(uri)?.use(InputStream::readBytes)
            ?: throw IllegalStateException("Could not open video at $uri")
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
                    retriever.getFrameAtTime(
                        ts * 1000L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    )
                } catch (_: Throwable) {
                    null
                }
                if (bmp != null) {
                    val baos = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                    val b64 = android.util.Base64.encodeToString(
                        baos.toByteArray(),
                        android.util.Base64.NO_WRAP,
                    )
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

    fun formatBytes(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> "%.2f GB".format(gb)
            mb >= 1.0 -> "%.1f MB".format(mb)
            kb >= 1.0 -> "%.0f KB".format(kb)
            else -> "$bytes B"
        }
    }

    /**
     * Re-encode [src] with a max height of [targetHeight] using Media3
     * Transformer. Writes the result to a new file under the app's cache dir
     * and returns that file. If [targetHeight] is null, returns null (caller
     * should skip downscaling).
     *
     * Requires [UnstableApi] because Transformer + Effects are still tagged
     * unstable in Media3 1.4.x.
     */
    @OptIn(UnstableApi::class)
    suspend fun downscaleVideo(
        context: Context,
        src: Uri,
        targetHeight: Int?,
    ): File? {
        if (targetHeight == null) return null

        val outDir = File(context.cacheDir, "downscaled").apply { mkdirs() }
        val outFile = File(outDir, "vid_${System.currentTimeMillis()}.mp4")

        return suspendCancellableCoroutine { cont ->
            val listener = object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (cont.isActive) cont.resume(outFile) {}
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exception: ExportException,
                ) {
                    // Clean up partial output if any
                    if (outFile.exists()) outFile.delete()
                    if (cont.isActive) cont.resumeWithException(exception)
                }
            }

            val transformer = Transformer.Builder(context)
                .addListener(listener)
                .build()

            val edited = EditedMediaItem.Builder(MediaItem.fromUri(src))
                .setEffects(
                    Effects(
                        audioProcessors = emptyList(),
                        videoEffects = listOf(Presentation.createForHeight(targetHeight)),
                    ),
                )
                .build()

            cont.invokeOnCancellation {
                try { transformer.cancel() } catch (_: Throwable) {}
                if (outFile.exists()) outFile.delete()
            }

            transformer.start(edited, outFile.absolutePath)
        }
    }
}