package iot.guitar.ui

import android.app.Activity
import android.net.Uri
import android.view.View
import android.view.WindowManager
import iot.guitar.R
import iot.guitar.databinding.ActivityMergeBinding
import iot.guitar.merge.MergeRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Drives the progress bar / result text / "open" button around one [MergeRunner.run]. */
class MergeProgressUi(
    private val activity: Activity,
    private val binding: ActivityMergeBinding,
    private val runner: MergeRunner,
) {
    fun start(scope: CoroutineScope, video: Uri, wavUri: Uri, offsetMs: Long, onDone: () -> Unit) {
        binding.progressMerge.visibility = View.VISIBLE
        binding.progressMerge.progress = 0
        binding.btnOpenResult.visibility = View.GONE
        binding.textResult.text = activity.getString(R.string.merge_progress, 0)
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        scope.launch {
            val outcome = runner.run(video, wavUri, offsetMs) { percent ->
                binding.progressMerge.setProgressCompat(percent, true)
                binding.textResult.text = activity.getString(R.string.merge_progress, percent)
            }
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            binding.progressMerge.visibility = View.GONE
            when (outcome) {
                is MergeRunner.Outcome.Success -> {
                    binding.textResult.text = activity.getString(R.string.merge_done, outcome.displayName)
                    binding.btnOpenResult.visibility = View.VISIBLE
                    binding.btnOpenResult.setOnClickListener { openVideo(outcome.uri) }
                }
                is MergeRunner.Outcome.Failure -> binding.textResult.text = outcome.message
            }
            onDone()
        }
    }

    private fun openVideo(uri: Uri) = activity.openMedia(uri, "video/mp4")
}
