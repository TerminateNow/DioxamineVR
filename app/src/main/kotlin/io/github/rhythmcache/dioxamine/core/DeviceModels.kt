package io.github.rhythmcache.dioxamine.core

import io.github.rhythmcache.adb.AdbClient
import io.github.rhythmcache.adb.AdbDeviceMode

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    data class Connected(val client: AdbClient) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

enum class DeviceTransport { TCP, USB }

data class DeviceConnection(
    val id: String,                 // e.g. "192.168.1.100:5555" or USB serial
    val label: String,               // display name / model
    val transport: DeviceTransport,
    val client: AdbClient?,          // null while connecting
    val state: ConnectionState,
    val supportsShellV2: Boolean = true,
    val androidVersion: String? = null,
    val apiLevel: Int? = null,
    val model: String? = null,
    val mode: AdbDeviceMode = AdbDeviceMode.UNKNOWN,
    val isRoot: Boolean = false,
    val uniqueId: String? = null
)

data class DeviceDetails(
    val model: String?,
    val androidVersion: String?,
    val apiLevel: Int?,
    val supportsShellV2: Boolean,
    val isRoot: Boolean = false,
    val uniqueId: String? = null
)

suspend fun fetchDeviceDetails(client: AdbClient): DeviceDetails {
    return when (client.deviceMode) {
        AdbDeviceMode.SIDELOAD -> {
            DeviceDetails(null, null, null, false, isRoot = false)
        }
        AdbDeviceMode.RESCUE -> {
            try {
                val model = client.rescue.getProp("ro.product.model").ifBlank { null }
                val serial = client.rescue.getProp("ro.serialno").ifBlank { client.rescue.getProp("ro.boot.serialno") }.ifBlank { null }
                DeviceDetails(model = model, androidVersion = null, apiLevel = null, supportsShellV2 = false, isRoot = true, uniqueId = serial)
            } catch (_: Exception) {
                DeviceDetails(null, null, null, false, isRoot = false)
            }
        }
        else -> {
            try {
                val rawOutput = client.open("shell:getprop ro.serialno; echo '---'; getprop ro.boot.serialno; echo '---'; settings get secure android_id; echo '---'; getprop ro.product.model; echo '---'; getprop ro.build.version.release; echo '---'; getprop ro.build.version.sdk; echo '---'; id -u").use { stream ->
                    String(stream.readToEnd(), Charsets.UTF_8).trim()
                }
                val parts = rawOutput.split("---").map { it.trim() }
                val roSerial = parts.getOrNull(0)?.ifEmpty { null }
                val bootSerial = parts.getOrNull(1)?.ifEmpty { null }
                val androidId = parts.getOrNull(2)?.ifEmpty { null }
                val model = parts.getOrNull(3)?.ifEmpty { null }
                val release = parts.getOrNull(4)?.ifEmpty { null }
                val apiLevel = parts.getOrNull(5)?.toIntOrNull()
                val uidStr = parts.getOrNull(6)?.ifEmpty { null }
                val supportsV2 = apiLevel != null && apiLevel >= 24
                val isRoot = uidStr == "0" || uidStr?.startsWith("uid=0") == true
                val hardwareSerial = roSerial?.takeIf { it != "unknown" }
                    ?: bootSerial?.takeIf { it != "unknown" }
                val uniqueId = hardwareSerial ?: androidId?.takeIf { it != "null" }
                DeviceDetails(model, release, apiLevel, supportsV2, isRoot, uniqueId)
            } catch (_: Exception) {
                DeviceDetails(null, null, null, false, isRoot = false)
            }
        }
    }
}

data class ShellExecResult(
    val output: String,
    val isSuccess: Boolean,
    val exitCode: Int = 0
)

suspend fun AdbClient.executeShellResult(command: String, supportsShellV2: Boolean): ShellExecResult {
    return try {
        kotlinx.coroutines.withTimeout(10_000) {
            if (supportsShellV2 && deviceMode != AdbDeviceMode.RECOVERY) {
                try {
                    val result = shell(command)
                    val text = if (result.isSuccess) {
                        result.stdoutText.trim()
                    } else {
                        result.stderrText.trim().ifBlank { result.stdoutText.trim() }
                    }
                    ShellExecResult(
                        output = text,
                        isSuccess = result.isSuccess,
                        exitCode = result.exitCode
                    )
                } catch (_: Exception) {
                    open("shell:$command").use { stream ->
                        val bytes = stream.readToEnd()
                        val text = String(bytes, Charsets.UTF_8).trim()
                        val isErr = text.startsWith("Error", ignoreCase = true) ||
                                text.contains("Exception", ignoreCase = true) ||
                                text.contains("Permission Denial", ignoreCase = true) ||
                                text.contains("Operation not allowed", ignoreCase = true)
                        ShellExecResult(output = text, isSuccess = !isErr, exitCode = if (isErr) 1 else 0)
                    }
                }
            } else {
                open("shell:$command").use { stream ->
                    val bytes = stream.readToEnd()
                    val text = String(bytes, Charsets.UTF_8).trim()
                    val isErr = text.startsWith("Error", ignoreCase = true) ||
                            text.contains("Exception", ignoreCase = true) ||
                            text.contains("Permission Denial", ignoreCase = true) ||
                            text.contains("Operation not allowed", ignoreCase = true)
                    ShellExecResult(output = text, isSuccess = !isErr, exitCode = if (isErr) 1 else 0)
                }
            }
        }
    } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
        ShellExecResult(output = "Command timed out - device may be disconnected", isSuccess = false, exitCode = -1)
    }
}

suspend fun AdbClient.executeShell(command: String, supportsShellV2: Boolean): String {
    val res = executeShellResult(command, supportsShellV2)
    return if (res.isSuccess) {
        res.output
    } else {
        if (res.exitCode != 0) "Error (${res.exitCode}): ${res.output}" else res.output
    }
}

enum class HelperInstallStep { INSTALLING, GRANTING, LAUNCHING }

/** Whether the headset helper is on the connected device, compared with the copy bundled in this app. */
sealed class HelperAppStatus {
    object Unknown : HelperAppStatus()
    object Checking : HelperAppStatus()
    /** Connected device is older than Android 11, which has no Wireless debugging. */
    object Unsupported : HelperAppStatus()
    /** This build doesn't contain the helper APK. */
    object NotBundled : HelperAppStatus()
    data class NotInstalled(val bundledVersion: Long) : HelperAppStatus()
    data class UpdateAvailable(val installedVersion: Long, val bundledVersion: Long) : HelperAppStatus()
    data class UpToDate(val installedVersion: Long) : HelperAppStatus()
}

sealed class HelperInstallState {
    object Idle : HelperInstallState()
    data class Running(val step: HelperInstallStep, val progress: Float) : HelperInstallState()
    /** [launched] is false when install and grant worked but `am start` did not. */
    data class Success(val launched: Boolean) : HelperInstallState()
    data class Error(val message: String) : HelperInstallState()
}

sealed class FlashUiState {
    object Idle : FlashUiState()
    data class Running(val percent: Float, val bytesTransferred: Long, val totalBytes: Long) : FlashUiState()
    object Success : FlashUiState()
    data class Error(val message: String) : FlashUiState()
}
