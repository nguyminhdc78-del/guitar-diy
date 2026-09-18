package iot.guitar.audio

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * RMS + peak level of a PCM16 little-endian buffer in dBFS (floor -96 dB).
 * Same formulas as the firmware serial VU meter so both meters agree.
 */
object PcmLevelMeter {
    const val DB_FLOOR = -96f

    data class Level(val rmsDb: Float, val peakDb: Float)

    fun measure(pcm: ByteArray, offset: Int = 0, length: Int = pcm.size - offset): Level {
        val samples = length / 2
        if (samples <= 0) return Level(DB_FLOOR, DB_FLOOR)
        var sumSq = 0.0
        var peak = 0
        var i = offset
        val end = offset + samples * 2
        while (i < end) {
            val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
            val a = if (s < 0) -s else s
            if (a > peak) peak = a
            sumSq += s.toDouble() * s.toDouble()
            i += 2
        }
        val rms = sqrt(sumSq / samples)
        return Level(toDb(rms.toFloat()), toDb(peak.toFloat()))
    }

    /** dBFS of a linear 0..32768 value. */
    fun toDb(linear: Float): Float {
        if (linear <= 0f) return DB_FLOOR
        val db = 20f * log10(linear / 32768f)
        return if (db < DB_FLOOR) DB_FLOOR else db
    }
}
