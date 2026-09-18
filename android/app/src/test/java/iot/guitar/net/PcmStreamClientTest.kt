package iot.guitar.net

import iot.guitar.protocol.StreamProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

class PcmStreamClientTest {

    private fun header(magic: String = "uMIC"): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .put(magic.toByteArray(Charsets.US_ASCII)).putInt(48000).array()

    private fun frame(seq: Int, ts: Int, fill: Byte): ByteArray {
        val bb = ByteBuffer.allocate(StreamProtocol.FRAME_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(seq).putInt(ts)
        repeat(StreamProtocol.FRAME_PCM_BYTES) { bb.put(fill) }
        return bb.array()
    }

    private class Recorder : PcmStreamClient.Listener {
        var sampleRate = -1L
        val seqs = mutableListOf<Long>()
        val timestamps = mutableListOf<Long>()
        val firstPcmBytes = mutableListOf<Byte>()
        var error: IOException? = null
        var closed = false
        val done = CountDownLatch(1)
        override fun onHeader(sampleRate: Long) { this.sampleRate = sampleRate }
        override fun onFrame(seq: Long, timestampUs: Long, frame: ByteArray) {
            seqs += seq; timestamps += timestampUs; firstPcmBytes += frame[StreamProtocol.FRAME_HEADER_BYTES]
        }
        override fun onError(error: IOException) { this.error = error; done.countDown() }
        override fun onClosed() { closed = true; done.countDown() }
    }

    @Test
    fun receivesHeaderAndFramesWithGapThenServerClose() {
        val server = ServerSocket(0)
        val serverThread = Thread {
            server.accept().use { s ->
                val out = s.getOutputStream()
                out.write(header())
                listOf(0 to 0, 1 to 20_000, 2 to 40_000, 5 to 100_000, 6 to 120_000).forEach { (seq, ts) ->
                    out.write(frame(seq, ts, seq.toByte()))
                }
                out.flush()
            }
        }.apply { start() }

        val rec = Recorder()
        val client = PcmStreamClient()
        client.run(SocketFactory.getDefault(), "127.0.0.1", server.localPort, rec)
        serverThread.join(5000)
        server.close()

        assertEquals(48000L, rec.sampleRate)
        assertEquals(listOf(0L, 1L, 2L, 5L, 6L), rec.seqs)
        assertEquals(listOf(0L, 20_000L, 40_000L, 100_000L, 120_000L), rec.timestamps)
        assertEquals(listOf<Byte>(0, 1, 2, 5, 6), rec.firstPcmBytes)
        // Server closed mid-stream without cancel(): reported as an error (EOF), not a clean close.
        assertNotNull(rec.error)
        assertTrue(!rec.closed)
    }

    @Test
    fun badMagicIsReportedAsError() {
        val server = ServerSocket(0)
        Thread { server.accept().use { it.getOutputStream().write(header(magic = "XXXX")) } }.start()
        val rec = Recorder()
        PcmStreamClient().run(SocketFactory.getDefault(), "127.0.0.1", server.localPort, rec)
        server.close()
        assertNotNull(rec.error)
        assertTrue(rec.error!!.message!!.contains("magic"))
        assertEquals(-1L, rec.sampleRate)
    }

    @Test
    fun cancelStopsRunAndReportsClosed() {
        val server = ServerSocket(0)
        val hold = CountDownLatch(1)
        Thread {
            server.accept().use { s ->
                s.getOutputStream().write(header())
                s.getOutputStream().write(frame(0, 0, 1))
                s.getOutputStream().flush()
                hold.await(5, TimeUnit.SECONDS)   // keep the socket open until the client cancels
            }
        }.start()

        val rec = Recorder()
        val client = PcmStreamClient()
        val runner = Thread { client.run(SocketFactory.getDefault(), "127.0.0.1", server.localPort, rec) }
        runner.start()
        while (rec.seqs.isEmpty()) Thread.sleep(5)
        client.cancel()
        assertTrue(rec.done.await(5, TimeUnit.SECONDS))
        hold.countDown()
        runner.join(5000)
        server.close()

        assertTrue(rec.closed)
        assertNull(rec.error)
        assertEquals(listOf(0L), rec.seqs)
    }

    @Test
    fun connectionRefusedIsError() {
        val server = ServerSocket(0)
        val port = server.localPort
        server.close()
        val rec = Recorder()
        PcmStreamClient().run(SocketFactory.getDefault(), "127.0.0.1", port, rec)
        assertNotNull(rec.error)
    }
}
