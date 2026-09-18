package iot.guitar.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import iot.guitar.protocol.StreamProtocol

/**
 * Requests the ESP32 SoftAP network via [WifiNetworkSpecifier] (Android 10+ local-only
 * WiFi API, no location permission for an exact SSID) or, in manual mode, any WiFi
 * network the user already joined in Settings. Sockets must be created through
 * `network.socketFactory`; the process is never bound so mobile data keeps working.
 * The callback stays registered for the whole recording; [release] drops the network.
 */
class EspWifiConnector(context: Context) {

    interface Listener {
        fun onAvailable(network: Network)
        fun onLost()
        fun onUnavailable()
    }

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var callback: ConnectivityManager.NetworkCallback? = null

    /**
     * @param useSpecifier true = ask the system to join `uMIC` (shows a one-time dialog);
     *                     false = manual fallback for OEMs that block the specifier.
     */
    fun request(useSpecifier: Boolean, listener: Listener) {
        release()
        val builder = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (useSpecifier) {
            val specifier = WifiNetworkSpecifier.Builder()
                .setSsid(StreamProtocol.AP_SSID)
                .setWpa2Passphrase(StreamProtocol.AP_PASSPHRASE)
                .build()
            builder.setNetworkSpecifier(specifier)
        }
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = listener.onAvailable(network)
            override fun onLost(network: Network) = listener.onLost()
            override fun onUnavailable() = listener.onUnavailable()
        }
        callback = cb
        try {
            cm.requestNetwork(builder.build(), cb, REQUEST_TIMEOUT_MS)
        } catch (e: SecurityException) {
            callback = null
            listener.onUnavailable()
        }
    }

    fun release() {
        callback?.let {
            try {
                cm.unregisterNetworkCallback(it)
            } catch (_: IllegalArgumentException) {
                // already unregistered
            }
        }
        callback = null
    }

    companion object {
        private const val REQUEST_TIMEOUT_MS = 30_000
    }
}
