package io.github.rhythmcache.dioxamine.wirelessdebug

import io.github.rhythmcache.dioxamine.core.AppLogger

/**
 * Meta Quest guardian (boundary), toggled through the `debug.oculus.guardian_pause` system
 * property: 1 pauses (turns off) the guardian, 0 turns it back on. Setting it normally needs the
 * ADB shell; it resets when the headset reboots.
 */
object QuestGuardian {
    private const val TAG = "QuestGuardian"
    const val PROP = "debug.oculus.guardian_pause"

    /** Helper app preference: keep the guardian off, re-applied by [BootReceiver] after each boot. */
    const val PREF_GUARDIAN_OFF = "quest_guardian_off"

    fun adbSetCommand(guardianOn: Boolean): String = "setprop $PROP ${if (guardianOn) 0 else 1}"
    const val ADB_GET_COMMAND = "getprop $PROP"

    /** Parses `getprop` output: guardian is on unless the pause property is "1". */
    fun isGuardianOn(propValue: String?): Boolean = propValue?.trim() != "1"

    /** Reads the property on the device this code runs on (null if it can't be read). */
    fun readLocal(): String? = runCatching {
        val cls = Class.forName("android.os.SystemProperties")
        cls.getMethod("get", String::class.java).invoke(null, PROP) as? String
    }.getOrNull()

    /**
     * Tries to set the property from this app (used by the helper on the headset itself) and
     * reads it back to check. Android usually only lets the ADB shell set `debug.*` properties,
     * so this returns a failure when the app isn't allowed to; never throws.
     */
    fun setLocal(guardianOn: Boolean): Result<Unit> = try {
        val wanted = if (guardianOn) "0" else "1"
        val proc = Runtime.getRuntime().exec(arrayOf("setprop", PROP, wanted))
        proc.waitFor()
        val now = readLocal()?.trim().orEmpty()
        val ok = if (guardianOn) now != "1" else now == "1"
        if (ok) {
            AppLogger.i(TAG, "Guardian ${if (guardianOn) "on" else "off"} ($PROP=$now)")
            Result.success(Unit)
        } else {
            Result.failure(SecurityException("This headset only allows the guardian to be changed over ADB"))
        }
    } catch (e: Exception) {
        AppLogger.e(TAG, "setprop failed: ${e.message}")
        Result.failure(e)
    }
}
