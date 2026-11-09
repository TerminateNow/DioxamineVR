package io.github.rhythmcache.dioxamine.scrcpy

import org.junit.Assert.*
import org.junit.Test

class ScrcpyConfigTest {

    @Test
    fun testDefaultConfig_videoEnabled() {
        val config = ScrcpyConfig()
        assertTrue(config.videoEnabled)
        assertFalse(config.audioEnabled)

        val args = config.toServerArgs()
        assertTrue(args.contains("video=true"))
        assertTrue(args.contains("video_codec=h264"))
        assertTrue(args.contains("audio=false"))
    }

    @Test
    fun testVp8AndVp9Config_toServerArgs() {
        val vp8Config = ScrcpyConfig(videoCodec = "vp8")
        assertTrue(vp8Config.toServerArgs().contains("video_codec=vp8"))

        val vp9Config = ScrcpyConfig(videoCodec = "vp9")
        assertTrue(vp9Config.toServerArgs().contains("video_codec=vp9"))
    }

    @Test
    fun testAudioOnlyConfig_toServerArgs() {
        val config = ScrcpyConfig(
            videoEnabled = false,
            audioEnabled = true,
            audioCodec = "opus",
            audioBitRateKbps = 128
        )

        val args = config.toServerArgs()
        assertTrue(args.contains("video=false"))
        assertFalse(args.contains("video_codec="))
        assertFalse(args.contains("video_bit_rate="))
        assertFalse(args.contains("max_size="))
        assertTrue(args.contains("audio=true"))
        assertTrue(args.contains("audio_codec=opus"))
        assertTrue(args.contains("audio_bit_rate=128000"))
    }

    @Test
    fun testValidation_bothDisabled() {
        val config = ScrcpyConfig(
            videoEnabled = false,
            audioEnabled = false
        )
        val errors = config.validate(apiLevel = 33)
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("At least one stream"))
    }

    @Test
    fun testValidation_audioOnly_unsupportedApi() {
        val config = ScrcpyConfig(
            videoEnabled = false,
            audioEnabled = true
        )
        val errors = config.validate(apiLevel = 29)
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("requires at least Android 11 (API 30)"))
    }

    @Test
    fun testValidation_audioOnly_supportedApi() {
        val config = ScrcpyConfig(
            videoEnabled = false,
            audioEnabled = true
        )
        val errors = config.validate(apiLevel = 30)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun testTurnScreenOff_ignoredWhenCamera() {
        val config = ScrcpyConfig(
            videoSource = "camera",
            turnScreenOff = true
        )
        val args = config.toServerArgs()
        assertTrue(args.contains("video_source=camera"))
        assertFalse(args.contains("turn_screen_off=true"))
    }

    @Test
    fun testTurnScreenOff_ignoredWhenAudioOnly() {
        val config = ScrcpyConfig(
            videoEnabled = false,
            audioEnabled = true,
            turnScreenOff = true
        )
        val args = config.toServerArgs()
        assertTrue(args.contains("video=false"))
        assertFalse(args.contains("turn_screen_off=true"))
    }

    @Test
    fun testCrop_addedToServerArgsForDisplaySource() {
        val config = ScrcpyConfig(crop = "1280:720:0:0")
        assertTrue(config.toServerArgs().contains("crop=1280:720:0:0"))
        assertTrue(config.validate(apiLevel = 34).isEmpty())
    }

    @Test
    fun testCrop_notSentWhenEmptyOrInvalid() {
        assertFalse(ScrcpyConfig().toServerArgs().contains("crop="))
        assertFalse(ScrcpyConfig(crop = "").toServerArgs().contains("crop="))
        // an invalid value must never reach the shell command
        assertFalse(ScrcpyConfig(crop = "1280:720; reboot").toServerArgs().contains("crop="))
    }

    @Test
    fun testCrop_invalidFormatFailsValidation() {
        assertTrue(ScrcpyConfig(crop = "1280:720").validate(apiLevel = 34).isNotEmpty())
        assertTrue(ScrcpyConfig(crop = "0:720:0:0").validate(apiLevel = 34).isNotEmpty())
        assertTrue(ScrcpyConfig(crop = "a:b:c:d").validate(apiLevel = 34).isNotEmpty())
    }

    @Test
    fun testCrop_notUsedForCameraSource() {
        val config = ScrcpyConfig(videoSource = "camera", crop = "1280:720:0:0")
        assertFalse(config.toServerArgs().contains("crop="))
    }

    @Test
    fun testCrop_survivesJsonRoundTrip() {
        val restored = ScrcpyConfig.fromJson(ScrcpyConfig(crop = "960:1080:0:0").toJson())
        assertEquals("960:1080:0:0", restored.crop)
        assertNull(ScrcpyConfig.fromJson(ScrcpyConfig().toJson()).crop)
    }

    @Test
    fun testAngle_addedToServerArgsForDisplaySource() {
        val config = ScrcpyConfig(angle = "-22")
        assertTrue(config.toServerArgs().contains("angle=-22"))
        assertTrue(config.validate(apiLevel = 34).isEmpty())
    }

    @Test
    fun testAngle_notSentWhenEmptyZeroOrInvalid() {
        assertFalse(ScrcpyConfig().toServerArgs().contains("angle="))
        assertFalse(ScrcpyConfig(angle = "0").toServerArgs().contains("angle="))
        assertFalse(ScrcpyConfig(angle = "-").toServerArgs().contains("angle="))
        // an invalid value must never reach the shell command
        assertFalse(ScrcpyConfig(angle = "22; reboot").toServerArgs().contains("angle="))
        assertFalse(ScrcpyConfig(angle = "400").toServerArgs().contains("angle="))
    }

    @Test
    fun testAngle_invalidValueFailsValidation() {
        assertTrue(ScrcpyConfig(angle = "abc").validate(apiLevel = 34).isNotEmpty())
        assertTrue(ScrcpyConfig(angle = "361").validate(apiLevel = 34).isNotEmpty())
        assertTrue(ScrcpyConfig(angle = "12.5").validate(apiLevel = 34).isEmpty())
    }

    @Test
    fun testAngle_notUsedForCameraSource() {
        val config = ScrcpyConfig(videoSource = "camera", angle = "-22")
        assertFalse(config.toServerArgs().contains("angle="))
    }

    @Test
    fun testAngle_stepButtons() {
        assertEquals("1", ScrcpyConfig.stepAngle(null, 1.0))
        assertEquals("-1", ScrcpyConfig.stepAngle(null, -1.0))
        assertEquals("-21", ScrcpyConfig.stepAngle("-22", 1.0))
        assertEquals("-23", ScrcpyConfig.stepAngle("-22", -1.0))
        assertEquals("-21.5", ScrcpyConfig.stepAngle("-22.5", 1.0))
        assertNull(ScrcpyConfig.stepAngle("1", -1.0))
        assertEquals("1", ScrcpyConfig.stepAngle("-", 1.0))
        assertEquals("360", ScrcpyConfig.stepAngle("360", 1.0))
    }

    @Test
    fun testAngle_survivesJsonRoundTrip() {
        val restored = ScrcpyConfig.fromJson(ScrcpyConfig(angle = "-22").toJson())
        assertEquals("-22", restored.angle)
        assertNull(ScrcpyConfig.fromJson(ScrcpyConfig().toJson()).angle)
    }
}
