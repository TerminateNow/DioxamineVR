package io.github.rhythmcache.dioxamine.wirelessdebug

import io.github.rhythmcache.dioxamine.core.AppLogger
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Enables wireless debugging after the device finishes booting, when the user turned on
 * "Enable wireless debugging on boot" and WRITE_SECURE_SETTINGS has been granted.
 *
 * Only BOOT_COMPLETED is handled (not LOCKED_BOOT_COMPLETED) because the preference lives in
 * credential-encrypted storage. Android also never delivers this broadcast to an app that has not
 * been opened at least once since install.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (prefs.getBoolean(WirelessDebugging.PREF_ENABLE_ON_BOOT, false)) {
            WirelessDebugging.enable(context).onFailure {
                AppLogger.w("BootReceiver", "Could not enable wireless debugging on boot: ${it.message}")
            }
        }

        // The Quest resets its proximity sensor mode on every boot; re-apply "off" if chosen
        // (only the headset helper sets this preference).
        if (prefs.getBoolean(QuestProximitySensor.PREF_SENSOR_OFF, false) && QuestProximitySensor.isQuestDevice) {
            QuestProximitySensor.sendFromApp(context, sensorOn = false)
        }
        if (prefs.getBoolean(QuestGuardian.PREF_GUARDIAN_OFF, false) && QuestProximitySensor.isQuestDevice) {
            QuestGuardian.setLocal(guardianOn = false)
        }
    }
}
