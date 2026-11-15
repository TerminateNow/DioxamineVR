package io.github.rhythmcache.dioxamine.ui.visibility

import androidx.annotation.StringRes
import io.github.rhythmcache.dioxamine.R

/** One entry in Settings > Appearance > Hide/Show Sections. */
data class VisNode(
    val id: String,
    @StringRes val label: Int,
    val children: List<VisNode> = emptyList(),
)

/** Stable ids used by the screens (`UiVisibility.isShown(VisIds.X)`). Never rename: they are persisted. */
object VisIds {
    const val TAB_ADB = "tab.adb"
    const val TAB_SCRCPY = "tab.scrcpy"
    const val TAB_FASTBOOT = "tab.fastboot"
    const val TAB_PLUGINS = "tab.plugins"

    const val ADB_BUILTIN = "adb.builtin"
    const val ADB_HELPER = "adb.builtin.helper"
    const val ADB_METAQUEST = "adb.builtin.metaquest"
    const val ADB_DEVICE_INFO = "adb.builtin.device_info"
    const val ADB_REMOTE = "adb.builtin.remote_control"
    const val ADB_TOUCHPAD = "adb.builtin.touchpad"
    const val ADB_FILES = "adb.builtin.files"
    const val ADB_PACKAGES = "adb.builtin.packages"
    const val ADB_PROCESSES = "adb.builtin.processes"
    const val ADB_LOGCAT = "adb.builtin.logcat"
    const val ADB_MISC = "adb.builtin.misc"
    const val ADB_SCREENSHOT = "adb.builtin.screenshot"
    const val ADB_REBOOT = "adb.builtin.reboot"
    const val ADB_SHELL = "adb.shell"

    const val SCRCPY_SETTINGS = "scrcpy.settings"
    const val SCRCPY_PRESETS = "scrcpy.presets"
    const val SCRCPY_VIDEO = "scrcpy.video"
    const val SCRCPY_VIDEO_ENABLE = "scrcpy.video.enable"
    const val SCRCPY_VIDEO_SOURCE = "scrcpy.video.source"
    const val SCRCPY_CAMERA = "scrcpy.video.camera"
    const val SCRCPY_VIRTUAL = "scrcpy.video.virtual"
    const val SCRCPY_MAX_SIZE = "scrcpy.video.max_size"
    const val SCRCPY_FPS = "scrcpy.video.fps"
    const val SCRCPY_BITRATE = "scrcpy.video.bitrate"
    const val SCRCPY_CODEC = "scrcpy.video.codec"
    const val SCRCPY_ORIENTATION = "scrcpy.video.orientation"
    const val SCRCPY_CROP = "scrcpy.video.crop"
    const val SCRCPY_ANGLE = "scrcpy.video.angle"
    const val SCRCPY_AUDIO = "scrcpy.audio"
    const val SCRCPY_AUDIO_ENABLE = "scrcpy.audio.enable"
    const val SCRCPY_AUDIO_SOURCE = "scrcpy.audio.source"
    const val SCRCPY_AUDIO_FORMAT = "scrcpy.audio.format"
    const val SCRCPY_AUDIO_BUFFER = "scrcpy.audio.buffer"
    const val SCRCPY_AUDIO_BITRATE = "scrcpy.audio.bitrate"
    const val SCRCPY_AUDIO_DUP = "scrcpy.audio.dup"
    const val SCRCPY_DISPLAY = "scrcpy.display"
    const val SCRCPY_SCREEN_OFF = "scrcpy.display.screen_off"
    const val SCRCPY_FLOATING_NAV = "scrcpy.display.floating_nav"
    const val SCRCPY_VOLUME_KEYS = "scrcpy.display.volume_keys"
    const val SCRCPY_CLIPBOARD = "scrcpy.display.clipboard"
    const val SCRCPY_TOUCHPAD = "scrcpy.display.touchpad"
    const val SCRCPY_KEYBOARD = "scrcpy.display.keyboard"
    const val SCRCPY_FOOTER = "scrcpy.footer"
    const val SCRCPY_RECORDINGS = "scrcpy.recordings"
    const val SCRCPY_LOGS = "scrcpy.logs"

    const val FASTBOOT_ACTIONS = "fastboot.actions"
    const val FASTBOOT_REBOOT = "fastboot.actions.reboot"
    const val FASTBOOT_FLASH = "fastboot.actions.flash"
    const val FASTBOOT_BOOT = "fastboot.actions.boot"
    const val FASTBOOT_LOCK = "fastboot.actions.lock"
    const val FASTBOOT_VARS = "fastboot.actions.vars"
    const val FASTBOOT_SHELL = "fastboot.shell"

    const val PLUGINS_INSTALLED = "plugins.installed"
    const val PLUGINS_BROWSE = "plugins.browse"
}

/**
 * Every hideable section and setting, in on-screen order. The Settings tab is deliberately not
 * listed (always shown), and neither are the device connector cards or the scrcpy Start button.
 */
val AppSections: List<VisNode> = listOf(
    VisNode(VisIds.TAB_ADB, R.string.tab_adb, listOf(
        VisNode(VisIds.ADB_BUILTIN, R.string.adb_subtab_builtin, listOf(
            VisNode(VisIds.ADB_HELPER, R.string.wireless_helper_tile_title_installed),
            VisNode(VisIds.ADB_METAQUEST, R.string.meta_quest_tile_title),
            VisNode(VisIds.ADB_DEVICE_INFO, R.string.action_device_info_title),
            VisNode(VisIds.ADB_REMOTE, R.string.remote_control_title),
            VisNode(VisIds.ADB_TOUCHPAD, R.string.touchpad_title),
            VisNode(VisIds.ADB_FILES, R.string.file_manager_title),
            VisNode(VisIds.ADB_PACKAGES, R.string.pkg_manager_title),
            VisNode(VisIds.ADB_PROCESSES, R.string.proc_manager_title),
            VisNode(VisIds.ADB_LOGCAT, R.string.adb_logcat_tile_title),
            VisNode(VisIds.ADB_MISC, R.string.adb_misc_tile_title),
            VisNode(VisIds.ADB_SCREENSHOT, R.string.adb_screenshot_tile_title),
            VisNode(VisIds.ADB_REBOOT, R.string.adb_reboot_tile_title),
        )),
        VisNode(VisIds.ADB_SHELL, R.string.adb_subtab_adb_shell),
    )),
    VisNode(VisIds.TAB_SCRCPY, R.string.tab_scrcpy, listOf(
        VisNode(VisIds.SCRCPY_SETTINGS, R.string.scrcpy_tab_configurator, listOf(
            VisNode(VisIds.SCRCPY_PRESETS, R.string.vis_scrcpy_presets),
            VisNode(VisIds.SCRCPY_VIDEO, R.string.section_video, listOf(
                VisNode(VisIds.SCRCPY_VIDEO_ENABLE, R.string.video_enable_title),
                VisNode(VisIds.SCRCPY_VIDEO_SOURCE, R.string.section_video_source),
                VisNode(VisIds.SCRCPY_CAMERA, R.string.section_camera),
                VisNode(VisIds.SCRCPY_VIRTUAL, R.string.section_virtual_screen),
                VisNode(VisIds.SCRCPY_MAX_SIZE, R.string.label_resolution),
                VisNode(VisIds.SCRCPY_FPS, R.string.label_frame_rate),
                VisNode(VisIds.SCRCPY_BITRATE, R.string.label_bitrate),
                VisNode(VisIds.SCRCPY_CODEC, R.string.label_codec),
                VisNode(VisIds.SCRCPY_ORIENTATION, R.string.label_orientation),
                VisNode(VisIds.SCRCPY_CROP, R.string.label_crop),
                VisNode(VisIds.SCRCPY_ANGLE, R.string.label_angle),
            )),
            VisNode(VisIds.SCRCPY_AUDIO, R.string.section_audio, listOf(
                VisNode(VisIds.SCRCPY_AUDIO_ENABLE, R.string.audio_enable_title),
                VisNode(VisIds.SCRCPY_AUDIO_SOURCE, R.string.label_audio_source),
                VisNode(VisIds.SCRCPY_AUDIO_FORMAT, R.string.label_audio_format),
                VisNode(VisIds.SCRCPY_AUDIO_BUFFER, R.string.label_audio_buffer),
                VisNode(VisIds.SCRCPY_AUDIO_BITRATE, R.string.label_audio_bitrate),
                VisNode(VisIds.SCRCPY_AUDIO_DUP, R.string.audio_dup_title),
            )),
            VisNode(VisIds.SCRCPY_DISPLAY, R.string.section_display, listOf(
                VisNode(VisIds.SCRCPY_SCREEN_OFF, R.string.display_screen_off_title),
                VisNode(VisIds.SCRCPY_FLOATING_NAV, R.string.display_floating_nav_title),
                VisNode(VisIds.SCRCPY_VOLUME_KEYS, R.string.display_bind_volume_title),
                VisNode(VisIds.SCRCPY_CLIPBOARD, R.string.display_clipboard_sync_title),
                VisNode(VisIds.SCRCPY_TOUCHPAD, R.string.display_touchpad_title),
                VisNode(VisIds.SCRCPY_KEYBOARD, R.string.display_keyboard_title),
            )),
            VisNode(VisIds.SCRCPY_FOOTER, R.string.vis_scrcpy_footer),
        )),
        VisNode(VisIds.SCRCPY_RECORDINGS, R.string.scrcpy_tab_recordings),
        VisNode(VisIds.SCRCPY_LOGS, R.string.scrcpy_tab_logs),
    )),
    VisNode(VisIds.TAB_FASTBOOT, R.string.tab_fastboot, listOf(
        VisNode(VisIds.FASTBOOT_ACTIONS, R.string.fastboot_tab_actions, listOf(
            VisNode(VisIds.FASTBOOT_REBOOT, R.string.fastboot_tile_reboot_title),
            VisNode(VisIds.FASTBOOT_FLASH, R.string.fastboot_tile_flash_title),
            VisNode(VisIds.FASTBOOT_BOOT, R.string.fastboot_tile_boot_title),
            VisNode(VisIds.FASTBOOT_LOCK, R.string.fastboot_tile_lock_title),
            VisNode(VisIds.FASTBOOT_VARS, R.string.fastboot_tile_vars_title),
        )),
        VisNode(VisIds.FASTBOOT_SHELL, R.string.fastboot_tab_shell),
    )),
    VisNode(VisIds.TAB_PLUGINS, R.string.tab_plugins, listOf(
        VisNode(VisIds.PLUGINS_INSTALLED, R.string.plugins_tab_installed),
        VisNode(VisIds.PLUGINS_BROWSE, R.string.plugins_tab_browse),
    )),
)
