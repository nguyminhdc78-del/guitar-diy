package iot.guitar.net

import iot.guitar.protocol.StreamProtocol
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * Blocking TCP receiver for stream protocol v1. Call [run] on a dedicated thread; call
 * [cancel] from any thread to stop it. Every frame is delivered synchronously through
 * [Listener.onFrame] with a reused buffer (PCM at offset [StreamProtocol.FRAME_HEADER_BYTES]);
 * listeners must consume it before returning.
 */
class PcmStreamClient {

    interface Listener {
        fun onHeader(sampleRate: Long)
        fun onFrame(seq: Long, timestampUs: Long, frame: ByteArray)
        /** Stream ended because of an I/O or protocol error (not because of [cancel]). */
        fun onError(error: IOException)
        /** Stream ended after [cancel]. */
        fun onClosed()
    }

    @Volatile private var cancelled = false
    @Volatile private var socket: Socket? = null

    fun run(factory: SocketFactory, host: String, port: Int, listener: Listener) {
        val frame = ByteArray(StreamProtocol.FRAME_BYTES)
        val header = ByteArray(StreamProtocol.HEADER_BYTES)
        var s: Socket? = null
        try {
            s = factory.createSocket().apply {
                receiveBufferSize = RECEIVE_BUFFER_BYTES
                keepAlive = true
                tcpNoDelay = true
                soTimeout = READ_TIMEOUT_MS
            }
            socket = s
            if (cancelled) throw CancelledException()
            s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            val input = DataInputStream(s.getInputStream())
            input.readFully(header)
            listener.onHeader(StreamProtocol.parseHeader(header))
            while (!cancelled) {
                input.readFully(frame, 0, StreamProtocol.FRAME_BYTES)
                val meta = StreamProtocol.parseFrameMeta(frame)
                listener.onFrame(meta.seq, meta.timestampUs, frame)
            }
            listener.onClosed()
        } catch (e: CancelledException) {
            listener.onClosed()
        } catch (e: IOException) {
            if (cancelled) listener.onClosed() else listener.onError(e)
        } catch (e: RuntimeException) {
            // Listener failures (MediaStore, notification) must not crash the process.
            if (cancelled) listener.onClosed() else listener.onError(IOException(e.message ?: e.javaClass.simpleName, e))
        } finally {
            socket = null
            try { s?.close() } catch (_: IOException) { }
        }
    }

    /** Stops [run]; safe to call from any thread and more than once. */
    fun cancel() {
        cancelled = true
        try { socket?.close() } catch (_: IOException) { }
    }

    private class CancelledException : IOException("cancelled")

    companion object {
        private const val RECEIVE_BUFFER_BYTES = 256 * 1024
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 5_000
    }
}
