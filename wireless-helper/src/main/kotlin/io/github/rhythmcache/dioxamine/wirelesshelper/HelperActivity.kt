package io.github.rhythmcache.dioxamine.wirelesshelper

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.rhythmcache.dioxamine.wirelessdebug.WirelessDebugging

class HelperActivity : ComponentActivity() {
    // Bumped in onResume so the status is re-read after granting the permission over ADB.
    private var refreshTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var showQuestSettings by remember { mutableStateOf(false) }
                    if (showQuestSettings) {
                        BackHandler { showQuestSettings = false }
                        QuestSettingsScreen(onBack = { showQuestSettings = false })
                    } else {
                        HelperScreen(
                            refreshTick = refreshTick,
                            onRefresh = { refreshTick++ },
                            onOpenQuestSettings = { showQuestSettings = true }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTick++
        // Make sure the watchdog service is running if either of its switches is on.
        WirelessWatchdogService.sync(this)
    }
}

private data class HelperStatus(
    val supported: Boolean,
    val permissionGranted: Boolean,
    val wirelessEnabled: Boolean,
)

private fun readStatus(context: Context) = HelperStatus(
    supported = WirelessDebugging.isSupported,
    permissionGranted = WirelessDebugging.hasPermission(context),
    wirelessEnabled = WirelessDebugging.isEnabled(context),
)

@Composable
private fun HelperScreen(refreshTick: Int, onRefresh: () -> Unit, onOpenQuestSettings: () -> Unit) {
    val context = LocalContext.current
    // Same prefs file and key the shared BootReceiver reads.
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var status by remember { mutableStateOf(readStatus(context)) }
    var enableOnBoot by remember {
        mutableStateOf(prefs.getBoolean(WirelessDebugging.PREF_ENABLE_ON_BOOT, false))
    }
    LaunchedEffect(refreshTick) { status = readStatus(context) }

    val canUse = status.supported && status.permissionGranted
    val switchFocus = remember { FocusRequester() }
    val refreshFocus = remember { FocusRequester() }

    // Give controller / D-pad input a starting point as soon as the screen opens.
    LaunchedEffect(canUse) {
        withFrameNanos { }
        runCatching { if (canUse) switchFocus.requestFocus() else refreshFocus.requestFocus() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
            Text(stringResource(R.string.title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusRow(stringResource(R.string.status_supported), status.supported)
                    StatusRow(stringResource(R.string.status_permission), status.permissionGranted)
                    StatusRow(stringResource(R.string.status_enabled), status.wirelessEnabled)
                }
            }
            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(switchFocus)
                    .toggleable(
                        value = enableOnBoot && canUse,
                        enabled = canUse,
                        role = Role.Switch,
                        onValueChange = { checked ->
                            enableOnBoot = checked
                            prefs.edit().putBoolean(WirelessDebugging.PREF_ENABLE_ON_BOOT, checked).apply()
                            if (checked) {
                                WirelessDebugging.enable(context)
                                status = readStatus(context)
                            }
                        },
                    )
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(stringResource(R.string.enable_on_boot_title), fontWeight = FontWeight.Medium)
                    Text(
                        stringResource(R.string.enable_on_boot_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = enableOnBoot && canUse, enabled = canUse, onCheckedChange = null)
            }
            Spacer(Modifier.height(8.dp))

            // Wireless debugging on/off right now (the switch above only controls boot).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = status.wirelessEnabled,
                        enabled = canUse,
                        role = Role.Switch,
                        onValueChange = { on ->
                            WirelessDebugging.setEnabled(context, on)
                                .onSuccess {
                                    Toast.makeText(
                                        context,
                                        if (on) R.string.enabled_toast else R.string.disabled_toast,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                .onFailure {
                                    Toast.makeText(context, it.message ?: it.toString(), Toast.LENGTH_LONG).show()
                                }
                            status = readStatus(context)
                        },
                    )
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(stringResource(R.string.wireless_now_title), fontWeight = FontWeight.Medium)
                    Text(
                        stringResource(R.string.wireless_now_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = status.wirelessEnabled, enabled = canUse, onCheckedChange = null)
            }
            // Keep Wireless debugging on while the screen is off (watchdog + Wi-Fi lock).
            WatchdogSettings(enabled = canUse)
            Spacer(Modifier.height(8.dp))

            // Meta Quest specific options live on their own page.
            OutlinedButton(
                onClick = onOpenQuestSettings,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(MetaLogoIcon, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.quest_settings_title), modifier = Modifier.weight(1f))
                Text("›", style = MaterialTheme.typography.titleLarge)
            }

            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                modifier = Modifier.focusRequester(refreshFocus),
                onClick = onRefresh,
            ) { Text(stringResource(R.string.refresh)) }

            if (status.supported && !status.permissionGranted) {
                Spacer(Modifier.height(24.dp))
                Text(stringResource(R.string.grant_title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.grant_desc), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                SelectionContainer {
                    Text(
                        "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.launch_once_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (ok) "✓" else "✗",
            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(label)
    }
}
