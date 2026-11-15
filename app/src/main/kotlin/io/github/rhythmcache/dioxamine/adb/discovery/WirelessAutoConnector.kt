package io.github.rhythmcache.dioxamine.adb.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import io.github.rhythmcache.dioxamine.core.AppLogger

/**
 * Watches mDNS for ADB connect services (Wireless debugging `_adb-tls-connect._tcp` and legacy
 * `_adb._tcp`) and reports each resolved one. Used to find a device's current port, which changes
 * every time Wireless debugging is turned on. Callbacks arrive on NsdManager's thread.
 */
class WirelessAutoConnector(context: Context) {
    data class FoundService(
        val type: AdbServiceType,
        /** Serial parsed from the `adb-<serial>-<suffix>` service name. */
        val deviceId: String,
        /** All usable addresses, best first (see [NsdAddressHelper.pickHosts]). */
        val hosts: List<String>,
        val port: Int,
    )

    private val nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()

    val isRunning: Boolean get() = listeners.isNotEmpty()

    fun start(onFound: (FoundService) -> Unit) {
        if (listeners.isNotEmpty()) return
        listOf(AdbServiceType.TLS_CONNECT, AdbServiceType.TCP).forEach { type ->
            val l = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {
                    AppLogger.i(TAG, "Watching $serviceType")
                }

                override fun onServiceFound(service: NsdServiceInfo) {
                    NsdResolver.resolve(
                        nsdManager = nsdManager,
                        serviceInfo = service,
                        onResolved = { resolved ->
                            val hosts = NsdAddressHelper.pickHosts(resolved)
                            if (hosts.isEmpty()) return@resolve
                            onFound(FoundService(type, extractDeviceIdentity(resolved.serviceName), hosts, resolved.port))
                        },
                        onFailed = { code -> AppLogger.w(TAG, "Resolve failed for ${service.serviceName}: $code") }
                    )
                }

                override fun onServiceLost(service: NsdServiceInfo) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    AppLogger.e(TAG, "Start discovery failed for $serviceType: $errorCode")
                }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            }
            runCatching {
                nsdManager.discoverServices(type.serviceType, NsdManager.PROTOCOL_DNS_SD, l)
                listeners.add(l)
            }.onFailure { AppLogger.e(TAG, "discoverServices(${type.serviceType}) threw: ${it.message}") }
        }
    }

    fun stop() {
        listeners.forEach { runCatching { nsdManager.stopServiceDiscovery(it) } }
        listeners.clear()
    }

    private companion object {
        const val TAG = "WirelessAutoConnect"
    }
}
