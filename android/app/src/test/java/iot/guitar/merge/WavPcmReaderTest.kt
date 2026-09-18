package iot.guitar.merge

import iot.guitar.audio.WavFileWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

class WavPcmReaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Writes [samples] int16 values (sample i = i) through WavFileWriter and returns the file. */
    private fun writeWav(samples: Int, sampleRate: Int = 48000): File {
        val file = tmp.newFile()
        val writer = WavFileWriter(RandomAccessFile(file, "rw").channel, sampleRate, 1, 16)
        val pcm = ByteArray(samples * 2)
        for (i in 0 until samples) {
            pcm[2 * i] = (i and 0xFF).toByte()
            pcm[2 * i + 1] = ((i shr 8) and 0xFF).toByte()
        }
        writer.write(pcm, 0, pcm.size)
        writer.close()
        return file
    }

    private fun sampleAt(buf: ByteArray, index: Int): Int =
        (buf[2 * index].toInt() and 0xFF) or (buf[2 * index + 1].toInt() shl 8)

    @Test
    fun roundTripWithWriter() {
        val reader = WavPcmReader.open(writeWav(1000))
        assertEquals(48000, reader.sampleRate)
        assertEquals(1, reader.channels)
        assertEquals(16, reader.bitsPerSample)
        assertEquals(44L, reader.dataOffset)
        assertEquals(2000L, reader.dataBytes)
        assertEquals(1000L, reader.totalSamples)
        val buf = ByteArray(2000)
        assertEquals(2000, reader.read(buf))
        assertEquals(0, sampleAt(buf, 0))
        assertEquals(999, sampleAt(buf, 999))
        assertEquals(-1, reader.read(buf))
        reader.close()
    }

    @Test
    fun seekAndPartialReads() {
        val reader = WavPcmReader.open(writeWav(1000))
        reader.seekToSample(990)
        val buf = ByteArray(100)
        assertEquals(20, reader.read(buf))           // only 10 samples left
        assertEquals(990, sampleAt(buf, 0))
        assertEquals(999, sampleAt(buf, 9))
        assertEquals(1000L, reader.positionSamples)
        assertEquals(-1, reader.read(buf))
        reader.seekToSample(5000)                    // clamped to end
        assertEquals(1000L, reader.positionSamples)
        reader.seekToSample(-3)                      // clamped to start
        assertEquals(0L, reader.positionSamples)
        assertEquals(6, reader.read(buf, 0, 7))      // odd length rounds down to whole frames
        assertEquals(2, sampleAt(buf, 2))
        reader.close()
    }

    @Test
    fun skipsUnknownChunksBeforeData() {
        val file = tmp.newFile()
        val header = byteArrayOf(
            'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(), 0, 0, 0, 0,
            'W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte(),
            'L'.code.toByte(), 'I'.code.toByte(), 'S'.code.toByte(), 'T'.code.toByte(), 3, 0, 0, 0, 1, 2, 3, 0, // odd size => pad byte
            'f'.code.toByte(), 'm'.code.toByte(), 't'.code.toByte(), ' '.code.toByte(), 16, 0, 0, 0,
            1, 0, 2, 0, 0x44, 0xAC.toByte(), 0, 0, 0x10, 0xB1.toByte(), 2, 0, 4, 0, 16, 0,   // stereo 44100
            'd'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte(), 8, 0, 0, 0,
            1, 0, 2, 0, 3, 0, 4, 0,
        )
        file.writeBytes(header)
        val reader = WavPcmReader.open(file)
        assertEquals(44100, reader.sampleRate)
        assertEquals(2, reader.channels)
        assertEquals(4, reader.bytesPerFrame)
        assertEquals(2L, reader.totalSamples)
        val buf = ByteArray(8)
        assertEquals(8, reader.read(buf))
        assertEquals(3, sampleAt(buf, 2))
        reader.close()
    }

    @Test
    fun rejectsNonWav() {
        val file = tmp.newFile()
        file.writeBytes(ByteArray(100) { 7 })
        assertThrows(IOException::class.java) { WavPcmReader.open(file) }
    }
}
