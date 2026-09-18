package iot.guitar.sync

import kotlin.math.sqrt

/**
 * Streams PCM16 (any channel count, downmixed to mono) into a 100 Hz RMS envelope.
 * Push chunks as they are decoded; [finish] flushes the partial last hop.
 */
class EnvelopeAccumulator(sampleRate: Int, channels: Int, hopMs: Int = HOP_MS) {
    private val channels = channels.coerceAtLeast(1)
    private val hopSamples = (sampleRate.toLong() * hopMs / 1000).toInt().coerceAtLeast(1)
    private val values = FloatArrayList()
    private var sumSq = 0.0
    private var count = 0

    /** Total sample frames pushed so far. */
    var samplesPushed: Long = 0
        private set

    /** Pushes [length] bytes of interleaved PCM16 LE starting at [offset]. */
    fun push(pcm: ByteArray, offset: Int, length: Int) {
        val frames = length / (2 * channels)
        var i = offset
        repeat(frames) {
            var mix = 0
            repeat(channels) {
                mix += ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
                i += 2
            }
            val mono = mix.toDouble() / channels
            sumSq += mono * mono
            if (++count == hopSamples) flushHop()
        }
        samplesPushed += frames
    }

    /** Pushes decoded 16-bit samples (interleaved) directly. */
    fun push(samples: ShortArray, offset: Int, length: Int) {
        val frames = length / channels
        var i = offset
        repeat(frames) {
            var mix = 0
            repeat(channels) { mix += samples[i++].toInt() }
            val mono = mix.toDouble() / channels
            sumSq += mono * mono
            if (++count == hopSamples) flushHop()
        }
        samplesPushed += frames
    }

    fun finish(): FloatArray {
        if (count > 0) flushHop()
        return values.toArray()
    }

    private fun flushHop() {
        values.add(sqrt(sumSq / count).toFloat())
        sumSq = 0.0
        count = 0
    }

    companion object {
        const val HOP_MS = 10

        /**
         * Half-wave rectified first difference, normalised to max 1. Claps become sharp
         * spikes while steady speech/noise flattens out.
         */
        fun onsetCurve(envelope: FloatArray): FloatArray {
            if (envelope.isEmpty()) return FloatArray(0)
            val out = FloatArray(envelope.size)
            var max = 0f
            for (i in 1 until envelope.size) {
                val d = envelope[i] - envelope[i - 1]
                out[i] = if (d > 0f) d else 0f
                if (out[i] > max) max = out[i]
            }
            if (max > 0f) for (i in out.indices) out[i] /= max
            return out
        }
    }
}

/** Minimal growable float list (avoids boxing for 15-minute envelopes = 90k values). */
class FloatArrayList(initialCapacity: Int = 1024) {
    private var data = FloatArray(initialCapacity)
    var size = 0
        private set

    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    fun toArray(): FloatArray = data.copyOf(size)
}
