package io.github.rhythmcache.dioxamine.adb.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import io.github.rhythmcache.dioxamine.core.AppLogger

/**
 * Watches mDNS for Wireless debugging connect services (`_adb-tls-connect._tcp`) and reports each
 * resolved one as (deviceId, host, port). Used to find a known device's new port after Wireless
 * debugging was turned on again (e.g. after a reboot). Callbacks arrive on NsdManager's thread.
 */
class WirelessAutoConnector(context: Context) {
    private val nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var listener: NsdManager.DiscoveryListener? = null

    val isRunning: Boolean get() = listener != null

    fun start(onFound: (deviceId: String, host: String, port: Int) -> Unit) {
        if (listener != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                AppLogger.i(TAG, "Watching $serviceType for known devices")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                NsdResolver.resolve(
                    nsdManager = nsdManager,
                    serviceInfo = service,
                    onResolved = { resolved ->
                        val host = NsdAddressHelper.pickHosts(resolved).firstOrNull() ?: return@resolve
                        onFound(extractDeviceIdentity(resolved.serviceName), host, resolved.port)
                    },
                    onFailed = { code -> AppLogger.w(TAG, "Resolve failed for ${service.serviceName}: $code") }
                )
            }

            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                AppLogger.e(TAG, "Start discovery failed: $errorCode")
                listener = null
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        listener = l
        runCatching {
            nsdManager.discoverServices(AdbServiceType.TLS_CONNECT.serviceType, NsdManager.PROTOCOL_DNS_SD, l)
        }.onFailure {
            AppLogger.e(TAG, "discoverServices threw: ${it.message}")
            listener = null
        }
    }

    fun stop() {
        val l = listener ?: return
        listener = null
        runCatching { nsdManager.stopServiceDiscovery(l) }
    }

    private companion object {
        const val TAG = "WirelessAutoConnect"
    }
}
