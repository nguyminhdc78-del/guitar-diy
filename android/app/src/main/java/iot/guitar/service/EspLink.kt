package iot.guitar.service

import android.content.Context
import android.net.Network
import android.os.Handler
import android.os.Looper
import iot.guitar.R
import iot.guitar.net.ControlChannelClient
import iot.guitar.net.EspWifiConnector
import iot.guitar.protocol.StreamProtocol

/**
 * Connection to the ESP32 that outlives a single take: the WiFi network plus the control
 * channel (button events in, LED state out). Audio [StreamSession]s are started on top of
 * [network] whenever a take begins, so the phone can sit connected and wait for the button.
 */
class EspLink(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onNetworkReady(network: Network)
        fun onControlConnected(connected: Boolean)
        fun onButtonPressed(count: Long)
        /** False while the ESP32 reports no usable mic signal (wiring fault). */
        fun onMicSignal(ok: Boolean)
        fun onLost(error: String)
    }

    private val connector = EspWifiConnector(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var control: ControlChannelClient? = null
    private var released = false

    /** Non-null once WiFi is up. */
    var network: Network? = null
        private set

    fun connect(manualWifi: Boolean) {
        connector.request(useSpecifier = !manualWifi, listener = object : EspWifiConnector.Listener {
            override fun onAvailable(network: Network) {
                mainHandler.post {
                    if (released || this@EspLink.network != null) return@post
                    this@EspLink.network = network
                    startControl(network)
                    listener.onNetworkReady(network)
                }
            }
            override fun onLost() { lost(context.getString(R.string.err_wifi_lost)) }
            override fun onUnavailable() { lost(context.getString(R.string.err_wifi_unavailable)) }
        })
    }

    private fun lost(error: String) {
        if (released) return
        mainHandler.post {
            network = null          // dead link: terminalPhase() must not report STANDBY
            control?.cancel()
            control = null
            listener.onControlConnected(false)
            listener.onLost(error)
        }
    }

    /** Tells the mic's LED whether the phone is recording. */
    fun sendRecording(on: Boolean) = control?.send(if (on) "REC 1" else "REC 0")

    fun release() {
        released = true
        control?.cancel()
        control = null
        connector.release()
        network = null
    }

    private fun startControl(network: Network) {
        val c = ControlChannelClient()
        control = c
        Thread({
            c.run(network.socketFactory, StreamProtocol.HOST, StreamProtocol.CONTROL_PORT, object : ControlChannelClient.Listener {
                override fun onConnected() { listener.onControlConnected(true) }
                override fun onButton(count: Long) { listener.onButtonPressed(count) }
                override fun onMicSignal(ok: Boolean) { listener.onMicSignal(ok) }
                override fun onDisconnected() { listener.onControlConnected(false) }
            })
        }, "umic-ctl").start()
    }
}
