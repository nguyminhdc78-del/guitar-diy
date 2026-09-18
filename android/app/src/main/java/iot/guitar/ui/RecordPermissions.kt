package iot.guitar.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Runtime permissions shared by both record screens. Must be constructed during the
 * activity's initialisation (registers an ActivityResult contract).
 * [onResult] receives (camera, mic, notifications) grant states after each request.
 */
class RecordPermissions(
    private val activity: AppCompatActivity,
    private val onResult: (camera: Boolean, mic: Boolean, notifications: Boolean) -> Unit,
) {
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        onResult(hasCamera(), hasMic(), hasNotifications())
    }

    fun hasCamera() = granted(Manifest.permission.CAMERA)
    fun hasMic() = granted(Manifest.permission.RECORD_AUDIO)
    fun hasNotifications() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(Manifest.permission.POST_NOTIFICATIONS)

    /** Mode 1 only needs the notification permission (API 33+). */
    fun requestNotificationsIfNeeded() {
        if (!hasNotifications()) launcher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
    }

    /** Mode 2: camera (required), mic (optional, sync only) and notifications in one dialog flow. */
    fun requestForInAppRecording() {
        val wanted = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) wanted += Manifest.permission.POST_NOTIFICATIONS
        val missing = wanted.filter { !granted(it) }
        if (missing.isEmpty()) onResult(true, true, true) else launcher.launch(missing.toTypedArray())
    }

    fun openAppSettings() {
        activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", activity.packageName, null)))
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED
}
