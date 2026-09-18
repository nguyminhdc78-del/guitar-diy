package iot.guitar.ui

import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import iot.guitar.R
import iot.guitar.databinding.ActivityRecordInAppBinding
import iot.guitar.service.RecorderCommands
import iot.guitar.service.InAppMergeJob
import iot.guitar.service.RecordMode
import iot.guitar.service.RecorderPhase
import iot.guitar.service.RecorderState
import iot.guitar.service.RecorderStateHolder
import iot.guitar.ui.RecordInAppUiBinder.Local
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Mode 2: CameraX records video (phone mic = sync track only) while the service captures the
 * ESP32 WAV; at STOP the service merges both. State machine:
 * IDLE -REC-> ARMING -service RECORDING+firstFrame-> CAM_STARTING -Start-> RECORDING -STOP-> FINALIZING -Finalize-> merge -> IDLE
 */
class RecordInAppActivity : AppCompatActivity(), CameraRecorderController.Listener {

    private lateinit var binding: ActivityRecordInAppBinding
    private lateinit var ui: RecordInAppUiBinder
    private lateinit var camera: CameraRecorderController
    private lateinit var gestures: CameraGestures
    private val permissions = RecordPermissions(this) { cam, _, _ -> if (cam) bindCamera() else showCameraDenied() }

    private var local = Local.IDLE
    private var message: String? = null
    private var cameraReady = false
    private var cameraDenied = false
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var videoStartElapsedMs = 0L
    private var keepBothFiles = false
    private var finalizeTimeout: Job? = null
    private var seenButtonPresses = -1L
    private var ignoreLateFinalize = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityRecordInAppBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.overlayTop.applySystemBarInsetsAsPadding(bottom = false)
        binding.overlayBottom.applySystemBarInsetsAsPadding(top = false)
        ui = RecordInAppUiBinder(binding)
        camera = CameraRecorderController(this, binding.previewView, this)
        gestures = CameraGestures(this, binding.previewView, camera, binding.textZoom, binding.focusRing)

        binding.btnBack.setOnClickListener { finish() }
        onBackPressedDispatcher.addCallback(this) { if (local == Local.IDLE) finish() }   // never kill a take via back
        binding.btnRecord.setOnClickListener { if (local == Local.RECORDING) stopRecording() else startRecording() }
        binding.btnFlipCamera.setOnClickListener { flipCamera() }
        binding.btnOpenResult.setOnClickListener { if (cameraDenied) permissions.openAppSettings() else RecorderStateHolder.current.mergedVideoUri?.let { openMedia(it, "video/mp4") } }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { RecorderStateHolder.state.collect { onServiceState(it) } }
        }
        permissions.requestForInAppRecording()
        render()
    }

    override fun onStart() {
        super.onStart()
        seenButtonPresses = -1                       // presses received while hidden must not toggle on return
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)   // standby lives as long as this screen shows
        if (cameraReady && local == Local.IDLE) RecorderCommands.standby(this, binding.switchManualWifi.isChecked)
    }

    /** Hidden (home/switch/finish): drop standby so no wake/WiFi lock or dataSync budget is burnt in the background. */
    override fun onStop() { if (local == Local.IDLE) RecorderCommands.release(this); super.onStop() }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig); camera.updateTargetRotation(binding.previewView.display?.rotation ?: 0)
    }

    private fun bindCamera() {
        lifecycleScope.launch {
            try {
                camera.bind(lensFacing)
                gestures.attach()
                cameraReady = true
                cameraDenied = false
                if (!permissions.hasMic()) message = getString(R.string.perm_mic_denied_hint)
                // Connect to the mic right away so its button can start a take while the phone sits far away.
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    RecorderCommands.standby(this@RecordInAppActivity, binding.switchManualWifi.isChecked)
                }
            } catch (e: Exception) {
                message = getString(R.string.err_camera_bind, e.message ?: e.javaClass.simpleName)
            }
            render()
        }
    }

    private fun showCameraDenied() { cameraDenied = true; message = getString(R.string.perm_camera_denied); render() }

    private fun flipCamera() {
        if (local != Local.IDLE || RecorderStateHolder.current.isBusy) return
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        bindCamera()
    }

    private fun startRecording() {
        if (local != Local.IDLE || RecorderStateHolder.current.isBusy || !cameraReady) return
        local = Local.ARMING; message = null; keepBothFiles = false
        RecorderCommands.start(this, manualWifi = binding.switchManualWifi.isChecked, mode = RecordMode.IN_APP)
        render()
    }

    private fun stopRecording() {
        if (local != Local.RECORDING) return
        local = Local.FINALIZING
        camera.stop()
        finalizeTimeout = lifecycleScope.launch {
            delay(FINALIZE_TIMEOUT_MS)
            if (local == Local.FINALIZING) { ignoreLateFinalize = true; onFinalized(null, -1, IllegalStateException("timeout")) }
        }
        render()
    }

    /** Service-side transitions drive the camera: start it once the WAV has its first frame. */
    private fun onServiceState(state: RecorderState) {
        if (seenButtonPresses >= 0 && state.buttonPresses != seenButtonPresses) {
            // Physical button on the mic: toggles exactly like the on-screen REC/STOP.
            if (local == Local.RECORDING) stopRecording() else if (local == Local.IDLE) startRecording()
        }
        seenButtonPresses = state.buttonPresses
        when {
            local == Local.ARMING && state.phase == RecorderPhase.RECORDING && state.startedAtElapsedMs != null && state.fileName != null -> {
                local = Local.CAM_STARTING
                camera.start(InAppMergeJob.rawVideoName(state.fileName), withAudio = permissions.hasMic())
            }
            local == Local.ARMING && state.phase == RecorderPhase.ERROR -> {
                local = Local.IDLE
                message = state.errorMessage
            }
            (local == Local.CAM_STARTING || local == Local.RECORDING) && !state.isBusy -> {
                // ESP32 stream ended under us (WiFi lost, STOP from the notification): keep what we have.
                keepBothFiles = true
                local = Local.FINALIZING
                message = getString(R.string.err_mic_lost_kept_files)
                camera.stop()
            }
        }
        render(state)
    }

    override fun onStarted(videoStartElapsedMs: Long) {
        this.videoStartElapsedMs = videoStartElapsedMs
        if (local == Local.CAM_STARTING) local = Local.RECORDING
        render()
    }

    override fun onFinalized(uri: Uri?, error: Int, cause: Throwable?) {
        if (ignoreLateFinalize && local != Local.FINALIZING) { ignoreLateFinalize = false; return }   // already handled by the timeout
        finalizeTimeout?.cancel()
        val wasFinalizing = local == Local.FINALIZING
        local = Local.IDLE
        val state = RecorderStateHolder.current
        when {
            uri == null -> {
                RecorderCommands.stop(this)
                message = getString(R.string.err_camera_finalize, cause?.message ?: "code $error")
            }
            keepBothFiles || !wasFinalizing -> {
                // Stream lost, or CameraX ended on its own (e.g. app backgrounded): no merge, both files stay.
                RecorderCommands.stop(this)
                if (message == null) message = getString(R.string.saved_raw_and_wav)
            }
            state.wavUri == null || state.startedAtElapsedMs == null || state.fileName == null -> {
                RecorderCommands.stop(this)
                message = getString(R.string.inapp_missing_inputs)
            }
            else -> RecorderCommands.mergeInApp(this, InAppMergeJob.Request(
                uri, camera.videoStartEstimateMs.takeIf { it != Long.MAX_VALUE } ?: videoStartElapsedMs,
                state.wavUri, state.startedAtElapsedMs, state.fileName))
        }
        render()
    }

    private fun render(state: RecorderState = RecorderStateHolder.current) = ui.render(state, local, message, cameraReady, cameraDenied)

    companion object {
        private const val FINALIZE_TIMEOUT_MS = 10_000L
    }
}
