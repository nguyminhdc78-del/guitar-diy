package iot.guitar.ui

import android.net.Uri
import android.os.Bundle
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import iot.guitar.R
import iot.guitar.audio.WavStore
import iot.guitar.databinding.ActivityMergeBinding
import iot.guitar.merge.MediaStoreVideoSaver
import iot.guitar.merge.MergeRunner
import iot.guitar.sync.AutoSyncEngine
import iot.guitar.sync.EnvelopeCrossCorrelator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Three-step merge screen: pick video -> WAV auto-picked by recording time and auto-sync
 * runs immediately (anchored on the wall-clock difference) -> preview (echo test) -> merge.
 * The offset is only fine-tuned with "earlier"/"later" buttons.
 */
class MergeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMergeBinding
    private lateinit var offset: OffsetControls
    private lateinit var preview: OffsetPreviewPlayer
    private val runner by lazy { MergeRunner(this) }
    private val wavStore by lazy { WavStore(contentResolver) }
    private val saver by lazy { MediaStoreVideoSaver(contentResolver) }
    private val syncEngine by lazy { AutoSyncEngine(this) }

    private var videoUri: Uri? = null
    private var videoStartCandidatesMs: List<Long> = emptyList()
    private var wav: WavStore.WavEntry? = null
    private var clapVideoMs: Long = 0
    private var busy = false
    private var previewRestart: Job? = null

    private val pickVideo = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@registerForActivityResult
        videoUri = uri
        clapVideoMs = 0
        binding.textVideo.text = uri.lastPathSegment ?: uri.toString()
        binding.textSync.text = ""
        preview.setVideo(uri)
        autoPickWavThenSync(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMergeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsetsAsPadding()
        binding.btnBack.setOnClickListener { finish() }

        offset = OffsetControls(binding.textOffset, mapOf(
            binding.btnNudgeM1000 to -1000L, binding.btnNudgeM100 to -100L, binding.btnNudgeM10 to -10L,
            binding.btnNudgeP10 to 10L, binding.btnNudgeP100 to 100L, binding.btnNudgeP1000 to 1000L,
        )) { restartPreviewIfPlaying() }
        preview = OffsetPreviewPlayer(binding.videoPreview) { runner.openWav(requireNotNull(wav).uri) }

        binding.btnPickVideo.setOnClickListener {
            pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
        }
        binding.btnPickWav.setOnClickListener { pickWavManually() }
        binding.btnAutoSync.setOnClickListener { autoSync() }
        binding.btnPreview.setOnClickListener { togglePreview() }
        binding.btnMerge.setOnClickListener { merge() }
    }

    override fun onPause() {
        preview.stop()
        super.onPause()
    }

    override fun onDestroy() {
        preview.release()
        super.onDestroy()
    }

    /** Picks the WAV that was recording when the video started; falls back to the manual list. */
    private fun autoPickWavThenSync(video: Uri) {
        lifecycleScope.launch {
            val entry = withContext(Dispatchers.IO) {
                videoStartCandidatesMs = saver.startTimeCandidatesMs(this@MergeActivity, video)
                videoStartCandidatesMs.firstNotNullOfOrNull { wavStore.findCovering(it / 1000) }
            }
            if (entry == null) {
                binding.textSync.text = getString(R.string.wav_not_found_for_video)
                pickWavManually()
                return@launch
            }
            wav = entry
            binding.textWav.text = getString(R.string.label_wav_auto, entry.displayName)
            autoSync()
        }
    }

    private fun pickWavManually() {
        lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) { wavStore.listRecordings() }
            WavFilePickerDialog.show(this@MergeActivity, entries) { entry ->
                wav = entry
                binding.textWav.text = getString(R.string.label_wav_manual, entry.displayName)
                autoSync()
            }
        }
    }

    private fun inputsReady(): Boolean {
        if (videoUri != null && wav != null) return true
        binding.textResult.text = getString(R.string.merge_need_inputs)
        return false
    }

    private fun autoSync() {
        if (busy || !inputsReady()) return
        val video = videoUri!!
        val entry = wav!!
        // Offset convention: video-timeline position of WAV sample 0 = wavStart - videoStart.
        val anchorsMs = videoStartCandidatesMs.map { entry.dateAddedSec * 1000 - it }
        setBusy(true)
        binding.textSync.text = getString(R.string.sync_running)
        lifecycleScope.launch {
            try {
                val result = syncEngine.compute(video, { runner.openWav(entry.uri) }, anchorsMs)
                offset.set(result.offsetMs)
                clapVideoMs = result.clapVideoMs
                binding.textSync.text = getString(when {
                    result.isTrusted -> R.string.sync_good
                    result.confidence >= UNSURE_CONFIDENCE -> R.string.sync_unsure
                    else -> R.string.sync_none
                })
            } catch (e: AutoSyncEngine.NoVideoAudioException) {
                binding.textSync.text = getString(R.string.sync_no_audio)
            } catch (e: Exception) {
                binding.textSync.text = getString(R.string.sync_failed, e.message ?: e.javaClass.simpleName)
            } finally {
                setBusy(false)
            }
        }
    }

    private fun togglePreview() {
        if (preview.isPlaying) {
            preview.stop()
            return
        }
        if (busy || !inputsReady()) return
        startPreview()
    }

    private fun startPreview() {
        val start = (clapVideoMs - PREVIEW_LEAD_MS).coerceAtLeast(0)
        val started = preview.play(start, offset.offsetMs) { binding.btnPreview.setText(R.string.btn_preview) }
        binding.btnPreview.setText(if (started) R.string.btn_preview_stop else R.string.btn_preview)
    }

    /** Nudges while previewing restart playback after a short debounce so the change is audible. */
    private fun restartPreviewIfPlaying() {
        if (!preview.isPlaying) return
        previewRestart?.cancel()
        previewRestart = lifecycleScope.launch {
            delay(PREVIEW_DEBOUNCE_MS)
            preview.stop()
            startPreview()
        }
    }

    private fun merge() {
        if (busy || !inputsReady()) return
        preview.stop()
        setBusy(true)
        MergeProgressUi(this, binding, runner).start(lifecycleScope, videoUri!!, wav!!.uri, offset.offsetMs) {
            setBusy(false)
        }
    }

    private fun setBusy(value: Boolean) {
        busy = value
        offset.setEnabled(!value)
        listOf(binding.btnPickVideo, binding.btnPickWav, binding.btnAutoSync, binding.btnPreview, binding.btnMerge)
            .forEach { it.isEnabled = !value }
    }

    companion object {
        private const val PREVIEW_LEAD_MS = 2_000L
        private const val PREVIEW_DEBOUNCE_MS = 300L
        private const val UNSURE_CONFIDENCE = 1.5f   // below this the peak is indistinguishable from noise
    }
}
