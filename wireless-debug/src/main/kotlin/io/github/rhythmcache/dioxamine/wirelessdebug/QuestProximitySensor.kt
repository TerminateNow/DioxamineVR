package io.github.rhythmcache.dioxamine.wirelessdebug

import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.rhythmcache.dioxamine.core.AppLogger

/**
 * Meta Quest proximity ("light") sensor — the sensor between the lenses that turns the display off
 * when the headset is taken off. Controlled through broadcasts handled by the Quest's
 * `vrpowermanager`:
 *  - [ACTION_SENSOR_OFF] (`prox_close`): behave as if the headset is always worn, so the display
 *    stays on;
 *  - [ACTION_SENSOR_ON] (`automation_disable`): return to normal sensor behaviour.
 * The effect lasts until the headset reboots.
 */
object QuestProximitySensor {
    private const val TAG = "QuestProxSensor"

    const val ACTION_SENSOR_OFF = "com.oculus.vrpowermanager.prox_close"
    const val ACTION_SENSOR_ON = "com.oculus.vrpowermanager.automation_disable"

    /** Helper app preference: keep the sensor off, re-applied by [BootReceiver] after each boot. */
    const val PREF_SENSOR_OFF = "quest_prox_sensor_off"

    /** Shell command to run over ADB on the headset. */
    fun adbCommand(sensorOn: Boolean): String =
        "am broadcast -a ${if (sensorOn) ACTION_SENSOR_ON else ACTION_SENSOR_OFF}"

    /** True on Meta/Oculus hardware (checked on the device the code runs on). */
    val isQuestDevice: Boolean
        get() = Build.MANUFACTURER.equals("Oculus", ignoreCase = true) ||
            Build.MANUFACTURER.equals("Meta", ignoreCase = true)

    /** True if `ro.product.manufacturer` read over ADB is Meta/Oculus. */
    fun isQuestManufacturer(manufacturer: String?): Boolean =
        manufacturer?.trim()?.let { it.equals("Oculus", true) || it.equals("Meta", true) } == true

    /**
     * Sends the broadcast from this app (used by the helper on the headset itself). The Quest may
     * only honour these broadcasts from the ADB shell; then this has no effect and the ADB toggle
     * in Dioxamine should be used instead. Never throws.
     */
    fun sendFromApp(context: Context, sensorOn: Boolean): Result<Unit> = try {
        val intent = Intent(if (sensorOn) ACTION_SENSOR_ON else ACTION_SENSOR_OFF)
            // FLAG_RECEIVER_INCLUDE_BACKGROUND, as `am broadcast` effectively behaves for system receivers.
            .addFlags(0x01000000)
        context.sendBroadcast(intent)
        AppLogger.i(TAG, "Sent ${intent.action}")
        Result.success(Unit)
    } catch (e: Exception) {
        AppLogger.e(TAG, "Broadcast failed: ${e.message}")
        Result.failure(e)
    }
}
