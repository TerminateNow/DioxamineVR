package io.github.rhythmcache.dioxamine.adb.builtin.wirelesshelper

import io.github.rhythmcache.dioxamine.ui.tv.TextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.rhythmcache.dioxamine.R
import io.github.rhythmcache.dioxamine.adb.AdbViewModel
import io.github.rhythmcache.dioxamine.adb.builtin.AdbActionTile
import io.github.rhythmcache.dioxamine.core.HelperAppStatus
import io.github.rhythmcache.dioxamine.core.HelperInstallState
import io.github.rhythmcache.dioxamine.core.HelperInstallStep

/**
 * One-tap install of the headset helper app (`:wireless-helper`) onto the connected device:
 * installs the bundled APK, grants WRITE_SECURE_SETTINGS and opens the helper once.
 */
@Composable
fun WirelessHelperTile(vm: AdbViewModel, isConnected: Boolean) {
    val context = LocalContext.current

    // Re-check whenever the connected device changes.
    LaunchedEffect(vm.activeDeviceId, isConnected) {
        if (isConnected) vm.refreshHelperStatus(context)
    }

    val status = vm.helperStatus
    val title = stringResource(
        when (status) {
            is HelperAppStatus.UpdateAvailable -> R.string.wireless_helper_tile_title_update
            is HelperAppStatus.UpToDate -> R.string.wireless_helper_tile_title_installed
            else -> R.string.wireless_helper_tile_title
        }
    )
    val description = when (status) {
        HelperAppStatus.Checking -> stringResource(R.string.wireless_helper_status_checking)
        HelperAppStatus.Unsupported -> stringResource(R.string.wireless_helper_status_unsupported)
        HelperAppStatus.NotBundled -> stringResource(R.string.wireless_helper_status_not_bundled)
        is HelperAppStatus.NotInstalled -> stringResource(R.string.wireless_helper_status_not_installed, status.bundledVersion)
        is HelperAppStatus.UpdateAvailable ->
            stringResource(R.string.wireless_helper_status_update, status.installedVersion, status.bundledVersion)
        is HelperAppStatus.UpToDate -> stringResource(R.string.wireless_helper_status_up_to_date, status.installedVersion)
        HelperAppStatus.Unknown -> stringResource(R.string.wireless_helper_tile_desc)
    }

    AdbActionTile(
        title = title,
        description = description,
        icon = if (status is HelperAppStatus.UpdateAvailable) Icons.Filled.SystemUpdate else Icons.Filled.Wifi,
        enabled = isConnected && status != HelperAppStatus.Unsupported && status != HelperAppStatus.NotBundled,
        onClick = { vm.installWirelessHelper(context) }
    )

    when (val state = vm.helperInstallState) {
        HelperInstallState.Idle -> Unit
        is HelperInstallState.Running -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.wireless_helper_tile_title)) },
            text = {
                Column {
                    Text(
                        stringResource(
                            when (state.step) {
                                HelperInstallStep.INSTALLING -> R.string.wireless_helper_step_installing
                                HelperInstallStep.GRANTING -> R.string.wireless_helper_step_granting
                                HelperInstallStep.LAUNCHING -> R.string.wireless_helper_step_launching
                            }
                        )
                    )
                    Spacer(Modifier.height(12.dp))
                    if (state.step == HelperInstallStep.INSTALLING) {
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {}
        )
        is HelperInstallState.Success -> AlertDialog(
            onDismissRequest = { vm.dismissHelperInstall() },
            title = { Text(stringResource(R.string.wireless_helper_done_title)) },
            text = {
                Text(
                    stringResource(
                        if (state.launched) {
                            R.string.wireless_helper_done_msg
                        } else {
                            R.string.wireless_helper_done_not_launched_msg
                        }
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.dismissHelperInstall() }) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        )
        is HelperInstallState.Error -> AlertDialog(
            onDismissRequest = { vm.dismissHelperInstall() },
            title = { Text(stringResource(R.string.wireless_helper_failed_title)) },
            text = {
                Text(state.message, color = MaterialTheme.colorScheme.error)
            },
            confirmButton = {
                TextButton(onClick = { vm.dismissHelperInstall() }) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        )
    }
}
