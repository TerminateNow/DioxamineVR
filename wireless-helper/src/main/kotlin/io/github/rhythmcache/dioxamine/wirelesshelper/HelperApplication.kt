package io.github.rhythmcache.dioxamine.wirelesshelper

import android.app.Application
import android.content.Context
import io.github.rhythmcache.dioxamine.core.AppLogger
import io.github.rhythmcache.dioxamine.wirelessdebug.WirelessDebugging

class HelperApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)

        // Enabling at boot is the whole point of this app, so it defaults to on. Only the first
        // launch writes it; after that the user's choice in the switch is kept.
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (!prefs.contains(WirelessDebugging.PREF_ENABLE_ON_BOOT)) {
            prefs.edit().putBoolean(WirelessDebugging.PREF_ENABLE_ON_BOOT, true).apply()
        }
    }
}
