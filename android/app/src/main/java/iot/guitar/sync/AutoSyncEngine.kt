package iot.guitar.sync

import android.content.Context
import android.net.Uri
import iot.guitar.merge.WavPcmReader
import iot.guitar.sync.EnvelopeCrossCorrelator.isTrusted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Computes the merge offset from a clap (or any shared transient) present in both the
 * phone video's own audio and the ESP32 WAV.
 *
 * Both devices know the wall clock: the WAV row's `date_added` is the recording start and
 * the MP4 carries a creation time, so `wavStart - videoStart` anchors the offset to about
 * +-1 s. The clap is then searched only within +-[ANCHOR_WINDOW_MS] of each anchor, which
 * removes the ambiguity of repeated claps/speech; the full +-[MAX_LAG_MS] scan is only a
 * fallback when there is no usable anchor or the anchored match is weak.
 */
class AutoSyncEngine(private val context: Context) {

    /** [offsetMs] follows the merge convention; [clapVideoMs] = loudest onset in the video. */
    data class SyncResult(val offsetMs: Long, val confidence: Float, val clapVideoMs: Long, val correlation: Float = 0f) {
        val isTrusted: Boolean
            get() = confidence >= EnvelopeCrossCorrelator.LOW_CONFIDENCE || correlation >= EnvelopeCrossCorrelator.MIN_CORRELATION
        val isLowConfidence: Boolean
            get() = !isTrusted
    }

    /** Thrown when the video has no audio track (camera muted). */
    class NoVideoAudioException : Exception("video has no audio track")

    /**
     * @param openWav factory producing a fresh reader (the engine closes it).
     * @param anchorsMs candidate offsets from wall-clock timestamps (may be empty).
     */
    suspend fun compute(
        videoUri: Uri,
        openWav: () -> WavPcmReader,
        anchorsMs: List<Long> = emptyList(),
        windowMs: Int = ANCHOR_WINDOW_MS,
        wideFallback: Boolean = true,
    ): SyncResult = withContext(Dispatchers.Default) {
        coroutineScope {
            val videoJob = async { VideoAudioDecoder.decodeMonoEnvelope(context, videoUri) }
            val wavJob = async { openWav().use { wavEnvelope(it) } }
            val videoEnv = videoJob.await() ?: throw NoVideoAudioException()
            val wavEnv = wavJob.await()
            correlate(videoEnv, wavEnv, anchorsMs, windowMs, wideFallback)
        }
    }

    companion object {
        const val MAX_LAG_MS = 60_000
        const val ANCHOR_WINDOW_MS = 2_500
        private const val HOP_MS = EnvelopeAccumulator.HOP_MS

        /** 100 Hz RMS envelope of a whole WAV, streamed in 64 KB chunks. */
        fun wavEnvelope(reader: WavPcmReader): FloatArray {
            val acc = EnvelopeAccumulator(reader.sampleRate, reader.channels)
            val buf = ByteArray(64 * 1024)
            reader.seekToSample(0)
            while (true) {
                val n = reader.read(buf)
                if (n <= 0) break
                acc.push(buf, 0, n)
            }
            return acc.finish()
        }

        /** Pure part of the algorithm; unit-tested with synthetic envelopes. */
        /**
         * @param windowMs half-width of the search around each anchor.
         * @param wideFallback false = in-app mode: both timestamps come from one monotonic clock, so a
         *   weak anchored match falls back to the raw anchor instead of a full-range scan.
         */
        fun correlate(
            videoEnvelope: FloatArray,
            wavEnvelope: FloatArray,
            anchorsMs: List<Long> = emptyList(),
            windowMs: Int = ANCHOR_WINDOW_MS,
            wideFallback: Boolean = true,
        ): SyncResult {
            val videoOnset = EnvelopeAccumulator.onsetCurve(videoEnvelope)
            val v = EnvelopeCrossCorrelator.sharpen(videoOnset)
            val w = EnvelopeCrossCorrelator.sharpen(EnvelopeAccumulator.onsetCurve(wavEnvelope))
            val window = windowMs / HOP_MS
            // Anchored candidates: highest correlation peak wins; confidence judged inside its window.
            val anchored = anchorsMs.map { a ->
                EnvelopeCrossCorrelator.bestLagAnchored(v, w, (a / HOP_MS).toInt(), window)
            }.maxByOrNull { it.peak }
            val lag = if (anchored != null && anchored.isTrusted()) {
                anchored
            } else if (!wideFallback && anchorsMs.isNotEmpty()) {
                // Trust the clock: a noise peak inside the window is worse than the anchor itself.
                EnvelopeCrossCorrelator.LagResult((anchorsMs.first() / HOP_MS).toInt(), anchored?.confidence ?: 0f, 0f, anchored?.correlation ?: 0f)
            } else {
                val wide = EnvelopeCrossCorrelator.bestLag(v, w, MAX_LAG_MS / HOP_MS)
                // A confident wide scan beats a weak anchored match; otherwise the clock is still the best guess.
                if (anchored == null || wide.isTrusted()) wide else anchored
            }
            var clapIndex = 0
            for (i in videoOnset.indices) if (videoOnset[i] > videoOnset[clapIndex]) clapIndex = i
            return SyncResult(
                offsetMs = lag.lagHops.toLong() * HOP_MS,
                confidence = lag.confidence,
                clapVideoMs = clapIndex.toLong() * HOP_MS,
                correlation = lag.correlation,
            )
        }
    }
}
