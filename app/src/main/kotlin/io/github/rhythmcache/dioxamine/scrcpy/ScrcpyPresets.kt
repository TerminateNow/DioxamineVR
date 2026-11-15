package io.github.rhythmcache.dioxamine.scrcpy

import io.github.rhythmcache.dioxamine.ui.tv.DropdownMenuItem
import io.github.rhythmcache.dioxamine.ui.tv.IconButton
import io.github.rhythmcache.dioxamine.ui.tv.OutlinedButton
import io.github.rhythmcache.dioxamine.ui.tv.TextButton
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.rhythmcache.dioxamine.R
import org.json.JSONArray
import org.json.JSONObject

/** A named, saved set of scrcpy settings. */
data class ScrcpyPreset(val name: String, val config: ScrcpyConfig)

/**
 * Named scrcpy setting presets, kept in order in SharedPreferences. Seeded on first use with
 * "Default" (the stock settings) and "Quest 3" (stock settings plus the crop and angle that fit a
 * Quest 3 mirror onto a 16:9 screen).
 */
object ScrcpyPresetStore {
    private const val PREFS_NAME = "scrcpy_presets"
    private const val KEY_PRESETS = "presets"
    private const val KEY_SELECTED = "selected"

    const val DEFAULT_NAME = "Default"
    const val QUEST3_NAME = "Quest 3"

    fun builtIns(): List<ScrcpyPreset> = listOf(
        ScrcpyPreset(DEFAULT_NAME, ScrcpyConfig()),
        ScrcpyPreset(QUEST3_NAME, ScrcpyConfig(crop = "1792:1008:2196:626", angle = "-22")),
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(context: Context): List<ScrcpyPreset> {
        val raw = prefs(context).getString(KEY_PRESETS, null)
            ?: return builtIns().also { save(context, it) }
        val list = runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ScrcpyPreset(o.getString("name"), ScrcpyConfig.fromJson(o.getString("config")))
            }
        }.getOrDefault(emptyList())
        return list.ifEmpty { builtIns().also { save(context, it) } }
    }

    fun save(context: Context, presets: List<ScrcpyPreset>) {
        val arr = JSONArray()
        presets.forEach { arr.put(JSONObject().put("name", it.name).put("config", it.config.toJson())) }
        prefs(context).edit().putString(KEY_PRESETS, arr.toString()).apply()
    }

    fun selectedName(context: Context): String? = prefs(context).getString(KEY_SELECTED, null)

    fun setSelectedName(context: Context, name: String) {
        prefs(context).edit().putString(KEY_SELECTED, name).apply()
    }
}

private enum class PresetDialog { NONE, ADD, CONFIRM_SAVE, CONFIRM_DELETE }

/**
 * Presets row for the top of the scrcpy settings: preset dropdown + Save, Load, Delete and Add.
 *  - Load applies the selected preset to the current settings ([onLoad]).
 *  - Save overwrites the selected preset with the current settings (after confirming).
 *  - Add asks for a name and creates a preset with the default settings.
 */
@Composable
fun ScrcpyPresetBar(
    currentConfig: ScrcpyConfig,
    isMirroring: Boolean,
    onLoad: (ScrcpyConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var presets by remember { mutableStateOf(ScrcpyPresetStore.load(context)) }
    var selected by remember {
        mutableStateOf(
            ScrcpyPresetStore.selectedName(context)?.takeIf { n -> presets.any { it.name == n } }
                ?: presets.first().name
        )
    }
    var menuOpen by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf(PresetDialog.NONE) }

    fun persist(newList: List<ScrcpyPreset>, newSelected: String) {
        presets = newList
        selected = newSelected
        ScrcpyPresetStore.save(context, newList)
        ScrcpyPresetStore.setSelectedName(context, newSelected)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f)) {
                OutlinedButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        stringResource(R.string.scrcpy_preset_label, selected),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    presets.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(preset.name) },
                            onClick = {
                                menuOpen = false
                                selected = preset.name
                                ScrcpyPresetStore.setSelectedName(context, preset.name)
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = { dialog = PresetDialog.CONFIRM_SAVE }) {
                Icon(Icons.Filled.Save, contentDescription = stringResource(R.string.scrcpy_preset_save))
            }
            IconButton(
                enabled = !isMirroring,
                onClick = {
                    presets.firstOrNull { it.name == selected }?.let {
                        onLoad(it.config)
                        Toast.makeText(context, context.getString(R.string.scrcpy_preset_loaded, it.name), Toast.LENGTH_SHORT).show()
                    }
                }
            ) {
                Icon(Icons.Filled.Restore, contentDescription = stringResource(R.string.scrcpy_preset_load))
            }
            IconButton(
                enabled = presets.size > 1,
                onClick = { dialog = PresetDialog.CONFIRM_DELETE }
            ) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.scrcpy_preset_delete))
            }
            IconButton(onClick = { dialog = PresetDialog.ADD }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.scrcpy_preset_add))
            }
        }
        Text(
            stringResource(R.string.scrcpy_preset_caption),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }

    when (dialog) {
        PresetDialog.NONE -> Unit
        PresetDialog.CONFIRM_SAVE -> AlertDialog(
            onDismissRequest = { dialog = PresetDialog.NONE },
            title = { Text(stringResource(R.string.scrcpy_preset_save_title)) },
            text = { Text(stringResource(R.string.scrcpy_preset_save_msg, selected)) },
            confirmButton = {
                TextButton(onClick = {
                    persist(
                        presets.map { if (it.name == selected) it.copy(config = currentConfig) else it },
                        selected
                    )
                    dialog = PresetDialog.NONE
                    Toast.makeText(context, context.getString(R.string.scrcpy_preset_saved, selected), Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.scrcpy_preset_overwrite)) }
            },
            dismissButton = {
                TextButton(onClick = { dialog = PresetDialog.NONE }) { Text(stringResource(R.string.btn_cancel)) }
            }
        )
        PresetDialog.CONFIRM_DELETE -> AlertDialog(
            onDismissRequest = { dialog = PresetDialog.NONE },
            title = { Text(stringResource(R.string.scrcpy_preset_delete_title)) },
            text = { Text(stringResource(R.string.scrcpy_preset_delete_msg, selected)) },
            confirmButton = {
                TextButton(onClick = {
                    val remaining = presets.filterNot { it.name == selected }
                    if (remaining.isNotEmpty()) persist(remaining, remaining.first().name)
                    dialog = PresetDialog.NONE
                }) { Text(stringResource(R.string.scrcpy_preset_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { dialog = PresetDialog.NONE }) { Text(stringResource(R.string.btn_cancel)) }
            }
        )
        PresetDialog.ADD -> AddPresetDialog(
            existingNames = presets.map { it.name },
            onDismiss = { dialog = PresetDialog.NONE },
            onAdd = { name ->
                persist(presets + ScrcpyPreset(name, ScrcpyConfig()), name)
                dialog = PresetDialog.NONE
            }
        )
    }
}

@Composable
private fun AddPresetDialog(
    existingNames: List<String>,
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val trimmed = name.trim()
    val duplicate = existingNames.any { it.equals(trimmed, ignoreCase = true) }
    val valid = trimmed.isNotEmpty() && !duplicate
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // Put the cursor in the name field and open the on-screen keyboard right away.
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(150)
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scrcpy_preset_add_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.scrcpy_preset_add_msg),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                // Plain Material field (not the press-to-edit TV one): it should take input at once.
                androidx.compose.material3.OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    singleLine = true,
                    isError = duplicate,
                    label = { Text(stringResource(R.string.scrcpy_preset_name)) },
                    supportingText = if (duplicate) {
                        { Text(stringResource(R.string.scrcpy_preset_name_taken)) }
                    } else null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (valid) onAdd(trimmed) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus)
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onAdd(trimmed) }) {
                Text(stringResource(R.string.scrcpy_preset_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        }
    )
}
