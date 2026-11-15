package io.github.rhythmcache.dioxamine.adb

import android.content.Context

/**
 * The single IP the user starred in Discover Devices. The app auto-connects to it on every launch;
 * only the host is stored because Wireless debugging picks a new port each time, which is found
 * again over mDNS.
 */
object FavoriteAdbHost {
    private const val PREFS_NAME = "settings"
    private const val KEY = "adb_favorite_host"

    fun get(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY, null)?.ifBlank { null }

    fun set(context: Context, host: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            if (host.isNullOrBlank()) remove(KEY) else putString(KEY, host.trim())
        }.apply()
    }

    fun isLocalhost(host: String): Boolean =
        host == "127.0.0.1" || host.equals("localhost", ignoreCase = true)
}
