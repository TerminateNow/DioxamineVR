package io.github.rhythmcache.dioxamine.wirelesshelper

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.rhythmcache.dioxamine.core.AppLogger
import io.github.rhythmcache.dioxamine.wirelessdebug.WirelessDebugging

/**
 * Keeps Wireless debugging usable while the headset sleeps. Runs as a foreground service while
 * either option is on:
 *  - **Re-enable** ([PREF_REENABLE]): Android turns Wireless debugging off when Wi-Fi drops (which
 *    the Quest does when its screen turns off). This turns it back on as soon as Wi-Fi is back, the
 *    screen comes on, or the setting flips to off — only while Wi-Fi is connected, so it doesn't
 *    fight Android while there is no network.
 *  - **Keep Wi-Fi awake** ([PREF_WIFI_LOCK]): holds a high-performance Wi-Fi lock plus a partial
 *    wake lock so Wi-Fi (and Wireless debugging with it) stays up while the screen is off. Uses
 *    more battery.
 */
class WirelessWatchdogService : Service() {

    companion object {
        private const val TAG = "WirelessWatchdog"
        const val PREF_REENABLE = "watchdog_reenable"
        const val PREF_WIFI_LOCK = "watchdog_wifi_lock"
        private const val CHANNEL_ID = "wireless_watchdog"
        private const val NOTIFICATION_ID = 1
        private const val REENABLE_DELAY_MS = 1500L

        private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

        fun reenableOn(context: Context) = prefs(context).getBoolean(PREF_REENABLE, false)
        fun wifiLockOn(context: Context) = prefs(context).getBoolean(PREF_WIFI_LOCK, false)

        /** Starts, updates or stops the service to match the two switches. Never throws. */
        fun sync(context: Context) {
            val intent = Intent(context, WirelessWatchdogService::class.java)
            runCatching {
                if (reenableOn(context) || wifiLockOn(context)) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.stopService(intent)
                }
            }.onFailure { AppLogger.w(TAG, "Could not sync service: ${it.message}") }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var settingObserver: ContentObserver? = null

    private val checkRunnable = Runnable { reenableIfNeeded() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        applyOptions()
        return START_STICKY
    }

    override fun onDestroy() {
        stopWatching()
        releaseLocks()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun applyOptions() {
        val reenable = reenableOn(this)
        val keepWifi = wifiLockOn(this)
        if (!reenable && !keepWifi) {
            stopSelf()
            return
        }
        if (reenable) startWatching() else stopWatching()
        if (keepWifi) acquireLocks() else releaseLocks()
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification())
        if (reenable) scheduleCheck()
    }

    // -- Option a: re-enable ---------------------------------------------------------------

    private fun startWatching() {
        if (screenReceiver == null) {
            screenReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = scheduleCheck()
            }.also {
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_USER_PRESENT)
                }
                ContextCompat.registerReceiver(this, it, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            }
        }
        if (networkCallback == null) {
            val cm = getSystemService(ConnectivityManager::class.java)
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = scheduleCheck()
            }.also { cb ->
                runCatching {
                    cm?.registerNetworkCallback(
                        NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                        cb
                    )
                }.onFailure { AppLogger.w(TAG, "Network callback failed: ${it.message}") }
            }
        }
        if (settingObserver == null) {
            settingObserver = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) = scheduleCheck()
            }.also {
                contentResolver.registerContentObserver(Settings.Global.getUriFor("adb_wifi_enabled"), false, it)
            }
        }
    }

    private fun stopWatching() {
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenReceiver = null
        networkCallback?.let { cb ->
            runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
        }
        networkCallback = null
        settingObserver?.let { contentResolver.unregisterContentObserver(it) }
        settingObserver = null
        handler.removeCallbacks(checkRunnable)
    }

    private fun scheduleCheck() {
        handler.removeCallbacks(checkRunnable)
        handler.postDelayed(checkRunnable, REENABLE_DELAY_MS)
    }

    private fun wifiConnected(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    private fun reenableIfNeeded() {
        if (!reenableOn(this) || !WirelessDebugging.hasPermission(this)) return
        if (WirelessDebugging.isEnabled(this) || !wifiConnected()) return
        WirelessDebugging.enable(this)
            .onSuccess { AppLogger.i(TAG, "Wireless debugging was off; turned it back on") }
            .onFailure { AppLogger.w(TAG, "Re-enable failed: ${it.message}") }
    }

    // -- Option b: keep Wi-Fi awake -----------------------------------------------------------

    @Suppress("DEPRECATION") // FULL_HIGH_PERF: LOW_LATENCY only applies while the screen is on
    private fun acquireLocks() {
        if (wifiLock == null) {
            wifiLock = getSystemService(WifiManager::class.java)
                ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DioxamineHelper:wifi")
                ?.apply { setReferenceCounted(false); acquire() }
        }
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DioxamineHelper:wifi")
                ?.apply { setReferenceCounted(false); acquire() }
        }
        AppLogger.i(TAG, "Keeping Wi-Fi awake")
    }

    private fun releaseLocks() {
        wifiLock?.let { if (it.isHeld) runCatching { it.release() } }
        wifiLock = null
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
    }

    // -- Notification -------------------------------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.watchdog_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle(getString(R.string.watchdog_notification_title))
        .setContentText(
            getString(
                when {
                    reenableOn(this) && wifiLockOn(this) -> R.string.watchdog_notification_both
                    wifiLockOn(this) -> R.string.watchdog_notification_wifi
                    else -> R.string.watchdog_notification_reenable
                }
            )
        )
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, HelperActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .build()
}
