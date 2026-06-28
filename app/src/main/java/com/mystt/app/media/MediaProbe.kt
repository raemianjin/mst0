package com.mystt.app.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns

data class MediaInfo(
    val displayName: String,
    val sizeBytes: Long,
    val durationMs: Long
)

object MediaProbe {
    fun probe(context: Context, uri: Uri): MediaInfo {
        var name = "input"
        var size = 0L
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (c.moveToFirst()) {
                    if (ni >= 0) name = c.getString(ni) ?: name
                    if (si >= 0) size = c.getLong(si)
                }
            }
        } catch (_: Exception) {}
        var dur = 0L
        try {
            MediaMetadataRetriever().use { r ->
                r.setDataSource(context, uri)
                dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            }
        } catch (_: Exception) {}
        return MediaInfo(name, size, dur)
    }

    fun baseName(name: String): String =
        name.substringBeforeLast('.', name).ifBlank { "input" }
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
}
