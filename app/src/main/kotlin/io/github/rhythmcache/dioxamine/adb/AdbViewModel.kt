package io.github.rhythmcache.dioxamine.adb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.rhythmcache.adb.*
import io.github.rhythmcache.adb.io.RandomAccessSource
import io.github.rhythmcache.dioxamine.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import io.github.rhythmcache.dioxamine.adb.discovery.LocalAdbDetector
import io.github.rhythmcache.dioxamine.adb.discovery.AdbServiceType
import io.github.rhythmcache.dioxamine.adb.discovery.WirelessAutoConnector
import io.github.rhythmcache.dioxamine.scrcpy.ScrcpyDiscoveryCache
import io.github.rhythmcache.dioxamine.wirelessdebug.WirelessDebugging
import java.io.File

const val PREF_AUTOCONNECT_WIRELESS = "adb_autoconnect_wireless"
private const val WIRELESS_AUTOCONNECT_WINDOW_MS = 60_000L

class AdbViewModel(
    private val keyDir: File,
    private val context: Context? = null
) : ViewModel() {

    private val prefs by lazy {
        context?.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    private val keyManager = AdbKeyManager(keyDir)

    var devices = mutableStateMapOf<String, DeviceConnection>()
        private set

    // id of the device currently "selected" for running shell/native actions against
    var activeDeviceId by mutableStateOf<String?>(null)
        private set

    // form fields for the "add TCP device" row
    var newIp by mutableStateOf("")
    var newPort by mutableStateOf("5555")

    var nativeOutputs = mutableStateMapOf<String, String>()
        private set
    var runningCommands = mutableStateMapOf<String, Boolean>()
        private set

    var keyFingerprint by mutableStateOf<String?>(keyManager.currentFingerprint())
        private set
    var keyMessage by mutableStateOf<String?>(null)
        private set

    var pairingError by mutableStateOf<String?>(null)
        private set

    var daemonDialogMessage by mutableStateOf<String?>(null)
        private set


    var flashState by mutableStateOf<FlashUiState>(FlashUiState.Idle)
        private set

    private var flashJob: Job? = null
    private var flashPfd: ParcelFileDescriptor? = null

    init {
        // Automatically generate key pair on very first launch if no key exists
        viewModelScope.launch(Dispatchers.IO) {
            if (!keyManager.hasKey()) {
                AppLogger.i("ADB_VM", "No ADB authorization key found. Automatically generating key pair on first launch...")
                when (val result = keyManager.regenerateKey()) {
                    is KeyLoadResult.Success -> {
                        withContext(Dispatchers.Main) {
                            keyFingerprint = result.fingerprint
                            AppLogger.i("ADB_VM", "Successfully generated initial ADB key pair: ${result.fingerprint}")
                        }
                    }
                    is KeyLoadResult.Failure -> {
                        AppLogger.e("ADB_VM", "Failed to generate initial ADB key pair: ${result.message}")
                    }
                }
            }
        }

        // background connection health monitor (every 5s)
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                kotlinx.coroutines.delay(5000)
                for ((id, conn) in devices.toMap()) {
                    if (conn.state is ConnectionState.Connected && conn.client != null) {
                        if (conn.client.isClosed) {
                            AppLogger.w("ADB_VM", "AdbViewModel: Connection $id died (transport=${conn.transport})")
                            handleDeadConnection(id, conn)
                        }
                    }
                }
            }
        }

        // Periodic local ADB detection (checks every 10s).
        // The "Local ADB Detected" prompt has been removed: when local wireless ADB is found we
        // only act if the user enabled "Auto-connect to Localhost"; otherwise we stay silent.
        // Localhost is still listed in the Discover Devices sheet for manual connection.
        viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(1000)
            while (isActive) {
                autoConnectLocalAdbIfEnabled()
                kotlinx.coroutines.delay(10000)
            }
        }
    }

    // -- Favorite host and auto-connect -------------------------------------------------------
    //
    // Wireless debugging picks a new port every time it is turned on (e.g. after the headset
    // reboots), so a saved "host:port" goes stale. On app start / return to foreground / a dropped
    // TLS connection we watch mDNS for ADB connect services and connect to the advertised port:
    //  - if the user starred a favorite IP in Discover Devices, only that IP is used;
    //  - otherwise (and if enabled in Settings) any device whose serial we connected to over TLS
    //    before, matched via the `adb-<serial>-*` service name.
    // Only one ADB connection is kept at a time (see [disconnectOthers]), so auto-connect never
    // runs while a device is already connected or connecting.

    /** The starred IP that is auto-connected on app start (port found via mDNS), or null. */
    var favoriteHost by mutableStateOf(context?.let { FavoriteAdbHost.get(it) })
        private set

    /** Makes [host] the single favorite, or clears the favorite when it already is (or [host] is null). */
    fun toggleFavoriteHost(host: String?) {
        val ctx = context ?: return
        val newValue = if (host == null || host == favoriteHost) null else host
        FavoriteAdbHost.set(ctx, newValue)
        favoriteHost = newValue
    }

    /** True while the mDNS watch for the favorite / known wireless devices is running. */
    var wirelessAutoConnecting by mutableStateOf(false)
        private set

    private val wirelessAutoConnector by lazy { context?.let { WirelessAutoConnector(it) } }
    private var wirelessAutoConnectJob: Job? = null
    private val wirelessAutoConnectAttempted = mutableSetOf<String>()

    private fun isConnectedOrConnecting(conn: DeviceConnection) =
        conn.state is ConnectionState.Connected || conn.state is ConnectionState.Connecting

    private fun anyDeviceBusy() = devices.values.any { isConnectedOrConnecting(it) }

    /** Safe to call repeatedly; does nothing if a device is already connected, or nothing to look for. */
    fun startWirelessAutoConnect() {
        val ctx = context ?: return
        if (wirelessAutoConnectJob?.isActive == true) return
        if (anyDeviceBusy()) return

        val favorite = favoriteHost
        if (favorite != null && FavoriteAdbHost.isLocalhost(favorite)) {
            // This device's own adbd isn't advertised over mDNS; probe its local port instead.
            viewModelScope.launch(Dispatchers.IO) {
                val target = LocalAdbDetector.detect() ?: return@launch
                withContext(Dispatchers.Main) {
                    if (!anyDeviceBusy()) connectTcpDirect("127.0.0.1", target.port)
                }
            }
            return
        }

        val known: Set<String> = if (favorite == null) {
            if (prefs?.getBoolean(PREF_AUTOCONNECT_WIRELESS, true) == false) return
            KnownWirelessDevices.getAll(ctx).toSet().ifEmpty { return }
        } else {
            emptySet()
        }
        val connector = wirelessAutoConnector ?: return

        wirelessAutoConnectAttempted.clear()
        wirelessAutoConnecting = true
        AppLogger.i("ADB_VM", "Auto-connect: looking for " + (favorite?.let { "favorite $it" } ?: "known devices $known"))
        connector.start { service ->
            viewModelScope.launch(Dispatchers.Main) {
                onAutoConnectServiceFound(favorite, known, service)
            }
        }
        wirelessAutoConnectJob = viewModelScope.launch(Dispatchers.Main) {
            try {
                // Keep watching for a while: after a reboot the device may take some time to get
                // Wi-Fi and turn Wireless debugging back on.
                val deadline = System.currentTimeMillis() + WIRELESS_AUTOCONNECT_WINDOW_MS
                while (isActive && System.currentTimeMillis() < deadline) {
                    if (devices.values.any { it.state is ConnectionState.Connected }) break
                    kotlinx.coroutines.delay(1000)
                }
            } finally {
                connector.stop()
                wirelessAutoConnecting = false
            }
        }
    }

    private fun onAutoConnectServiceFound(
        favorite: String?,
        known: Set<String>,
        service: WirelessAutoConnector.FoundService
    ) {
        if (anyDeviceBusy()) return
        val host: String
        if (favorite != null) {
            if (favorite !in service.hosts) return
            host = favorite
        } else {
            if (service.type != AdbServiceType.TLS_CONNECT || service.deviceId !in known) return
            host = service.hosts.first()
        }
        if (!wirelessAutoConnectAttempted.add("${service.type}|$host:${service.port}")) return

        AppLogger.i("ADB_VM", "Auto-connect: ${service.deviceId} found at $host:${service.port} (${service.type})")
        when (service.type) {
            AdbServiceType.TLS_CONNECT -> connectTls(host, service.port)
            AdbServiceType.TCP -> connectTcpDirect(host, service.port)
            AdbServiceType.TLS_PAIRING -> Unit
        }
    }

    /**
     * Only one ADB connection is kept at a time: once [keepId] has connected, every other device
     * (connected, connecting or failed) is disconnected and removed.
     */
    private fun disconnectOthers(keepId: String) {
        devices.keys.filter { it != keepId }.forEach { disconnect(it) }
    }

    private fun rememberWirelessDevice(serial: String?) {
        val ctx = context ?: return
        if (!serial.isNullOrBlank()) KnownWirelessDevices.add(ctx, serial)
    }

    override fun onCleared() {
        super.onCleared()
        wirelessAutoConnector?.stop()
    }

    fun triggerLocalAdbCheck() {
        viewModelScope.launch(Dispatchers.IO) {
            autoConnectLocalAdbIfEnabled()
        }
    }

    private fun autoConnectLocalAdbIfEnabled() {
        val detectEnabled = prefs?.getBoolean("adb_detect_localhost", true) ?: true
        val autoConnect = prefs?.getBoolean("adb_autoconnect_localhost", false) ?: false
        if (!detectEnabled || !autoConnect || isLocalhostConnected()) return
        // Only one ADB connection at a time: don't replace a device the user is already using.
        if (anyDeviceBusy()) return
        val target = LocalAdbDetector.detect() ?: return
        connectTcpDirect("127.0.0.1", target.port)
    }

    /** Set once the startup "Discover Devices" sheet has been considered, so it only auto-opens once per app session. */
    var startupDiscoveryHandled = false

    /** True when at least one device is connected or in the middle of connecting. */
    fun hasActiveDevice(): Boolean = devices.values.any {
        it.state is ConnectionState.Connected || it.state is ConnectionState.Connecting
    }

    fun isLocalhostConnected(): Boolean {
        return devices.values.any { conn ->
            val isLocal = conn.transport == DeviceTransport.TCP && (
                conn.id.startsWith("127.0.0.1:") ||
                conn.id.startsWith("localhost:") ||
                conn.id.startsWith("tls:127.0.0.1:") ||
                conn.id.startsWith("tls:localhost:")
            )
            val activeState = conn.state is ConnectionState.Connected || conn.state is ConnectionState.Connecting
            isLocal && activeState
        }
    }

    private fun handleDeadConnection(id: String, conn: DeviceConnection) {
    // close the dead client cleanly
    runCatching { conn.client?.close() }

    when (conn.transport) {
        DeviceTransport.TCP -> {
            // Auto-reconnect TCP/TLS devices in background
            val isTls = id.startsWith("tls:")
            val stripped = if (isTls) id.removePrefix("tls:") else id
            val parts = stripped.split(":")
            val host = parts.getOrNull(0) ?: return
            val portInt = parts.getOrNull(1)?.toIntOrNull() ?: 5555

            devices[id] = conn.copy(client = null, state = ConnectionState.Connecting)
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val keyProvider = keyManager.createKeyProvider()
                    val newClient = if (isTls) {
                        AdbClient.connectTls(host = host, port = portInt, keyProvider = keyProvider, handshakeTimeoutMs = 15000)
                    } else {
                        AdbClient.connect(host = host, port = portInt, keyProvider = keyProvider, handshakeTimeoutMs = 15000)
                    }
                    val details = fetchDeviceDetails(newClient)
                    devices[id] = conn.copy(
                        client = newClient,
                        state = ConnectionState.Connected(newClient),
                        supportsShellV2 = details.supportsShellV2,
                        androidVersion = details.androidVersion,
                        apiLevel = details.apiLevel,
                        model = details.model ?: conn.label,
                        mode = newClient.deviceMode,
                        isRoot = details.isRoot
                    )
                    AppLogger.i("ADB_VM", "AdbViewModel: Auto-reconnected $id successfully")
                    if (isTls) rememberWirelessDevice(details.uniqueId)
                } catch (e: Exception) {
                    devices[id] = conn.copy(client = null, state = ConnectionState.Error("Connection lost: ${e.message}"))
                    // Wireless debugging likely came back on a new port (e.g. the device rebooted):
                    // look for it again over mDNS.
                    if (isTls) {
                        withContext(Dispatchers.Main) { startWirelessAutoConnect() }
                    }
                }
            }
        }
        DeviceTransport.USB -> {
            devices[id] = conn.copy(client = null, state = ConnectionState.Error("USB connection lost. Please reconnect."))
        }
    }
}

    fun connectTcp() {
        val host = newIp.trim()
        val portInt = newPort.trim().toIntOrNull()
        if (host.isEmpty() || portInt == null) return
        val id = "$host:$portInt"
        if (devices.containsKey(id) && devices[id]?.state is ConnectionState.Connected) return

        devices[id] = DeviceConnection(id, id, DeviceTransport.TCP, null, ConnectionState.Connecting)
        viewModelScope.launch {
            try {
                val keyProvider = keyManager.createKeyProvider()
                val identity = keyManager.getPairingIdentity()
                val client = withContext(Dispatchers.IO) {
                    runCatching {
                        AdbClient.connectTls(host = host, port = portInt, keyProvider = keyProvider, identity = identity, handshakeTimeoutMs = 15000)
                    }.getOrElse {
                        AdbClient.connectTcp(host = host, port = portInt, keyProvider = keyProvider, handshakeTimeoutMs = 15000)
                    }
                }

                val details = withContext(Dispatchers.IO) { fetchDeviceDetails(client) }
                val displayLabel = details.model ?: id

                devices[id] = DeviceConnection(
                    id = id,
                    label = displayLabel,
                    transport = DeviceTransport.TCP,
                    client = client,
                    state = ConnectionState.Connected(client),
                    supportsShellV2 = details.supportsShellV2,
                    androidVersion = details.androidVersion,
                    apiLevel = details.apiLevel,
                    model = details.model,
                    mode = client.deviceMode,
                    isRoot = details.isRoot,
                    uniqueId = details.uniqueId ?: id
                )
                activeDeviceId = id
                disconnectOthers(id)
            } catch (e: Exception) {
                devices[id] = DeviceConnection(id, id, DeviceTransport.TCP, null, ConnectionState.Error(e.message ?: "Connection failed"))
            }
        }
    }

    fun connectTcpDirect(host: String, port: Int) {
        if (host.isEmpty()) return
        val id = "$host:$port"
        if (devices.containsKey(id) && devices[id]?.state is ConnectionState.Connected) return

        devices[id] = DeviceConnection(id, id, DeviceTransport.TCP, null, ConnectionState.Connecting)
        viewModelScope.launch {
            try {
                val keyProvider = keyManager.createKeyProvider()
                val identity = keyManager.getPairingIdentity()
                val client = withContext(Dispatchers.IO) {
                    runCatching {
                        AdbClient.connectTls(host = host, port = port, keyProvider = keyProvider, identity = identity, handshakeTimeoutMs = 15000)
                    }.getOrElse {
                        AdbClient.connectTcp(host = host, port = port, keyProvider = keyProvider, handshakeTimeoutMs = 15000)
                    }
                }

                val details = withContext(Dispatchers.IO) { fetchDeviceDetails(client) }
                val displayLabel = details.model ?: if (host == "127.0.0.1") "This Device ($id)" else id

                devices[id] = DeviceConnection(
                    id = id,
                    label = displayLabel,
                    transport = DeviceTransport.TCP,
                    client = client,
                    state = ConnectionState.Connected(client),
                    supportsShellV2 = details.supportsShellV2,
                    androidVersion = details.androidVersion,
                    apiLevel = details.apiLevel,
                    model = details.model,
                    mode = client.deviceMode,
                    isRoot = details.isRoot,
                    uniqueId = details.uniqueId ?: id
                )
                activeDeviceId = id
                disconnectOthers(id)
            } catch (e: Exception) {
                devices[id] = DeviceConnection(id, id, DeviceTransport.TCP, null, ConnectionState.Error(e.message ?: "Connection failed"))
            }
        }
    }


    fun connectUsb(usbManager: UsbManager, device: UsbDevice) {
        val serial = runCatching { device.serialNumber }.getOrNull() ?: device.deviceName
        val defaultLabel = (runCatching { device.productName }.getOrNull() ?: device.deviceName).ifBlank { serial }
        val id = "usb:$serial"
        if (devices.containsKey(id) && devices[id]?.state is ConnectionState.Connected) return

        devices[id] = DeviceConnection(id, defaultLabel, DeviceTransport.USB, null, ConnectionState.Connecting)
        viewModelScope.launch {
            try {
                val iface = UsbPacketTransport.findAdbInterface(device)
                    ?: throw Exception("No ADB USB interface found on device")
                val (inEp, outEp) = UsbPacketTransport.findAdbEndpoints(iface)
                    ?: throw Exception("Failed to locate USB bulk endpoints")

                val connection = usbManager.openDevice(device)
                    ?: throw Exception("Failed to open USB connection")

                if (!connection.claimInterface(iface, true)) {
                    connection.close()
                    throw Exception("Failed to claim USB interface")
                }

                val transport = UsbPacketTransport(connection, iface, inEp, outEp)
                val keyProvider = keyManager.createKeyProvider()

                val client = withContext(Dispatchers.IO) {
                    AdbClient.connect(transport = transport, keyProvider = keyProvider, handshakeTimeoutMs = 15000)
                }

                val details = withContext(Dispatchers.IO) { fetchDeviceDetails(client) }
                val displayLabel = details.model ?: defaultLabel

                UsbHelper.registerDeviceMapping(device.deviceName, id)
                devices[id] = DeviceConnection(
                    id = id,
                    label = displayLabel,
                    transport = DeviceTransport.USB,
                    client = client,
                    state = ConnectionState.Connected(client),
                    supportsShellV2 = details.supportsShellV2,
                    androidVersion = details.androidVersion,
                    apiLevel = details.apiLevel,
                    model = details.model,
                    mode = client.deviceMode,
                    isRoot = details.isRoot,
                    uniqueId = details.uniqueId ?: serial
                )
                activeDeviceId = id
                disconnectOthers(id)
            } catch (e: Exception) {
                devices[id] = DeviceConnection(id, defaultLabel, DeviceTransport.USB, null, ConnectionState.Error(e.message ?: "USB connection failed"))
            }
        }
    }

    fun connectTls(host: String, port: Int, onResult: ((Boolean, String?) -> Unit)? = null) {
        val id = "tls:$host:$port"
        if (devices.containsKey(id) && devices[id]?.state is ConnectionState.Connected) {
            onResult?.invoke(true, null)
            return
        }

        devices[id] = DeviceConnection(id, "$host:$port", DeviceTransport.TCP, null, ConnectionState.Connecting)
        viewModelScope.launch {
            try {
                val keyProvider = keyManager.createKeyProvider()
                val client = withContext(Dispatchers.IO) {
                    AdbClient.connectTls(host = host, port = port, keyProvider = keyProvider, handshakeTimeoutMs = 15000)
                }
                val details = withContext(Dispatchers.IO) { fetchDeviceDetails(client) }
                devices[id] = DeviceConnection(
                    id = id,
                    label = details.model ?: id,
                    transport = DeviceTransport.TCP,
                    client = client,
                    state = ConnectionState.Connected(client),
                    supportsShellV2 = details.supportsShellV2,
                    androidVersion = details.androidVersion,
                    apiLevel = details.apiLevel,
                    model = details.model,
                    mode = client.deviceMode,
                    isRoot = details.isRoot,
                    uniqueId = details.uniqueId ?: id
                )
                activeDeviceId = id
                disconnectOthers(id)
                rememberWirelessDevice(details.uniqueId)
                onResult?.invoke(true, null)
            } catch (e: io.github.rhythmcache.adb.AdbException.NotPaired) {
                devices.remove(id)
                ScrcpyDiscoveryCache.clear(id)
                val err = "Device not paired. Pair it first."
                pairingError = err
                onResult?.invoke(false, err)
            } catch (e: Exception) {
                val errMsg = e.message ?: "TLS connection failed"
                devices[id] = DeviceConnection(id, id, DeviceTransport.TCP, null, ConnectionState.Error(errMsg))
                onResult?.invoke(false, errMsg)
            }
        }
    }

    fun pairTls(host: String, port: Int, pairingCode: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            try {
                val keyProvider = keyManager.createKeyProvider()
                val result = withContext(Dispatchers.IO) {
                    AdbClient.pairTls(host = host, port = port, pairingCode = pairingCode, keyProvider = keyProvider)
                }
                result.fold(
                    onSuccess = { onResult(true, "Paired successfully") },
                    onFailure = { onResult(false, it.message ?: "Pairing failed") }
                )
            } catch (e: Exception) {
                onResult(false, e.message ?: "Pairing failed")
            }
        }
    }

    fun clearPairingError() { pairingError = null }


    fun disconnect(id: String) {
        val conn = devices[id] ?: return
        // Remove first so the health monitor can't mistake the closing client for a dropped
        // connection and auto-reconnect it.
        devices.remove(id)
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try { conn.client?.close() } catch (_: Exception) {}
            }
            ScrcpyDiscoveryCache.clear(id)
            nativeOutputs.clear()
            runningCommands.clear()
            flashState = FlashUiState.Idle
            if (activeDeviceId == id) {
                activeDeviceId = devices.keys.firstOrNull { devices[it]?.state is ConnectionState.Connected }
            }
        }
    }

    fun setActiveDevice(id: String) {
        activeDeviceId = id
        nativeOutputs.clear() // fresh output panel per device switch
        runningCommands.clear()
        flashState = FlashUiState.Idle
    }

    fun activeClient(): AdbClient? =
        activeDeviceId?.let { (devices[it]?.state as? ConnectionState.Connected)?.client }

    fun runShell(label: String, command: String) {
        val activeId = activeDeviceId ?: return
        val conn = devices[activeId] ?: return
        val client = activeClient() ?: run {
            nativeOutputs[label] = "Device not connected"
            return
        }
        runningCommands[label] = true
        viewModelScope.launch {
            try {
                val outputText = withContext(Dispatchers.IO) {
                    client.executeShell(command, conn.supportsShellV2)
                }
                nativeOutputs[label] = outputText
            } catch (e: Exception) {
                nativeOutputs[label] = "Failed: ${e.message}"
            } finally {
                runningCommands[label] = false
            }
        }
    }

    suspend fun runShellSuspend(label: String, command: String) {
        val activeId = activeDeviceId ?: return
        val conn = devices[activeId] ?: return
        val client = activeClient() ?: run {
            nativeOutputs[label] = "Device not connected"
            return
        }
        runningCommands[label] = true
        try {
            val outputText = withContext(Dispatchers.IO) {
                client.executeShell(command, conn.supportsShellV2)
            }
            nativeOutputs[label] = outputText
        } catch (e: Exception) {
            nativeOutputs[label] = "Failed: ${e.message}"
        } finally {
            runningCommands[label] = false
        }
    }

    fun startFlash(uri: Uri, contentResolver: ContentResolver) {
        val client = activeClient() ?: run {
            flashState = FlashUiState.Error("No device connected")
            return
        }
        if (flashJob?.isActive == true) {
            return // already flashing, ignore duplicate taps
        }

        flashState = FlashUiState.Running(0f, 0, 0)
        flashJob = viewModelScope.launch(Dispatchers.IO) {
            var pfd: ParcelFileDescriptor? = null
            try {
                pfd = contentResolver.openFileDescriptor(uri, "r")
                    ?: throw Exception("Could not open selected file")
                flashPfd = pfd

                val source = RandomAccessSource.of(pfd.fileDescriptor, pfd.statSize)

                val activeMode = devices[activeDeviceId]?.mode ?: AdbDeviceMode.UNKNOWN

                val progressFlow = when (activeMode) {
                    AdbDeviceMode.RESCUE -> client.rescue.install(source)
                    else -> client.sideload.sideload(source)
                }

                
                progressFlow.collect { progress ->
                    
                    flashState = FlashUiState.Running(
                        percent = progress.percentage,
                        bytesTransferred = progress.bytesTransferred,
                        totalBytes = progress.totalBytes
                    )
                }
                
                flashState = FlashUiState.Success
            } catch (e: Exception) {
                
                flashState = FlashUiState.Error(e.message ?: "Flash failed")
            } finally {
                
                try { pfd?.close() } catch (_: Exception) {}
                flashPfd = null
            }
        }
    }

    fun cancelFlash() {
        flashJob?.cancel()
        flashJob = null
        try { flashPfd?.close() } catch (_: Exception) {}
        flashPfd = null
        flashState = FlashUiState.Idle
    }

    fun resetFlashState() {
        flashState = FlashUiState.Idle
    }

    fun rescueWipeUserdata() {
        val client = activeClient() ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = client.rescue.wipeUserdata()
                withContext(Dispatchers.Main) { nativeOutputs["wipe"] = result }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { nativeOutputs["wipe"] = "Failed: ${e.message}" }
            }
        }
    }

    fun regenerateKey() {
        viewModelScope.launch {
            when (val r = withContext(Dispatchers.IO) { keyManager.regenerateKey() }) {
                is KeyLoadResult.Success -> { keyFingerprint = r.fingerprint; keyMessage = "New key generated." }
                is KeyLoadResult.Failure -> keyMessage = r.message
            }
        }
    }

    fun loadCustomKey(bytes: ByteArray) {
        viewModelScope.launch {
            when (val r = withContext(Dispatchers.IO) { keyManager.loadCustomKey(bytes) }) {
                is KeyLoadResult.Success -> { keyFingerprint = r.fingerprint; keyMessage = "Custom key loaded." }
                is KeyLoadResult.Failure -> keyMessage = r.message
            }
        }
    }

    /** Returns standard PKCS#8 PEM formatted private key for exporting (~/.android/adbkey). */
    fun getPrivateKeyPem(): String? = keyManager.getPrivateKeyPem()

    fun dismissDaemonDialog() {
        daemonDialogMessage = null
    }

    fun toggleRoot(id: String) {
        val conn = devices[id] ?: return
        val client = conn.client ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val output = if (conn.isRoot) {
                    client.unroot()
                } else {
                    client.root()
                }
                withContext(Dispatchers.Main) {
                    daemonDialogMessage = output
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    daemonDialogMessage = "Failed: ${e.message}"
                }
            }
        }
    }

    /**
     * Grants this app WRITE_SECURE_SETTINGS by running `pm grant` over an ADB connection to the
     * device the app itself is running on (needed to toggle wireless debugging at boot).
     * [onResult] is invoked on the main thread with a user-facing message.
     */
    fun grantWriteSecureSettings(context: Context, onResult: (success: Boolean, message: String) -> Unit) {
        val client = activeClient()
        if (client == null) {
            onResult(false, "No connected ADB device. Connect to this device's own ADB first.")
            return
        }
        val pkg = context.packageName
        viewModelScope.launch(Dispatchers.IO) {
            val (ok, msg) = try {
                val res = client.shell("pm grant $pkg android.permission.WRITE_SECURE_SETTINGS")
                if (res.isSuccess) {
                    true to "Permission granted"
                } else {
                    false to (res.stderr.toString(Charsets.UTF_8).ifBlank { res.stdoutText }.trim()
                        .ifBlank { "pm grant failed (exit ${res.exitCode})" })
                }
            } catch (e: Exception) {
                false to "Failed: ${e.message}"
            }
            withContext(Dispatchers.Main) { onResult(ok, msg) }
        }
    }

    var helperInstallState by mutableStateOf<HelperInstallState>(HelperInstallState.Idle)
        private set

    private var helperInstallJob: Job? = null

    var helperStatus by mutableStateOf<HelperAppStatus>(HelperAppStatus.Unknown)
        private set
    private var bundledHelperVersion: Long? = null
    private var helperStatusJob: Job? = null

    /** versionCode of the helper APK bundled in assets (read once), or null if not bundled. */
    private fun readBundledHelperVersion(appContext: Context): Long? {
        bundledHelperVersion?.let { return it }
        val probe = File(appContext.cacheDir, "helper-version-probe.apk")
        return try {
            appContext.assets.open(WirelessDebugging.HELPER_ASSET).use { input ->
                probe.outputStream().use { input.copyTo(it) }
            }
            @Suppress("DEPRECATION")
            val info = appContext.packageManager.getPackageArchiveInfo(probe.absolutePath, 0) ?: return null
            val version = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            bundledHelperVersion = version
            version
        } catch (e: java.io.FileNotFoundException) {
            null
        } finally {
            probe.delete()
        }
    }

    /** Checks whether the helper is installed on the active device and whether it's outdated. */
    fun refreshHelperStatus(context: Context) {
        val client = activeClient()
        if (client == null) {
            helperStatus = HelperAppStatus.Unknown
            return
        }
        val apiLevel = activeDeviceId?.let { devices[it]?.apiLevel }
        if (apiLevel != null && apiLevel < 30) {
            helperStatus = HelperAppStatus.Unsupported
            return
        }
        val appContext = context.applicationContext
        helperStatusJob?.cancel()
        helperStatus = HelperAppStatus.Checking
        helperStatusJob = viewModelScope.launch(Dispatchers.IO) {
            val status = try {
                val bundled = readBundledHelperVersion(appContext)
                if (bundled == null) {
                    HelperAppStatus.NotBundled
                } else {
                    val out = client.shell(
                        "dumpsys package ${WirelessDebugging.HELPER_PACKAGE} | grep -m1 versionCode="
                    ).stdoutText
                    val installed = Regex("versionCode=(\\d+)").find(out)?.groupValues?.get(1)?.toLongOrNull()
                    when {
                        installed == null -> HelperAppStatus.NotInstalled(bundled)
                        installed < bundled -> HelperAppStatus.UpdateAvailable(installed, bundled)
                        else -> HelperAppStatus.UpToDate(installed)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w("ADB_VM", "Helper status check failed: ${e.message}")
                HelperAppStatus.Unknown
            }
            withContext(Dispatchers.Main) { helperStatus = status }
        }
    }

    /**
     * One-tap setup of the headset helper on the active device: installs the helper APK bundled in
     * this app's assets, grants it WRITE_SECURE_SETTINGS, and launches it once (Android only delivers
     * BOOT_COMPLETED to apps that have been opened since install). Progress goes to [helperInstallState].
     */
    fun installWirelessHelper(context: Context) {
        if (helperInstallState is HelperInstallState.Running) return
        val client = activeClient()
        if (client == null) {
            helperInstallState = HelperInstallState.Error("No connected ADB device.")
            return
        }
        val apiLevel = activeDeviceId?.let { devices[it]?.apiLevel }
        if (apiLevel != null && apiLevel < 30) {
            helperInstallState = HelperInstallState.Error(
                "Wireless debugging needs Android 11 or newer on the connected device (it reports API $apiLevel)."
            )
            return
        }
        val appContext = context.applicationContext
        helperInstallJob = viewModelScope.launch(Dispatchers.IO) {
            val apk = File(appContext.cacheDir, WirelessDebugging.HELPER_ASSET)
            try {
                withContext(Dispatchers.Main) {
                    helperInstallState = HelperInstallState.Running(HelperInstallStep.INSTALLING, 0f)
                }
                val alreadyCurrent = helperStatus is HelperAppStatus.UpToDate
                if (!alreadyCurrent) {
                appContext.assets.open(WirelessDebugging.HELPER_ASSET).use { input ->
                    apk.outputStream().use { input.copyTo(it) }
                }
                suspend fun installApk() {
                    // -r: replace an existing install, -d: allow a lower versionCode.
                    client.install.install(apk, flags = listOf("-r", "-d")).collect { progress ->
                        withContext(Dispatchers.Main) {
                            helperInstallState = HelperInstallState.Running(
                                HelperInstallStep.INSTALLING,
                                progress.percentage / 100f
                            )
                        }
                    }
                }
                try {
                    installApk()
                } catch (e: AdbException) {
                    // A helper signed with a different key (e.g. from a CI artifact) can't be
                    // updated in place; it only holds one preference, so replace it.
                    if (e.message?.contains("UPDATE_INCOMPATIBLE") != true) throw e
                    AppLogger.i("ADB_VM", "Helper signature mismatch; uninstalling old helper and retrying")
                    client.shell("pm uninstall ${WirelessDebugging.HELPER_PACKAGE}")
                    installApk()
                }
                } // !alreadyCurrent: an up-to-date helper only needs the grant + launch below

                withContext(Dispatchers.Main) {
                    helperInstallState = HelperInstallState.Running(HelperInstallStep.GRANTING, 1f)
                }
                val grant = client.shell(
                    "pm grant ${WirelessDebugging.HELPER_PACKAGE} android.permission.WRITE_SECURE_SETTINGS"
                )
                if (!grant.isSuccess) {
                    val msg = grant.stderr.toString(Charsets.UTF_8).ifBlank { grant.stdoutText }.trim()
                    throw IllegalStateException("Installed, but granting the permission failed: $msg")
                }

                withContext(Dispatchers.Main) {
                    helperInstallState = HelperInstallState.Running(HelperInstallStep.LAUNCHING, 1f)
                }
                val launch = client.shell(
                    "am start -n ${WirelessDebugging.HELPER_PACKAGE}/${WirelessDebugging.HELPER_ACTIVITY}"
                )
                val launchOut = (launch.stdoutText + launch.stderr.toString(Charsets.UTF_8)).trim()
                val launched = launch.isSuccess && !launchOut.contains("Error", ignoreCase = true)
                if (!launched) {
                    AppLogger.w("ADB_VM", "Helper launch output: $launchOut")
                }

                withContext(Dispatchers.Main) {
                    helperInstallState = HelperInstallState.Success(launched)
                    refreshHelperStatus(appContext)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: java.io.FileNotFoundException) {
                withContext(Dispatchers.Main) {
                    helperInstallState = HelperInstallState.Error("This build does not include the helper APK.")
                }
            } catch (e: Exception) {
                AppLogger.e("ADB_VM", "Helper install failed", e)
                withContext(Dispatchers.Main) {
                    helperInstallState = HelperInstallState.Error(e.message ?: e.toString())
                }
            } finally {
                apk.delete()
            }
        }
    }

    fun dismissHelperInstall() {
        if (helperInstallState !is HelperInstallState.Running) {
            helperInstallState = HelperInstallState.Idle
        }
    }

    fun switchTcpip(id: String, port: Int = 5555) {
        val conn = devices[id] ?: return
        val client = conn.client ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val output = client.tcpip(port)
                withContext(Dispatchers.Main) {
                    daemonDialogMessage = output
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    daemonDialogMessage = "Failed: ${e.message}"
                }
            }
        }
    }
}
