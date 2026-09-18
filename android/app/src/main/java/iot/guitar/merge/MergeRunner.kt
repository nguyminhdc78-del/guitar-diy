package iot.guitar.merge

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import iot.guitar.R
import iot.guitar.audio.WavStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs one merge off the main thread: pending MediaStore entry -> mux -> finalize, or
 * discard on failure. Progress callbacks are delivered on the main thread.
 */
class MergeRunner(private val context: Context) {

    sealed class Outcome {
        data class Success(val uri: Uri, val displayName: String) : Outcome()
        data class Failure(val message: String) : Outcome()
    }

    private val saver = MediaStoreVideoSaver(context.contentResolver)
    private val wavStore = WavStore(context.contentResolver)
    private val merger = VideoAudioMerger(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** @param outputName final display name; default derives `<video>-umic.mp4` from the picked video. */
    suspend fun run(videoUri: Uri, wavUri: Uri, offsetMs: Long, outputName: String? = null, onProgress: (Int) -> Unit): Outcome =
        withContext(Dispatchers.IO) {
            val name = outputName ?: MediaStoreVideoSaver.outputName(saver.displayName(videoUri))
            var outUri: Uri? = null
            try {
                val uri = saver.createPending(name)
                outUri = uri
                saver.openFd(uri).use { pfd ->
                    openWav(wavUri).use { wav ->
                        merger.merge(videoUri, wav, offsetMs, pfd.fileDescriptor) { percent ->
                            mainHandler.post { onProgress(percent) }
                        }
                    }
                }
                saver.finalize(uri)
                Outcome.Success(uri, name)
            } catch (e: VideoAudioMerger.MergeException) {
                outUri?.let { saver.discard(it) }
                val msg = if (e.likelyHdr) context.getString(R.string.merge_failed_hdr)
                else context.getString(R.string.merge_failed, e.message ?: "?")
                Outcome.Failure(msg)
            } catch (e: Exception) {
                outUri?.let { saver.discard(it) }
                Outcome.Failure(context.getString(R.string.merge_failed, e.message ?: e.javaClass.simpleName))
            }
        }

    /** Opens a MediaStore WAV as a seekable reader; closing the reader closes the descriptor. */
    fun openWav(uri: Uri): WavPcmReader {
        val pfd = wavStore.openReadable(uri)
        return WavPcmReader(ParcelFileDescriptor.AutoCloseInputStream(pfd).channel)
    }
}
