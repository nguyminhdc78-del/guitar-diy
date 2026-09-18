package iot.guitar.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/** In-app mode contract: tight anchored window, no wide fallback, clock trusted when nothing correlates. */
class AutoSyncEngineTest {

    /** 100 Hz envelope of low noise with optional 20 ms bursts (same path as the real accumulator). */
    private fun envelope(seconds: Int, burstsSec: List<Double>, seed: Int): FloatArray {
        val rnd = Random(seed)
        val sr = 48000
        val acc = EnvelopeAccumulator(sr, 1)
        val chunk = ShortArray(4800)
        var produced = 0
        val bursts = burstsSec.map { (it * sr).toInt() }
        while (produced < seconds * sr) {
            for (i in chunk.indices) {
                val n = produced + i
                var v = rnd.nextInt(-200, 201)
                if (bursts.any { n in it until it + sr / 50 }) v += if (n % 2 == 0) 20000 else -20000
                chunk[i] = v.toShort()
            }
            acc.push(chunk, 0, chunk.size)
            produced += chunk.size
        }
        return acc.finish()
    }

    @Test
    fun tightWindowRefinesTheClockAnchor() {
        val wav = envelope(30, listOf(12.0), seed = 1)
        val video = envelope(20, listOf(3.0), seed = 2)      // true offset 3.0 - 12.0 = -9.0 s; clock says -8.8 s
        val r = AutoSyncEngine.correlate(video, wav, anchorsMs = listOf(-8_800L), windowMs = 500, wideFallback = false)
        assertTrue("offset=${r.offsetMs}", abs(r.offsetMs + 9_000) <= 10)
        assertTrue("confidence=${r.confidence}", r.confidence > EnvelopeCrossCorrelator.LOW_CONFIDENCE)
    }

    @Test
    fun noWideFallbackTrustsTheClockWhenNothingCorrelates() {
        val wav = envelope(30, emptyList(), seed = 3)
        val video = envelope(20, emptyList(), seed = 4)
        val r = AutoSyncEngine.correlate(video, wav, anchorsMs = listOf(-4_560L), windowMs = 500, wideFallback = false)
        assertEquals(-4_560L, r.offsetMs)
        assertTrue("confidence=${r.confidence}", r.confidence < EnvelopeCrossCorrelator.LOW_CONFIDENCE)
    }

    @Test
    fun noWideFallbackIgnoresAConfidentPeakOutsideTheWindow() {
        val wav = envelope(30, listOf(12.0), seed = 5)
        val video = envelope(20, listOf(3.0), seed = 6)      // true -9.0 s but the clock (wrongly) says -2.0 s
        val r = AutoSyncEngine.correlate(video, wav, anchorsMs = listOf(-2_000L), windowMs = 500, wideFallback = false)
        assertTrue("offset=${r.offsetMs}", abs(r.offsetMs + 2_000) <= 500)   // stays near the anchor, never jumps to -9 s
    }

    @Test
    fun wideFallbackStillFindsDistantPeaksForMode1() {
        val wav = envelope(30, listOf(12.0), seed = 7)
        val video = envelope(20, listOf(3.0), seed = 8)
        val r = AutoSyncEngine.correlate(video, wav, anchorsMs = listOf(-2_000L))   // defaults: ±2.5 s window + wide fallback
        assertTrue("offset=${r.offsetMs}", abs(r.offsetMs + 9_000) <= 10)
    }
}
