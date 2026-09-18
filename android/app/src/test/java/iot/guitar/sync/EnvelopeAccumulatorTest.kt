package iot.guitar.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class EnvelopeAccumulatorTest {

    private fun pcmBytes(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s ->
            out[2 * i] = (s.toInt() and 0xFF).toByte()
            out[2 * i + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }

    @Test
    fun constantAmplitudeGivesRmsEqualToAmplitude() {
        val acc = EnvelopeAccumulator(48000, 1)
        val square = ShortArray(4800) { if (it % 2 == 0) 1000 else -1000 }   // 100 ms
        acc.push(pcmBytes(square), 0, square.size * 2)
        val env = acc.finish()
        assertEquals(10, env.size)
        env.forEach { assertEquals(1000f, it, 0.5f) }
    }

    @Test
    fun stereoOppositePhaseDownmixesToZero() {
        val acc = EnvelopeAccumulator(48000, 2)
        val stereo = ShortArray(960 * 2) { if (it % 2 == 0) 5000 else -5000 }  // (A, -A) pairs
        acc.push(stereo, 0, stereo.size)
        val env = acc.finish()
        assertEquals(2, env.size)
        env.forEach { assertEquals(0f, it, 0.001f) }
    }

    @Test
    fun partialHopIsFlushedAndChunkBoundariesDoNotMatter() {
        val a = EnvelopeAccumulator(1000, 1)   // hop = 10 samples
        val b = EnvelopeAccumulator(1000, 1)
        val samples = ShortArray(25) { (it * 100).toShort() }
        a.push(samples, 0, 25)
        b.push(samples, 0, 7)
        b.push(samples, 7, 18)
        val ea = a.finish()
        val eb = b.finish()
        assertEquals(3, ea.size)
        assertArrayEquals(ea, eb, 0.001f)
        assertEquals(25L, a.samplesPushed)
    }

    @Test
    fun onsetCurveIsRectifiedDiffNormalised() {
        val env = floatArrayOf(1f, 1f, 5f, 3f, 3f, 4f)
        val onset = EnvelopeAccumulator.onsetCurve(env)
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 0f, 0f, 0.25f), onset, 0.001f)
        assertEquals(0, EnvelopeAccumulator.onsetCurve(FloatArray(0)).size)
        assertArrayEquals(floatArrayOf(0f, 0f), EnvelopeAccumulator.onsetCurve(floatArrayOf(2f, 2f)), 0f)
    }
}
