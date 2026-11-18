package io.github.nath_goa.usugami.ui.kit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

// Pills act. Each button wraps its Material component, which draws a ripple of its own and never reads
// the theme's indication: the wrapper gives that component ONE interaction source and adds the press
// feedback over it with Modifier.indication, as the outermost of its own modifiers (the caller's
// padding or weight stays outside the feedback). The semantics are Material's own. The quiet button
// is the exception: it is built here with Material's metrics, so that its 48 dp touch box, which
// carries the caller's modifier, is also the node that is clicked (see QuietButton).

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
 * (deleting something) the text is `error`, never a red fill. Disabled, its text is faint `onSurface`,
 * as every disabled button's is.
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
            disabledContentColor = scheme.onSurface.copy(alpha = 0.38f),
        ),
        elevation = null,
        border = BorderStroke(1.dp, if (enabled) colors.hairlineStrong else colors.hairline),
        contentPadding = contentPadding,
        interactionSource = source,
        content = content,
    )
}

/**
 * A quiet button: text alone, in [contentColor] or else `primary` (`error` with [danger]); disabled,
 * faint `onSurface` whatever its colour. It has the metrics of Material's text button: 40 dp tall in a
 * 48 dp box. The press tint, scale and focus outline are the 40 dp button's; the touch target, the
 * click and the caller's [modifier] (with any semantics it sets) are the box's, one node, as
 * Material's own button is.
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
    val ink = if (enabled) contentColor.takeOrElse { if (danger) scheme.error else scheme.primary } else scheme.onSurface.copy(alpha = 0.38f)
    // The click is on the box, with the caller's modifier: a description a call site gives is on the
    // node that is clicked. A width or a weight the caller gives the box reaches the button too (its
    // minimum constraints are passed on).
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
        propagateMinConstraints = true,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides ink,
            LocalTextStyle provides LocalTextStyle.current.merge(MaterialTheme.typography.labelLarge),
        ) {
            Row(
                Modifier
                    .indication(source, PressIndication.button(CircleShape, ink))
                    .defaultMinSize(ButtonDefaults.MinWidth, ButtonDefaults.MinHeight)
                    .padding(contentPadding),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
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

/**
 * An icon button on paper: a 40 dp circle of `paper` as it shows on the ground, solid, with a hairline
 * edge in the 48 dp box, its glyph `primary`; on a page or a photo it is the same solid disc. Pressed,
 * the disc lies over the paper (under it, it would hardly show) and both shrink.
 */
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
    // Opaque: over a white page the dark theme's translucent paper would leave the lilac glyph on white.
    val disc = colors.paper.compositeOver(colors.ground)
    OutlinedIconButton(
        onClick = onClick,
        modifier = modifier.indication(source, PressIndication.glyphOverFill(scheme.primary)),
        enabled = enabled,
        colors = IconButtonDefaults.outlinedIconButtonColors(
            containerColor = disc,
            contentColor = scheme.primary,
            disabledContainerColor = disc,
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
