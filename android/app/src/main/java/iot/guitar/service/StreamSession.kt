package iot.guitar.service

import android.content.Context
import android.net.Network
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import iot.guitar.R
import iot.guitar.audio.PcmLevelMeter
import iot.guitar.audio.WavFileWriter
import iot.guitar.audio.WavStore
import iot.guitar.net.PcmStreamClient
import iot.guitar.protocol.FrameGapTracker
import iot.guitar.protocol.ProtocolException
import iot.guitar.protocol.StreamProtocol
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One ESP32 capture session on an already connected [Network]: TCP audio stream -> WAV in
 * Music/uMIC with gap-to-silence timeline. Owned by [AudioReceiverService]; callbacks may
 * arrive on background threads.
 */
class StreamSession(private val context: Context, private val network: Network, private val listener: Listener) {

    interface Listener {
        fun onPhase(phase: RecorderPhase)
        fun onWavOpened(uri: Uri, displayName: String)
        /** First frame written; [elapsedMs] = SystemClock.elapsedRealtime() (in-app sync anchor). */
        fun onFirstFrame(elapsedMs: Long)
        fun onLevel(rmsDb: Float, peakDb: Float, received: Long, dropped: Long, timelineMs: Long)
        /** Session ended on its own (WiFi lost, stream error, remote close). [error] null = clean close. */
        fun onEnded(error: String?)
    }

    private val wavStore = WavStore(context.contentResolver)
    private val stopped = AtomicBoolean(false)
    private val wavLock = Any()
    private var client: PcmStreamClient? = null
    private var writer: WavFileWriter? = null
    private var wavUri: Uri? = null
    private val gapTracker = FrameGapTracker()

    val framesReceived: Long get() = gapTracker.totalAccepted
    val framesDropped: Long get() = gapTracker.totalDropped

    fun start() {
        listener.onPhase(RecorderPhase.CONNECTING_TCP)
        val c = PcmStreamClient().also { client = it }
        Thread({ c.run(network.socketFactory, StreamProtocol.HOST, StreamProtocol.PORT, streamListener) }, "umic-rx").start()
    }

    /** Closes everything and publishes the WAV (or discards it when nothing was received). */
    fun stop(): Uri? {
        stopped.set(true)
        client?.cancel()
        client = null
        synchronized(wavLock) {
            try {
                writer?.close()
            } catch (e: IOException) {
                Log.w(TAG, "WAV close failed", e)
            }
            writer = null
            val uri = wavUri
            wavUri = null
            if (uri != null) {
                if (gapTracker.totalAccepted > 0) wavStore.finalize(uri) else wavStore.discard(uri)
            }
            return if (gapTracker.totalAccepted > 0) uri else null
        }
    }

    private val streamListener = object : PcmStreamClient.Listener {
        override fun onHeader(sampleRate: Long) {
            if (sampleRate != StreamProtocol.SAMPLE_RATE.toLong()) {
                throw ProtocolException(context.getString(R.string.err_bad_sample_rate, sampleRate))
            }
            val name = openWav()
            listener.onWavOpened(requireNotNull(wavUri), name)
            listener.onPhase(RecorderPhase.RECORDING)
        }
        override fun onFrame(seq: Long, timestampUs: Long, frame: ByteArray) = handleFrame(seq, frame)
        override fun onError(error: IOException) {
            end(context.getString(R.string.err_stream, error.message ?: error.javaClass.simpleName))
        }
        override fun onClosed() { end(null) }
    }

    private fun openWav(): String = synchronized(wavLock) {
        if (stopped.get()) throw IOException("stopped before header")
        val name = "umic-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".wav"
        val (uri, w) = wavStore.createPendingWriter(name)
        wavUri = uri
        writer = w
        name
    }

    private fun handleFrame(seq: Long, frame: ByteArray) {
        val w = writer ?: return
        val first = gapTracker.totalAccepted == 0L
        val gap = gapTracker.onFrame(seq)
        if (gap < 0) return
        // Sample 0 of this frame was captured one frame (20 ms) before its last byte arrived.
        if (first) listener.onFirstFrame(SystemClock.elapsedRealtime() - StreamProtocol.FRAME_MS)
        if (gap > 0) w.writeSilence(gap.toLong() * StreamProtocol.FRAME_PCM_BYTES)
        w.write(frame, StreamProtocol.FRAME_HEADER_BYTES, StreamProtocol.FRAME_PCM_BYTES)
        if (gapTracker.totalAccepted % LEVEL_EVERY_FRAMES != 0L) return
        val level = PcmLevelMeter.measure(frame, StreamProtocol.FRAME_HEADER_BYTES, StreamProtocol.FRAME_PCM_BYTES)
        val timelineMs = (gapTracker.totalAccepted + gapTracker.totalDropped) * StreamProtocol.FRAME_MS
        listener.onLevel(level.rmsDb, level.peakDb, gapTracker.totalAccepted, gapTracker.totalDropped, timelineMs)
    }

    private fun end(error: String?) {
        if (stopped.get()) return
        listener.onEnded(error)
    }

    companion object {
        private const val TAG = "StreamSession"
        private const val LEVEL_EVERY_FRAMES = 5L   // 10 Hz meter updates
    }
}
