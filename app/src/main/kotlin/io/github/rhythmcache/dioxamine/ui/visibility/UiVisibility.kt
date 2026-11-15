package io.github.rhythmcache.dioxamine.ui.visibility

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp

/**
 * Which app sections/settings are hidden (Settings > Appearance > Hide/Show Sections).
 * Hiding only removes things from the UI; their values keep applying. Backed by Compose state, so
 * every screen updates as soon as something is toggled.
 */
object UiVisibility {
    private const val PREFS = "settings"
    private const val KEY = "hidden_sections"

    /** Ids switched off by the user (a hidden parent also hides everything under it). */
    var hidden by mutableStateOf<Set<String>>(emptySet())
        private set

    private val parentOf: Map<String, String> = buildMap {
        fun walk(nodes: List<VisNode>, parent: String?) {
            nodes.forEach { n ->
                if (parent != null) put(n.id, parent)
                walk(n.children, n.id)
            }
        }
        walk(AppSections, null)
    }

    val allIds: List<String> = buildList {
        fun walk(nodes: List<VisNode>) = nodes.forEach { add(it.id); walk(it.children) }
        walk(AppSections)
    }

    fun init(context: Context) {
        hidden = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet()).orEmpty().toSet()
    }

    /** False if this id or any of its parents is switched off. Unknown ids are always shown. */
    fun isShown(id: String): Boolean {
        var current: String? = id
        while (current != null) {
            if (current in hidden) return false
            current = parentOf[current]
        }
        return true
    }

    /** Whether the user's own switch for [id] is on (ignores parents). */
    fun isSwitchOn(id: String): Boolean = id !in hidden

    fun setShown(context: Context, id: String, shown: Boolean) {
        hidden = if (shown) hidden - id else hidden + id
        persist(context)
    }

    fun showAll(context: Context) {
        hidden = emptySet()
        persist(context)
    }

    /** Number of entries currently not shown (switched off, or under a switched-off parent). */
    fun hiddenCount(): Int = allIds.count { !isShown(it) }

    private fun persist(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY, HashSet(hidden)).apply()
    }
}

/** Renders [content] only while [id] is shown. */
@Composable
fun Shown(id: String, content: @Composable () -> Unit) {
    if (UiVisibility.isShown(id)) content()
}

/** Shown where every sub-section of a page has been hidden. */
@Composable
fun AllSectionsHiddenHint() {
    androidx.compose.material3.Text(
        androidx.compose.ui.res.stringResource(io.github.rhythmcache.dioxamine.R.string.vis_all_hidden_hint),
        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = androidx.compose.ui.Modifier.padding(24.dp)
    )
}
