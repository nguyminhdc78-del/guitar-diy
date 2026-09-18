package iot.guitar.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class PcmLevelMeterTest {

    private fun pcm(vararg samples: Int): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s ->
            out[2 * i] = (s and 0xFF).toByte()
            out[2 * i + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    @Test
    fun silenceIsFloor() {
        val level = PcmLevelMeter.measure(ByteArray(1920))
        assertEquals(-96f, level.rmsDb, 0.01f)
        assertEquals(-96f, level.peakDb, 0.01f)
    }

    @Test
    fun fullScaleSquareIsZeroDb() {
        val samples = IntArray(960) { if (it % 2 == 0) 32767 else -32768 }
        val level = PcmLevelMeter.measure(pcm(*samples))
        assertEquals(0f, level.peakDb, 0.01f)
        assertEquals(0f, level.rmsDb, 0.01f)
    }

    @Test
    fun halfScaleIsMinusSixDb() {
        val samples = IntArray(100) { if (it % 2 == 0) 16384 else -16384 }
        val level = PcmLevelMeter.measure(pcm(*samples))
        assertEquals(-6.02f, level.peakDb, 0.05f)
        assertEquals(-6.02f, level.rmsDb, 0.05f)
    }

    @Test
    fun respectsOffsetAndLength() {
        val bytes = ByteArray(8 + 4)                         // 8-byte frame header + 2 samples
        bytes[8] = 0x00; bytes[9] = 0x40                     // 16384
        bytes[10] = 0x00; bytes[11] = 0x40.toByte()
        val level = PcmLevelMeter.measure(bytes, 8, 4)
        assertEquals(-6.02f, level.peakDb, 0.05f)
    }

    @Test
    fun emptyBufferIsFloor() {
        assertEquals(-96f, PcmLevelMeter.measure(ByteArray(0)).rmsDb, 0.01f)
    }
}
