package iot.guitar.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/** Synthetic 48 kHz mono signals: low noise + one 20 ms burst, run through the real envelope path. */
class EnvelopeCrossCorrelatorTest {

    private fun burstSignal(seconds: Int, burstAtSec: Double?, seed: Int, noiseAmp: Int = 200): FloatArray {
        val rnd = Random(seed)
        val sr = 48000
        val acc = EnvelopeAccumulator(sr, 1)
        val chunk = ShortArray(4800)
        val total = seconds * sr
        var produced = 0
        val burstStart = burstAtSec?.let { (it * sr).toInt() } ?: -1
        val burstEnd = burstStart + sr / 50   // 20 ms
        while (produced < total) {
            for (i in chunk.indices) {
                val n = produced + i
                var v = rnd.nextInt(-noiseAmp, noiseAmp + 1)
                if (n in burstStart until burstEnd) v += if (n % 2 == 0) 20000 else -20000
                chunk[i] = v.toShort()
            }
            acc.push(chunk, 0, chunk.size)
            produced += chunk.size
        }
        return acc.finish()
    }

    @Test
    fun recoversPositiveOffsetWithinOneHop() {
        val wav = burstSignal(30, 5.000, seed = 1)
        val video = burstSignal(30, 6.230, seed = 2)
        val r = AutoSyncEngine.correlate(video, wav)
        assertTrue("offset=${r.offsetMs}", abs(r.offsetMs - 1230) <= 10)
        assertTrue("confidence=${r.confidence}", r.confidence > EnvelopeCrossCorrelator.LOW_CONFIDENCE)
        assertTrue("clap=${r.clapVideoMs}", abs(r.clapVideoMs - 6230) <= 10)
    }

    @Test
    fun recoversNegativeOffset() {
        val wav = burstSignal(30, 6.230, seed = 3)
        val video = burstSignal(30, 5.000, seed = 4)
        val r = AutoSyncEngine.correlate(video, wav)
        assertTrue("offset=${r.offsetMs}", abs(r.offsetMs + 1230) <= 10)
        assertTrue(r.confidence > EnvelopeCrossCorrelator.LOW_CONFIDENCE)
    }

    @Test
    fun pureNoiseHasLowConfidence() {
        val wav = burstSignal(30, null, seed = 5)
        val video = burstSignal(30, null, seed = 6)
        val r = AutoSyncEngine.correlate(video, wav)
        assertTrue("confidence=${r.confidence}", r.confidence < EnvelopeCrossCorrelator.LOW_CONFIDENCE)
    }

    @Test
    fun recoversLargeLagWithinSixtySecondWindow() {
        // Typical field case: recording started ~26 s before filming.
        val wav = burstSignal(40, 28.0, seed = 7)
        val video = burstSignal(40, 2.0, seed = 8)
        val r = AutoSyncEngine.correlate(video, wav)
        assertTrue("offset=${r.offsetMs}", abs(r.offsetMs + 26_000) <= 10)
        assertTrue("confidence=${r.confidence}", r.confidence > EnvelopeCrossCorrelator.LOW_CONFIDENCE)
    }

    @Test
    fun clockAnchorDisambiguatesRepeatedClaps() {
        // WAV has two identical claps 2.35 s apart; the video shows only the second one.
        val wav = burstSignal(40, 20.0, seed = 9).let { env ->
            val second = burstSignal(40, 22.35, seed = 9)
            FloatArray(env.size) { maxOf(env[it], second[it]) }
        }
        val video = burstSignal(20, 3.0, seed = 10)          // true offset = 3.0 - 22.35 = -19.35 s
        val unanchored = AutoSyncEngine.correlate(video, wav)
        val anchored = AutoSyncEngine.correlate(video, wav, anchorsMs = listOf(-19_000L))   // clock says ~-19 s
        assertTrue("anchored offset=${anchored.offsetMs}", abs(anchored.offsetMs + 19_350) <= 10)
        // Two identical claps => PSR stays ~1 by design (honest ambiguity); the Pearson agreement carries the trust.
        assertTrue("anchored r=${anchored.correlation} psr=${anchored.confidence}", anchored.isTrusted)
        // Without the anchor both claps are equally plausible: either answer, but never a third one.
        assertTrue(abs(unanchored.offsetMs + 19_350) <= 10 || abs(unanchored.offsetMs + 17_000) <= 10)
    }

    @Test
    fun bestLagRangeOverloadMatchesFullSearchInsideRange() {
        val a = FloatArray(500).also { it[300] = 1f }
        val b = FloatArray(500).also { it[250] = 1f }
        val full = EnvelopeCrossCorrelator.bestLag(a, b, 100)
        val ranged = EnvelopeCrossCorrelator.bestLag(a, b, 30, 70)
        assertEquals(50, full.lagHops)
        assertEquals(50, ranged.lagHops)
        assertEquals(0, EnvelopeCrossCorrelator.bestLag(a, b, 70, 30).lagHops)   // empty range => no result
    }

    @Test
    fun sharpenSquaresAndSmoothsKeepingThePeakCentred() {
        val onset = FloatArray(50).also { it[20] = 1f; it[30] = 0.5f }
        val s = EnvelopeCrossCorrelator.sharpen(onset, smoothHops = 5)
        assertEquals(50, s.size)
        assertEquals(1f / 5, s[20], 1e-6f)              // spike spread over 5 hops
        assertEquals(1f / 5, s[18], 1e-6f)
        assertEquals(0f, s[17], 1e-6f)
        assertEquals(0.25f / 5, s[30], 1e-6f)           // squared: 0.5 -> 0.25
        assertEquals(0f, s[40], 1e-6f)
        assertArrayEquals(floatArrayOf(0f, 0.25f), EnvelopeCrossCorrelator.sharpen(floatArrayOf(0f, 0.5f), 1), 1e-6f)
    }

    @Test
    fun degenerateInputsDoNotCrash() {
        val empty = EnvelopeCrossCorrelator.bestLag(FloatArray(0), FloatArray(10), 5)
        assertEquals(0, empty.lagHops)
        assertEquals(0f, empty.confidence, 0f)
        val short = EnvelopeCrossCorrelator.bestLag(FloatArray(10) { 1f }, FloatArray(10) { 1f }, 5)
        assertEquals(0f, short.confidence, 0f)      // overlap below minimum
    }
}
