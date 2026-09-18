package iot.guitar.merge

/**
 * PCM source that applies the merge offset and the video-duration limit.
 *
 * Offset convention (normative for merge + auto-sync): `offsetMs` is the position on the
 * VIDEO timeline where WAV sample 0 is placed. Positive => emit `offsetMs` of silence
 * first; negative => skip the first `|offsetMs|` of the WAV. Each chunk carries the
 * presentation time of its first sample on the video timeline. Emission stops at
 * [limitUs] (`<= 0` = unlimited, e.g. unknown video duration) or at WAV end-of-data.
 *
 * [leadSkipSamples] shifts the whole stream earlier by that many samples (used to cancel
 * the AAC encoder's fixed pre-roll delay so the muxed audio lands exactly on `offsetMs`).
 *
 * The returned [Chunk.data] array is reused between calls: consume it before calling
 * [next] again.
 */
class OffsetPcmSource(
    private val reader: WavPcmReader,
    offsetMs: Long,
    private val limitUs: Long,
    private val chunkBytes: Int = DEFAULT_CHUNK_BYTES,
    private val leadSkipSamples: Long = 0,
) {
    class Chunk(val data: ByteArray, val length: Int, val ptsUs: Long)

    private val sampleRate = reader.sampleRate
    private val bytesPerFrame = reader.bytesPerFrame
    private val silence = ByteArray(chunkBytes)
    private val buffer = ByteArray(chunkBytes)
    private var silenceBytesRemaining = 0L
    private var samplesEmitted = 0L
    private var done = false

    /** Total sample frames this source will emit at most (silence + WAV), before the limit. */
    val limitSamples: Long
        get() = if (limitUs <= 0) Long.MAX_VALUE else limitUs * sampleRate / 1_000_000L + leadSkipSamples

    init {
        require(chunkBytes % bytesPerFrame == 0) { "chunkBytes must be a multiple of the frame size" }
        require(leadSkipSamples >= 0) { "leadSkipSamples must be >= 0" }
        // Video-timeline sample at which WAV sample 0 lands, minus the encoder pre-roll.
        val startSample = offsetMs * sampleRate / 1000L - leadSkipSamples
        if (startSample < 0) {
            reader.seekToSample(-startSample)
        } else {
            silenceBytesRemaining = startSample * bytesPerFrame
        }
    }

    fun next(): Chunk? {
        if (done) return null
        val ptsUs = samplesEmitted * 1_000_000L / sampleRate
        val samplesLeft = limitSamples - samplesEmitted
        if (samplesLeft <= 0) {
            done = true
            return null
        }
        // Compare in samples first: samplesLeft * bytesPerFrame would overflow when unlimited.
        val chunkSamples = chunkBytes / bytesPerFrame
        val maxBytes = if (samplesLeft >= chunkSamples) chunkBytes else (samplesLeft * bytesPerFrame).toInt()

        if (silenceBytesRemaining > 0) {
            val n = minOf(silenceBytesRemaining, maxBytes.toLong()).toInt()
            silenceBytesRemaining -= n
            samplesEmitted += n / bytesPerFrame
            return Chunk(silence, n, ptsUs)
        }
        val n = reader.read(buffer, 0, maxBytes)
        if (n <= 0) {
            done = true
            return null
        }
        samplesEmitted += n / bytesPerFrame
        return Chunk(buffer, n, ptsUs)
    }

    companion object {
        const val DEFAULT_CHUNK_BYTES = 8192
    }
}
