package iot.guitar.audio

import android.content.ContentResolver
import android.net.Uri
import iot.guitar.merge.MediaStoreVideoSaver

/** Newest recordings across Movies/uMIC (mp4) and Music/uMIC (wav) for the Home screen. */
object RecentRecordings {

    data class Item(
        val uri: Uri,
        val displayName: String,
        val isVideo: Boolean,
        val dateAddedSec: Long,
        val sizeBytes: Long,
        /** Known for videos (MediaStore) and derived from size for WAVs (96 000 B/s). */
        val durationMs: Long,
    )

    fun load(resolver: ContentResolver, limit: Int = 10): List<Item> {
        val videos = MediaStoreVideoSaver(resolver).listOutputs().map {
            Item(it.uri, it.displayName, true, it.dateAddedSec, it.sizeBytes, it.durationMs)
        }
        val wavs = WavStore(resolver).listRecordings().map {
            Item(it.uri, it.displayName, false, it.dateAddedSec, it.sizeBytes, (it.sizeBytes - 44).coerceAtLeast(0) / 96)
        }
        return (videos + wavs).sortedByDescending { it.dateAddedSec }.take(limit)
    }
}
