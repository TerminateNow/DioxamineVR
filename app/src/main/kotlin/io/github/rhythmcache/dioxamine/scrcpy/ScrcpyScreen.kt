package io.github.rhythmcache.dioxamine.scrcpy

import kotlinx.coroutines.delay
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import io.github.rhythmcache.dioxamine.ui.tv.Button
import io.github.rhythmcache.dioxamine.ui.tv.FilterChip
import io.github.rhythmcache.dioxamine.ui.tv.IconButton
import io.github.rhythmcache.dioxamine.ui.tv.OutlinedButton
import io.github.rhythmcache.dioxamine.ui.tv.OutlinedTextField
import io.github.rhythmcache.dioxamine.ui.tv.Switch
import io.github.rhythmcache.dioxamine.ui.tv.TextButton
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.input.key.onPreviewKeyEvent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import io.github.rhythmcache.dioxamine.adb.builtin.touchpad.HidKeyCodes
import io.github.rhythmcache.dioxamine.adb.builtin.touchpad.VirtualKeyboardHelper
import io.github.rhythmcache.dioxamine.core.AppLogger
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.rhythmcache.dioxamine.R
import io.github.rhythmcache.dioxamine.adb.AdbViewModel
import io.github.rhythmcache.adb.AdbDeviceMode
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt

private enum class ScrcpyTab { CONFIGURATOR, RECORDINGS, LOGS }
private enum class AddCustomDialogType { MAX_SIZE, FPS, BITRATE, AUDIO_BITRATE, DPI }
private enum class ControlSide { LEFT, TOP, RIGHT, BOTTOM }
private enum class DiscoveryState { IDLE, LOADING, SUCCESS, EMPTY, TIMED_OUT, FAILED }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScrcpyScreen(
    vm: AdbViewModel,
    scrcpyVm: ScrcpyViewModel = viewModel(),
    onFullScreenChange: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val activeId = vm.activeDeviceId
    val activeConn = activeId?.let { vm.devices[it] }
    val client = vm.activeClient()
    val isDeviceConnected = activeConn != null && client != null

    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    val allowCustomValues = remember(prefs) { prefs.getBoolean("scrcpy_allow_custom_values", false) }

    var customMaxSizes by remember {
        mutableStateOf(
            prefs.getStringSet("scrcpy_custom_max_sizes", emptySet())
                ?.mapNotNull { it.toIntOrNull() }?.sorted() ?: emptyList()
        )
    }
    var customFps by remember {
        mutableStateOf(
            prefs.getStringSet("scrcpy_custom_fps", emptySet())
                ?.mapNotNull { it.toIntOrNull() }?.sorted() ?: emptyList()
        )
    }
    var customBitrates by remember {
        mutableStateOf(
            prefs.getStringSet("scrcpy_custom_bitrates", emptySet())
                ?.mapNotNull { it.toIntOrNull() }?.sorted() ?: emptyList()
        )
    }
    var customAudioBitrates by remember {
        mutableStateOf(
            prefs.getStringSet("scrcpy_custom_audio_bitrates", emptySet())
                ?.mapNotNull { it.toIntOrNull() }?.sorted() ?: emptyList()
        )
    }
    var customDpis by remember {
        mutableStateOf(
            prefs.getStringSet("scrcpy_custom_dpis", emptySet())
                ?.mapNotNull { it.toIntOrNull() }?.sorted() ?: emptyList()
        )
    }

    var activeAddDialog by remember { mutableStateOf<AddCustomDialogType?>(null) }
    var dialogInputValue by remember { mutableStateOf("") }
    var dialogErrorMsg by remember { mutableStateOf<String?>(null) }

    val deviceKey = activeConn?.uniqueId ?: activeConn?.id
    var config by remember(deviceKey) {
        mutableStateOf(scrcpyVm.activeSession?.config ?: ScrcpyConfig())
    }
    var rememberSettings by remember(deviceKey) {
        mutableStateOf(ScrcpyConfigStore.isRememberEnabled(context, deviceKey))
    }

    LaunchedEffect(deviceKey) {
        if (deviceKey != null) {
            val savedConfig = ScrcpyConfigStore.load(context, deviceKey)
            if (savedConfig != null) {
                if (!scrcpyVm.isMirroring) {
                    config = savedConfig
                    scrcpyVm.showFloatingNav = savedConfig.showFloatingNav
                    scrcpyVm.showTouchpadOverlay = savedConfig.showTouchpad
                    scrcpyVm.showKeyboard = savedConfig.showKeyboard
                }
            } else if (!scrcpyVm.isMirroring) {
                config = ScrcpyConfig()
                scrcpyVm.showFloatingNav = false
                scrcpyVm.showTouchpadOverlay = false
                scrcpyVm.showKeyboard = false
            }
            rememberSettings = ScrcpyConfigStore.isRememberEnabled(context, deviceKey)
        }
    }

    val isMirroring = scrcpyVm.isMirroring
    val isRecording = scrcpyVm.isRecording
    var isFullScreen by remember { mutableStateOf(false) }

    var selectedTab by remember(isDeviceConnected) {
        mutableStateOf(if (isDeviceConnected) ScrcpyTab.CONFIGURATOR else ScrcpyTab.RECORDINGS)
    }

    val canRecord = (config.videoEnabled || config.audioEnabled) &&
            (!config.videoEnabled || config.videoCodec in listOf("h264", "h265")) &&
            (!config.audioEnabled || config.audioCodec == "aac")

    LaunchedEffect(isDeviceConnected) {
        if (!isDeviceConnected) {
            selectedTab = ScrcpyTab.RECORDINGS
        }
    }

    LaunchedEffect(isFullScreen) {
        onFullScreenChange(isFullScreen)
    }

    LaunchedEffect(allowCustomValues) {
        if (!allowCustomValues) {
            val defaultMaxSizes = listOf(0, 480, 720, 1080)
            val defaultFps = listOf(15, 30, 60)
            val defaultBitrates = listOf(2, 4, 8)
            val defaultAudioBitrates = listOf(64, 128, 192, 256, 320)
            val defaultDpis = listOf(null, 160, 240, 320, 420, 480)

            var newConfig = config
            if (config.maxSize !in defaultMaxSizes) newConfig = newConfig.copy(maxSize = 1080)
            if (config.maxFps !in defaultFps) newConfig = newConfig.copy(maxFps = 60)
            if (config.bitRateMbps !in defaultBitrates) newConfig = newConfig.copy(bitRateMbps = 8)
            if (config.audioBitRateKbps !in defaultAudioBitrates) newConfig = newConfig.copy(audioBitRateKbps = 128)
            if (config.newDisplayDpi !in defaultDpis) newConfig = newConfig.copy(newDisplayDpi = null)
            config = newConfig
        }
    }

    val torchOn = scrcpyVm.torchOn
    val errorMessage = scrcpyVm.errorMessage

    val videoWidth = scrcpyVm.videoWidth
    val videoHeight = scrcpyVm.videoHeight

    val activeSession = scrcpyVm.activeSession

    var discoveredCameras by remember(activeId) {
        mutableStateOf(activeId?.let { ScrcpyDiscoveryCache.getCameras(it) } ?: emptyList())
    }
    var cameraDiscoveryState by remember(activeId) {
        mutableStateOf(
            if (activeId != null && !ScrcpyDiscoveryCache.getCameras(activeId).isNullOrEmpty()) {
                DiscoveryState.SUCCESS
            } else {
                DiscoveryState.IDLE
            }
        )
    }
    var refreshCamerasTrigger by remember { mutableIntStateOf(0) }

    var discoveredApps by remember(activeId) {
        mutableStateOf(activeId?.let { ScrcpyDiscoveryCache.getApps(it) } ?: emptyList())
    }
    var appDiscoveryState by remember(activeId) {
        mutableStateOf(
            if (activeId != null && !ScrcpyDiscoveryCache.getApps(activeId).isNullOrEmpty()) {
                DiscoveryState.SUCCESS
            } else {
                DiscoveryState.IDLE
            }
        )
    }
    var refreshAppsTrigger by remember { mutableIntStateOf(0) }

    val apiLevel = activeConn?.apiLevel ?: 30
    val supportsAudio = apiLevel >= 30
    val supportsCamera = apiLevel >= 31
    val supportsVirtual = apiLevel >= 29

    LaunchedEffect(supportsCamera) {
        if (!supportsCamera && config.videoSource == "camera") {
            config = config.copy(videoSource = "display")
        }
    }

    LaunchedEffect(supportsVirtual) {
        if (!supportsVirtual && config.videoSource == "virtual") {
            config = config.copy(videoSource = "display")
        }
    }

    LaunchedEffect(activeId, config.videoSource, refreshCamerasTrigger) {
        val currentActiveId = activeId ?: return@LaunchedEffect
        val currentClient = client ?: return@LaunchedEffect

        if (supportsCamera && config.videoSource == "camera") {
            val cached = ScrcpyDiscoveryCache.getCameras(currentActiveId)
            if (!cached.isNullOrEmpty()) {
                discoveredCameras = cached
                cameraDiscoveryState = DiscoveryState.SUCCESS
                return@LaunchedEffect
            }
            cameraDiscoveryState = DiscoveryState.LOADING
            try {
                when (val result = ScrcpyDiscoveryCache.runQuery(currentClient, context, currentActiveId, "list_camera_sizes=true")) {
                    is DiscoveryQueryResult.Success -> {
                        val parsed = ScrcpyCameraParser.parse(result.output)
                        if (parsed.isNotEmpty()) {
                            ScrcpyDiscoveryCache.setCameras(currentActiveId, parsed)
                            discoveredCameras = parsed
                            cameraDiscoveryState = DiscoveryState.SUCCESS
                        } else {
                            AppLogger.w("ScrcpyScreen", "Discovered 0 cameras. Output: ${result.output}")
                            cameraDiscoveryState = DiscoveryState.EMPTY
                        }
                    }
                    is DiscoveryQueryResult.TimedOut -> {
                        AppLogger.w("ScrcpyScreen", "Camera discovery timed out after ${result.timeoutMs}ms")
                        cameraDiscoveryState = DiscoveryState.TIMED_OUT
                    }
                    is DiscoveryQueryResult.Failed -> {
                        AppLogger.e("ScrcpyScreen", "Camera discovery failed: ${result.reason}", result.cause)
                        cameraDiscoveryState = DiscoveryState.FAILED
                    }
                }
            } catch (e: CancellationException) {
                if (cameraDiscoveryState == DiscoveryState.LOADING) {
                    cameraDiscoveryState = DiscoveryState.IDLE
                }
                throw e
            } catch (e: Exception) {
                AppLogger.e("ScrcpyScreen", "Failed to discover cameras", e)
                cameraDiscoveryState = DiscoveryState.FAILED
            }
        }
    }

    LaunchedEffect(activeId, config.videoSource, refreshAppsTrigger) {
        val currentActiveId = activeId ?: return@LaunchedEffect
        val currentClient = client ?: return@LaunchedEffect

        if (supportsVirtual && config.videoSource == "virtual") {
            val cached = ScrcpyDiscoveryCache.getApps(currentActiveId)
            if (!cached.isNullOrEmpty()) {
                discoveredApps = cached
                appDiscoveryState = DiscoveryState.SUCCESS
                return@LaunchedEffect
            }
            appDiscoveryState = DiscoveryState.LOADING
            try {
                when (val result = ScrcpyDiscoveryCache.runQuery(currentClient, context, currentActiveId, "list_apps=true")) {
                    is DiscoveryQueryResult.Success -> {
                        val parsed = ScrcpyAppParser.parse(result.output)
                        if (parsed.isNotEmpty()) {
                            ScrcpyDiscoveryCache.setApps(currentActiveId, parsed)
                            discoveredApps = parsed
                            appDiscoveryState = DiscoveryState.SUCCESS
                        } else {
                            AppLogger.w("ScrcpyScreen", "Discovered 0 apps. Output: ${result.output}")
                            appDiscoveryState = DiscoveryState.EMPTY
                        }
                    }
                    is DiscoveryQueryResult.TimedOut -> {
                        AppLogger.w("ScrcpyScreen", "App discovery timed out after ${result.timeoutMs}ms")
                        appDiscoveryState = DiscoveryState.TIMED_OUT
                    }
                    is DiscoveryQueryResult.Failed -> {
                        AppLogger.e("ScrcpyScreen", "App discovery failed: ${result.reason}", result.cause)
                        appDiscoveryState = DiscoveryState.FAILED
                    }
                }
            } catch (e: CancellationException) {
                if (appDiscoveryState == DiscoveryState.LOADING) {
                    appDiscoveryState = DiscoveryState.IDLE
                }
                throw e
            } catch (e: Exception) {
                AppLogger.e("ScrcpyScreen", "Failed to discover apps", e)
                appDiscoveryState = DiscoveryState.FAILED
            }
        }
    }

    val isModeSupported = activeConn == null || activeConn.mode !in setOf(AdbDeviceMode.SIDELOAD, AdbDeviceMode.RECOVERY, AdbDeviceMode.RESCUE)
    val unsupportedModeName = when (activeConn?.mode) {
        AdbDeviceMode.SIDELOAD -> "Sideload"
        AdbDeviceMode.RECOVERY -> "Recovery"
        AdbDeviceMode.RESCUE -> "Rescue"
        else -> "this"
    }

    fun stopMirroring() {
        scrcpyVm.stopMirroring()
        isFullScreen = false
    }

    DisposableEffect(Unit) {
        onDispose {
            onFullScreenChange(false)
        }
    }

    fun startMirroring(holder: SurfaceHolder? = null) {
        val activeClient = client ?: return
        val validationErrors = config.validate(apiLevel)
        if (validationErrors.isNotEmpty()) {
            scrcpyVm.errorMessage = context.getString(R.string.scrcpy_invalid_config_prefix) + validationErrors.joinToString("\n") { "\u2022 $it" }
            scrcpyVm.isMirroring = false
            return
        }

        if (rememberSettings && deviceKey != null) {
            ScrcpyConfigStore.save(context, deviceKey, config)
        }

        val isDisplayMode = config.videoEnabled && config.videoSource != "camera"
        scrcpyVm.showFloatingNav = if (isDisplayMode) config.showFloatingNav else false
        scrcpyVm.showTouchpadOverlay = if (isDisplayMode) config.showTouchpad else false
        scrcpyVm.showKeyboard = if (isDisplayMode) config.showKeyboard else false

        val session = ScrcpySession(
            context = context.applicationContext,
            client = activeClient,
            config = config,
            onDimensions = { w, h ->
                scrcpyVm.videoWidth = w
                scrcpyVm.videoHeight = h
            },
            onError = { msg ->
                if (scrcpyVm.isMirroring) {
                    scrcpyVm.errorMessage = msg
                    scrcpyVm.stopMirroring()
                }
            },
            onRecordingStateChanged = { recording ->
                scrcpyVm.isRecording = recording
            },
            awaitPreviousTeardown = {
                scrcpyVm.awaitPreviousTeardown()
            }
        )
        scrcpyVm.activeSession = session
        scrcpyVm.isMirroring = true
        ScrcpyStreamStats.reset()
        session.start(holder)
    }

    fun toggleRecording() {
        val session = activeSession ?: return
        if (isRecording) {
            session.stopRecording()
        } else {
            session.startRecording()
        }
    }

    // ---- Mirrored device volume (media stream) via `cmd media_session volume` ----
    var remoteVolume by remember { mutableStateOf<Int?>(null) }
    var remoteVolumeMax by remember { mutableStateOf<Int?>(null) }
    var pendingRemoteVolume by remember { mutableStateOf<Int?>(null) }
    val volumeClient = client
    LaunchedEffect(isMirroring, volumeClient) {
        remoteVolume = null
        remoteVolumeMax = null
        if (isMirroring && volumeClient != null) {
            val parsed = runCatching {
                withContext(Dispatchers.IO) {
                    val r = volumeClient.shell("cmd media_session volume --stream 3 --get")
                    r.stdoutText + "\n" + r.stderrText
                }
            }.getOrNull()?.let { parseMediaSessionVolume(it) }
            if (parsed != null) {
                remoteVolume = parsed.first
                remoteVolumeMax = parsed.second
            }
        }
    }
    // Debounced so holding Up/Down doesn't flood the device with shell commands.
    LaunchedEffect(pendingRemoteVolume) {
        val target = pendingRemoteVolume ?: return@LaunchedEffect
        kotlinx.coroutines.delay(120)
        val c = volumeClient ?: return@LaunchedEffect
        runCatching { withContext(Dispatchers.IO) { c.shell("cmd media_session volume --stream 3 --set $target") } }
    }
    fun setRemoteVolume(level: Int) {
        val max = remoteVolumeMax ?: return
        val v = level.coerceIn(0, max)
        remoteVolume = v
        pendingRemoteVolume = v
    }
    fun stepRemoteVolume(delta: Int) {
        val cur = remoteVolume
        if (cur != null && remoteVolumeMax != null) {
            setRemoteVolume(cur + delta)
        } else {
            // Absolute volume unavailable: fall back to volume key presses on the device.
            val key = if (delta > 0) KeyEvent.KEYCODE_VOLUME_UP else KeyEvent.KEYCODE_VOLUME_DOWN
            activeSession?.sendKeycode(0, key)
            activeSession?.sendKeycode(1, key)
        }
    }

    // Double-press Back while mirroring: first press shows a hint for 3 s, second press stops
    // mirroring and returns to the Settings tab. (An expanded control overlay closes first.)
    var backExitArmed by remember { mutableStateOf(false) }
    var backArmToken by remember { mutableIntStateOf(0) }
    LaunchedEffect(backArmToken) {
        if (backExitArmed) {
            kotlinx.coroutines.delay(3000)
            backExitArmed = false
        }
    }
    // Mirroring always plays fullscreen.
    LaunchedEffect(isMirroring) {
        isFullScreen = isMirroring
        if (!isMirroring) backExitArmed = false
    }
    BackHandler(enabled = isMirroring) {
        if (backExitArmed) {
            backExitArmed = false
            stopMirroring()
            selectedTab = ScrcpyTab.CONFIGURATOR
        } else {
            backExitArmed = true
            backArmToken++
        }
    }

    val scrollState = rememberScrollState()

    Box(modifier = Modifier.fillMaxSize()) {
        if (isMirroring) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
            if (config.videoEnabled) {
                ScrcpyVideoPlayer(
                    modifier = Modifier.fillMaxSize(),
                    session = activeSession,
                    videoWidth = videoWidth,
                    videoHeight = videoHeight,
                    isFullScreen = isFullScreen,
                    showFloatingNav = scrcpyVm.showFloatingNav && config.videoSource != "camera",
                    onToggleFloatingNav = {
                        if (config.videoSource != "camera") {
                            val next = !scrcpyVm.showFloatingNav
                            scrcpyVm.showFloatingNav = next
                            config = config.copy(showFloatingNav = next)
                            if (rememberSettings && deviceKey != null) {
                                ScrcpyConfigStore.save(context, deviceKey, config)
                            }
                        }
                    },
                    showTouchpadOverlay = scrcpyVm.showTouchpadOverlay && config.videoSource != "camera",
                    onToggleTouchpadOverlay = {
                        if (config.videoSource != "camera") {
                            val next = !scrcpyVm.showTouchpadOverlay
                            scrcpyVm.showTouchpadOverlay = next
                            config = config.copy(showTouchpad = next)
                            if (next) activeSession?.ensureUhidDevices()
                            if (rememberSettings && deviceKey != null) {
                                ScrcpyConfigStore.save(context, deviceKey, config)
                            }
                        }
                    },
                    showKeyboard = scrcpyVm.showKeyboard && config.videoSource != "camera",
                    onToggleKeyboard = { next ->
                        if (config.videoSource != "camera") {
                            scrcpyVm.showKeyboard = next
                            config = config.copy(showKeyboard = next)
                            if (next) activeSession?.ensureUhidDevices()
                            if (rememberSettings && deviceKey != null) {
                                ScrcpyConfigStore.save(context, deviceKey, config)
                            }
                        }
                    },
                    bindVolumeKeys = config.bindVolumeKeys,
                    videoSourceIsCamera = config.videoSource == "camera",
                    torchOn = torchOn,
                    isRecording = isRecording,
                    canRecord = canRecord,
                    onToggleRecord = { toggleRecording() },
                    onToggleTorch = {
                        scrcpyVm.torchOn = !torchOn
                        activeSession?.sendCameraSetTorch(scrcpyVm.torchOn)
                    },
                    onToggleFullScreen = { },
                    onRotateDevice = { activeSession?.sendRotateDevice() },
                    onPasteClipboard = if (config.controlEnabled && config.videoSource != "camera") {
                        {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val clip = cm?.primaryClip
                            if (clip != null && clip.itemCount > 0) {
                                val text = clip.getItemAt(0)?.text?.toString()
                                if (!text.isNullOrEmpty()) {
                                    activeSession?.sendSetClipboard(text, paste = true)
                                }
                            }
                        }
                    } else null,
                    onStop = { stopMirroring() },
                    onNavBack = { activeSession?.sendNavBack() },
                    onNavHome = { activeSession?.sendNavHome() },
                    onNavRecents = { activeSession?.sendNavRecents() },
                    onKeyEvent = { action, keyCode ->
                        activeSession?.sendKeycode(action, keyCode)
                    },
                    onTouchEvent = { action, pointerId, x, y, vw, vh ->
                        if (config.videoSource != "camera") {
                            activeSession?.sendTouchEvent(action, pointerId, x, y, vw, vh)
                        }
                    },
                    onSurfaceCreated = { holder ->
                        val existingSession = activeSession
                        if (existingSession != null && isMirroring) {
                            existingSession.attachSurface(holder.surface)
                        } else {
                            startMirroring(holder)
                        }
                    },
                    onSurfaceDestroyed = {
                        activeSession?.detachSurface()
                    },
                    remoteVolume = remoteVolume,
                    remoteVolumeMax = remoteVolumeMax,
                    onRemoteVolumeStep = { stepRemoteVolume(it) },
                    onRemoteVolumeSet = { setRemoteVolume(it) }
                )
            } else {
                ScrcpyAudioOnlyPlayer(
                    modifier = Modifier.fillMaxSize(),
                    config = config,
                    isRecording = isRecording,
                    canRecord = canRecord,
                    onToggleRecord = { toggleRecording() },
                    onStop = { stopMirroring() }
                )
            }

                androidx.compose.animation.AnimatedVisibility(
                    visible = backExitArmed,
                    enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(150)),
                    exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(450)),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 48.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = Color.Black.copy(alpha = 0.8f),
                        contentColor = Color.White
                    ) {
                        Text(
                            stringResource(R.string.scrcpy_back_again_to_exit),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                        )
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                // Title bar: mirror device on the left, compact Start button on the right
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (activeConn?.androidVersion != null) {
                            stringResource(R.string.scrcpy_target_version, activeConn.label, activeConn.androidVersion)
                        } else if (activeConn != null) {
                            stringResource(R.string.scrcpy_target, activeConn.label)
                        } else {
                            stringResource(R.string.no_devices_connected)
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(12.dp))
                    Button(
                        onClick = {
                        scrcpyVm.errorMessage = null
                        if (isMirroring) {
                            stopMirroring()
                        } else {
                            if (rememberSettings && deviceKey != null) {
                                ScrcpyConfigStore.save(context, deviceKey, config)
                            }
                            if (config.videoEnabled) {
                                scrcpyVm.isMirroring = true
                            } else {
                                scrcpyVm.isMirroring = true
                                startMirroring(null)
                            }
                        }
                    },
                        enabled = isDeviceConnected && isModeSupported,
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            if (isMirroring) Icons.Filled.Stop else io.github.rhythmcache.dioxamine.ui.icons.DioxamineIcons.VrHeadset,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (isMirroring) stringResource(R.string.scrcpy_stop_mirroring) else stringResource(R.string.scrcpy_start_mirroring))
                    }
                }

                if (isDeviceConnected && !isModeSupported) {
                    Text(
                        stringResource(R.string.scrcpy_mode_not_supported, unsupportedModeName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                    )
                }

                if (errorMessage != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.ErrorOutline, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.scrcpy_mirroring_failed), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                Text(errorMessage, style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { scrcpyVm.errorMessage = null }) {
                                Text(stringResource(R.string.btn_dismiss))
                            }
                        }
                    }
                }

                val disabledTabColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

                PrimaryTabRow(selectedTabIndex = selectedTab.ordinal) {
                    Tab(
                        selected = selectedTab == ScrcpyTab.CONFIGURATOR,
                        onClick = { if (isDeviceConnected) selectedTab = ScrcpyTab.CONFIGURATOR },
                        enabled = isDeviceConnected,
                        unselectedContentColor = if (isDeviceConnected) MaterialTheme.colorScheme.onSurfaceVariant else disabledTabColor,
                        text = {
                            Text(
                                text = stringResource(R.string.scrcpy_tab_configurator),
                                color = if (isDeviceConnected) Color.Unspecified else disabledTabColor,
                            )
                        },
                    )
                    Tab(
                        selected = selectedTab == ScrcpyTab.RECORDINGS,
                        onClick = { selectedTab = ScrcpyTab.RECORDINGS },
                        text = { Text(stringResource(R.string.scrcpy_tab_recordings)) },
                    )
                    Tab(
                        selected = selectedTab == ScrcpyTab.LOGS,
                        onClick = { if (isDeviceConnected) selectedTab = ScrcpyTab.LOGS },
                        enabled = isDeviceConnected,
                        unselectedContentColor = if (isDeviceConnected) MaterialTheme.colorScheme.onSurfaceVariant else disabledTabColor,
                        text = {
                            Text(
                                text = stringResource(R.string.scrcpy_tab_logs),
                                color = if (isDeviceConnected) Color.Unspecified else disabledTabColor,
                            )
                        },
                    )
                }

            when (selectedTab) {
                ScrcpyTab.CONFIGURATOR -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(16.dp)
                    ) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                VideoSettings(
                                    config = config,
                                    isMirroring = isMirroring,
                                    supportsAudio = supportsAudio,
                                    supportsCamera = supportsCamera,
                                    supportsVirtual = supportsVirtual,
                                    apiLevel = apiLevel,
                                    allowCustomValues = allowCustomValues,
                                    customMaxSizes = customMaxSizes,
                                    customFps = customFps,
                                    customBitrates = customBitrates,
                                    customDpis = customDpis,
                                    discoveredCameras = discoveredCameras,
                                    cameraDiscoveryState = cameraDiscoveryState,
                                    discoveredApps = discoveredApps,
                                    appDiscoveryState = appDiscoveryState,
                                    onRefreshCameras = {
                                        activeId?.let { ScrcpyDiscoveryCache.clearCameras(it) }
                                        discoveredCameras = emptyList()
                                        if (supportsCamera && config.videoSource == "camera") {
                                            cameraDiscoveryState = DiscoveryState.LOADING
                                        }
                                        refreshCamerasTrigger++
                                    },
                                    onRefreshApps = {
                                        activeId?.let { ScrcpyDiscoveryCache.clearApps(it) }
                                        discoveredApps = emptyList()
                                        if (supportsVirtual && config.videoSource == "virtual") {
                                            appDiscoveryState = DiscoveryState.LOADING
                                        }
                                        refreshAppsTrigger++
                                    },
                                    onConfigChange = { config = it },
                                    onOpenAddDialog = { type ->
                                        activeAddDialog = type
                                        dialogInputValue = ""
                                        dialogErrorMsg = null
                                    }
                                )

                                Spacer(Modifier.height(12.dp))
                                HorizontalDivider()
                                Spacer(Modifier.height(12.dp))

                                AudioSettings(
                                    config = config,
                                    isMirroring = isMirroring,
                                    supportsAudio = supportsAudio,
                                    apiLevel = apiLevel,
                                    allowCustomValues = allowCustomValues,
                                    customAudioBitrates = customAudioBitrates,
                                    onConfigChange = { config = it },
                                    onOpenAddDialog = { type ->
                                        activeAddDialog = type
                                        dialogInputValue = ""
                                        dialogErrorMsg = null
                                    }
                                )

                                Spacer(Modifier.height(12.dp))
                                HorizontalDivider()
                                Spacer(Modifier.height(12.dp))

                                DisplaySettings(
                                    config = config,
                                    isMirroring = isMirroring,
                                    showFloatingNav = scrcpyVm.showFloatingNav,
                                    showTouchpadOverlay = scrcpyVm.showTouchpadOverlay,
                                    showKeyboard = scrcpyVm.showKeyboard,
                                    onShowFloatingNavChange = { next ->
                                        if (config.videoEnabled && config.videoSource != "camera") {
                                            scrcpyVm.showFloatingNav = next
                                            config = config.copy(showFloatingNav = next)
                                            if (rememberSettings && deviceKey != null) {
                                                ScrcpyConfigStore.save(context, deviceKey, config)
                                            }
                                        }
                                    },
                                    onShowTouchpadChange = { next ->
                                        if (config.videoEnabled && config.videoSource != "camera") {
                                            scrcpyVm.showTouchpadOverlay = next
                                            config = config.copy(showTouchpad = next)
                                            if (next) activeSession?.ensureUhidDevices()
                                            if (rememberSettings && deviceKey != null) {
                                                ScrcpyConfigStore.save(context, deviceKey, config)
                                            }
                                        }
                                    },
                                    onShowKeyboardChange = { next ->
                                        if (config.videoEnabled && config.videoSource != "camera") {
                                            scrcpyVm.showKeyboard = next
                                            config = config.copy(showKeyboard = next)
                                            if (next) activeSession?.ensureUhidDevices()
                                            if (rememberSettings && deviceKey != null) {
                                                ScrcpyConfigStore.save(context, deviceKey, config)
                                            }
                                        }
                                    },
                                    onConfigChange = {
                                        config = it
                                        if (config.videoEnabled && config.videoSource != "camera") {
                                            activeSession?.clipboardSyncEnabled = it.clipboardSync
                                        }
                                        if (rememberSettings && deviceKey != null) {
                                            ScrcpyConfigStore.save(context, deviceKey, it)
                                        }
                                    }
                                )

                                Spacer(Modifier.height(16.dp))

                                if (isDeviceConnected && deviceKey != null) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable(enabled = !isMirroring) {
                                                    val next = !rememberSettings
                                                    rememberSettings = next
                                                    ScrcpyConfigStore.setRememberEnabled(context, deviceKey, next)
                                                    if (!next) {
                                                        ScrcpyConfigStore.resetToDefaults(context, deviceKey)
                                                    }
                                                }
                                        ) {
                                            Checkbox(
                                                checked = rememberSettings,
                                                onCheckedChange = { checked ->
                                                    rememberSettings = checked
                                                    ScrcpyConfigStore.setRememberEnabled(context, deviceKey, checked)
                                                    if (!checked) {
                                                        ScrcpyConfigStore.resetToDefaults(context, deviceKey)
                                                    }
                                                },
                                                enabled = !isMirroring
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                text = stringResource(R.string.scrcpy_remember_settings),
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                        }

                                        TextButton(
                                            onClick = {
                                                config = ScrcpyConfig()
                                                ScrcpyConfigStore.resetToDefaults(context, deviceKey)
                                                Toast.makeText(context, context.getString(R.string.scrcpy_settings_reset), Toast.LENGTH_SHORT).show()
                                            },
                                            enabled = !isMirroring
                                        ) {
                                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text(stringResource(R.string.scrcpy_btn_reset_defaults))
                                        }
                                    }

                                    Spacer(Modifier.height(8.dp))
                                }

                            }
                        }
                    }
                }
                ScrcpyTab.LOGS -> {
                    ScrcpyLogViewer(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        activeConn = activeConn,
                        config = config
                    )
                }
                ScrcpyTab.RECORDINGS -> {
                    ScrcpyRecordingsViewer(
                        modifier = Modifier.fillMaxSize(),
                        isRecording = isRecording
                    )
                }
            }
            }
        }
    }

    if (activeAddDialog != null) {
        val dialogType = activeAddDialog!!
        val titleText = when (dialogType) {
            AddCustomDialogType.MAX_SIZE -> stringResource(R.string.dialog_add_max_size_title)
            AddCustomDialogType.FPS -> stringResource(R.string.dialog_add_fps_title)
            AddCustomDialogType.BITRATE -> stringResource(R.string.dialog_add_bitrate_title)
            AddCustomDialogType.AUDIO_BITRATE -> stringResource(R.string.dialog_add_audio_bitrate_title)
            AddCustomDialogType.DPI -> stringResource(R.string.dialog_add_dpi_title)
        }
        val msgText = when (dialogType) {
            AddCustomDialogType.MAX_SIZE -> stringResource(R.string.dialog_add_max_size_msg)
            AddCustomDialogType.FPS -> stringResource(R.string.dialog_add_fps_msg)
            AddCustomDialogType.BITRATE -> stringResource(R.string.dialog_add_bitrate_msg)
            AddCustomDialogType.AUDIO_BITRATE -> stringResource(R.string.dialog_add_audio_bitrate_msg)
            AddCustomDialogType.DPI -> stringResource(R.string.dialog_add_dpi_msg)
        }
        val invalidNumMsg = stringResource(R.string.err_invalid_positive_number)

        AlertDialog(
            onDismissRequest = { activeAddDialog = null },
            title = { Text(titleText) },
            text = {
                Column {
                    Text(msgText, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = dialogInputValue,
                        onValueChange = {
                            dialogInputValue = it
                            dialogErrorMsg = null
                        },
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (dialogErrorMsg != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(dialogErrorMsg!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val num = dialogInputValue.toIntOrNull()
                        if (num == null || num <= 0) {
                            dialogErrorMsg = invalidNumMsg
                        } else {
                            when (dialogType) {
                                AddCustomDialogType.MAX_SIZE -> {
                                    val newList = (customMaxSizes + num).distinct().sorted()
                                    customMaxSizes = newList
                                    prefs.edit().putStringSet("scrcpy_custom_max_sizes", newList.map { it.toString() }.toSet()).apply()
                                    config = config.copy(maxSize = num)
                                }
                                AddCustomDialogType.FPS -> {
                                    val newList = (customFps + num).distinct().sorted()
                                    customFps = newList
                                    prefs.edit().putStringSet("scrcpy_custom_fps", newList.map { it.toString() }.toSet()).apply()
                                    config = config.copy(maxFps = num)
                                }
                                AddCustomDialogType.BITRATE -> {
                                    val newList = (customBitrates + num).distinct().sorted()
                                    customBitrates = newList
                                    prefs.edit().putStringSet("scrcpy_custom_bitrates", newList.map { it.toString() }.toSet()).apply()
                                    config = config.copy(bitRateMbps = num)
                                }
                                AddCustomDialogType.AUDIO_BITRATE -> {
                                    val newList = (customAudioBitrates + num).distinct().sorted()
                                    customAudioBitrates = newList
                                    prefs.edit().putStringSet("scrcpy_custom_audio_bitrates", newList.map { it.toString() }.toSet()).apply()
                                    config = config.copy(audioBitRateKbps = num)
                                }
                                AddCustomDialogType.DPI -> {
                                    val newList = (customDpis + num).distinct().sorted()
                                    customDpis = newList
                                    prefs.edit().putStringSet("scrcpy_custom_dpis", newList.map { it.toString() }.toSet()).apply()
                                    config = config.copy(newDisplayDpi = num)
                                }
                            }
                            activeAddDialog = null
                        }
                    }
                ) {
                    Text(stringResource(R.string.btn_add))
                }
            },
            dismissButton = {
                TextButton(onClick = { activeAddDialog = null }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }
}

@Composable
private fun VideoSourceSettings(
    config: ScrcpyConfig,
    isMirroring: Boolean,
    supportsCamera: Boolean,
    supportsVirtual: Boolean,
    apiLevel: Int,
    onConfigChange: (ScrcpyConfig) -> Unit
) {
    Text(stringResource(R.string.section_video_source), style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        listOf(
            stringResource(R.string.video_source_screen) to "display",
            stringResource(R.string.video_source_camera) to "camera",
            stringResource(R.string.video_source_virtual) to "virtual"
        ).forEach { (label, value) ->
            val isCamera = value == "camera"
            val isVirtual = value == "virtual"
            val isSupported = (!isCamera || supportsCamera) && (!isVirtual || supportsVirtual)
            FilterChip(
                selected = config.videoSource == value,
                onClick = {
                    if (isSupported) {
                        onConfigChange(
                            config.copy(
                                videoSource = value,
                                maxSize = if (value == "camera" && config.maxSize == 0) 1080 else config.maxSize
                            )
                        )
                    }
                },
                enabled = !isMirroring && isSupported,
                label = { Text(label, style = MaterialTheme.typography.labelSmall) }
            )
        }
    }
    if (!supportsCamera) {
        Spacer(Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.camera_source_subtitle_req, apiLevel),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error
        )
    }
    if (!supportsVirtual) {
        Spacer(Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.virtual_source_subtitle_req, apiLevel),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun CameraSettings(
    config: ScrcpyConfig,
    isMirroring: Boolean,
    discoveredCameras: List<CameraDevice>,
    discoveryState: DiscoveryState,
    onRefreshCameras: () -> Unit = {},
    onConfigChange: (ScrcpyConfig) -> Unit
) {
    if (config.videoSource != "camera") return

    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.section_camera), style = MaterialTheme.typography.labelMedium)
        if (!isMirroring && discoveryState != DiscoveryState.LOADING) {
            IconButton(
                onClick = onRefreshCameras,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.btn_refresh),
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    Spacer(Modifier.height(6.dp))

    when (discoveryState) {
        DiscoveryState.LOADING -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.camera_discovering), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
        }
        DiscoveryState.TIMED_OUT -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.camera_discovery_timed_out),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(6.dp))
        }
        DiscoveryState.FAILED -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.camera_discovery_failed),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(6.dp))
        }
        DiscoveryState.EMPTY -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.camera_no_cameras_found),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(6.dp))
        }
        DiscoveryState.IDLE, DiscoveryState.SUCCESS -> {
            // No banner to display
        }
    }

    if (discoveredCameras.isNotEmpty()) {
        Text(stringResource(R.string.camera_selector), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            discoveredCameras.forEach { cam ->
                val label = stringResource(R.string.camera_label_format, cam.id, cam.facing)
                FilterChip(
                    selected = config.cameraId == cam.id || (config.cameraId == null && config.cameraFacing == cam.facing),
                    onClick = {
                        onConfigChange(
                            config.copy(
                                cameraId = cam.id,
                                cameraFacing = cam.facing
                            )
                        )
                    },
                    enabled = !isMirroring,
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        val activeCam = discoveredCameras.find { it.id == config.cameraId } ?: discoveredCameras.find { it.facing == config.cameraFacing } ?: discoveredCameras.first()

        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.camera_resolution), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val allSizes = (activeCam.standardSizes + activeCam.highSpeedOptions.map { it.resolution }).distinct()
            allSizes.forEach { sizeStr ->
                FilterChip(
                    selected = config.cameraSize == sizeStr,
                    onClick = {
                        onConfigChange(config.copy(cameraSize = sizeStr))
                    },
                    enabled = !isMirroring,
                    label = { Text(sizeStr, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.camera_frame_rate), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val highSpeedForSize = activeCam.highSpeedOptions.find { it.resolution == config.cameraSize }
            val availableFps = if (highSpeedForSize != null) {
                (activeCam.fpsRange + highSpeedForSize.fpsList).distinct().sortedDescending()
            } else if (activeCam.fpsRange.isNotEmpty()) {
                activeCam.fpsRange.sortedDescending()
            } else {
                listOf(60, 30, 15)
            }

            availableFps.forEach { fps ->
                FilterChip(
                    selected = config.cameraFps == fps,
                    onClick = {
                        val isHighSpeed = fps >= 120
                        onConfigChange(
                            config.copy(
                                cameraFps = fps,
                                cameraHighSpeed = isHighSpeed,
                                videoCodec = if (isHighSpeed) "h264" else config.videoCodec
                            )
                        )
                    },
                    enabled = !isMirroring,
                    label = { Text(stringResource(R.string.label_fps_format, fps), style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                stringResource(R.string.camera_facing_back) to "back",
                stringResource(R.string.camera_facing_front) to "front",
                stringResource(R.string.camera_facing_external) to "external"
            ).forEach { (label, value) ->
                FilterChip(
                    selected = config.cameraFacing == value,
                    onClick = { onConfigChange(config.copy(cameraFacing = value, cameraId = null)) },
                    enabled = !isMirroring,
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
    }
}

@Composable
private fun VirtualScreenSettings(
    config: ScrcpyConfig,
    isMirroring: Boolean,
    discoveredApps: List<ScrcpyApp>,
    discoveryState: DiscoveryState,
    onRefreshApps: () -> Unit = {},
    allowCustomValues: Boolean,
    customDpis: List<Int>,
    onConfigChange: (ScrcpyConfig) -> Unit,
    onOpenAddDialog: (AddCustomDialogType) -> Unit
) {
    if (config.videoSource != "virtual") return

    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.section_virtual_screen), style = MaterialTheme.typography.labelMedium)
        if (!isMirroring && discoveryState != DiscoveryState.LOADING) {
            IconButton(
                onClick = onRefreshApps,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.btn_refresh),
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    Spacer(Modifier.height(6.dp))

    when (discoveryState) {
        DiscoveryState.LOADING -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.virtual_screen_discovering),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(6.dp))
        }
        DiscoveryState.TIMED_OUT -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.virtual_screen_discovery_timed_out),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(6.dp))
        }
        DiscoveryState.FAILED -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.virtual_screen_discovery_failed),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(6.dp))
        }
        DiscoveryState.EMPTY -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.virtual_screen_no_apps_found),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(6.dp))
        }
        DiscoveryState.IDLE, DiscoveryState.SUCCESS -> {
            // No banner to display
        }
    }

    Text(stringResource(R.string.virtual_screen_dpi), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        FilterChip(
            selected = config.newDisplayDpi == null,
            onClick = { onConfigChange(config.copy(newDisplayDpi = null)) },
            enabled = !isMirroring,
            label = { Text(stringResource(R.string.virtual_screen_dpi_auto), style = MaterialTheme.typography.labelSmall) }
        )
        val defaultDpis = listOf(160, 240, 320, 420, 480)
        defaultDpis.forEach { dpi ->
            FilterChip(
                selected = config.newDisplayDpi == dpi,
                onClick = { onConfigChange(config.copy(newDisplayDpi = dpi)) },
                enabled = !isMirroring,
                label = { Text(stringResource(R.string.virtual_screen_dpi_format, dpi), style = MaterialTheme.typography.labelSmall) }
            )
        }
        if (allowCustomValues) {
            customDpis.forEach { dpi ->
                FilterChip(
                    selected = config.newDisplayDpi == dpi,
                    onClick = { onConfigChange(config.copy(newDisplayDpi = dpi)) },
                    enabled = !isMirroring,
                    label = { Text(stringResource(R.string.virtual_screen_dpi_format, dpi), style = MaterialTheme.typography.labelSmall) }
                )
            }
            FilterChip(
                selected = false,
                onClick = { onOpenAddDialog(AddCustomDialogType.DPI) },
                enabled = !isMirroring,
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.label_add_chip), style = MaterialTheme.typography.labelSmall)
                    }
                }
            )
        }
    }

    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.virtual_screen_app), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(4.dp))

    var showAppPicker by remember { mutableStateOf(false) }

    val selectedApp = discoveredApps.find { it.packageName == config.newDisplayApp }
    val displayAppName = when {
        config.newDisplayApp == null -> stringResource(R.string.virtual_screen_default_launcher)
        selectedApp != null -> selectedApp.name
        else -> config.newDisplayApp
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isMirroring) { showAppPicker = true }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    Icons.Filled.Android,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = displayAppName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (config.newDisplayApp != null) {
                        Text(
                            text = config.newDisplayApp,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showAppPicker) {
        AppPickerDialog(
            currentPackage = config.newDisplayApp,
            apps = discoveredApps,
            onDismiss = { showAppPicker = false },
            onSelect = { appPackage ->
                onConfigChange(config.copy(newDisplayApp = appPackage))
                showAppPicker = false
            }
        )
    }
}

@Composable
private fun AppPickerDialog(
    currentPackage: String?,
    apps: List<ScrcpyApp>,
    onDismiss: () -> Unit,
    onSelect: (String?) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val trimmedQuery = searchQuery.trim()
    val isCustomPackage = trimmedQuery.isNotBlank() &&
            ScrcpyAppParser.validPkgRegex.matches(trimmedQuery) &&
            apps.none { it.packageName.equals(trimmedQuery, ignoreCase = true) }

    val filteredApps = remember(searchQuery, apps) {
        if (searchQuery.isBlank()) {
            apps
        } else {
            val q = searchQuery.trim().lowercase()
            apps.filter {
                it.name.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            }
        }
    }

    val userApps = remember(filteredApps) { filteredApps.filter { !it.isSystem } }
    val systemApps = remember(filteredApps) { filteredApps.filter { it.isSystem } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.virtual_screen_select_app), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(stringResource(R.string.virtual_screen_search_apps)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                ) {
                    item {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(null) },
                            shape = RoundedCornerShape(8.dp),
                            color = if (currentPackage == null) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.virtual_screen_default_launcher),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                if (currentPackage == null) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    }

                    if (isCustomPackage) {
                        item {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(trimmedQuery) },
                                shape = RoundedCornerShape(8.dp),
                                color = if (currentPackage == trimmedQuery) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Filled.PlayArrow,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(R.string.virtual_screen_use_custom_package),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = trimmedQuery,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (currentPackage == trimmedQuery) {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        }
                    }

                    if (apps.isEmpty() && searchQuery.isBlank()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    stringResource(R.string.virtual_screen_empty_entry_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else if (filteredApps.isEmpty() && !isCustomPackage && searchQuery.isNotBlank()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    stringResource(R.string.virtual_screen_no_apps_found),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    if (userApps.isNotEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.virtual_screen_user_apps),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
                            )
                        }
                        items(userApps, key = { it.packageName }) { app ->
                            AppPickerRow(
                                app = app,
                                isSelected = currentPackage == app.packageName,
                                onClick = { onSelect(app.packageName) }
                            )
                        }
                    }

                    if (systemApps.isNotEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.virtual_screen_system_apps),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
                            )
                        }
                        items(systemApps, key = { it.packageName }) { app ->
                            AppPickerRow(
                                app = app,
                                isSelected = currentPackage == app.packageName,
                                onClick = { onSelect(app.packageName) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
    )
}

@Composable
private fun AppPickerRow(
    app: ScrcpyApp,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (isSelected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun VideoSettings(
    config: ScrcpyConfig,
    isMirroring: Boolean,
    supportsAudio: Boolean,
    supportsCamera: Boolean,
    supportsVirtual: Boolean,
    apiLevel: Int,
    allowCustomValues: Boolean,
    customMaxSizes: List<Int>,
    customFps: List<Int>,
    customBitrates: List<Int>,
    customDpis: List<Int>,
    discoveredCameras: List<CameraDevice>,
    cameraDiscoveryState: DiscoveryState,
    discoveredApps: List<ScrcpyApp>,
    appDiscoveryState: DiscoveryState,
    onRefreshCameras: () -> Unit = {},
    onRefreshApps: () -> Unit = {},
    onConfigChange: (ScrcpyConfig) -> Unit,
    onOpenAddDialog: (AddCustomDialogType) -> Unit
) {
    Text(stringResource(R.string.section_video), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))

    // Row is the D-pad stop; its focus shows as an oval ring on the switch only.
    val switchRowFocus1 = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(
        modifier = Modifier.toggleable(
            value = config.videoEnabled,
            enabled = !isMirroring,
            role = Role.Switch,
            interactionSource = switchRowFocus1,
            indication = null,
            onValueChange = { isChecked ->
                val newConfig = if (!isChecked && supportsAudio && !config.audioEnabled) {
                    config.copy(videoEnabled = false, audioEnabled = true)
                } else {
                    config.copy(videoEnabled = isChecked)
                }
                onConfigChange(newConfig)
            }
        ).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.video_enable_title), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.video_enable_subtitle),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = config.videoEnabled,
            interactionSource = switchRowFocus1,
            onCheckedChange = null,
            enabled = !isMirroring
        )
    }

    if (!config.videoEnabled) return

    Spacer(Modifier.height(8.dp))

    VideoSourceSettings(
        config = config,
        isMirroring = isMirroring,
        supportsCamera = supportsCamera,
        supportsVirtual = supportsVirtual,
        apiLevel = apiLevel,
        onConfigChange = onConfigChange
    )

    CameraSettings(
        config = config,
        isMirroring = isMirroring,
        discoveredCameras = discoveredCameras,
        discoveryState = cameraDiscoveryState,
        onRefreshCameras = onRefreshCameras,
        onConfigChange = onConfigChange
    )

    VirtualScreenSettings(
        config = config,
        isMirroring = isMirroring,
        discoveredApps = discoveredApps,
        discoveryState = appDiscoveryState,
        onRefreshApps = onRefreshApps,
        allowCustomValues = allowCustomValues,
        customDpis = customDpis,
        onConfigChange = onConfigChange,
        onOpenAddDialog = onOpenAddDialog
    )

    if (config.videoSource != "camera") {
        Text(stringResource(R.string.label_resolution), style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val defaultResolutions = listOf(
                stringResource(R.string.res_1080p) to 1080,
                stringResource(R.string.res_720p) to 720,
                stringResource(R.string.res_480p) to 480,
                stringResource(R.string.res_auto) to 0
            )
            defaultResolutions.forEach { (label, value) ->
                FilterChip(
                    selected = config.maxSize == value,
                    onClick = { onConfigChange(config.copy(maxSize = value)) },
                    enabled = !isMirroring,
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                )
            }
            if (allowCustomValues) {
                customMaxSizes.forEach { customVal ->
                    FilterChip(
                        selected = config.maxSize == customVal,
                        onClick = { onConfigChange(config.copy(maxSize = customVal)) },
                        enabled = !isMirroring,
                        label = { Text("${customVal}p", style = MaterialTheme.typography.labelSmall) }
                    )
                }
                FilterChip(
                    selected = false,
                    onClick = { onOpenAddDialog(AddCustomDialogType.MAX_SIZE) },
                    enabled = !isMirroring,
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.label_add_chip), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.label_frame_rate), style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val defaultFps = listOf(60, 30, 15)
            defaultFps.forEach { fps ->
                FilterChip(
                    selected = config.maxFps == fps,
                    onClick = { onConfigChange(config.copy(maxFps = fps)) },
                    enabled = !isMirroring,
                    label = { Text(stringResource(R.string.label_fps_format, fps), style = MaterialTheme.typography.labelSmall) }
                )
            }
            if (allowCustomValues) {
                customFps.forEach { customVal ->
                    FilterChip(
                        selected = config.maxFps == customVal,
                        onClick = { onConfigChange(config.copy(maxFps = customVal)) },
                        enabled = !isMirroring,
                        label = { Text(stringResource(R.string.label_fps_format, customVal), style = MaterialTheme.typography.labelSmall) }
                    )
                }
                FilterChip(
                    selected = false,
                    onClick = { onOpenAddDialog(AddCustomDialogType.FPS) },
                    enabled = !isMirroring,
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.label_add_chip), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                )
            }
        }

        Spacer(Modifier.height(8.dp))
    }

    Text(stringResource(R.string.label_bitrate), style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        listOf(8, 4, 2).forEach { mbps ->
            FilterChip(
                selected = config.bitRateMbps == mbps,
                onClick = { onConfigChange(config.copy(bitRateMbps = mbps)) },
                enabled = !isMirroring,
                label = { Text(stringResource(R.string.label_mbps_format, mbps), style = MaterialTheme.typography.labelSmall) }
            )
        }
        if (allowCustomValues) {
            customBitrates.forEach { customVal ->
                FilterChip(
                    selected = config.bitRateMbps == customVal,
                    onClick = { onConfigChange(config.copy(bitRateMbps = customVal)) },
                    enabled = !isMirroring,
                    label = { Text(stringResource(R.string.label_mbps_format, customVal), style = MaterialTheme.typography.labelSmall) }
                )
            }
            FilterChip(
                selected = false,
                onClick = { onOpenAddDialog(AddCustomDialogType.BITRATE) },
                enabled = !isMirroring,
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.label_add_chip), style = MaterialTheme.typography.labelSmall)
                    }
                }
            )
        }
    }

    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.label_codec), style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        listOf(
            stringResource(R.string.codec_h264) to "h264",
            stringResource(R.string.codec_h265) to "h265",
            stringResource(R.string.codec_av1) to "av1",
            stringResource(R.string.codec_vp8) to "vp8",
            stringResource(R.string.codec_vp9) to "vp9"
        ).forEach { (label, value) ->
            val isEnabled = !isMirroring && (!config.cameraHighSpeed || value == "h264")
            FilterChip(
                selected = config.videoCodec == value,
                onClick = { onConfigChange(config.copy(videoCodec = value)) },
                enabled = isEnabled,
                label = { Text(label, style = MaterialTheme.typography.labelSmall) }
            )
        }
    }
    if (config.videoCodec in listOf("av1", "vp8", "vp9")) {
        Spacer(Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.scrcpy_recording_codec_warning),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error
        )
    }

    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.label_orientation), style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        listOf(
            stringResource(R.string.orient_normal) to "0",
            stringResource(R.string.orient_90) to "90",
            stringResource(R.string.orient_180) to "180",
            stringResource(R.string.orient_270) to "270",
            stringResource(R.string.orient_flip_0) to "flip0",
            stringResource(R.string.orient_flip_90) to "flip90",
            stringResource(R.string.orient_flip_180) to "flip180",
            stringResource(R.string.orient_flip_270) to "flip270"
        ).forEach { (label, value) ->
            FilterChip(
                selected = config.captureOrientation == value || (config.captureOrientation == null && value == "0"),
                onClick = { onConfigChange(config.copy(captureOrientation = if (value == "0") null else value)) },
                enabled = !isMirroring,
                label = { Text(label, style = MaterialTheme.typography.labelSmall) }
            )
        }
    }

    if (config.videoSource == "display") {
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.label_crop), style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        val cropValue = config.crop.orEmpty()
        val cropInvalid = cropValue.isNotEmpty() && !ScrcpyConfig.isValidCrop(cropValue)
        OutlinedTextField(
            value = cropValue,
            onValueChange = { input ->
                val cleaned = input.filter { it.isDigit() || it == ':' }
                onConfigChange(config.copy(crop = cleaned.ifEmpty { null }))
            },
            enabled = !isMirroring,
            singleLine = true,
            isError = cropInvalid,
            placeholder = { Text("1280:720:0:0") },
            supportingText = {
                Text(
                    stringResource(if (cropInvalid) R.string.crop_invalid else R.string.crop_hint),
                    style = MaterialTheme.typography.labelSmall
                )
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.label_angle), style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        val angleValue = config.angle.orEmpty()
        val angleInvalid = angleValue.isNotEmpty() && !ScrcpyConfig.isValidAngle(angleValue)
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedButton(
                onClick = { onConfigChange(config.copy(angle = ScrcpyConfig.stepAngle(config.angle, -1.0))) },
                enabled = !isMirroring,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.padding(top = 4.dp).size(48.dp)
            ) {
                Text("\u2212", style = MaterialTheme.typography.titleLarge)
            }
            OutlinedTextField(
                value = angleValue,
                onValueChange = { input ->
                    val cleaned = input.filter { it.isDigit() || it == '-' || it == '.' }
                    onConfigChange(config.copy(angle = cleaned.ifEmpty { null }))
                },
                enabled = !isMirroring,
                singleLine = true,
                isError = angleInvalid,
                placeholder = { Text("0") },
                supportingText = {
                    Text(
                        stringResource(if (angleInvalid) R.string.angle_invalid else R.string.angle_hint),
                        style = MaterialTheme.typography.labelSmall
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(
                onClick = { onConfigChange(config.copy(angle = ScrcpyConfig.stepAngle(config.angle, 1.0))) },
                enabled = !isMirroring,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.padding(top = 4.dp).size(48.dp)
            ) {
                Text("+", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

@Composable
private fun AudioSettings(
    config: ScrcpyConfig,
    isMirroring: Boolean,
    supportsAudio: Boolean,
    apiLevel: Int,
    allowCustomValues: Boolean,
    customAudioBitrates: List<Int>,
    onConfigChange: (ScrcpyConfig) -> Unit,
    onOpenAddDialog: (AddCustomDialogType) -> Unit
) {
    Text(stringResource(R.string.section_audio), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))

    // Row is the D-pad stop; its focus shows as an oval ring on the switch only.
    val switchRowFocus2 = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(
        modifier = Modifier.toggleable(
            value = config.audioEnabled && supportsAudio,
            enabled = supportsAudio && !isMirroring,
            role = Role.Switch,
            interactionSource = switchRowFocus2,
            indication = null,
            onValueChange = { onConfigChange(config.copy(audioEnabled = it)) }
        ).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.audio_enable_title), style = MaterialTheme.typography.bodyMedium)
            Text(
                if (supportsAudio) stringResource(R.string.audio_enable_subtitle_ok)
                else stringResource(R.string.audio_enable_subtitle_req, apiLevel),
                style = MaterialTheme.typography.labelSmall,
                color = if (supportsAudio) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
            )
        }
        Switch(
            checked = config.audioEnabled && supportsAudio,
            interactionSource = switchRowFocus2,
            onCheckedChange = null,
            enabled = supportsAudio && !isMirroring
        )
    }

    if (config.audioEnabled && supportsAudio) {
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.label_audio_source), style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                stringResource(R.string.audio_source_device) to "output",
                stringResource(R.string.audio_source_mic) to "mic"
            ).forEach { (label, value) ->
                FilterChip(
                    selected = config.effectiveAudioSource() == value ||
                        (value == "output" && config.effectiveAudioSource() == "playback"),
                    onClick = { onConfigChange(config.copy(audioSource = value, audioSourceExplicit = true)) },
                    enabled = !isMirroring,
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.label_audio_format), style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                stringResource(R.string.codec_opus) to "opus",
                stringResource(R.string.codec_aac) to "aac",
                stringResource(R.string.codec_flac) to "flac",
                stringResource(R.string.codec_raw_pcm) to "raw"
            ).forEach { (label, value) ->
                FilterChip(
                    selected = config.audioCodec == value,
                    onClick = { onConfigChange(config.copy(audioCodec = value)) },
                    enabled = !isMirroring,
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
        if (config.audioCodec != "aac") {
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.scrcpy_recording_codec_warning),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        if (config.audioCodec == "opus" || config.audioCodec == "aac") {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.label_audio_bitrate), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(64, 128, 192, 256, 320).forEach { kbps ->
                    FilterChip(
                        selected = config.audioBitRateKbps == kbps,
                        onClick = { onConfigChange(config.copy(audioBitRateKbps = kbps)) },
                        enabled = !isMirroring,
                        label = { Text("${kbps} Kbps", style = MaterialTheme.typography.labelSmall) }
                    )
                }
                customAudioBitrates.forEach { customVal ->
                    FilterChip(
                        selected = config.audioBitRateKbps == customVal,
                        onClick = { onConfigChange(config.copy(audioBitRateKbps = customVal)) },
                        enabled = !isMirroring,
                        label = { Text("${customVal} Kbps", style = MaterialTheme.typography.labelSmall) }
                    )
                }
                if (allowCustomValues) {
                    FilterChip(
                        selected = false,
                        onClick = { onOpenAddDialog(AddCustomDialogType.AUDIO_BITRATE) },
                        enabled = !isMirroring,
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.label_add_chip), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    )
                }
            }
        }

        val showAudioDup = config.effectiveAudioSource() != "mic"
        if (showAudioDup) {
            val supportsAudioDup = apiLevel >= 33
            Spacer(Modifier.height(8.dp))
            // Row is the D-pad stop; its focus shows as an oval ring on the switch only.
            val switchRowFocus3 = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            Row(
                modifier = Modifier.toggleable(
            value = config.audioDup && supportsAudioDup,
            enabled = supportsAudioDup && !isMirroring,
            role = Role.Switch,
            interactionSource = switchRowFocus3,
            indication = null,
            onValueChange = { isChecked ->
                        onConfigChange(config.copy(audioDup = isChecked))
                    }
        ).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.audio_dup_title), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (supportsAudioDup) stringResource(R.string.audio_dup_subtitle_ok)
                        else stringResource(R.string.audio_dup_subtitle_req),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (supportsAudioDup) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                    )
                }
                Switch(
                    checked = config.audioDup && supportsAudioDup,
                    interactionSource = switchRowFocus3,
                    onCheckedChange = null,
                    enabled = supportsAudioDup && !isMirroring
                )
            }
        }
    }
}

@Composable
private fun DisplaySettings(
    config: ScrcpyConfig,
    isMirroring: Boolean,
    showFloatingNav: Boolean,
    showTouchpadOverlay: Boolean,
    showKeyboard: Boolean,
    onShowFloatingNavChange: (Boolean) -> Unit,
    onShowTouchpadChange: (Boolean) -> Unit,
    onShowKeyboardChange: (Boolean) -> Unit,
    onConfigChange: (ScrcpyConfig) -> Unit
) {
    val isCamera = config.videoSource == "camera"
    val isAudioOnly = !config.videoEnabled
    val isDisplaySupported = !isCamera && !isAudioOnly
    val disabledAlpha = 0.38f
    val unsupportedSubtitle = when {
        isCamera -> stringResource(R.string.scrcpy_not_supported_camera)
        isAudioOnly -> stringResource(R.string.scrcpy_not_supported_audio_only)
        else -> ""
    }

    Text(
        stringResource(R.string.section_display),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = if (!isDisplaySupported) MaterialTheme.colorScheme.onSurface.copy(alpha = disabledAlpha) else MaterialTheme.colorScheme.onSurface
    )
    Spacer(Modifier.height(8.dp))

    val screenOffEnabled = !isMirroring && isDisplaySupported
    // Row is the D-pad stop; its focus shows as an oval ring on the switch only.
    val switchRowFocus4 = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(
        modifier = Modifier.toggleable(
            value = config.turnScreenOff && isDisplaySupported,
            enabled = screenOffEnabled,
            role = Role.Switch,
            interactionSource = switchRowFocus4,
            indication = null,
            onValueChange = { onConfigChange(config.copy(turnScreenOff = it)) }
        ).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.display_screen_off_title),
                style = MaterialTheme.typography.bodyMedium,
                color = if (screenOffEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = disabledAlpha)
            )
            Text(
                if (!isDisplaySupported) unsupportedSubtitle else stringResource(R.string.display_screen_off_subtitle),
                style = MaterialTheme.typography.labelSmall,
                color = if (screenOffEnabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = disabledAlpha)
            )
        }
        Switch(
            checked = config.turnScreenOff && isDisplaySupported,
            interactionSource = switchRowFocus4,
            onCheckedChange = null,
            enabled = screenOffEnabled
        )
    }

    Spacer(Modifier.height(8.dp))

    val navEnabled = isDisplaySupported
    // Row is the D-pad stop; its focus shows as an oval ring on the switch only.
    val switchRowFocus5 = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(
        modifier = Modifier.toggleable(
            value = showFloatingNav && isDisplaySupported,
            enabled = navEnabled,
            role = Role.Switch,
            interactionSource = switchRowFocus5,
            indication = null,
            onValueChange = { onShowFloatingNavChange(it) }
        ).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.display_floating_nav_title),
                style = MaterialTheme.typography.bodyMedium,
                color = if (navEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = disabledAlpha)
            )
            Text(
                if (!isDisplaySupported) unsupportedSubtitle else stringResource(R.string.display_floating_nav_subtitle),
                style = MaterialTheme.typography.labelSmall,
                color = if (navEnabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = disabledAlpha)
            )
        }
        Switch(
            checked = showFloatingNav && isDisplaySupported,
            interactionSource = switchRowFocus5,
            onCheckedChange = null,
            enabled = navEnabled
        )
    }

    Spacer(Modifier.height(8.dp))

    val volEnabled = isDisplaySupported
    // Row is the D-pad stop; its focus shows as an oval ring on the switch only.
    val switchRowFocus6 = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(
        modifier = Modifier.toggleable(
            value = config.bindVolumeKeys && isDisplaySupported,
            enabled = volEnabled,
            role = Role.Switch,
            interactionSource = switchRowFocus6,
            indication = null,
            onValueChange = { onConfigChange(config.copy(bindVolumeKeys = it)) }
        ).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.display_bind_volume_title),
                style = MaterialTheme.typography.bodyMedium,
                color = if (volEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = disabledAlpha)
            )
            Text(
                if (!isDisplaySupported) unsupportedSubtitle else stringResource(R.string.display_bind_volume_subtitle),
                style = MaterialTheme.typography.labelSmall,
                color = if (volEnabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = disabledAlpha)
            )
        }
        Switch(
            checked = config.bindVolumeKeys && isDisplaySupported,
            interactionSource = switchRowFocus6,
            onCheckedChange = null,
            enabled = volEnabled
        )
    }

    Spacer(Modifier.height(8.dp))

    val clipEnabled = isDisplaySupported
    // Row is the D-pad stop; its focus shows as an oval ring on the switch only.
    val switchRowFocus7 = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(
        modifier = Modifier.toggleable(
            value = config.clipboardSync && isDisplaySupported,
            enabled = clipEnabled,
            role = Role.Switch,
            interactionSource = switchRowFocus7,
            indication = null,
            onValueChange = { onConfigChange(config.copy(clipboardSync = it)) }
        ).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.display_clipboard_sync_title),
                style = MaterialTheme.typography.bodyMedium,
                color = if (clipEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = disabledAlpha)
            )
            Text(
                if (!isDisplaySupported) unsupportedSubtitle else stringResource(R.string.display_clipboard_sync_subtitle),
                style = MaterialTheme.typography.labelSmall,
                color = if (clipEnabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = disabledAlpha)
            )
        }
        Switch(
            checked = config.clipboardSync && isDisplaySupported,
            interactionSource = switchRowFocus7,
            onCheckedChange = null,
            enabled = clipEnabled
        )
    }

    Spacer(Modifier.height(8.dp))

    val touchpadEnabled = isDisplaySupported
    // Row is the D-pad stop; its focus shows as an oval ring on the switch only.
    val switchRowFocus8 = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(
        modifier = Modifier.toggleable(
            value = showTouchpadOverlay && isDisplaySupported,
            enabled = touchpadEnabled,
            role = Role.Switch,
            interactionSource = switchRowFocus8,
            indication = null,
            onValueChange = { onShowTouchpadChange(it) }
        ).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.display_touchpad_title),
                style = MaterialTheme.typography.bodyMedium,
                color = if (touchpadEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = disabledAlpha)
            )
            Text(
                if (!isDisplaySupported) unsupportedSubtitle else stringResource(R.string.display_touchpad_subtitle),
                style = MaterialTheme.typography.labelSmall,
                color = if (touchpadEnabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = disabledAlpha)
            )
        }
        Switch(
            checked = showTouchpadOverlay && isDisplaySupported,
            interactionSource = switchRowFocus8,
            onCheckedChange = null,
            enabled = touchpadEnabled
        )
    }

    Spacer(Modifier.height(8.dp))

    val keyboardEnabled = isDisplaySupported
    // Row is the D-pad stop; its focus shows as an oval ring on the switch only.
    val switchRowFocus9 = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(
        modifier = Modifier.toggleable(
            value = showKeyboard && isDisplaySupported,
            enabled = keyboardEnabled,
            role = Role.Switch,
            interactionSource = switchRowFocus9,
            indication = null,
            onValueChange = { onShowKeyboardChange(it) }
        ).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.display_keyboard_title),
                style = MaterialTheme.typography.bodyMedium,
                color = if (keyboardEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = disabledAlpha)
            )
            Text(
                if (!isDisplaySupported) unsupportedSubtitle else stringResource(R.string.display_keyboard_subtitle),
                style = MaterialTheme.typography.labelSmall,
                color = if (keyboardEnabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = disabledAlpha)
            )
        }
        Switch(
            checked = showKeyboard && isDisplaySupported,
            interactionSource = switchRowFocus9,
            onCheckedChange = null,
            enabled = keyboardEnabled
        )
    }
}

@Composable
private fun FloatingVerticalNavBar(
    modifier: Modifier = Modifier,
    onNavBack: () -> Unit,
    onNavHome: () -> Unit,
    onNavRecents: () -> Unit
) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Surface(
        modifier = modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    offsetX += dragAmount.x
                    offsetY += dragAmount.y
                }
            },
        shape = RoundedCornerShape(20.dp),
        color = Color.Black.copy(alpha = 0.75f),
        tonalElevation = 6.dp,
        shadowElevation = 6.dp
    ) {
        Column(
            modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(
                onClick = onNavBack,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cd_nav_back),
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(
                onClick = onNavHome,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.RadioButtonUnchecked,
                    contentDescription = stringResource(R.string.cd_nav_home),
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(
                onClick = onNavRecents,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.CropSquare,
                    contentDescription = stringResource(R.string.cd_nav_recents),
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class)
@Composable
private fun ScrcpyVideoPlayer(
    modifier: Modifier,
    session: ScrcpySession? = null,
    videoWidth: Int?,
    videoHeight: Int?,
    isFullScreen: Boolean,
    showFloatingNav: Boolean,
    onToggleFloatingNav: () -> Unit = {},
    showTouchpadOverlay: Boolean = false,
    onToggleTouchpadOverlay: () -> Unit = {},
    showKeyboard: Boolean = false,
    onToggleKeyboard: (Boolean) -> Unit = {},
    bindVolumeKeys: Boolean,
    videoSourceIsCamera: Boolean,
    torchOn: Boolean,
    isRecording: Boolean,
    canRecord: Boolean,
    onToggleRecord: () -> Unit,
    onToggleTorch: () -> Unit,
    onToggleFullScreen: () -> Unit,
    onRotateDevice: () -> Unit,
    onPasteClipboard: (() -> Unit)? = null,
    onStop: () -> Unit,
    onNavBack: () -> Unit,
    onNavHome: () -> Unit,
    onNavRecents: () -> Unit,
    onKeyEvent: (action: Int, keyCode: Int) -> Unit,
    onTouchEvent: (action: Int, pointerId: Long, x: Float, y: Float, vw: Int, vh: Int) -> Unit,
    onSurfaceCreated: (SurfaceHolder) -> Unit,
    onSurfaceDestroyed: () -> Unit = {},
    remoteVolume: Int? = null,
    remoteVolumeMax: Int? = null,
    onRemoteVolumeStep: (Int) -> Unit = {},
    onRemoteVolumeSet: (Int) -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var surfaceViewRef by remember { mutableStateOf<SurfaceView?>(null) }
    val flashAlpha = remember { Animatable(0f) }

    fun captureScreenshot() {
        val sv = surfaceViewRef ?: return
        if (!sv.holder.surface.isValid || sv.width <= 0 || sv.height <= 0) {
            Toast.makeText(context, R.string.scrcpy_screenshot_failed, Toast.LENGTH_SHORT).show()
            return
        }

        coroutineScope.launch {
            flashAlpha.snapTo(0.7f)
            flashAlpha.animateTo(0f, animationSpec = tween(durationMillis = 250))
        }

        val width = sv.width
        val height = sv.height
        val bitmap = runCatching {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }.getOrNull()

        if (bitmap == null) {
            Toast.makeText(context, R.string.scrcpy_screenshot_failed, Toast.LENGTH_SHORT).show()
            return
        }

        val handler = Handler(Looper.getMainLooper())
        try {
            PixelCopy.request(sv, bitmap, { copyResult ->
                if (copyResult == PixelCopy.SUCCESS) {
                    RecordingsManager.ioScope.launch {
                        try {
                            val savedFile = RecordingsManager.saveScreenshot(context, bitmap)
                            withContext(Dispatchers.Main) {
                                if (savedFile != null) {
                                    Toast.makeText(context, R.string.scrcpy_screenshot_saved, Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, R.string.scrcpy_screenshot_failed, Toast.LENGTH_SHORT).show()
                                }
                            }
                        } catch (e: CancellationException) {
                            AppLogger.i("SCRCPY_CLIENT", "Screenshot capture coroutine cancelled")
                            throw e
                        } catch (e: Exception) {
                            AppLogger.e("SCRCPY_CLIENT", "Failed to save screenshot: ${e.message}", e)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, R.string.scrcpy_screenshot_failed, Toast.LENGTH_SHORT).show()
                            }
                        } finally {
                            bitmap.recycle()
                        }
                    }
                } else {
                    bitmap.recycle()
                    Toast.makeText(context, R.string.scrcpy_screenshot_failed, Toast.LENGTH_SHORT).show()
                }
            }, handler)
        } catch (e: Exception) {
            bitmap.recycle()
            AppLogger.e("SCRCPY_CLIENT", "PixelCopy failed: ${e.message}", e)
            Toast.makeText(context, R.string.scrcpy_screenshot_failed, Toast.LENGTH_SHORT).show()
        }
    }

    val focusRequester = remember { FocusRequester() }
    val keyboardFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val isImeVisible = WindowInsets.isImeVisible
    var imeBuffer by remember { mutableStateOf(TextFieldValue("  ", selection = TextRange(2))) }

    // Remote-friendly overlay state. Visible at start, hides after 6 s without input.
    var overlayVisible by remember { mutableStateOf(true) }
    var overlayActivity by remember { mutableIntStateOf(0) }
    var volumeAdjusting by remember { mutableStateOf(false) }
    var statsVisible by rememberSaveable { mutableStateOf(false) }
    var rootFocused by remember { mutableStateOf(false) }
    val overlayControlsFocus = remember { FocusRequester() }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val backDispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    fun pressBack() { backDispatcher?.onBackPressed() }

    // Back (or Left at the edge) hides the overlay; while adjusting volume it ends adjusting first.
    BackHandler(enabled = overlayVisible) {
        overlayVisible = false
    }
    BackHandler(enabled = volumeAdjusting) {
        volumeAdjusting = false
        overlayActivity++
    }
    var overlayRectInRoot by remember { mutableStateOf(Rect.Zero) }
    var videoPositionInRoot by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(showTouchpadOverlay, videoSourceIsCamera) {
        if (showTouchpadOverlay && !videoSourceIsCamera) {
            session?.ensureUhidDevices()
        } else {
            overlayRectInRoot = Rect.Zero
        }
    }

    var imeWasVisible by remember { mutableStateOf(false) }
    LaunchedEffect(isImeVisible) {
        if (isImeVisible) {
            imeWasVisible = true
        } else if (imeWasVisible) {
            imeWasVisible = false
            if (showKeyboard) {
                onToggleKeyboard(false)
            }
        }
    }

    LaunchedEffect(showKeyboard, videoSourceIsCamera) {
        if (showKeyboard && !videoSourceIsCamera) {
            session?.ensureUhidDevices()
            runCatching { keyboardFocusRequester.requestFocus() }
            keyboardController?.show()
        } else {
            keyboardController?.hide()
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    BoxWithConstraints(
        modifier = modifier
            .focusRequester(focusRequester)
            .onFocusChanged { rootFocused = it.isFocused }
            .focusTarget()
            .onPreviewKeyEvent { keyEvent ->
                if (bindVolumeKeys && !videoSourceIsCamera) {
                    val nativeEvent = keyEvent.nativeKeyEvent
                    val keyCode = nativeEvent.keyCode
                    if (keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                        keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
                        keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
                    ) {
                        val action = when (nativeEvent.action) {
                            KeyEvent.ACTION_DOWN -> 0
                            KeyEvent.ACTION_UP -> 1
                            else -> -1
                        }
                        if (action != -1) {
                            onKeyEvent(action, keyCode)
                        }
                        return@onPreviewKeyEvent true
                    }
                }
                // While typing to the device, arrows/enter go to the device keyboard.
                if (showKeyboard && !videoSourceIsCamera) return@onPreviewKeyEvent false
                if (keyEvent.type == KeyEventType.KeyDown) {
                    overlayActivity++
                    when (keyEvent.key) {
                        Key.DirectionLeft -> {
                            // Left acts as Back unless it can move focus to a control on the left.
                            when {
                                volumeAdjusting || !overlayVisible || rootFocused -> pressBack()
                                !focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Left) -> pressBack()
                            }
                            return@onPreviewKeyEvent true
                        }
                        Key.DirectionUp, Key.DirectionDown, Key.DirectionRight,
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            if (!overlayVisible) {
                                overlayVisible = true
                                return@onPreviewKeyEvent true
                            }
                            if (rootFocused) {
                                runCatching { overlayControlsFocus.requestFocus() }
                                return@onPreviewKeyEvent true
                            }
                        }
                        else -> {}
                    }
                }
                false
            },
        contentAlignment = Alignment.Center
    ) {
        // Always-composed invisible text field to intercept keyboard input and map to HID keystrokes
        BasicTextField(
            value = imeBuffer,
            onValueChange = { newBuffer ->
                imeBuffer = session?.let {
                    VirtualKeyboardHelper.handleImeValueChange(newBuffer, "  ", it)
                } ?: TextFieldValue("  ", selection = TextRange(2))
            },
            modifier = Modifier
                .size(1.dp)
                .alpha(0f)
                .focusRequester(keyboardFocusRequester)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) {
                        when (event.key) {
                            Key.Enter, Key.NumPadEnter -> {
                                session?.sendKeyStroke(HidKeyCodes.KEY_ENTER)
                                true
                            }
                            Key.Tab -> {
                                session?.sendKeyStroke(HidKeyCodes.KEY_TAB)
                                true
                            }
                            Key.Escape -> {
                                session?.sendKeyStroke(HidKeyCodes.KEY_ESC)
                                true
                            }
                            Key.DirectionLeft -> {
                                session?.sendKeyStroke(HidKeyCodes.KEY_LEFT)
                                true
                            }
                            Key.DirectionRight -> {
                                session?.sendKeyStroke(HidKeyCodes.KEY_RIGHT)
                                true
                            }
                            Key.DirectionUp -> {
                                session?.sendKeyStroke(HidKeyCodes.KEY_UP)
                                true
                            }
                            Key.DirectionDown -> {
                                session?.sendKeyStroke(HidKeyCodes.KEY_DOWN)
                                true
                            }
                            Key.Delete -> {
                                session?.sendKeyStroke(HidKeyCodes.KEY_DELETE)
                                true
                            }
                            else -> false
                        }
                    } else false
                },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false,
                imeAction = ImeAction.None
            ),
            singleLine = true
        )
        val hasDimensions = videoWidth != null && videoHeight != null && videoWidth > 0 && videoHeight > 0
        val videoRatio = if (hasDimensions) {
            (videoWidth.toFloat() / maxOf(videoHeight, 1).toFloat()).coerceIn(0.2f, 5.0f)
        } else {
            16f / 9f
        }
        val containerRatio = if (maxHeight.value > 0f) (maxWidth / maxHeight).coerceIn(0.05f, 20f) else 1f

        val playerModifier = if (videoRatio > containerRatio) {
            Modifier.fillMaxWidth().aspectRatio(videoRatio)
        } else {
            Modifier.fillMaxHeight().aspectRatio(videoRatio)
        }

        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    surfaceViewRef = this
                    keepScreenOn = true
                    isFocusable = true
                    isFocusableInTouchMode = true
                    if (bindVolumeKeys && !videoSourceIsCamera) {
                        requestFocus()
                    }
                    setOnKeyListener { _, keyCode, event ->
                        if (bindVolumeKeys && !videoSourceIsCamera && (keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                                               keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
                                               keyCode == KeyEvent.KEYCODE_VOLUME_MUTE)) {
                            val action = when (event.action) {
                                KeyEvent.ACTION_DOWN -> 0
                                KeyEvent.ACTION_UP -> 1
                                else -> -1
                            }
                            if (action != -1) {
                                onKeyEvent(action, keyCode)
                            }
                            true
                        } else {
                            false
                        }
                    }
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            surfaceViewRef = this@apply
                            onSurfaceCreated(holder)
                        }
                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, height: Int) {}
                        override fun surfaceDestroyed(h: SurfaceHolder) {
                            surfaceViewRef = null
                            onSurfaceDestroyed()
                        }
                    })
                }
            },
            update = { /* view identity persists across recompositions */ },
            modifier = playerModifier
        )

        if (flashAlpha.value > 0f) {
            Box(
                modifier = playerModifier
                    .background(Color.White.copy(alpha = flashAlpha.value))
            )
        }

        if (!hasDimensions) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White
            )
        }

        var playerWidth by remember { mutableIntStateOf(0) }
        var playerHeight by remember { mutableIntStateOf(0) }

        Box(
            modifier = playerModifier
                .onSizeChanged {
                    playerWidth = it.width
                    playerHeight = it.height
                }
                .onGloballyPositioned {
                    videoPositionInRoot = it.positionInRoot()
                }
                .pointerInteropFilter { event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        overlayVisible = true
                        overlayActivity++
                    }
                    if (videoSourceIsCamera) return@pointerInteropFilter false
                    val touchInRoot = Offset(event.x, event.y) + videoPositionInRoot
                    if (showTouchpadOverlay && overlayRectInRoot.contains(touchInRoot)) {
                        return@pointerInteropFilter false
                    }
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                            val idx = event.actionIndex
                            onTouchEvent(0, event.getPointerId(idx).toLong(), event.getX(idx), event.getY(idx), playerWidth, playerHeight)
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                            val idx = event.actionIndex
                            onTouchEvent(1, event.getPointerId(idx).toLong(), event.getX(idx), event.getY(idx), playerWidth, playerHeight)
                        }
                        MotionEvent.ACTION_MOVE -> {
                            for (i in 0 until event.pointerCount) {
                                onTouchEvent(2, event.getPointerId(i).toLong(), event.getX(i), event.getY(i), playerWidth, playerHeight)
                            }
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            for (i in 0 until event.pointerCount) {
                                onTouchEvent(1, event.getPointerId(i).toLong(), event.getX(i), event.getY(i), playerWidth, playerHeight)
                            }
                        }
                    }
                    true
                }
        )

        // ---- Remote-friendly overlay ------------------------------------------------------
        // Right: control buttons stacked vertically. Left: device volume + stream stats toggle.
        // Any D-pad direction / Select / touch shows it; Back (or Left at the edge) hides it;
        // it fades out after 6 seconds without input.
        LaunchedEffect(overlayVisible) {
            if (overlayVisible) {
                if (!showKeyboard) {
                    delay(80)
                    runCatching { overlayControlsFocus.requestFocus() }
                }
            } else {
                volumeAdjusting = false
                if (!showKeyboard) runCatching { focusRequester.requestFocus() }
            }
        }
        LaunchedEffect(overlayVisible, overlayActivity) {
            if (overlayVisible) {
                delay(6000)
                overlayVisible = false
            }
        }

        if (statsVisible) {
            StreamStatsPanel(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .zIndex(12f)
            )
        }

        AnimatedVisibility(
            visible = overlayVisible,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(400)),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .zIndex(10f)
        ) {
            Column(
                modifier = Modifier
                    .padding(end = 16.dp, top = 16.dp, bottom = 16.dp)
                    .verticalScroll(rememberScrollState())
                    .focusRequester(overlayControlsFocus)
                    .focusGroup(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                    if (canRecord) {
                        IconButton(
                            onClick = onToggleRecord,
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = if (isRecording) Color.Red.copy(alpha = 0.8f) else Color.Black.copy(alpha = 0.6f)
                            )
                        ) {
                            Icon(
                                if (isRecording) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
                                contentDescription = stringResource(if (isRecording) R.string.scrcpy_recording_stopped else R.string.scrcpy_recording_started),
                                tint = if (isRecording) Color.White else Color.Red
                            )
                        }
                    }
                    IconButton(
                        onClick = { captureScreenshot() },
                        colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.6f))
                    ) {
                        Icon(
                            Icons.Filled.PhotoCamera,
                            contentDescription = stringResource(R.string.cd_take_screenshot),
                            tint = Color.White
                        )
                    }
                    if (videoSourceIsCamera) {
                        IconButton(
                            onClick = onToggleTorch,
                            colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.6f))
                        ) {
                            Icon(
                                if (torchOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                                contentDescription = stringResource(R.string.cd_toggle_torch),
                                tint = if (torchOn) Color.Yellow else Color.White
                            )
                        }
                    }
                    if (!videoSourceIsCamera) {
                        IconButton(
                            onClick = onRotateDevice,
                            colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.6f))
                        ) {
                            Icon(
                                Icons.Filled.ScreenRotation,
                                contentDescription = stringResource(R.string.cd_rotate_device),
                                tint = Color.White
                            )
                        }
                        if (onPasteClipboard != null) {
                            IconButton(
                                onClick = onPasteClipboard,
                                colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.6f))
                            ) {
                                Icon(
                                    Icons.Filled.ContentPaste,
                                    contentDescription = stringResource(R.string.cd_paste_clipboard),
                                    tint = Color.White
                                )
                            }
                        }
                        IconButton(
                            onClick = onToggleFloatingNav,
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = if (showFloatingNav) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                                } else {
                                    Color.Black.copy(alpha = 0.6f)
                                }
                            )
                        ) {
                            Icon(
                                Icons.Filled.Navigation,
                                contentDescription = stringResource(R.string.display_floating_nav_title),
                                tint = if (showFloatingNav) MaterialTheme.colorScheme.onPrimary else Color.White
                            )
                        }
                        IconButton(
                            onClick = onToggleTouchpadOverlay,
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = if (showTouchpadOverlay) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                                } else {
                                    Color.Black.copy(alpha = 0.6f)
                                }
                            )
                        ) {
                            Icon(
                                Icons.Filled.TouchApp,
                                contentDescription = stringResource(R.string.tab_touchpad),
                                tint = if (showTouchpadOverlay) MaterialTheme.colorScheme.onPrimary else Color.White
                            )
                        }
                        IconButton(
                            onClick = {
                                onToggleKeyboard(!showKeyboard)
                            },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = if (showKeyboard || isImeVisible) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                                } else {
                                    Color.Black.copy(alpha = 0.6f)
                                }
                            )
                        ) {
                            Icon(
                                Icons.Filled.Keyboard,
                                contentDescription = stringResource(R.string.tab_keyboard),
                                tint = if (showKeyboard || isImeVisible) MaterialTheme.colorScheme.onPrimary else Color.White
                            )
                        }
                    }
                    IconButton(
                        onClick = onStop,
                        colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.6f))
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_stop_mirroring), tint = Color.White)
                    }
            }
        }

        AnimatedVisibility(
            visible = overlayVisible,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(400)),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .zIndex(10f)
        ) {
            Column(
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                RemoteVolumeSlider(
                    level = remoteVolume,
                    max = remoteVolumeMax,
                    adjusting = volumeAdjusting,
                    onAdjustingChange = { volumeAdjusting = it },
                    onStep = onRemoteVolumeStep,
                    onSetLevel = onRemoteVolumeSet,
                    onActivity = { overlayActivity++ }
                )
                IconButton(
                    onClick = {
                        statsVisible = !statsVisible
                        overlayActivity++
                    },
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = if (statsVisible) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                        } else {
                            Color.Black.copy(alpha = 0.6f)
                        }
                    )
                ) {
                    Icon(
                        io.github.rhythmcache.dioxamine.ui.icons.DioxamineIcons.StreamStats,
                        contentDescription = "Stream stats",
                        tint = if (statsVisible) MaterialTheme.colorScheme.onPrimary else Color.White
                    )
                }
            }
        }

        if (showFloatingNav && !videoSourceIsCamera) {
            FloatingVerticalNavBar(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    // Sit left of the control column while the overlay is showing
                    .padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = if (overlayVisible) 80.dp else 16.dp),
                onNavBack = onNavBack,
                onNavHome = onNavHome,
                onNavRecents = onNavRecents
            )
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(15f)
        ) {
            val density = LocalDensity.current
            val parentW = with(density) { maxWidth.toPx() }
            val parentH = with(density) { maxHeight.toPx() }
            val minW = with(density) { 220.dp.toPx() }
            val minH = with(density) { 160.dp.toPx() }

            val imeBottomPx = WindowInsets.ime.getBottom(density).toFloat()

            if (parentW > 0f && parentH > 0f) {
                var winW by rememberSaveable { mutableFloatStateOf(with(density) { 320.dp.toPx() }) }
                var winH by rememberSaveable { mutableFloatStateOf(with(density) { 230.dp.toPx() }) }
                var offX by rememberSaveable { mutableFloatStateOf((parentW - winW - 24f).coerceAtLeast(0f)) }
                var offY by rememberSaveable { mutableFloatStateOf((parentH - winH - 24f).coerceAtLeast(0f)) }
                var prevParentW by rememberSaveable { mutableFloatStateOf(parentW) }
                var prevParentH by rememberSaveable { mutableFloatStateOf(parentH) }

                // Clamp size and saved offset when parent changes (rotation), preserving proportional screen placement
                LaunchedEffect(parentW, parentH) {
                    if (prevParentW > 0f && prevParentH > 0f && (prevParentW != parentW || prevParentH != parentH)) {
                        val oldW = winW
                        val oldH = winH
                        val relX = (offX / (prevParentW - oldW).coerceAtLeast(1f)).coerceIn(0f, 1f)
                        val relY = (offY / (prevParentH - oldH).coerceAtLeast(1f)).coerceIn(0f, 1f)
                        winW = winW.coerceIn(minW, parentW.coerceAtLeast(minW))
                        winH = winH.coerceIn(minH, parentH.coerceAtLeast(minH))
                        offX = (relX * (parentW - winW).coerceAtLeast(0f)).coerceIn(0f, (parentW - winW).coerceAtLeast(0f))
                        offY = (relY * (parentH - winH).coerceAtLeast(0f)).coerceIn(0f, (parentH - winH).coerceAtLeast(0f))
                    } else {
                        winW = winW.coerceIn(minW, parentW.coerceAtLeast(minW))
                        winH = winH.coerceIn(minH, parentH.coerceAtLeast(minH))
                        offX = offX.coerceIn(0f, (parentW - winW).coerceAtLeast(0f))
                        offY = offY.coerceIn(0f, (parentH - winH).coerceAtLeast(0f))
                    }
                    prevParentW = parentW
                    prevParentH = parentH
                }

                // Display offset: temporarily lift above the soft keyboard without altering saved offY
                val shownOffY = offY.coerceAtMost((parentH - winH - imeBottomPx).coerceAtLeast(0f))
                val displayOffX = offX.coerceIn(0f, (parentW - winW).coerceAtLeast(0f))
                val displayOffY = shownOffY.coerceIn(0f, (parentH - winH).coerceAtLeast(0f))

                val currentParentW by rememberUpdatedState(parentW)
                val currentParentH by rememberUpdatedState(parentH)
                val currentWinW by rememberUpdatedState(winW)
                val currentWinH by rememberUpdatedState(winH)
                val currentOffX by rememberUpdatedState(displayOffX)
                val currentShownOffY by rememberUpdatedState(displayOffY)
                val currentImeBottom by rememberUpdatedState(imeBottomPx)

                AnimatedVisibility(
                    visible = showTouchpadOverlay && !videoSourceIsCamera,
                    enter = fadeIn() + scaleIn(initialScale = 0.92f),
                    exit = fadeOut() + scaleOut(targetScale = 0.92f)
                ) {
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(displayOffX.roundToInt(), displayOffY.roundToInt()) }
                            .size(with(density) { winW.toDp() }, with(density) { winH.toDp() })
                            .onGloballyPositioned { overlayRectInRoot = it.boundsInRoot() }
                    ) {
                        ScrcpyTouchpadOverlay(
                            session = session,
                            onClose = {
                                if (showTouchpadOverlay) onToggleTouchpadOverlay()
                                overlayRectInRoot = Rect.Zero
                            },
                            isFullScreen = isFullScreen,
                            onDrag = { dx, dy ->
                                val maxOffX = (currentParentW - currentWinW).coerceAtLeast(0f)
                                val maxOffY = (currentParentH - currentWinH).coerceAtLeast(0f)
                                offX = (offX + dx).coerceIn(0f, maxOffX)
                                offY = (offY + dy).coerceIn(0f, maxOffY)
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                        // Resize grip, bottom-right (enlarged hit area to 36.dp)
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(36.dp)
                                .pointerInput(Unit) {
                                    detectDragGestures { change, drag ->
                                        change.consume()
                                        val maxAllowedW = (currentParentW - currentOffX).coerceAtLeast(minW)
                                        val maxAllowedH = (currentParentH - currentShownOffY - currentImeBottom).coerceAtLeast(minH)
                                        winW = (winW + drag.x).coerceIn(minW, maxAllowedW)
                                        winH = (winH + drag.y).coerceIn(minH, maxAllowedH)
                                    }
                                }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.DragHandle,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.6f),
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .size(16.dp)
                                    .rotate(-45f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScrcpyAudioOnlyPlayer(
    modifier: Modifier = Modifier,
    config: ScrcpyConfig,
    isRecording: Boolean,
    canRecord: Boolean,
    onToggleRecord: () -> Unit,
    onStop: () -> Unit
) {
    Box(
        modifier = modifier.background(Color(0xFF121212)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(24.dp)
        ) {
            Surface(
                modifier = Modifier.size(64.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                tonalElevation = 4.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.GraphicEq,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.scrcpy_audio_only_banner),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.scrcpy_audio_only_desc),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SuggestionChip(
                    onClick = {},
                    label = { Text(config.audioCodec.uppercase()) }
                )
                SuggestionChip(
                    onClick = {},
                    label = { Text("${config.audioBitRateKbps} Kbps") }
                )
                SuggestionChip(
                    onClick = {},
                    label = {
                        Text(if (config.effectiveAudioSource() == "mic") stringResource(R.string.audio_source_mic) else stringResource(R.string.audio_source_device))
                    }
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (canRecord) {
                IconButton(
                    onClick = onToggleRecord,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = if (isRecording) Color.Red.copy(alpha = 0.8f) else Color.Black.copy(alpha = 0.6f)
                    )
                ) {
                    Icon(
                        if (isRecording) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
                        contentDescription = stringResource(if (isRecording) R.string.scrcpy_recording_stopped else R.string.scrcpy_recording_started),
                        tint = if (isRecording) Color.White else Color.Red
                    )
                }
            }
            IconButton(
                onClick = onStop,
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.6f))
            ) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_stop_mirroring), tint = Color.White)
            }
        }
    }
}
