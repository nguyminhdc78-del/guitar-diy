package iot.guitar.service

import android.content.Context
import android.net.Uri
import android.util.Log
import iot.guitar.R
import iot.guitar.merge.MediaStoreVideoSaver
import iot.guitar.merge.MergeRunner
import iot.guitar.sync.AutoSyncEngine
import iot.guitar.sync.EnvelopeCrossCorrelator

/**
 * In-app mode post-processing: the raw CameraX MP4 (video + phone-mic audio, sync only) and
 * the ESP32 WAV are aligned with a clock anchor refined by a tight clap correlation, then
 * muxed into the final MP4. The raw file is deleted only after a confident merge so that
 * nothing is ever lost; on failure both inputs stay in place.
 */
class InAppMergeJob(private val context: Context) {

    data class Request(
        val videoUri: Uri,
        val videoStartElapsedMs: Long,
        val wavUri: Uri,
        val wavStartElapsedMs: Long,
        val wavName: String,
    )

    data class Result(val outputUri: Uri?, val outputName: String, val confidence: Float, val keptRaw: Boolean, val error: String?)

    private val runner = MergeRunner(context)
    private val saver = MediaStoreVideoSaver(context.contentResolver)

    suspend fun run(req: Request, onProgress: (Int) -> Unit): Result {
        val outputName = finalVideoName(req.wavName)
        val anchorMs = anchorOffsetMs(req.wavStartElapsedMs, req.videoStartElapsedMs)
        val sync = try {
            AutoSyncEngine(context).compute(req.videoUri, { runner.openWav(req.wavUri) },
                anchorsMs = listOf(anchorMs), windowMs = ANCHOR_WINDOW_MS, wideFallback = false)
        } catch (_: AutoSyncEngine.NoVideoAudioException) {
            AutoSyncEngine.SyncResult(anchorMs, 0f, 0)           // mic permission denied: clock only
        } catch (e: Exception) {
            Log.w(TAG, "sync failed", e)
            return Result(null, outputName, 0f, true, context.getString(R.string.inapp_merge_failed_kept_files,
                e.message ?: e.javaClass.simpleName))
        }
        Log.i(TAG, "anchor=${anchorMs}ms offset=${sync.offsetMs}ms confidence=${sync.confidence} r=${sync.correlation} trusted=${sync.isTrusted}")
        return when (val outcome = runner.run(req.videoUri, req.wavUri, sync.offsetMs, outputName, onProgress)) {
            is MergeRunner.Outcome.Success -> {
                val deleteRaw = shouldDeleteRaw(sync.confidence, sync.correlation)
                if (deleteRaw) saver.discard(req.videoUri)
                Result(outcome.uri, outcome.displayName, reportedConfidence(sync), keptRaw = !deleteRaw, error = null)
            }
            is MergeRunner.Outcome.Failure ->
                Result(null, outputName, sync.confidence, true, context.getString(R.string.inapp_merge_failed_kept_files, outcome.message))
        }
    }

    companion object {
        /**
         * Both timestamps are in-process SystemClock.elapsedRealtime(). Measured on S21 FE: the
         * `Start`-event anchor was ~480 ms late; the Status-based estimate is far tighter, but the
         * window keeps 3x margin over that worst case.
         */
        const val ANCHOR_WINDOW_MS = 1_500
        private const val TAG = "InAppMergeJob"

        /** Offset convention: video-timeline position of WAV sample 0. Camera starts after the WAV => negative. */
        fun anchorOffsetMs(wavStartElapsedMs: Long, videoStartElapsedMs: Long): Long = wavStartElapsedMs - videoStartElapsedMs

        /** `umic-20260916-140000.wav` -> `umic-20260916-140000-raw.mp4` (CameraX output, phone audio). */
        fun rawVideoName(wavName: String): String = wavName.substringBeforeLast('.') + "-raw.mp4"

        /** `umic-20260916-140000.wav` -> `umic-20260916-140000.mp4` (final, ESP32 audio). */
        fun finalVideoName(wavName: String): String = wavName.substringBeforeLast('.') + ".mp4"

        fun shouldDeleteRaw(confidence: Float, correlation: Float = 0f): Boolean =
            confidence >= EnvelopeCrossCorrelator.LOW_CONFIDENCE || correlation >= EnvelopeCrossCorrelator.MIN_CORRELATION

        /** UI/notification show one number: PSR, or a PSR-equivalent when Pearson carried the decision. */
        fun reportedConfidence(sync: AutoSyncEngine.SyncResult): Float =
            if (sync.isTrusted) maxOf(sync.confidence, EnvelopeCrossCorrelator.LOW_CONFIDENCE) else sync.confidence
    }
}
