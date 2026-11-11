package io.github.rhythmcache.dioxamine

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.foundation.focusGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.rhythmcache.dioxamine.adb.*
import io.github.rhythmcache.dioxamine.core.*
import io.github.rhythmcache.dioxamine.fastboot.FastbootScreen
import io.github.rhythmcache.dioxamine.fastboot.FastbootViewModel
import io.github.rhythmcache.dioxamine.fastboot.ListenForFastbootDevices
import io.github.rhythmcache.dioxamine.scrcpy.ScrcpyScreen
import io.github.rhythmcache.dioxamine.scrcpy.ScrcpyViewModel
import io.github.rhythmcache.dioxamine.settings.SettingsScreen
import io.github.rhythmcache.dioxamine.plugin.PluginsScreen
import io.github.rhythmcache.adb.AdbDeviceMode
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File

// ---------------------------------------------------------------------------
// Navigation
// ---------------------------------------------------------------------------

enum class Tab(@StringRes val labelRes: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    ADB(R.string.tab_adb, Icons.Filled.PhoneAndroid),
    SCRCPY(R.string.tab_scrcpy, io.github.rhythmcache.dioxamine.ui.icons.DioxamineIcons.VrHeadset),
    FASTBOOT(R.string.tab_fastboot, Icons.Filled.Bolt),
    PLUGINS(R.string.tab_plugins, Icons.Filled.Extension),
    SETTINGS(R.string.tab_settings, Icons.Filled.Settings)
}

class MainActivity : AppCompatActivity() {
    companion object {
        // Owner-token pattern: prevents a disposing/unmounting screen from clobbering a newly active
        // screen's key interceptor during overlapping Compose transitions or recomposition.
        private var interceptorOwner: Any? = null
        private var activeInterceptor: ((KeyEvent) -> Boolean)? = null

        fun setKeyEventInterceptor(owner: Any, interceptor: (KeyEvent) -> Boolean) {
            interceptorOwner = owner
            activeInterceptor = interceptor
        }

        fun clearKeyEventInterceptor(owner: Any) {
            if (interceptorOwner === owner) {
                interceptorOwner = null
                activeInterceptor = null
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (activeInterceptor?.invoke(event) == true) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()
        enableEdgeToEdge()

        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (prefs.getBoolean("keep_alive_enabled", false)) {
            DioxForegroundService.start(this)
        }

        setContent {
            val context = LocalContext.current
            val prefsState = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
            var currentAppTheme by remember {
                mutableStateOf(
                    runCatching { AppTheme.valueOf(prefsState.getString("theme_mode", "SYSTEM") ?: "SYSTEM") }
                        .getOrDefault(AppTheme.SYSTEM)
                )
            }

            var currentUseMonet by remember {
                mutableStateOf(prefsState.getBoolean("use_monet", false))
            }

            DisposableEffect(Unit) {
                val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
                    if (key == "theme_mode") {
                        currentAppTheme = runCatching { AppTheme.valueOf(prefs.getString("theme_mode", "SYSTEM") ?: "SYSTEM") }
                            .getOrDefault(AppTheme.SYSTEM)
                    } else if (key == "use_monet") {
                        currentUseMonet = prefs.getBoolean("use_monet", false)
                    }
                }
                prefsState.registerOnSharedPreferenceChangeListener(listener)
                onDispose { prefsState.unregisterOnSharedPreferenceChangeListener(listener) }
            }

            DioxamineTheme(appTheme = currentAppTheme, useMonet = currentUseMonet) {
                DioxamineApp(keyDir = filesDir)
            }
        }
    }
}

@Composable
fun DioxamineApp(keyDir: File) {
    val context = LocalContext.current
    var selectedTab by rememberSaveable { mutableStateOf(Tab.ADB) }
    val vm: AdbViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AdbViewModel(keyDir, context.applicationContext) as T
        }
    })
    val fastbootVm: FastbootViewModel = viewModel()
    val scrcpyVm: ScrcpyViewModel = viewModel()

    val coroutineScope = rememberCoroutineScope()
    val pluginRepo = remember { io.github.rhythmcache.dioxamine.plugin.PluginRepository(context.applicationContext, coroutineScope) }
    val permissionStore = remember { io.github.rhythmcache.dioxamine.plugin.PluginPermissionStore(context.applicationContext) }
    val permissionGate = remember { io.github.rhythmcache.dioxamine.plugin.PluginPermissionGate(store = permissionStore) }
    val dialogGate = remember { io.github.rhythmcache.dioxamine.plugin.PluginDialogGate() }
    val safBridge = remember { io.github.rhythmcache.dioxamine.plugin.PluginSafBridge(context.applicationContext) }

    io.github.rhythmcache.dioxamine.plugin.PluginPermissionDialogHost(permissionGate)
    io.github.rhythmcache.dioxamine.plugin.PluginDialogHost(dialogGate)
    io.github.rhythmcache.dioxamine.plugin.PluginSafLauncherHost(safBridge)

    ListenForUsbDevices(vm)
    ListenForFastbootDevices(fastbootVm)

    val adbConnectedCount = vm.devices.values.count { it.state is ConnectionState.Connected }
    val fastbootConnectedCount = if (fastbootVm.isConnected) 1 else fastbootVm.devices.size

    LaunchedEffect(adbConnectedCount, fastbootConnectedCount) {
        DioxForegroundService.updateDeviceCounts(context, adbConnectedCount, fastbootConnectedCount)
    }

    var isScrcpyFullScreen by remember { mutableStateOf(false) }
    var isPluginActive by remember { mutableStateOf(false) }

    DisposableEffect(isScrcpyFullScreen) {
        val activity = context as? ComponentActivity
        val window = activity?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            if (isScrcpyFullScreen) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            val activityOnDispose = context as? ComponentActivity
            val windowOnDispose = activityOnDispose?.window
            if (windowOnDispose != null) {
                WindowCompat.getInsetsController(windowOnDispose, windowOnDispose.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    val imeInsets = WindowInsets.ime
    val density = LocalDensity.current
    val isImeVisible by remember(imeInsets, density) {
        derivedStateOf { imeInsets.getBottom(density) > 0 }
    }
    val isFullScreen = isScrcpyFullScreen || isPluginActive
    val hideSideRail = isFullScreen

    // Side rail focus state: expanded (icon + label) while D-pad focus is on the rail,
    // collapsed to icons only while focus is inside the page content.
    // The rail only opens via remote Back or pressing Left from the page edge.
    val tabFocusRequesters = remember { Tab.entries.associateWith { FocusRequester() } }
    val contentFocusRequester = remember { FocusRequester() }
    var railHasFocus by remember { mutableStateOf(false) }
    var contentHasFocus by remember { mutableStateOf(false) }
    val inputModeManager = LocalInputModeManager.current
    val usingDpad = inputModeManager.inputMode == InputMode.Keyboard

    // On launch with a remote/D-pad, put focus on the page (not the rail) so the remote works
    // immediately. Only if the page has nothing focusable does focus fall back to the rail.
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(150)
        if (inputModeManager.inputMode == InputMode.Keyboard) {
            runCatching { contentFocusRequester.requestFocus() }
            kotlinx.coroutines.delay(100)
            if (!contentHasFocus && !railHasFocus) {
                runCatching { tabFocusRequesters.getValue(selectedTab).requestFocus() }
            }
        }
    }

    // Navigate back to ADB home tab before exiting app
    BackHandler(enabled = selectedTab != Tab.ADB && !hideSideRail) {
        selectedTab = Tab.ADB
    }

    // Remote "Back" from inside a page returns focus to the side rail first.
    // (Screens' own back handlers, e.g. closing a sub-page, still take priority.)
    BackHandler(enabled = usingDpad && !railHasFocus && !hideSideRail) {
        runCatching { tabFocusRequesters.getValue(selectedTab).requestFocus() }
    }

    // Exit Scrcpy fullscreen on back gesture
    BackHandler(enabled = isScrcpyFullScreen) {
        isScrcpyFullScreen = false
    }

    Scaffold { padding ->
        val layoutDirection = LocalLayoutDirection.current
        val contentPadding = remember(padding, isImeVisible, layoutDirection) {
            if (isImeVisible) {
                PaddingValues(
                    start = padding.calculateStartPadding(layoutDirection),
                    top = padding.calculateTopPadding(),
                    end = padding.calculateEndPadding(layoutDirection),
                    bottom = 0.dp
                )
            } else {
                padding
            }
        }
        Row(
            modifier = if (isFullScreen) {
                Modifier.fillMaxSize()
            } else {
                Modifier
                    .padding(contentPadding)
                    .consumeWindowInsets(contentPadding)
                    .fillMaxSize()
            }
        ) {
            if (!hideSideRail) {
                SideTabRail(
                    selectedTab = selectedTab,
                    expanded = railHasFocus,
                    focusRequesters = tabFocusRequesters,
                    onFocusChanged = { railHasFocus = it },
                    onSelect = { selectedTab = it }
                )
            }
            // Quick fade-in when switching tabs (old page is not kept alive, so no overlap cost).
            val pageAlpha = remember(selectedTab) { Animatable(0f) }
            LaunchedEffect(selectedTab) {
                pageAlpha.animateTo(1f, tween(durationMillis = 180, easing = LinearOutSlowInEasing))
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .graphicsLayer { alpha = pageAlpha.value }
                    .focusRequester(contentFocusRequester)
                    .onFocusChanged { contentHasFocus = it.hasFocus }
                    .focusGroup()
            ) {
            when (selectedTab) {
                Tab.ADB -> AdbScreen(vm)
                Tab.SCRCPY -> ScrcpyScreen(vm, scrcpyVm = scrcpyVm, onFullScreenChange = { isScrcpyFullScreen = it })
                Tab.FASTBOOT -> FastbootScreen(fastbootVm)
                Tab.PLUGINS -> PluginsScreen(
                    vm = vm,
                    fastbootVm = fastbootVm,
                    pluginRepo = pluginRepo,
                    permissionGate = permissionGate,
                    dialogGate = dialogGate,
                    safBridge = safBridge,
                    onPluginActiveChange = { isPluginActive = it }
                )
                Tab.SETTINGS -> SettingsScreen(vm)
            }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Left-side tab rail (TV / D-pad friendly)
// ---------------------------------------------------------------------------

private val RailExpandedWidth = 132.dp   // 66% of the original 200dp
private val RailCollapsedWidth = 60.dp   // 75% of the original 80dp

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SideTabRail(
    selectedTab: Tab,
    expanded: Boolean,
    focusRequesters: Map<Tab, FocusRequester>,
    onFocusChanged: (Boolean) -> Unit,
    onSelect: (Tab) -> Unit,
) {
    val width by animateDpAsState(
        targetValue = if (expanded) RailExpandedWidth else RailCollapsedWidth,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "railWidth"
    )

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(width)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .onFocusChanged { onFocusChanged(it.hasFocus) }
            // The rail can only be entered by pressing Left (lands on the selected tab) or by a
            // direct request (remote Back). Up/Down/Right from the page never wander into it.
            .focusProperties {
                onEnter = {
                    when (requestedFocusDirection) {
                        FocusDirection.Left -> focusRequesters.getValue(selectedTab).requestFocus()
                        FocusDirection.Enter -> Unit
                        else -> cancelFocusChange()
                    }
                }
            }
            .focusGroup()
            .selectableGroup()
            .padding(horizontal = 6.dp, vertical = 12.dp),
        // Tab buttons are centered vertically on the rail
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)
    ) {
        Tab.entries.forEach { tab ->
            SideTabRailItem(
                tab = tab,
                selected = tab == selectedTab,
                expanded = expanded,
                modifier = Modifier.focusRequester(focusRequesters.getValue(tab)),
                onClick = { onSelect(tab) }
            )
        }
    }
}

@Composable
private fun SideTabRailItem(
    tab: Tab,
    selected: Boolean,
    expanded: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    val label = stringResource(tab.labelRes)

    // Strong, TV-visible focus highlight; a softer pill marks the selected tab.
    val containerColor by animateColorAsState(
        targetValue = when {
            focused -> colors.primary
            selected -> colors.secondaryContainer
            else -> Color.Transparent
        },
        label = "railItemContainer"
    )
    val contentColor by animateColorAsState(
        targetValue = when {
            focused -> colors.onPrimary
            selected -> colors.onSecondaryContainer
            else -> colors.onSurfaceVariant
        },
        label = "railItemContent"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(containerColor)
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = ripple(),
                role = Role.Tab,
                onClick = onClick
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = label,
            tint = contentColor,
            modifier = Modifier.size(24.dp)
        )
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(durationMillis = 140, delayMillis = 60)),
            exit = fadeOut(tween(durationMillis = 80))
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(10.dp))
            Text(
                text = label,
                color = contentColor,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip
            )
          }
        }
    }
}
