package io.github.rhythmcache.dioxamine.wirelesshelper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts [WirelessWatchdogService] after a reboot or an app update when either of its switches
 * is on. (Turning Wireless debugging on at boot is done by the shared BootReceiver.)
 */
class HelperBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                WirelessWatchdogService.sync(context)
        }
    }
}
