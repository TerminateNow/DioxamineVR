package io.github.rhythmcache.dioxamine.wirelesshelper

import android.content.Context
import android.widget.Toast
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.rhythmcache.dioxamine.wirelessdebug.QuestGuardian
import io.github.rhythmcache.dioxamine.wirelessdebug.QuestProximitySensor

/** Meta logo (same drawing as Dioxamine's DioxamineIcons.MetaLogo). */
val MetaLogoIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "MetaLogo",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        addPath(
            pathData = addPathNodes(
                "M5.2 17.3C3.3 17.3 2.1 15.7 2.1 13.4C2.1 9.6 4.4 5.7 7.3 5.7" +
                    "C9.4 5.7 10.8 7.8 12.6 11.2C14.3 14.6 15.6 17.3 18.4 17.3" +
                    "C20.6 17.3 21.9 15.6 21.9 13.3C21.9 9.4 19.4 5.7 16.6 5.7" +
                    "C14.5 5.7 13.1 7.9 11.4 11.2C9.7 14.6 8 17.3 5.2 17.3Z"
            ),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        )
    }.build()
}

/**
 * Meta Quest Settings page: proximity (light) sensor and guardian boundary. Both are applied from
 * this app on the headset itself and, when set to off, re-applied after every boot by the shared
 * BootReceiver. The Quest may only accept them over ADB; then Dioxamine's Meta Quest Settings
 * (which sends the same commands over ADB) is the way to change them.
 */
@Composable
fun QuestSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var proxSensorOn by remember {
        mutableStateOf(!prefs.getBoolean(QuestProximitySensor.PREF_SENSOR_OFF, false))
    }
    // The guardian property can be read back, so show its real state.
    var guardianOn by remember { mutableStateOf(QuestGuardian.isGuardianOn(QuestGuardian.readLocal())) }
    val firstFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstFocus.requestFocus() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("←  " + stringResource(R.string.back)) }
                Spacer(Modifier.width(8.dp))
                Icon(MetaLogoIcon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.quest_settings_title), style = MaterialTheme.typography.headlineSmall)
            }
            Spacer(Modifier.height(12.dp))

            if (!QuestProximitySensor.isQuestDevice) {
                Text(
                    stringResource(R.string.quest_not_quest),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingSwitchRow(
                        title = stringResource(R.string.prox_title),
                        subtitle = stringResource(R.string.prox_subtitle),
                        checked = proxSensorOn,
                        modifier = Modifier.focusRequester(firstFocus),
                        onChange = { on ->
                            QuestProximitySensor.sendFromApp(context, sensorOn = on)
                                .onSuccess {
                                    proxSensorOn = on
                                    prefs.edit().putBoolean(QuestProximitySensor.PREF_SENSOR_OFF, !on).apply()
                                    Toast.makeText(
                                        context,
                                        if (on) R.string.prox_on_toast else R.string.prox_off_toast,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                .onFailure {
                                    Toast.makeText(context, it.message ?: it.toString(), Toast.LENGTH_LONG).show()
                                }
                        }
                    )
                    SettingSwitchRow(
                        title = stringResource(R.string.guardian_title),
                        subtitle = stringResource(R.string.guardian_subtitle),
                        checked = guardianOn,
                        onChange = { on ->
                            QuestGuardian.setLocal(guardianOn = on)
                                .onSuccess {
                                    prefs.edit().putBoolean(QuestGuardian.PREF_GUARDIAN_OFF, !on).apply()
                                    Toast.makeText(
                                        context,
                                        if (on) R.string.guardian_on_toast else R.string.guardian_off_toast,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                .onFailure {
                                    Toast.makeText(context, R.string.guardian_needs_adb, Toast.LENGTH_LONG).show()
                                }
                            guardianOn = QuestGuardian.isGuardianOn(QuestGuardian.readLocal())
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
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
        Switch(checked = checked, onCheckedChange = null)
    }
}
