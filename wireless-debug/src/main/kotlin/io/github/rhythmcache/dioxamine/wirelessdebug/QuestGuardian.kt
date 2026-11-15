package io.github.rhythmcache.dioxamine.wirelessdebug

/**
 * Meta Quest guardian (boundary), toggled through the `debug.oculus.guardian_pause` system
 * property: 1 pauses (turns off) the guardian, 0 turns it back on. Only the ADB shell may set it
 * (an app can't), and it resets when the headset reboots, so Dioxamine stores the choice per
 * headset and re-applies it over ADB each time it connects.
 */
object QuestGuardian {
    const val PROP = "debug.oculus.guardian_pause"

    fun adbSetCommand(guardianOn: Boolean): String = "setprop $PROP ${if (guardianOn) 0 else 1}"
    const val ADB_GET_COMMAND = "getprop $PROP"

    /** Parses `getprop` output: guardian is on unless the pause property is "1". */
    fun isGuardianOn(propValue: String?): Boolean = propValue?.trim() != "1"
}
