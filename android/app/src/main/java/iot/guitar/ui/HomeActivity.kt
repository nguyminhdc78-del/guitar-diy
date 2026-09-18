package iot.guitar.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import iot.guitar.R
import iot.guitar.audio.RecentRecordings
import iot.guitar.databinding.ActivityHomeBinding
import iot.guitar.service.RecordMode
import iot.guitar.service.RecorderStateHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Launcher screen: two recording modes, the merge tool, and the latest recordings. */
class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var recent: RecentRecordingsView

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsetsAsPadding()
        recent = RecentRecordingsView(binding.listRecent, binding.textRecentEmpty)

        binding.modeInApp.icon.setImageResource(R.drawable.ic_videocam)
        binding.modeInApp.title.setText(R.string.mode_in_app_title)
        binding.modeInApp.caption.setText(R.string.mode_in_app_caption)
        binding.modeExternal.icon.setImageResource(R.drawable.ic_smartphone)
        binding.modeExternal.title.setText(R.string.mode_external_title)
        binding.modeExternal.caption.setText(R.string.mode_external_caption)

        binding.cardRecordInApp.setOnClickListener { startActivity(Intent(this, RecordInAppActivity::class.java)) }
        binding.cardRecordExternal.setOnClickListener { startActivity(Intent(this, RecordExternalActivity::class.java)) }
        binding.cardMerge.setOnClickListener { startActivity(Intent(this, MergeActivity::class.java)) }
        binding.chipRecorderStatus.setOnClickListener { openActiveRecorder() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RecorderStateHolder.state.collect { binding.chipRecorderStatus.bindRecorderState(it, showIdle = false) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { RecentRecordings.load(contentResolver) }
            recent.bind(items)
        }
    }

    private fun openActiveRecorder() {
        val target = if (RecorderStateHolder.current.mode == RecordMode.IN_APP) RecordInAppActivity::class.java
        else RecordExternalActivity::class.java
        startActivity(Intent(this, target))
    }
}
