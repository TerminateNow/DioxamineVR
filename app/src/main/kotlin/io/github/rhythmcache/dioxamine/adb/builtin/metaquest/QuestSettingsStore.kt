package io.github.rhythmcache.dioxamine.adb.builtin.metaquest

import android.content.Context

/**
 * The Meta Quest choices made in Meta Quest Settings, per headset (keyed by the device's serial /
 * unique id). Both settings reset when the headset reboots, so AdbViewModel re-applies whatever is
 * stored here every time it connects to that headset. `null` = never changed, left alone.
 */
object QuestSettingsStore {
    private const val PREFS_NAME = "settings"

    // Same key the sensor switch already used, so existing choices carry over.
    private fun sensorKey(device: String) = "quest_prox_sensor_on_$device"
    private fun guardianKey(device: String) = "quest_guardian_on_$device"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun sensorOn(context: Context, device: String): Boolean? =
        prefs(context).let { if (it.contains(sensorKey(device))) it.getBoolean(sensorKey(device), true) else null }

    fun setSensorOn(context: Context, device: String, on: Boolean) {
        prefs(context).edit().putBoolean(sensorKey(device), on).apply()
    }

    fun guardianOn(context: Context, device: String): Boolean? =
        prefs(context).let { if (it.contains(guardianKey(device))) it.getBoolean(guardianKey(device), true) else null }

    fun setGuardianOn(context: Context, device: String, on: Boolean) {
        prefs(context).edit().putBoolean(guardianKey(device), on).apply()
    }
}
