package io.github.rhythmcache.dioxamine.settings

import io.github.rhythmcache.dioxamine.ui.tv.IconButton
import io.github.rhythmcache.dioxamine.ui.tv.Switch
import io.github.rhythmcache.dioxamine.ui.tv.TextButton
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.rhythmcache.dioxamine.R
import io.github.rhythmcache.dioxamine.ui.visibility.AppSections
import io.github.rhythmcache.dioxamine.ui.visibility.UiVisibility
import io.github.rhythmcache.dioxamine.ui.visibility.VisNode

/** One flattened row of the tree: the node, its depth, and whether a parent is switched off. */
private data class VisRow(val node: VisNode, val depth: Int, val underHiddenParent: Boolean)

/**
 * Settings > Appearance > Hide/Show Sections: every hideable section and setting in on-screen
 * order. On = shown in the menus, off = hidden (its value still applies). Children of a hidden
 * section are greyed out and keep their own switch state for when the section is shown again.
 */
@Composable
fun HideShowSectionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    BackHandler(onBack = onBack)

    // Top-level sections (tabs) can be collapsed; all start expanded.
    var collapsed by rememberSaveable { mutableStateOf(setOf<String>()) }
    val hiddenCount = UiVisibility.hiddenCount()

    val rows = buildList {
        fun walk(nodes: List<VisNode>, depth: Int, parentHidden: Boolean) {
            nodes.forEach { node ->
                add(VisRow(node, depth, parentHidden))
                if (depth == 0 && node.id in collapsed) return@forEach
                walk(node.children, depth + 1, parentHidden || !UiVisibility.isSwitchOn(node.id))
            }
        }
        walk(AppSections, 0, false)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_nav_back))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.vis_hide_show_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    if (hiddenCount == 0) {
                        stringResource(R.string.vis_none_hidden)
                    } else {
                        pluralStringResource(R.plurals.vis_hidden_count, hiddenCount, hiddenCount)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(
                onClick = { UiVisibility.showAll(context) },
                enabled = UiVisibility.hidden.isNotEmpty()
            ) { Text(stringResource(R.string.vis_show_all)) }
        }
        Text(
            stringResource(R.string.vis_page_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        HorizontalDivider()

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ) {
            items(rows, key = { it.node.id }) { row ->
                VisToggleRow(
                    row = row,
                    isCollapsed = row.node.id in collapsed,
                    onToggleCollapse = {
                        collapsed = if (row.node.id in collapsed) collapsed - row.node.id else collapsed + row.node.id
                    },
                    onChange = { on -> UiVisibility.setShown(context, row.node.id, on) }
                )
            }
        }
    }
}

@Composable
private fun VisToggleRow(
    row: VisRow,
    isCollapsed: Boolean,
    onToggleCollapse: () -> Unit,
    onChange: (Boolean) -> Unit,
) {
    val node = row.node
    val isSection = node.children.isNotEmpty()
    val checked = UiVisibility.isSwitchOn(node.id)
    val enabled = !row.underHiddenParent
    // Row is the D-pad stop; its focus shows as an oval ring on the switch only.
    val focus = remember { MutableInteractionSource() }

    if (row.depth == 0) Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (row.depth * 20).dp)
            .alpha(if (enabled) 1f else 0.38f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (row.depth == 0) {
            // Expand / collapse a whole tab's list.
            IconButton(onClick = onToggleCollapse) {
                Icon(
                    if (isCollapsed) Icons.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(if (isCollapsed) R.string.cd_expand else R.string.cd_collapse)
                )
            }
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Switch,
                    interactionSource = focus,
                    indication = null,
                    onValueChange = onChange
                )
                .padding(vertical = if (isSection) 10.dp else 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(node.label),
                style = if (row.depth == 0) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSection) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f).padding(end = 12.dp)
            )
            Switch(
                checked = checked,
                enabled = enabled,
                interactionSource = focus,
                onCheckedChange = null
            )
        }
    }
    if (row.depth == 0) HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
}
