package iot.guitar.service

import android.content.Context
import iot.guitar.R
import iot.guitar.sync.EnvelopeCrossCorrelator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Runs the in-app merge inside the service scope, publishing progress to [RecorderStateHolder]
 * and the foreground notification, then hands the terminal state back via [onDone].
 */
class MergeCoordinator(private val context: Context, private val scope: CoroutineScope) {

    private var job: Job? = null

    val isRunning: Boolean
        get() = job != null

    fun start(req: InAppMergeJob.Request, onDone: (InAppMergeJob.Result) -> Unit) {
        if (job != null) return
        var lastPercent = -1
        job = scope.launch {
            val result = InAppMergeJob(context).run(req) { percent ->
                RecorderStateHolder.update { it.copy(mergePercent = percent) }
                if (percent - lastPercent >= 5) {
                    lastPercent = percent
                    RecordingNotification.update(context, RecorderStateHolder.current)
                }
            }
            RecorderStateHolder.update {
                it.copy(mergePercent = 100, mergedVideoUri = result.outputUri, fileName = result.outputName,
                    lastSyncConfidence = result.confidence, errorMessage = result.error)
            }
            RecordingNotification.showResult(context, resultText(result), result.outputUri)
            job = null
            onDone(result)
        }
    }

    private fun resultText(r: InAppMergeJob.Result): String = r.error ?: buildString {
        append(context.getString(when {
            r.confidence >= EnvelopeCrossCorrelator.LOW_CONFIDENCE -> R.string.inapp_sync_good
            r.confidence <= 0f -> R.string.inapp_sync_clock_only
            else -> R.string.inapp_sync_unsure_kept_raw
        }))
        append(' ')
        append(context.getString(R.string.inapp_merge_done, r.outputName))
    }
}
