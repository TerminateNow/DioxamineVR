package io.github.rhythmcache.dioxamine.adb.builtin.metaquest

import io.github.rhythmcache.dioxamine.ui.tv.IconButton
import io.github.rhythmcache.dioxamine.ui.tv.Switch
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.rhythmcache.dioxamine.R
import io.github.rhythmcache.dioxamine.adb.AdbViewModel
import io.github.rhythmcache.dioxamine.adb.builtin.AdbActionTile
import io.github.rhythmcache.dioxamine.core.executeShell
import io.github.rhythmcache.dioxamine.core.executeShellResult
import io.github.rhythmcache.dioxamine.ui.icons.DioxamineIcons
import io.github.rhythmcache.dioxamine.wirelessdebug.QuestProximitySensor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MetaQuestTile(
    isConnected: Boolean,
    onClick: () -> Unit
) {
    AdbActionTile(
        title = stringResource(R.string.meta_quest_tile_title),
        description = stringResource(R.string.meta_quest_tile_desc),
        icon = DioxamineIcons.MetaLogo,
        enabled = isConnected,
        onClick = onClick
    )
}

/**
 * Meta Quest specific settings, applied over ADB to the connected headset.
 * Currently: the proximity ("light") sensor between the lenses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetaQuestScreen(
    vm: AdbViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = vm.activeClient()
    val conn = vm.activeDeviceId?.let { vm.devices[it] }
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    // There is no way to read the sensor mode back, so remember what was last sent per headset.
    val prefKey = "quest_prox_sensor_on_${conn?.uniqueId ?: conn?.id ?: "unknown"}"

    var deviceLine by remember { mutableStateOf<String?>(null) }
    var isQuest by remember { mutableStateOf(true) }
    var sensorOn by remember(prefKey) { mutableStateOf(prefs.getBoolean(prefKey, true)) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(client) {
        val c = client ?: return@LaunchedEffect
        val supportsV2 = conn?.supportsShellV2 ?: true
        val out = withContext(Dispatchers.IO) {
            runCatching {
                c.executeShell("getprop ro.product.manufacturer; getprop ro.product.model", supportsV2)
            }.getOrNull()
        }
        val lines = out?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        isQuest = QuestProximitySensor.isQuestManufacturer(lines.getOrNull(0))
        deviceLine = lines.joinToString(" ").ifBlank { null }
    }

    fun setSensor(on: Boolean) {
        val c = client ?: return
        val supportsV2 = conn?.supportsShellV2 ?: true
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { c.executeShellResult(QuestProximitySensor.adbCommand(on), supportsV2) }.getOrNull()
            }
            busy = false
            val ok = result != null && result.isSuccess && !result.output.contains("Error", ignoreCase = true)
            if (ok) {
                sensorOn = on
                prefs.edit().putBoolean(prefKey, on).apply()
            }
            Toast.makeText(
                context,
                when {
                    !ok -> context.getString(R.string.meta_quest_command_failed, result?.output ?: "")
                    on -> context.getString(R.string.meta_quest_sensor_on_toast)
                    else -> context.getString(R.string.meta_quest_sensor_off_toast)
                },
                if (ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
            ).show()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.meta_quest_tile_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_nav_back))
                }
            },
            windowInsets = WindowInsets(0, 0, 0, 0)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (client == null) {
                Text(
                    stringResource(R.string.connect_device_warning),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    DioxamineIcons.MetaLogo,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    deviceLine ?: stringResource(R.string.status_running_command),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!isQuest) {
                Text(
                    stringResource(R.string.meta_quest_not_quest_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                // Row is the D-pad stop; its focus shows as an oval ring on the switch only.
                val rowFocus = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                Row(
                    modifier = Modifier
                        .toggleable(
                            value = sensorOn,
                            enabled = !busy,
                            role = Role.Switch,
                            interactionSource = rowFocus,
                            indication = null,
                            onValueChange = { setSensor(it) }
                        )
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.LightMode,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                        Text(
                            stringResource(R.string.meta_quest_sensor_title),
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            stringResource(R.string.meta_quest_sensor_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Switch(
                        checked = sensorOn,
                        enabled = !busy,
                        interactionSource = rowFocus,
                        onCheckedChange = null
                    )
                }
            }
        }
    }
}
