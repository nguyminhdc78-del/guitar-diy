package iot.guitar.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat

/** Intent builders/parsers for [AudioReceiverService]; every start is guarded against background-start rules. */
object RecorderCommands {
    const val ACTION_STANDBY = "iot.guitar.action.STANDBY"
    const val ACTION_START = "iot.guitar.action.START"
    const val ACTION_STOP = "iot.guitar.action.STOP"
    const val ACTION_RELEASE = "iot.guitar.action.RELEASE"
    const val ACTION_MERGE_IN_APP = "iot.guitar.action.MERGE_IN_APP"
    const val EXTRA_MANUAL_WIFI = "manual_wifi"
    const val EXTRA_MODE = "mode"
    private const val EXTRA_VIDEO_URI = "video_uri"
    private const val EXTRA_VIDEO_START = "video_start_elapsed_ms"
    private const val EXTRA_WAV_URI = "wav_uri"
    private const val EXTRA_WAV_START = "wav_start_elapsed_ms"
    private const val EXTRA_WAV_NAME = "wav_name"
    private const val TAG = "RecorderCommands"

    fun standby(context: Context, manualWifi: Boolean) =
        foreground(context, intent(context, ACTION_STANDBY).putExtra(EXTRA_MANUAL_WIFI, manualWifi))

    fun start(context: Context, manualWifi: Boolean, mode: RecordMode) =
        foreground(context, intent(context, ACTION_START).putExtra(EXTRA_MANUAL_WIFI, manualWifi).putExtra(EXTRA_MODE, mode.name))

    fun stop(context: Context) = plain(context, intent(context, ACTION_STOP))

    fun release(context: Context) = plain(context, intent(context, ACTION_RELEASE))

    fun mergeInApp(context: Context, req: InAppMergeJob.Request) = foreground(context,
        intent(context, ACTION_MERGE_IN_APP).putExtra(EXTRA_VIDEO_URI, req.videoUri)
            .putExtra(EXTRA_VIDEO_START, req.videoStartElapsedMs).putExtra(EXTRA_WAV_URI, req.wavUri)
            .putExtra(EXTRA_WAV_START, req.wavStartElapsedMs).putExtra(EXTRA_WAV_NAME, req.wavName))

    fun parseMergeRequest(intent: Intent): InAppMergeJob.Request? {
        val video = IntentCompat.getParcelableExtra(intent, EXTRA_VIDEO_URI, Uri::class.java) ?: return null
        val wav = IntentCompat.getParcelableExtra(intent, EXTRA_WAV_URI, Uri::class.java) ?: return null
        val name = intent.getStringExtra(EXTRA_WAV_NAME) ?: return null
        return InAppMergeJob.Request(video, intent.getLongExtra(EXTRA_VIDEO_START, 0L), wav, intent.getLongExtra(EXTRA_WAV_START, 0L), name)
    }

    private fun intent(context: Context, action: String) = Intent(context, AudioReceiverService::class.java).setAction(action)

    private fun foreground(context: Context, intent: Intent) {
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {   // ForegroundServiceStartNotAllowedException / IllegalStateException from background
            Log.w(TAG, "cannot start service for ${intent.action}", e)
        }
    }

    private fun plain(context: Context, intent: Intent) {
        try {
            context.startService(intent)
        } catch (e: Exception) {
            Log.w(TAG, "cannot start service for ${intent.action}", e)
        }
    }
}
