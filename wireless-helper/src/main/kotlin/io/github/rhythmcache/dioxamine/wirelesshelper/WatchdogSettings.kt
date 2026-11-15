package io.github.rhythmcache.dioxamine.wirelesshelper

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * The two "keep Wireless debugging on while the screen is off" switches. Both are handled by
 * [WirelessWatchdogService], which runs while either is on.
 */
@Composable
fun WatchdogSettings(enabled: Boolean) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var reenable by remember { mutableStateOf(WirelessWatchdogService.reenableOn(context)) }
    var keepWifi by remember { mutableStateOf(WirelessWatchdogService.wifiLockOn(context)) }

    // The service shows an ongoing notification; ask once for permission to show it (Android 13+).
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun askNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Column {
        WatchdogSwitchRow(
            title = stringResource(R.string.watchdog_reenable_title),
            subtitle = stringResource(R.string.watchdog_reenable_subtitle),
            checked = reenable && enabled,
            enabled = enabled,
            onChange = { on ->
                reenable = on
                prefs.edit().putBoolean(WirelessWatchdogService.PREF_REENABLE, on).commit()
                if (on) askNotificationPermissionIfNeeded()
                WirelessWatchdogService.sync(context)
            }
        )
        WatchdogSwitchRow(
            title = stringResource(R.string.watchdog_wifi_title),
            subtitle = stringResource(R.string.watchdog_wifi_subtitle),
            checked = keepWifi && enabled,
            enabled = enabled,
            onChange = { on ->
                keepWifi = on
                prefs.edit().putBoolean(WirelessWatchdogService.PREF_WIFI_LOCK, on).commit()
                if (on) askNotificationPermissionIfNeeded()
                WirelessWatchdogService.sync(context)
            }
        )
    }
}

@Composable
private fun WatchdogSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = null)
    }
}
