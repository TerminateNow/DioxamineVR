package io.github.rhythmcache.dioxamine.ui.tv

// TV / D-pad friendly drop-in replacements for Material 3 components.
//
// Files import these by name (e.g. `import io.github.rhythmcache.dioxamine.ui.tv.Button`),
// which takes priority over `import androidx.compose.material3.*`, so call sites stay unchanged.
//
//  * Text fields no longer open the keyboard just because the remote moved over them.
//    They act like a button: highlight on focus, and only start editing (and show the
//    keyboard) after Select/OK or a tap.
//  * Buttons, icon buttons, chips, switches and menu items show a thin border in their
//    text colour while focused, so the highlighted control is easy to see on a TV.

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.ChipElevation
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.MenuItemColors
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Width of the focus border drawn around a highlighted control. */
val TvFocusBorderWidth: Dp = 1.5.dp

// ---------------------------------------------------------------------------
// Focus border indication
// ---------------------------------------------------------------------------

/**
 * Draws a thin outline of [shape] in [color] while the interaction source is focused.
 * A null [color] uses the current content (text) colour at the place it is drawn.
 */
class FocusBorderIndication(
    private val shape: Shape,
    private val color: Color?,
    private val width: Dp = TvFocusBorderWidth,
    private val base: IndicationNodeFactory? = null,
) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        FocusBorderNode(interactionSource, shape, color, width, base?.create(interactionSource))

    override fun equals(other: Any?): Boolean =
        other is FocusBorderIndication && other.shape == shape && other.color == color &&
            other.width == width && other.base == base

    override fun hashCode(): Int {
        var h = shape.hashCode()
        h = 31 * h + color.hashCode()
        h = 31 * h + width.hashCode()
        h = 31 * h + base.hashCode()
        return h
    }
}

private class FocusBorderNode(
    private val interactionSource: InteractionSource,
    private val shape: Shape,
    private val color: Color?,
    private val width: Dp,
    inner: DelegatableNode?,
) : androidx.compose.ui.node.DelegatingNode(), DrawModifierNode,
    androidx.compose.ui.node.CompositionLocalConsumerModifierNode {

    private var focused = false

    init {
        if (inner != null) delegate(inner)
    }

    override fun onAttach() {
        coroutineScope.launch {
            var focusCount = 0
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is FocusInteraction.Focus -> focusCount++
                    is FocusInteraction.Unfocus -> focusCount--
                }
                val nowFocused = focusCount > 0
                if (nowFocused != focused) {
                    focused = nowFocused
                    invalidateDraw()
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (!focused) return
        val strokePx = width.toPx()
        val borderColor = color ?: currentValueOf(LocalContentColor)
        // Inset by half the stroke so the whole line is visible inside the bounds.
        val inset = strokePx / 2f
        val innerSize = Size(size.width - strokePx, size.height - strokePx)
        if (innerSize.width <= 0f || innerSize.height <= 0f) return
        val outline = shape.createOutline(innerSize, layoutDirection, this)
        translate(inset, inset) {
            drawOutline(outline, borderColor, style = Stroke(strokePx))
        }
    }
}

/** Global indication for Modifier.clickable/selectable rows: normal ripple plus a focus border. */
@Composable
fun rememberTvFocusIndication(base: IndicationNodeFactory): IndicationNodeFactory =
    remember(base) { FocusBorderIndication(RoundedCornerShape(12.dp), color = null, base = base) }

private fun focusBorder(focused: Boolean, color: Color, fallback: BorderStroke?): BorderStroke? =
    if (focused) BorderStroke(TvFocusBorderWidth, color) else fallback

// ---------------------------------------------------------------------------
// Buttons
// ---------------------------------------------------------------------------

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    androidx.compose.material3.Button(
        onClick = onClick, modifier = modifier, enabled = enabled, shape = shape, colors = colors,
        elevation = elevation, border = focusBorder(focused, colors.contentColor, border),
        contentPadding = contentPadding, interactionSource = source, content = content
    )
}

@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.textShape,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    androidx.compose.material3.TextButton(
        onClick = onClick, modifier = modifier, enabled = enabled, shape = shape, colors = colors,
        elevation = elevation, border = focusBorder(focused, colors.contentColor, border),
        contentPadding = contentPadding, interactionSource = source, content = content
    )
}

@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.outlinedShape,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    androidx.compose.material3.OutlinedButton(
        onClick = onClick, modifier = modifier, enabled = enabled, shape = shape, colors = colors,
        elevation = elevation, border = focusBorder(focused, colors.contentColor, border),
        contentPadding = contentPadding, interactionSource = source, content = content
    )
}

@Composable
fun FilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.filledTonalShape,
    colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(),
    elevation: ButtonElevation? = ButtonDefaults.filledTonalButtonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    androidx.compose.material3.FilledTonalButton(
        onClick = onClick, modifier = modifier, enabled = enabled, shape = shape, colors = colors,
        elevation = elevation, border = focusBorder(focused, colors.contentColor, border),
        contentPadding = contentPadding, interactionSource = source, content = content
    )
}

@Composable
fun IconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val ring = remember(colors.contentColor) { FocusBorderIndication(CircleShape, colors.contentColor) }
    androidx.compose.material3.IconButton(
        onClick = onClick,
        modifier = modifier.indication(source, ring),
        enabled = enabled, colors = colors, interactionSource = source, content = content
    )
}

// ---------------------------------------------------------------------------
// Chips, switches, menu items
// ---------------------------------------------------------------------------

@Composable
fun FilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    shape: Shape = FilterChipDefaults.shape,
    colors: SelectableChipColors = FilterChipDefaults.filterChipColors(),
    elevation: ChipElevation? = FilterChipDefaults.filterChipElevation(),
    border: BorderStroke? = FilterChipDefaults.filterChipBorder(enabled, selected),
    interactionSource: MutableInteractionSource? = null
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val textColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant
    androidx.compose.material3.FilterChip(
        selected = selected, onClick = onClick, label = label, modifier = modifier, enabled = enabled,
        leadingIcon = leadingIcon, trailingIcon = trailingIcon, shape = shape, colors = colors,
        elevation = elevation, border = focusBorder(focused, textColor, border),
        interactionSource = source
    )
}

@Composable
fun Switch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    thumbContent: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    colors: SwitchColors = SwitchDefaults.colors(),
    interactionSource: MutableInteractionSource? = null,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val textColor = MaterialTheme.colorScheme.onSurface
    val ring = remember(textColor) { FocusBorderIndication(CircleShape, textColor) }
    androidx.compose.material3.Switch(
        checked = checked, onCheckedChange = onCheckedChange,
        modifier = modifier.indication(source, ring),
        thumbContent = thumbContent, enabled = enabled, colors = colors, interactionSource = source
    )
}

@Composable
fun DropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    colors: MenuItemColors = MenuDefaults.itemColors(),
    contentPadding: PaddingValues = MenuDefaults.DropdownMenuItemContentPadding,
    interactionSource: MutableInteractionSource? = null,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val ring = remember(colors.textColor) { FocusBorderIndication(RoundedCornerShape(4.dp), colors.textColor) }
    androidx.compose.material3.DropdownMenuItem(
        text = text, onClick = onClick,
        modifier = modifier.indication(source, ring),
        leadingIcon = leadingIcon, trailingIcon = trailingIcon, enabled = enabled, colors = colors,
        contentPadding = contentPadding, interactionSource = source
    )
}

// ---------------------------------------------------------------------------
// Text fields: press Select to edit
// ---------------------------------------------------------------------------

/**
 * Shared "press to edit" behaviour. While not editing, an invisible button sits on top of the
 * field: the remote can focus it (the field shows its focused outline) but the field itself
 * can't take focus, so the keyboard stays closed. Select/OK or a tap switches to editing,
 * focuses the field and opens the keyboard. Moving focus away ends editing.
 */
@Composable
private fun PressToEditField(
    modifier: Modifier,
    enabled: Boolean,
    readOnly: Boolean,
    hasTrailingIcon: Boolean,
    interactionSource: MutableInteractionSource?,
    field: @Composable (fieldModifier: Modifier, source: MutableInteractionSource) -> Unit,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }

    // Read-only / disabled fields never open a keyboard, so leave them exactly as they were.
    if (readOnly || !enabled) {
        field(modifier, source)
        return
    }

    var editing by remember { mutableStateOf(false) }
    var fieldHadFocus by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(editing) {
        if (editing) {
            runCatching { fieldFocus.requestFocus() }
            keyboard?.show()
        }
    }

    Box(modifier = modifier, propagateMinConstraints = true) {
        field(
            Modifier
                .focusRequester(fieldFocus)
                .focusProperties { canFocus = editing }
                .onFocusChanged { state ->
                    if (state.isFocused) {
                        fieldHadFocus = true
                    } else if (fieldHadFocus) {
                        fieldHadFocus = false
                        editing = false
                    }
                },
            source
        )
        if (!editing) {
            Box(
                Modifier
                    .matchParentSize()
                    // Leave a trailing icon button (clear, browse, show password…) usable by touch.
                    .padding(end = if (hasTrailingIcon) 48.dp else 0.dp)
                    // Shares the field's interaction source, so focusing this shows the field's
                    // own focused outline.
                    .clickable(
                        interactionSource = source,
                        indication = null,
                        role = Role.Button,
                        onClick = { editing = true }
                    )
            )
        }
    }
}

/** Focused outline uses the text colour, matching the focus border on buttons. */
private fun TextFieldColors.withTextColoredFocus(): TextFieldColors =
    copy(focusedIndicatorColor = focusedTextColor)

@Composable
fun OutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    prefix: @Composable (() -> Unit)? = null,
    suffix: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    interactionSource: MutableInteractionSource? = null,
    shape: Shape = OutlinedTextFieldDefaults.shape,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors()
) {
    PressToEditField(modifier, enabled, readOnly, trailingIcon != null, interactionSource) { fieldModifier, source ->
        androidx.compose.material3.OutlinedTextField(
            value = value, onValueChange = onValueChange, modifier = fieldModifier, enabled = enabled,
            readOnly = readOnly, textStyle = textStyle, label = label, placeholder = placeholder,
            leadingIcon = leadingIcon, trailingIcon = trailingIcon, prefix = prefix, suffix = suffix,
            supportingText = supportingText, isError = isError, visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions, keyboardActions = keyboardActions, singleLine = singleLine,
            maxLines = maxLines, minLines = minLines, interactionSource = source, shape = shape,
            colors = colors.withTextColoredFocus()
        )
    }
}

@Composable
fun OutlinedTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    prefix: @Composable (() -> Unit)? = null,
    suffix: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    interactionSource: MutableInteractionSource? = null,
    shape: Shape = OutlinedTextFieldDefaults.shape,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors()
) {
    PressToEditField(modifier, enabled, readOnly, trailingIcon != null, interactionSource) { fieldModifier, source ->
        androidx.compose.material3.OutlinedTextField(
            value = value, onValueChange = onValueChange, modifier = fieldModifier, enabled = enabled,
            readOnly = readOnly, textStyle = textStyle, label = label, placeholder = placeholder,
            leadingIcon = leadingIcon, trailingIcon = trailingIcon, prefix = prefix, suffix = suffix,
            supportingText = supportingText, isError = isError, visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions, keyboardActions = keyboardActions, singleLine = singleLine,
            maxLines = maxLines, minLines = minLines, interactionSource = source, shape = shape,
            colors = colors.withTextColoredFocus()
        )
    }
}

@Composable
fun TextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    prefix: @Composable (() -> Unit)? = null,
    suffix: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    interactionSource: MutableInteractionSource? = null,
    shape: Shape = TextFieldDefaults.shape,
    colors: TextFieldColors = TextFieldDefaults.colors()
) {
    PressToEditField(modifier, enabled, readOnly, trailingIcon != null, interactionSource) { fieldModifier, source ->
        androidx.compose.material3.TextField(
            value = value, onValueChange = onValueChange, modifier = fieldModifier, enabled = enabled,
            readOnly = readOnly, textStyle = textStyle, label = label, placeholder = placeholder,
            leadingIcon = leadingIcon, trailingIcon = trailingIcon, prefix = prefix, suffix = suffix,
            supportingText = supportingText, isError = isError, visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions, keyboardActions = keyboardActions, singleLine = singleLine,
            maxLines = maxLines, minLines = minLines, interactionSource = source, shape = shape,
            colors = colors.withTextColoredFocus()
        )
    }
}
