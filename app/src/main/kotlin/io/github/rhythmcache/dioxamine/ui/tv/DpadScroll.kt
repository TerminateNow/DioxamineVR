package io.github.rhythmcache.dioxamine.ui.tv

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Select / OK / Enter on a remote or keyboard. */
fun isSelectKey(keyCode: Int): Boolean =
    keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER

/**
 * Makes a scrollable area a single D-pad stop with a "scroll mode":
 *  - focused: thin outline; D-pad moves on to other controls as usual;
 *  - Select: enters scroll mode (thicker primary outline); Up/Down now scroll the content
 *    by [step] and focus stays here;
 *  - Select again or Back: leaves scroll mode.
 *
 * Works with any [ScrollableState] (LazyListState, ScrollState, …).
 */
@Composable
fun Modifier.dpadScrollArea(
    state: ScrollableState,
    step: Dp = 120.dp,
    onScrollModeChange: (Boolean) -> Unit = {},
): Modifier {
    var focused by remember { mutableStateOf(false) }
    var scrollMode by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val stepPx = with(LocalDensity.current) { step.toPx() }

    fun setMode(on: Boolean) {
        if (scrollMode != on) {
            scrollMode = on
            onScrollModeChange(on)
        }
    }

    // Registered after the app-level handlers, so it takes priority while scroll mode is on.
    BackHandler(enabled = scrollMode) { setMode(false) }

    val outline = when {
        scrollMode -> MaterialTheme.colorScheme.primary
        focused -> MaterialTheme.colorScheme.onSurface
        else -> Color.Transparent
    }

    return this
        .onFocusChanged {
            focused = it.isFocused
            if (!it.isFocused) setMode(false)
        }
        .border(if (scrollMode) 3.dp else 2.dp, outline, RoundedCornerShape(8.dp))
        .onPreviewKeyEvent { event ->
            val code = event.nativeKeyEvent.keyCode
            val down = event.type == KeyEventType.KeyDown
            when {
                isSelectKey(code) -> {
                    if (down && event.nativeKeyEvent.repeatCount == 0) setMode(!scrollMode)
                    true
                }
                scrollMode && code == AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                    if (down) scope.launch { state.animateScrollBy(-stepPx) }
                    true
                }
                scrollMode && code == AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (down) scope.launch { state.animateScrollBy(stepPx) }
                    true
                }
                // Keep focus here while scrolling; Left/Right would otherwise jump away.
                scrollMode && (code == AndroidKeyEvent.KEYCODE_DPAD_LEFT || code == AndroidKeyEvent.KEYCODE_DPAD_RIGHT) -> true
                else -> false
            }
        }
        .focusable()
}

/**
 * Calls [onLongPress] when Select/OK is held on a focused element (and swallows the matching
 * key-up so it doesn't also count as a click). Pair with `combinedClickable(onLongClick = …)`
 * for touch.
 */
@Composable
fun Modifier.dpadLongPress(onLongPress: () -> Unit): Modifier {
    var fired by remember { mutableStateOf(false) }
    return this.onPreviewKeyEvent { event ->
        if (!isSelectKey(event.nativeKeyEvent.keyCode)) return@onPreviewKeyEvent false
        when (event.type) {
            KeyEventType.KeyDown -> {
                val native = event.nativeKeyEvent
                if (native.repeatCount == 0) {
                    fired = false
                    false
                } else {
                    if (!fired) {
                        fired = true
                        onLongPress()
                    }
                    true
                }
            }
            KeyEventType.KeyUp -> {
                val consume = fired
                fired = false
                consume
            }
            else -> false
        }
    }
}
