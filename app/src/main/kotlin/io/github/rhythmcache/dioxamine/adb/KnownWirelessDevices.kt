package io.github.rhythmcache.dioxamine.adb

import android.content.Context

/**
 * Serial numbers (`ro.serialno`) of devices this app has connected to over Wireless debugging
 * (ADB over TLS). Wireless debugging advertises itself over mDNS as `adb-<serial>-<suffix>` with a
 * new port each time it is turned on, so the serial is what lets us find a known device again.
 * Most recently connected first.
 */
object KnownWirelessDevices {
    private const val PREFS_NAME = "adb_known_wireless"
    private const val KEY_SERIALS = "serials"
    private const val DELIMITER = "|"
    private const val MAX_SAVED = 10

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getAll(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_SERIALS, null) ?: return emptyList()
        return raw.split(DELIMITER).filter { it.isNotBlank() }
    }

    fun add(context: Context, serial: String) {
        val trimmed = serial.trim()
        if (trimmed.isEmpty() || trimmed.contains(DELIMITER)) return
        val current = getAll(context).toMutableList()
        current.remove(trimmed)
        current.add(0, trimmed)
        while (current.size > MAX_SAVED) current.removeAt(current.lastIndex)
        prefs(context).edit().putString(KEY_SERIALS, current.joinToString(DELIMITER)).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_SERIALS).apply()
    }
}
