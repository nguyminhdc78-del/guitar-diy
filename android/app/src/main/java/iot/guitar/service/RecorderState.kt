package iot.guitar.service

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** EXTERNAL = film with the stock Camera app and merge later; IN_APP = CameraX inside uMIC, auto-merge at stop. */
enum class RecordMode { EXTERNAL, IN_APP }

/** STANDBY = connected to the ESP32 (WiFi + control channel), not recording: the button can start a take. */
enum class RecorderPhase { IDLE, CONNECTING_WIFI, STANDBY, CONNECTING_TCP, RECORDING, STOPPING, MERGING, ERROR }

/** Snapshot of the recording service, observed by the activities, the Home chip and the notification. */
data class RecorderState(
    val phase: RecorderPhase = RecorderPhase.IDLE,
    val mode: RecordMode = RecordMode.EXTERNAL,
    val rmsDb: Float = -96f,
    val peakDb: Float = -96f,
    val framesReceived: Long = 0,
    val framesDropped: Long = 0,
    val elapsedMs: Long = 0,
    val fileName: String? = null,
    val wavUri: Uri? = null,
    /** SystemClock.elapsedRealtime() when the first WAV frame was written (in-app sync anchor). */
    val startedAtElapsedMs: Long? = null,
    val mergePercent: Int = 0,
    val mergedVideoUri: Uri? = null,
    val lastSyncConfidence: Float = 0f,
    /** Incremented for every physical button press received; observers toggle on change. */
    val buttonPresses: Long = 0,
    /** True while the control channel to the ESP32 is open. */
    val controlConnected: Boolean = false,
    /** False while the ESP32 reports a dead/floating INMP441 (see firmware mic-signal-monitor). */
    val micSignal: Boolean = true,
    val errorMessage: String? = null,
) {
    /** A take (or its merge) is in progress; STANDBY is connected but idle. */
    val isBusy: Boolean
        get() = phase == RecorderPhase.CONNECTING_TCP || phase == RecorderPhase.RECORDING ||
            phase == RecorderPhase.STOPPING || phase == RecorderPhase.MERGING || phase == RecorderPhase.CONNECTING_WIFI
    val isConnected: Boolean
        get() = phase == RecorderPhase.STANDBY || phase == RecorderPhase.CONNECTING_TCP || phase == RecorderPhase.RECORDING
}

/** Process-wide holder so the service (no binder) and the UI share one StateFlow. */
object RecorderStateHolder {
    private val _state = MutableStateFlow(RecorderState())
    val state: StateFlow<RecorderState> = _state

    fun update(transform: (RecorderState) -> RecorderState) = _state.update(transform)

    fun set(state: RecorderState) {
        _state.value = state
    }

    val current: RecorderState
        get() = _state.value
}
