package io.github.nath_goa.usugami.ui.kit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Pills act. Each button wraps its Material component, which draws a ripple of its own and never reads
// the theme's indication: the wrapper gives that component ONE interaction source and adds the press
// feedback over it with Modifier.indication, as the outermost of its own modifiers (the caller's
// padding or weight stays outside the feedback). The semantics are Material's own.

/** A text button's padding. */
val ButtonPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp)

/** A text button's padding when it starts with a [ButtonGlyph]. */
val ButtonWithGlyphPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)

/**
 * A quiet button's padding: Material's text-button padding, which today's rows of text buttons
 * (dialogs, wrapped rows, "See all") were fitted with.
 */
val QuietPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)

/** The minimum height of a primary, tonal or paper button. */
private val ButtonHeight = 52.dp

/**
 * The button that finishes the job, the one strong colour on a screen (one per screen): `primary`,
 * 52 dp tall at least. Disabled, it is a well with faint text.
 */
@Composable
fun PrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val scheme = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = ButtonHeight)
            .indication(source, PressIndication.button(CircleShape, scheme.onPrimary)),
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = scheme.primary,
            contentColor = scheme.onPrimary,
            disabledContainerColor = Usugami.colors.well,
            disabledContentColor = scheme.onSurface.copy(alpha = 0.38f),
        ),
        elevation = null,
        contentPadding = contentPadding,
        interactionSource = source,
        content = content,
    )
}

/** A button second to the primary one: tonal purple, 52 dp tall at least. */
@Composable
fun TonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val scheme = MaterialTheme.colorScheme
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = ButtonHeight)
            .indication(source, PressIndication.button(CircleShape, scheme.onSecondaryContainer)),
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = scheme.secondaryContainer,
            contentColor = scheme.onSecondaryContainer,
            disabledContainerColor = Usugami.colors.well,
            disabledContentColor = scheme.onSurface.copy(alpha = 0.38f),
        ),
        elevation = null,
        contentPadding = contentPadding,
        interactionSource = source,
        content = content,
    )
}

/**
 * A paper button: `paper` with a hairline edge and `primary` text, 52 dp tall at least; with [danger]
 * (deleting something) the text is `error`, never a red fill.
 */
@Composable
fun PaperButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonPadding,
    interactionSource: MutableInteractionSource? = null,
    danger: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val scheme = MaterialTheme.colorScheme
    val colors = Usugami.colors
    val ink = if (danger) scheme.error else scheme.primary
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = ButtonHeight)
            .indication(source, PressIndication.button(CircleShape, ink)),
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = colors.paper,
            contentColor = ink,
            disabledContainerColor = colors.paper,
            disabledContentColor = ink.copy(alpha = 0.38f),
        ),
        elevation = null,
        border = BorderStroke(1.dp, if (enabled) colors.hairlineStrong else colors.hairline),
        contentPadding = contentPadding,
        interactionSource = source,
        content = content,
    )
}

/**
 * A quiet button: text alone, in [contentColor] or else `primary` (`error` with [danger]). It is
 * Material's 40 dp text button in a 48 dp box: the press tint, scale and focus outline are the 40 dp
 * button's, the touch target is the box.
 */
@Composable
fun QuietButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = QuietPadding,
    interactionSource: MutableInteractionSource? = null,
    danger: Boolean = false,
    contentColor: Color = Color.Unspecified,
    content: @Composable RowScope.() -> Unit,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val scheme = MaterialTheme.colorScheme
    val ink = contentColor.takeOrElse { if (danger) scheme.error else scheme.primary }
    // The box keeps the 48 dp touch target; inside it the button is its visible 40 dp. A width or a
    // weight the caller gives the box reaches the button too (its minimum constraints are passed on).
    Box(
        modifier.minimumInteractiveComponentSize(),
        contentAlignment = Alignment.Center,
        propagateMinConstraints = true,
    ) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            TextButton(
                onClick = onClick,
                modifier = Modifier.indication(source, PressIndication.button(CircleShape, ink)),
                enabled = enabled,
                shape = CircleShape,
                colors = ButtonDefaults.textButtonColors(
                    containerColor = Color.Transparent,
                    contentColor = ink,
                    disabledContainerColor = Color.Transparent,
                    disabledContentColor = ink.copy(alpha = 0.38f),
                ),
                elevation = null,
                contentPadding = contentPadding,
                interactionSource = source,
                content = content,
            )
        }
    }
}

/** A glyph at the start of a button's label: 20 dp, then 8 dp. Use it with [ButtonWithGlyphPadding]. */
@Composable
fun ButtonGlyph(icon: ImageVector) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
    Spacer(Modifier.width(8.dp))
}

/**
 * An icon button: Material's, 48 dp, its glyph in the content colour (38 % disabled). Pressed, a
 * 40 dp disc shows behind the glyph and the glyph shrinks a little.
 */
@Composable
fun GlyphButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    IconButton(
        onClick = onClick,
        modifier = modifier.indication(source, PressIndication.glyph(LocalContentColor.current)),
        enabled = enabled,
        interactionSource = source,
        content = content,
    )
}

/** An icon button on paper: a 40 dp `paper` circle with a hairline edge in the 48 dp box, its glyph `primary`. */
@Composable
fun PaperGlyphButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val scheme = MaterialTheme.colorScheme
    val colors = Usugami.colors
    OutlinedIconButton(
        onClick = onClick,
        modifier = modifier.indication(source, PressIndication.glyph(scheme.primary)),
        enabled = enabled,
        colors = IconButtonDefaults.outlinedIconButtonColors(
            containerColor = colors.paper,
            contentColor = scheme.primary,
            disabledContainerColor = colors.paper,
            disabledContentColor = scheme.onSurface.copy(alpha = 0.38f),
        ),
        border = BorderStroke(1.dp, colors.hairlineStrong),
        interactionSource = source,
        content = content,
    )
}

/** An item of a menu: Material's, its text `onSurface` and its icons `onSurfaceVariant`, tinted while pressed. */
@Composable
fun MenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val scheme = MaterialTheme.colorScheme
    DropdownMenuItem(
        text = text,
        onClick = onClick,
        modifier = modifier.indication(source, PressIndication.Row),
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        enabled = enabled,
        colors = MenuDefaults.itemColors(
            textColor = scheme.onSurface,
            leadingIconColor = scheme.onSurfaceVariant,
            trailingIconColor = scheme.onSurfaceVariant,
        ),
        interactionSource = source,
    )
}
