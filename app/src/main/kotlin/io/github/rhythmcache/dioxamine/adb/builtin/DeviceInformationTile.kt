package io.github.rhythmcache.dioxamine.adb.builtin

import io.github.rhythmcache.dioxamine.ui.tv.IconButton
import io.github.rhythmcache.dioxamine.ui.tv.TextButton
import io.github.rhythmcache.dioxamine.ui.tv.dpadLongPress
import io.github.rhythmcache.dioxamine.ui.tv.dpadScrollArea
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.rhythmcache.dioxamine.R
import io.github.rhythmcache.dioxamine.adb.AdbViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DeviceInformationTile(
    vm: AdbViewModel,
    isConnected: Boolean,
    onClick: () -> Unit
) {
    AdbActionTile(
        title = stringResource(R.string.action_device_info_title),
        description = stringResource(R.string.action_device_info_desc),
        icon = Icons.Filled.PhoneAndroid,
        enabled = isConnected,
        onClick = onClick
    )
}

@Composable
fun DeviceInformationDetailScreen(
    vm: AdbViewModel,
    onBack: () -> Unit
) {
    val modelLabel = stringResource(R.string.action_device_model)
    val versionLabel = stringResource(R.string.action_android_version)
    val batteryLabel = stringResource(R.string.action_battery_info)
    val serialLabel = stringResource(R.string.action_serial_number)
    val cpuLabel = stringResource(R.string.action_cpu_info)
    val uptimeLabel = stringResource(R.string.action_uptime)
    val resLabel = stringResource(R.string.action_screen_resolution)
    val pkgsLabel = stringResource(R.string.action_installed_packages)

    val actions = remember(modelLabel, versionLabel, batteryLabel, serialLabel, cpuLabel, uptimeLabel, resLabel, pkgsLabel) {
        listOf(
            modelLabel to "getprop ro.product.model",
            versionLabel to "getprop ro.build.version.release",
            batteryLabel to "dumpsys battery",
            serialLabel to "getprop ro.serialno",
            cpuLabel to "cat /proc/cpuinfo",
            uptimeLabel to "cat /proc/uptime",
            resLabel to "wm size",
            pkgsLabel to "pm list packages | wc -l"
        )
    }

    val isConnected = vm.activeClient() != null
    val scope = rememberCoroutineScope()
    var copyTarget by remember { mutableStateOf<Pair<String, String>?>(null) }

    val triggerFetchSequential = remember(isConnected) {
        {
            if (isConnected) {
                scope.launch {
                    for ((label, command) in actions) {
                        vm.runShellSuspend(label, command)
                        delay(120)
                    }
                }
            }
        }
    }

    LaunchedEffect(isConnected) {
        triggerFetchSequential()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Top Header Bar with Back Button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cd_nav_back),
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Filled.PhoneAndroid,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.dialog_device_info_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = { triggerFetchSequential() },
                enabled = isConnected
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.cd_refresh)
                )
            }
        }

        if (!isConnected) {
            Text(
                stringResource(R.string.connect_device_warning),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            val listState = rememberLazyListState()
            var viewportTop by remember { mutableFloatStateOf(0f) }
            var viewportBottom by remember { mutableFloatStateOf(0f) }
            Text(
                stringResource(R.string.device_info_copy_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned {
                        val b = it.boundsInRoot()
                        viewportTop = b.top
                        viewportBottom = b.bottom
                    },
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(4.dp)
            ) {
                items(actions) { (label, _) ->
                    DeviceInfoSectionCard(
                        label = label,
                        output = vm.nativeOutputs[label],
                        isRunning = vm.runningCommands[label] == true,
                        listState = listState,
                        viewportTop = { viewportTop },
                        viewportBottom = { viewportBottom },
                        onLongPress = { copyTarget = label to (vm.nativeOutputs[label] ?: "") }
                    )
                }
            }
        }
    }

    copyTarget?.let { (label, text) ->
        CopySectionDialog(label = label, text = text, onDismiss = { copyTarget = null })
    }
}

/**
 * One device-info section. It is a D-pad stop (outline when focused); a section taller than the
 * screen scrolls with Up/Down until its edge is visible before focus moves on, so long outputs
 * (e.g. CPU info) can be read with the remote. Holding Select (or long-pressing) opens the copy dialog.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeviceInfoSectionCard(
    label: String,
    output: String?,
    isRunning: Boolean,
    listState: LazyListState,
    viewportTop: () -> Float,
    viewportBottom: () -> Float,
    onLongPress: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var top by remember { mutableFloatStateOf(0f) }
    var bottom by remember { mutableFloatStateOf(0f) }
    val stepPx = with(LocalDensity.current) { 120.dp.toPx() }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned {
                val b = it.boundsInRoot()
                top = b.top
                bottom = b.bottom
            }
            .border(
                width = 2.dp,
                color = if (focused) MaterialTheme.colorScheme.onSurface else androidx.compose.ui.graphics.Color.Transparent,
                shape = CardDefaults.shape
            )
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                        val hidden = bottom - viewportBottom()
                        if (hidden > 1f) {
                            scope.launch { listState.animateScrollBy(minOf(stepPx, hidden + 16f)) }
                            true
                        } else false
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                        val hidden = viewportTop() - top
                        if (hidden > 1f) {
                            scope.launch { listState.animateScrollBy(-minOf(stepPx, hidden + 16f)) }
                            true
                        } else false
                    }
                    else -> false
                }
            }
            .dpadLongPress(onLongPress)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = {},
                onLongClick = onLongPress
            )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                if (isRunning && output == null) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            when {
                isRunning && output == null -> {
                    Text(
                        stringResource(R.string.status_running_command),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                output != null -> {
                    Text(
                        text = output,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                else -> {
                    Text(
                        stringResource(R.string.tap_refresh_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun CopySectionDialog(label: String, text: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scroll = rememberScrollState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(label) },
        text = {
            Box(
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .dpadScrollArea(scroll)
                    .verticalScroll(scroll)
                    .padding(6.dp)
            ) {
                SelectionContainer {
                    Text(
                        text.ifEmpty { stringResource(R.string.tap_refresh_hint) },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotEmpty(),
                onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
                    Toast.makeText(context, context.getString(R.string.device_info_copied, label), Toast.LENGTH_SHORT).show()
                    onDismiss()
                }
            ) { Text(stringResource(R.string.device_info_copy)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_close)) }
        }
    )
}
