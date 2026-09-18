package iot.guitar.audio

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavFileWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun readAll(name: String): ByteArray = tmp.root.resolve(name).readBytes()

    @Test
    fun headerIsPatchedOnClose() {
        val file = tmp.newFile("a.wav")
        val writer = WavFileWriter(RandomAccessFile(file, "rw").channel, 48000, 1, 16)
        val pcm = ByteArray(1920) { it.toByte() }
        writer.write(pcm, 0, pcm.size)
        writer.write(pcm, 0, pcm.size)
        writer.close()

        val bytes = readAll("a.wav")
        assertEquals(44 + 3840, bytes.size)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals(36 + 3840, bb.getInt(4))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(bytes, 12, 4, Charsets.US_ASCII))
        assertEquals(16, bb.getInt(16))
        assertEquals(1, bb.getShort(20).toInt())          // PCM
        assertEquals(1, bb.getShort(22).toInt())          // mono
        assertEquals(48000, bb.getInt(24))
        assertEquals(96000, bb.getInt(28))                // byte rate
        assertEquals(2, bb.getShort(32).toInt())          // block align
        assertEquals(16, bb.getShort(34).toInt())
        assertEquals("data", String(bytes, 36, 4, Charsets.US_ASCII))
        assertEquals(3840, bb.getInt(40))
        assertEquals(pcm[7], bytes[44 + 7])
        assertEquals(pcm[7], bytes[44 + 1920 + 7])
        assertEquals(3840L, writer.dataBytes)
    }

    @Test
    fun writeSilenceAppendsZerosOfExactLength() {
        val file = tmp.newFile("b.wav")
        val writer = WavFileWriter(RandomAccessFile(file, "rw").channel)
        writer.write(byteArrayOf(1, 2, 3, 4), 0, 4)
        writer.writeSilence(10_000)        // > 4 KB chunk => multiple chunks
        writer.write(byteArrayOf(5, 6), 0, 2)
        writer.close()

        val bytes = readAll("b.wav")
        assertEquals(44 + 4 + 10_000 + 2, bytes.size)
        assertEquals(10_006, ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(40))
        for (i in 48 until 48 + 10_000) assertEquals("byte $i", 0, bytes[i].toInt())
        assertEquals(5, bytes[bytes.size - 2].toInt())
        assertEquals(6, bytes[bytes.size - 1].toInt())
    }

    @Test
    fun writesAfterCloseAreIgnored() {
        val file = tmp.newFile("c.wav")
        val writer = WavFileWriter(RandomAccessFile(file, "rw").channel)
        writer.close()
        writer.write(ByteArray(100), 0, 100)
        writer.writeSilence(100)
        assertEquals(0L, writer.dataBytes)
        assertEquals(44, readAll("c.wav").size)
    }

    @Test
    fun largeWritesCrossBufferBoundary() {
        val file = tmp.newFile("d.wav")
        val writer = WavFileWriter(RandomAccessFile(file, "rw").channel)
        val big = ByteArray(100_000) { (it % 251).toByte() }
        writer.write(big, 0, big.size)
        writer.close()
        val bytes = readAll("d.wav")
        assertEquals(44 + 100_000, bytes.size)
        for (i in big.indices step 997) assertEquals(big[i], bytes[44 + i])
    }
}
