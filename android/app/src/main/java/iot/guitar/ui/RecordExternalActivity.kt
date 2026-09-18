package iot.guitar.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import iot.guitar.R
import iot.guitar.databinding.ActivityRecordExternalBinding
import iot.guitar.service.RecorderCommands
import iot.guitar.service.RecordMode
import iot.guitar.service.RecorderPhase
import iot.guitar.service.RecorderState
import iot.guitar.service.RecorderStateHolder
import iot.guitar.service.RecordingNotification
import kotlinx.coroutines.launch

/** Mode 1: REC/STOP the ESP32 WAV recording while the user films with the stock Camera app. */
class RecordExternalActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecordExternalBinding
    private val permissions = RecordPermissions(this) { _, _, notifications ->
        if (!notifications) Toast.makeText(this, R.string.notif_permission_denied, Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityRecordExternalBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsetsAsPadding()

        binding.btnBack.setOnClickListener { finish() }
        binding.btnRecord.setOnClickListener { toggleRecording() }
        binding.btnMerge.setOnClickListener { startActivity(Intent(this, MergeActivity::class.java)) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RecorderStateHolder.state.collect { render(it) }
            }
        }
        permissions.requestNotificationsIfNeeded()
    }

    private fun toggleRecording() {
        if (RecorderStateHolder.current.isBusy) {
            RecorderCommands.stop(this)
        } else {
            RecorderCommands.start(this, manualWifi = binding.switchManualWifi.isChecked, mode = RecordMode.EXTERNAL)
        }
    }

    private fun render(state: RecorderState) {
        val busy = state.isBusy
        binding.btnRecord.setText(if (busy) R.string.btn_stop else R.string.btn_record)
        binding.btnRecord.setIconResource(if (busy) R.drawable.ic_stop else R.drawable.ic_rec)
        binding.btnRecord.backgroundTintList = getColorStateList(if (busy) R.color.umic_red else R.color.umic_amber)
        binding.btnRecord.setTextColor(getColor(if (busy) R.color.umic_text else R.color.umic_on_amber))
        binding.btnRecord.iconTint = getColorStateList(if (busy) R.color.umic_text else R.color.umic_on_amber)
        binding.switchManualWifi.isEnabled = !busy
        binding.btnMerge.isEnabled = !busy
        binding.chipStatus.bindRecorderState(state)

        binding.textStatus.text = if (!state.micSignal && state.isConnected) getString(R.string.warn_mic_no_signal) else when (state.phase) {
            RecorderPhase.IDLE -> getString(R.string.status_idle)
            RecorderPhase.CONNECTING_WIFI -> getString(R.string.status_connecting_wifi)
            RecorderPhase.STANDBY, RecorderPhase.CONNECTING_TCP -> getString(R.string.status_connecting_tcp)
            RecorderPhase.RECORDING -> getString(R.string.status_recording)
            RecorderPhase.STOPPING -> getString(R.string.status_stopping)
            RecorderPhase.MERGING -> getString(R.string.status_merging)
            RecorderPhase.ERROR -> getString(R.string.status_error, state.errorMessage ?: "?")
        }
        if (state.phase == RecorderPhase.RECORDING) binding.levelMeter.setLevel(state.rmsDb, state.peakDb)
        else binding.levelMeter.reset()
        binding.textLevel.text = getString(R.string.label_level, state.rmsDb, state.peakDb)
        binding.textElapsed.text = RecordingNotification.formatElapsed(state.elapsedMs)
        binding.textDrops.text = getString(R.string.label_drops, state.framesDropped)
        binding.textFile.text = state.fileName?.let { getString(R.string.label_file, it) } ?: ""
    }
}
