package iot.guitar.service

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager

/**
 * Partial wake lock + low-latency WiFi lock held for the duration of a recording so the
 * stream keeps flowing with the screen off and the Camera app in the foreground.
 */
class RecorderLocks(context: Context) {
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    fun acquire() {
        release()
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "uMIC:rx").apply { acquire(MAX_LOCK_MS) }
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "uMIC:wifi").apply { acquire() }
    }

    fun release() {
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
        wakeLock = null
    }

    companion object {
        // Matches the Android 15 dataSync foreground-service budget (6 h) plus a margin.
        private const val MAX_LOCK_MS = 6L * 3600_000L + 60_000L
    }
}
