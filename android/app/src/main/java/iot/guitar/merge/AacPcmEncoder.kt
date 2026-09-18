package iot.guitar.merge

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.io.Closeable
import java.nio.ByteBuffer

/**
 * Pull-style AAC-LC encoder around [MediaCodec].
 *
 * [feed] queues PCM16 with a presentation time; [pull] returns encoded samples (copied out
 * of the codec, codec-config buffers skipped). [outputFormat] becomes non-null after the
 * first few feeds and must be passed to `MediaMuxer.addTrack` before `start()`.
 */
class AacPcmEncoder(
    private val sampleRate: Int = 48000,
    private val channels: Int = 1,
    bitrate: Int = 128_000,
) : Closeable {

    class EncodedSample(val data: ByteBuffer, val info: MediaCodec.BufferInfo)

    private val codec: MediaCodec
    private val info = MediaCodec.BufferInfo()
    private val pending = ArrayDeque<EncodedSample>()
    private val bytesPerFrame = channels * 2

    var outputFormat: MediaFormat? = null
        private set

    /** True once the encoder has emitted its end-of-stream marker. */
    var endOfStream = false
        private set

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_BYTES)
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    /** Queues [length] bytes of PCM16 starting at [ptsUs]. Splits across input buffers as needed. */
    fun feed(data: ByteArray, length: Int, ptsUs: Long) {
        var offset = 0
        while (offset < length) {
            val index = acquireInputBuffer()
            val input = codec.getInputBuffer(index) ?: throw IllegalStateException("null input buffer $index")
            input.clear()
            val n = minOf(input.capacity(), length - offset) / bytesPerFrame * bytesPerFrame
            input.put(data, offset, n)
            val pts = ptsUs + (offset / bytesPerFrame).toLong() * 1_000_000L / sampleRate
            codec.queueInputBuffer(index, 0, n, pts, 0)
            offset += n
        }
    }

    fun signalEnd() {
        val index = acquireInputBuffer()
        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
    }

    /**
     * Returns the next encoded sample, or null if none is ready within [timeoutUs].
     */
    fun pull(timeoutUs: Long = 0): EncodedSample? {
        drainCodec(timeoutUs)
        return pending.removeFirstOrNull()
    }

    /** Drains available output into the internal queue (updates [outputFormat]) without returning samples. */
    fun drain(timeoutUs: Long = 0) = drainCodec(timeoutUs)

    override fun close() {
        try {
            codec.stop()
        } catch (_: IllegalStateException) {
            // already stopped
        }
        codec.release()
    }

    /** Blocks until an input buffer is free, draining output in the meantime so the codec never stalls. */
    private fun acquireInputBuffer(): Int {
        while (true) {
            val index = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (index >= 0) return index
            drainCodec(0)
        }
    }

    /** Moves every available output buffer into [pending]. */
    private fun drainCodec(firstTimeoutUs: Long) {
        var timeout = firstTimeoutUs
        while (!endOfStream) {
            val index = codec.dequeueOutputBuffer(info, timeout)
            timeout = 0
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = codec.outputFormat
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index < 0 -> continue
                else -> {
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) endOfStream = true
                    if (!isConfig && info.size > 0) {
                        val out = codec.getOutputBuffer(index)!!
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        val copy = ByteBuffer.allocate(info.size).put(out)
                        copy.flip()
                        val copyInfo = MediaCodec.BufferInfo().apply {
                            set(0, info.size, info.presentationTimeUs,
                                info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
                        }
                        pending.addLast(EncodedSample(copy, copyInfo))
                    }
                    codec.releaseOutputBuffer(index, false)
                }
            }
        }
    }

    companion object {
        private const val MAX_INPUT_BYTES = 16384
        private const val DEQUEUE_TIMEOUT_US = 10_000L

        /**
         * Fixed pre-roll of Android's AAC-LC encoder (FDK): the first 2048 decoded samples
         * are filter warm-up, and MediaMuxer writes no edit list to hide them, so players
         * hear everything 42.67 ms late. Measured on Samsung S21 FE / Android 16 by comparing
         * a clap in the merged track against the source WAV. Feed PCM this much earlier.
         */
        const val ENCODER_DELAY_SAMPLES = 2048L
    }
}
