package iot.guitar.audio

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import iot.guitar.protocol.StreamProtocol
import java.io.IOException

/**
 * MediaStore helper for WAV recordings in the public `Music/uMIC/` folder.
 *
 * Rows are created with `IS_PENDING=1` while recording (hidden from other apps) and
 * published on [finalize]. App-owned rows need no storage permission on API 29+.
 */
class WavStore(private val resolver: ContentResolver) {

    data class WavEntry(val uri: Uri, val displayName: String, val sizeBytes: Long, val dateAddedSec: Long)

    /** Creates a pending row and returns its content Uri. */
    fun createPending(displayName: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, MIME_WAV)
            put(MediaStore.Audio.Media.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        return resolver.insert(collection, values)
            ?: throw IOException("MediaStore insert failed for $displayName")
    }

    fun openWritable(uri: Uri): ParcelFileDescriptor =
        resolver.openFileDescriptor(uri, "rw") ?: throw IOException("cannot open $uri for writing")

    /** Pending row + protocol-format WAV writer in one step; closing the writer closes the descriptor. */
    fun createPendingWriter(displayName: String): Pair<Uri, WavFileWriter> {
        val uri = createPending(displayName)
        val stream = ParcelFileDescriptor.AutoCloseOutputStream(openWritable(uri))
        val writer = WavFileWriter(stream.channel, StreamProtocol.SAMPLE_RATE, StreamProtocol.CHANNELS,
            StreamProtocol.BITS_PER_SAMPLE)
        return uri to writer
    }

    fun openReadable(uri: Uri): ParcelFileDescriptor =
        resolver.openFileDescriptor(uri, "r") ?: throw IOException("cannot open $uri for reading")

    /** Clears IS_PENDING so the file becomes visible to file managers and other apps. */
    fun finalize(uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
        resolver.update(uri, values, null, null)
    }

    fun discard(uri: Uri) {
        try {
            resolver.delete(uri, null, null)
        } catch (_: SecurityException) {
            // Row already gone or not ours; nothing to clean up.
        }
    }

    /** Finalized recordings in Music/uMIC, newest first. */
    fun listRecordings(): List<WavEntry> {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_ADDED,
        )
        val selection = "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ? AND " +
            "${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("$RELATIVE_PATH%", "%.wav")
        val sort = "${MediaStore.Audio.Media.DATE_ADDED} DESC"
        val result = mutableListOf<WavEntry>()
        resolver.query(collection, projection, selection, args, sort)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                result += WavEntry(
                    uri = ContentUris.withAppendedId(collection, id),
                    displayName = c.getString(nameCol) ?: "umic-$id.wav",
                    sizeBytes = c.getLong(sizeCol),
                    dateAddedSec = c.getLong(dateCol),
                )
            }
        }
        return result
    }

    /**
     * The recording that was in progress at [epochSec] (video start time), newest first if
     * several overlap. `date_added` is set when the pending row is created = recording start;
     * duration comes from the file size (48 kHz x 16-bit mono = 96 000 B/s).
     */
    fun findCovering(epochSec: Long, toleranceSec: Long = 5): WavEntry? =
        listRecordings().firstOrNull { e ->
            val durationSec = (e.sizeBytes - 44).coerceAtLeast(0) / 96_000
            epochSec >= e.dateAddedSec - toleranceSec && epochSec <= e.dateAddedSec + durationSec + toleranceSec
        }

    /** Display name of a recording Uri, or null if the row does not exist. */
    fun displayName(uri: Uri): String? {
        resolver.query(uri, arrayOf(MediaStore.Audio.Media.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return null
    }

    private val collection: Uri
        get() = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    companion object {
        const val RELATIVE_PATH = "Music/uMIC/"
        const val MIME_WAV = "audio/x-wav"
    }
}
