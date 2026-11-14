package io.github.rhythmcache.dioxamine.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * Turns the system "Wireless debugging" switch on from inside the app.
 *
 * Writing `Settings.Global.adb_wifi_enabled` needs [Manifest.permission.WRITE_SECURE_SETTINGS], which
 * cannot be requested at runtime. It is granted once over ADB (the app can do that itself through an
 * existing ADB connection, see AdbViewModel.grantWriteSecureSettings).
 */
object WirelessDebugging {
    private const val TAG = "WirelessDebugging"

    const val PREF_ENABLE_ON_BOOT = "wireless_debug_on_boot"

    private const val SETTING_ADB_ENABLED = "adb_enabled"
    private const val SETTING_ADB_WIFI_ENABLED = "adb_wifi_enabled"

    /** Wireless debugging was introduced in Android 11 (API 30). */
    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    fun isEnabled(context: Context): Boolean =
        Settings.Global.getInt(context.contentResolver, SETTING_ADB_WIFI_ENABLED, 0) == 1

    /**
     * Enables wireless debugging. Returns a failure with a readable message when the platform or
     * permission does not allow it; never throws.
     */
    fun enable(context: Context): Result<Unit> {
        if (!isSupported) {
            return Result.failure(UnsupportedOperationException("Wireless debugging requires Android 11+"))
        }
        if (!hasPermission(context)) {
            return Result.failure(SecurityException("WRITE_SECURE_SETTINGS is not granted"))
        }
        return try {
            val resolver = context.contentResolver
            // Wireless debugging only works while USB debugging / developer options are on.
            if (Settings.Global.getInt(resolver, SETTING_ADB_ENABLED, 0) != 1) {
                Settings.Global.putInt(resolver, SETTING_ADB_ENABLED, 1)
            }
            if (!isEnabled(context)) {
                Settings.Global.putInt(resolver, SETTING_ADB_WIFI_ENABLED, 1)
            }
            AppLogger.i(TAG, "Wireless debugging enabled (adb_wifi_enabled=${isEnabled(context)})")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to enable wireless debugging: ${e.message}")
            Result.failure(e)
        }
    }
}
