package iot.guitar.net

import iot.guitar.protocol.StreamProtocol
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/**
 * Line-based control channel to the ESP32 (port [StreamProtocol.CONTROL_PORT]).
 * Incoming `BTN <n>` = physical button pressed, `MIC 0|1` = mic signal lost / back;
 * outgoing `REC 1|0` drives the mic's LED.
 * [run] blocks on a dedicated thread and reconnects until [cancel] so a button press works
 * even while the phone sits idle on the record screen. A PING every [HEARTBEAT_S] s with a
 * [READ_TIMEOUT_MS] read timeout detects half-open sockets after a WiFi blip on either side.
 */
class ControlChannelClient {

    interface Listener {
        fun onConnected()
        fun onButton(count: Long)
        fun onMicSignal(ok: Boolean)
        fun onDisconnected()
    }

    @Volatile private var cancelled = false
    @Volatile private var socket: Socket? = null
    @Volatile private var output: OutputStream? = null
    private val sender = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "umic-ctl-tx") }
    private var heartbeat: ScheduledFuture<*>? = null

    fun run(factory: SocketFactory, host: String, port: Int, listener: Listener) {
        while (!cancelled) {
            var s: Socket? = null
            try {
                s = factory.createSocket().apply { keepAlive = true; tcpNoDelay = true; soTimeout = READ_TIMEOUT_MS }
                socket = s
                s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                output = s.getOutputStream()
                heartbeat = sender.scheduleAtFixedRate({ send("PING") }, HEARTBEAT_S, HEARTBEAT_S, TimeUnit.SECONDS)
                listener.onConnected()
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.US_ASCII))
                while (!cancelled) {
                    val line = reader.readLine() ?: break
                    when {
                        line.startsWith("BTN ") -> listener.onButton(line.substring(4).trim().toLongOrNull() ?: 0L)
                        line.startsWith("MIC ") -> listener.onMicSignal(line.substring(4).trim() != "0")
                    }
                }
            } catch (_: IOException) {
                // fall through to reconnect
            } finally {
                heartbeat?.cancel(false)
                heartbeat = null
                output = null
                socket = null
                try { s?.close() } catch (_: IOException) { }
                listener.onDisconnected()
            }
            if (!cancelled) Thread.sleep(RECONNECT_DELAY_MS)
        }
    }

    /** Queues one line for sending (own thread, never blocks the caller); dropped while disconnected. */
    fun send(line: String) {
        if (cancelled) return
        sender.execute {
            val out = output ?: return@execute
            try {
                out.write((line + "\n").toByteArray(Charsets.US_ASCII))
                out.flush()
            } catch (_: IOException) {
                // The reader thread will notice and reconnect.
            }
        }
    }

    fun cancel() {
        cancelled = true
        sender.shutdown()
        try { socket?.close() } catch (_: IOException) { }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val HEARTBEAT_S = 5L
        private const val RECONNECT_DELAY_MS = 1_500L
    }
}
