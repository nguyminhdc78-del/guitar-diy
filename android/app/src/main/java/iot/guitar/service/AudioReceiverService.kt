package iot.guitar.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Network
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import iot.guitar.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground (dataSync) service owning the [EspLink] (WiFi + button channel), the current
 * [StreamSession] (ESP32 -> WAV) and the in-app [MergeCoordinator].
 * STANDBY keeps the link alive between takes so the mic's button can start the next one.
 * All mutable fields are main-thread confined; background callbacks hop via [mainHandler].
 */
class AudioReceiverService : Service() {

    private lateinit var locks: RecorderLocks
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val merge by lazy { MergeCoordinator(this, scope) }
    private var link: EspLink? = null
    private var linkMode = RecordMode.EXTERNAL
    private var recordWhenReady = false
    private val takeActive = AtomicBoolean(false)
    private var session: StreamSession? = null
    private var lastNotifiedMs = 0L
    private var pendingMerge: InAppMergeJob.Request? = null
    private var releaseRequested = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() { super.onCreate(); locks = RecorderLocks(this) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manual = intent?.getBooleanExtra(RecorderCommands.EXTRA_MANUAL_WIFI, false) ?: false
        when (intent?.action) {
            RecorderCommands.ACTION_STANDBY -> ensureLink(manual, RecordMode.IN_APP, record = false)
            RecorderCommands.ACTION_START -> ensureLink(manual,
                RecordMode.valueOf(intent.getStringExtra(RecorderCommands.EXTRA_MODE) ?: "EXTERNAL"), record = true)
            RecorderCommands.ACTION_STOP -> if (!stopTake(null) && !merge.isRunning) release()   // no take: leave standby
            RecorderCommands.ACTION_RELEASE -> release()
            RecorderCommands.ACTION_MERGE_IN_APP -> {
                promote()
                RecorderCommands.parseMergeRequest(intent)?.let(::handleMergeRequest) ?: release(keepState = true)
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    /** Brings up the link (once) and optionally starts a take as soon as the network is ready. */
    private fun ensureLink(manualWifi: Boolean, mode: RecordMode, record: Boolean) {
        releaseRequested = false
        if (record && (takeActive.get() || session != null || merge.isRunning)) { promote(); return }
        linkMode = mode
        recordWhenReady = record
        val existing = link
        if (existing == null) {
            RecorderStateHolder.set(RecorderState(phase = RecorderPhase.CONNECTING_WIFI, mode = mode))
            promote()
            locks.acquire()
            link = EspLink(this, linkListener).also { it.connect(manualWifi) }
        } else {
            promote()
            existing.network?.let { if (record) beginTake(it) }
        }
    }

    private val linkListener = object : EspLink.Listener {
        override fun onNetworkReady(network: Network) {
            if (recordWhenReady) { recordWhenReady = false; beginTake(network) }
            else RecorderStateHolder.update { it.copy(phase = RecorderPhase.STANDBY) }
        }
        override fun onControlConnected(connected: Boolean) {
            RecorderStateHolder.update { it.copy(controlConnected = connected, micSignal = if (connected) it.micSignal else true) }
        }
        override fun onMicSignal(ok: Boolean) { RecorderStateHolder.update { it.copy(micSignal = ok) } }
        // In-app: the activity toggles camera + take. External: a press while recording stops the WAV.
        override fun onButtonPressed(count: Long) =
            if (linkMode == RecordMode.EXTERNAL) { stopTake(null); Unit } else RecorderStateHolder.update { it.copy(buttonPresses = it.buttonPresses + 1) }
        override fun onLost(error: String) { mainHandler.post { if (!stopTake(error)) failStandby(error) } }
    }

    private fun beginTake(network: Network) {
        if (session != null || !takeActive.compareAndSet(false, true)) return   // running or still stopping
        lastNotifiedMs = 0
        RecorderStateHolder.update {
            RecorderState(phase = RecorderPhase.CONNECTING_TCP, mode = linkMode, controlConnected = it.controlConnected, buttonPresses = it.buttonPresses)
        }
        promote()
        session = StreamSession(this, network, sessionListener).also { it.start() }
    }

    private val sessionListener = object : StreamSession.Listener {
        override fun onPhase(phase: RecorderPhase) {
            RecorderStateHolder.update { it.copy(phase = phase) }
            if (phase == RecorderPhase.RECORDING) link?.sendRecording(true)
        }
        override fun onWavOpened(uri: Uri, displayName: String) {
            RecorderStateHolder.update { it.copy(fileName = displayName, wavUri = uri) }
            RecordingNotification.update(this@AudioReceiverService, RecorderStateHolder.current)
        }
        override fun onFirstFrame(elapsedMs: Long) { RecorderStateHolder.update { it.copy(startedAtElapsedMs = elapsedMs) } }
        override fun onLevel(rmsDb: Float, peakDb: Float, received: Long, dropped: Long, timelineMs: Long) {
            RecorderStateHolder.update {
                it.copy(rmsDb = rmsDb, peakDb = peakDb, framesReceived = received, framesDropped = dropped, elapsedMs = timelineMs)
            }
            if (timelineMs - lastNotifiedMs >= 1000) {
                lastNotifiedMs = timelineMs
                RecordingNotification.update(this@AudioReceiverService, RecorderStateHolder.current)
            }
        }
        override fun onEnded(error: String?) { stopTake(error) }
    }

    /** Idempotent end of the current take; the teardown runs on main. False when no take was running. */
    private fun stopTake(error: String?): Boolean {
        if (!takeActive.compareAndSet(true, false)) return false
        mainHandler.post {
            RecorderStateHolder.update { it.copy(phase = RecorderPhase.STOPPING) }
            val wavUri = session?.stop()
            session = null
            link?.sendRecording(false)
            val prev = RecorderStateHolder.current
            RecorderStateHolder.set(prev.copy(phase = terminalPhase(error), wavUri = wavUri,
                fileName = if (wavUri != null) prev.fileName else null, errorMessage = error))
            val req = pendingMerge
            pendingMerge = null
            when {
                req != null -> startMerge(req)
                releaseRequested || error != null || linkMode == RecordMode.EXTERNAL -> release()
            }
        }
        return true
    }

    private fun terminalPhase(error: String?) = when {
        error != null -> RecorderPhase.ERROR
        linkMode == RecordMode.IN_APP && link?.network != null && !releaseRequested -> RecorderPhase.STANDBY
        else -> RecorderPhase.IDLE
    }

    private fun failStandby(error: String) {
        RecorderStateHolder.update { it.copy(phase = RecorderPhase.ERROR, errorMessage = error, controlConnected = false) }
        if (!merge.isRunning) release(keepState = true)
    }

    private fun handleMergeRequest(req: InAppMergeJob.Request) {
        if (merge.isRunning) return
        // A take that is still (being) stopped hands over through pendingMerge once its WAV is closed.
        if (takeActive.get() || session != null) { pendingMerge = req; stopTake(null) } else startMerge(req)
    }

    private fun startMerge(req: InAppMergeJob.Request) {
        RecorderStateHolder.update { it.copy(phase = RecorderPhase.MERGING, mode = RecordMode.IN_APP, mergePercent = 0) }
        promote()
        locks.acquire()
        merge.start(req) { result ->
            RecorderStateHolder.update { it.copy(phase = if (result.error != null) RecorderPhase.ERROR else terminalPhase(null)) }
            if (releaseRequested || link?.network == null) release(keepState = true)
        }
    }

    /** Tears the link down and stops the service, unless a take or merge still needs it. */
    private fun release(keepState: Boolean = false) {
        releaseRequested = true
        if (merge.isRunning) return
        if (takeActive.get() || session != null) { stopTake(null); return }
        link?.release()
        link = null
        recordWhenReady = false
        if (!keepState) RecorderStateHolder.update { it.copy(phase = RecorderPhase.IDLE, controlConnected = false) }
        locks.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun promote() {
        startForeground(RecordingNotification.NOTIFICATION_ID, RecordingNotification.build(this, RecorderStateHolder.current),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    // Android 15+: dataSync services are limited to 6 h per day.
    override fun onTimeout(startId: Int, fgsType: Int) { stopTake(getString(R.string.err_service_timeout)); release() }

    override fun onDestroy() {
        session?.stop()
        link?.release()
        scope.cancel()
        super.onDestroy()
    }
}
