package io.github.rhythmcache.dioxamine.wirelesshelper

import android.app.Application
import io.github.rhythmcache.dioxamine.core.AppLogger

class HelperApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
    }
}
