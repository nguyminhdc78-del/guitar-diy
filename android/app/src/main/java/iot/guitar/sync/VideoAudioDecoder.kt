package iot.guitar.sync

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri

/**
 * Decodes the first audio track of a video into a 100 Hz RMS envelope, streaming PCM
 * straight into an [EnvelopeAccumulator] (no full-length PCM in memory).
 */
object VideoAudioDecoder {

    /** Returns the 100 Hz envelope, or null when the video has no audio track. */
    fun decodeMonoEnvelope(context: Context, uri: Uri): FloatArray? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i
                    format = f
                    break
                }
            }
            if (track < 0 || format == null) return null
            extractor.selectTrack(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val dec = MediaCodec.createDecoderByType(mime)
            codec = dec
            dec.configure(format, null, null, 0)
            dec.start()

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var acc = EnvelopeAccumulator(sampleRate, channels)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var scratch = ShortArray(8192)

            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = dec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buf = dec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            dec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = dec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = dec.outputFormat
                        val sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (acc.samplesPushed == 0L && (sr != sampleRate || ch != channels)) {
                            sampleRate = sr
                            channels = ch
                            acc = EnvelopeAccumulator(sampleRate, channels)
                        }
                    }
                    outIndex >= 0 -> {
                        if (info.size > 0) {
                            val out = dec.getOutputBuffer(outIndex)!!
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            val shorts = out.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
                            val n = shorts.remaining()
                            if (scratch.size < n) scratch = ShortArray(n)
                            shorts.get(scratch, 0, n)
                            acc.push(scratch, 0, n)
                        }
                        dec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            return acc.finish()
        } finally {
            try { codec?.stop() } catch (_: IllegalStateException) { }
            codec?.release()
            extractor.release()
        }
    }

    private const val TIMEOUT_US = 10_000L
}
