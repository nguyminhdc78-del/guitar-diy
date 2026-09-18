package iot.guitar.merge

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.IOException

/**
 * MediaStore pending-entry pattern for merged videos in the public `Movies/uMIC/` folder:
 * [createPending] -> write through [openFd] -> [finalize], or [discard] on failure so no
 * orphan row is left behind.
 */
class MediaStoreVideoSaver(private val resolver: ContentResolver) {

    fun createPending(displayName: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return resolver.insert(collection, values) ?: throw IOException("MediaStore insert failed for $displayName")
    }

    fun openFd(uri: Uri): ParcelFileDescriptor =
        resolver.openFileDescriptor(uri, "rw") ?: throw IOException("cannot open $uri for writing")

    fun finalize(uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
        resolver.update(uri, values, null, null)
    }

    fun discard(uri: Uri) {
        try {
            resolver.delete(uri, null, null)
        } catch (_: SecurityException) {
            // Row already gone or not ours.
        }
    }

    data class VideoEntry(val uri: Uri, val displayName: String, val sizeBytes: Long, val dateAddedSec: Long, val durationMs: Long)

    /** Finished outputs in Movies/uMIC, newest first (used by the Home "recent" list). */
    fun listOutputs(): List<VideoEntry> {
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.DURATION)
        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?"
        val out = mutableListOf<VideoEntry>()
        resolver.query(collection, projection, selection, arrayOf("$RELATIVE_PATH%", "%.mp4"),
            "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                out += VideoEntry(android.content.ContentUris.withAppendedId(collection, id), c.getString(1) ?: "$id.mp4",
                    c.getLong(2), c.getLong(3), c.getLong(4))
            }
        }
        return out
    }

    /** Display name of a picked video (used to name the output), or null. */
    fun displayName(uri: Uri): String? {
        try {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) return c.getString(0)
            }
        } catch (_: Exception) {
            // Some providers reject the projection; fall back to a generic name.
        }
        return null
    }

    /**
     * Possible recording-start times of a video (epoch milliseconds) from the MP4 creation time
     * (UTC `yyyyMMdd'T'HHmmss.SSS'Z'`). Samsung stamps the END of the recording, other
     * cameras the start, so both `creation - duration` and `creation` are returned (end
     * convention first). Empty when the metadata is missing.
     */
    fun startTimeCandidatesMs(context: Context, uri: Uri): List<Long> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val raw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE) ?: return emptyList()
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val fmt = java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss", java.util.Locale.US)
            fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
            val creationMs = fmt.parse(raw.take(15))?.time ?: return emptyList()
            listOf(creationMs - durationMs, creationMs).distinct()
        } catch (_: Exception) {
            emptyList()
        } finally {
            retriever.release()
        }
    }

    companion object {
        const val RELATIVE_PATH = "Movies/uMIC/"

        /**
         * `clip.mp4` -> `clip-umic.mp4`. The Photo Picker often reports only a numeric id
         * instead of a file name; fall back to a timestamp so outputs stay recognisable.
         */
        fun outputName(videoName: String?, now: java.util.Date = java.util.Date()): String {
            val base = videoName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() && !it.all(Char::isDigit) }
                ?: ("umic-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(now))
            return "$base-umic.mp4"
        }
    }
}
