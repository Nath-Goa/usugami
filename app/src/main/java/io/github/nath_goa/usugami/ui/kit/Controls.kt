package io.github.nath_goa.usugami.ui.kit

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SingleChoiceSegmentedButtonRowScope
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.nath_goa.usugami.ui.Motion
import io.github.nath_goa.usugami.ui.SegmentPadding
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

// Everything chosen or slid sits in a well cut into the paper; what is chosen is a white thumb on it.

/**
 * A segmented choice: a well the width of the row with a thumb under the segment [selectedIndex] of
 * [count], which slides to the next one chosen; no thumb when [selectedIndex] is null (a batch with
 * mixed levels). Its [content] is [Segment]s. Each segment stays Material's own radio button, with
 * its 48 dp box round a 40 dp segment; the thumb is inset 4 dp from the well on every side.
 */
@Composable
fun SegmentedTrack(
    selectedIndex: Int?,
    count: Int,
    modifier: Modifier = Modifier,
    content: @Composable SingleChoiceSegmentedButtonRowScope.() -> Unit,
) {
    val colors = Usugami.colors
    val edge = MaterialTheme.colorScheme.outline
    val thumb = remember { TrackThumb(selectedIndex) }
    LaunchedEffect(selectedIndex) {
        val from = thumb.previous
        thumb.previous = selectedIndex
        if (selectedIndex == null) {
            thumb.shown.animateTo(0f, Motion.vanish())
        } else {
            launch { thumb.shown.animateTo(1f, Motion.appear()) }
            // From no thumb (or on the first composition) it is simply there; else it slides.
            if (from == null) {
                thumb.position.snapTo(selectedIndex.toFloat())
            } else {
                thumb.position.animateTo(selectedIndex.toFloat(), Motion.ui(0.001f))
            }
        }
    }
    val track = remember(count, colors.well, colors.wellEdge, colors.thumb, edge) {
        Modifier.trackDrawing(thumb, count, colors.well, colors.wellEdge, colors.thumb, edge)
    }
    SingleChoiceSegmentedButtonRow(
        modifier = modifier.then(track).padding(horizontal = 4.dp),
        space = 0.dp,
        content = content,
    )
}

/** Where a [SegmentedTrack]'s thumb is (in segments) and how much it shows. */
private class TrackThumb(index: Int?) {
    var previous: Int? = index
    val position = Animatable(index?.toFloat() ?: 0f)
    val shown = Animatable(if (index != null) 1f else 0f)
}

/** The well over the whole row and the thumb on it, drawn behind the segments; the thumb is read while drawing. */
private fun Modifier.trackDrawing(thumb: TrackThumb, count: Int, well: Color, wellEdge: Color, fill: Color, edge: Color): Modifier =
    this.drawWithCache {
        val line = 1.dp.toPx()
        val half = line / 2
        val stroke = Stroke(line)
        val track = WellShape.createOutline(size, layoutDirection, this)
        val trackRing = WellShape.concentric(size, layoutDirection, this, half)
        val gap = 4.dp.toPx()
        val segment = if (count > 0) ((size.width - 2 * gap) / count).coerceAtLeast(0f) else 0f
        val height = (size.height - 2 * gap).coerceAtLeast(0f)
        val thumbSize = Size(segment, height)
        val thumbShape = ThumbShape.createOutline(thumbSize, layoutDirection, this)
        val thumbRing = ThumbShape.concentric(thumbSize, layoutDirection, this, half)
        val rtl = layoutDirection == LayoutDirection.Rtl
        onDrawBehind {
            drawOutline(track, well)
            drawOutline(trackRing, wellEdge, style = stroke)
            val alpha = thumb.shown.value
            if (count > 0 && segment > 0f && alpha > 0f) {
                val at = thumb.position.value.coerceIn(0f, (count - 1).toFloat())
                val left = if (rtl) size.width - gap - (at + 1) * segment else gap + at * segment
                translate(left, gap) {
                    drawOutline(thumbShape, fill, alpha = alpha)
                    drawOutline(thumbRing, edge, alpha = alpha, style = stroke)
                }
            }
        }
    }

/**
 * A segment of a [SegmentedTrack]: Material's segmented button with nothing of its own drawn (no
 * container, no border, no check icon), its [label] (a SegmentLabel) in `onSurface` when [selected]
 * and `onSurfaceVariant` otherwise, tinted while pressed in the thumb's shape.
 */
@Composable
fun SingleChoiceSegmentedButtonRowScope.Segment(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: @Composable () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val scheme = MaterialTheme.colorScheme
    val disabled = scheme.onSurface.copy(alpha = 0.38f)
    SegmentedButton(
        selected = selected,
        onClick = onClick,
        shape = ThumbShape,
        modifier = modifier.indication(source, PressIndication.shaped(ThumbShape, insetVertical = 4.dp)),
        enabled = enabled,
        colors = SegmentedButtonDefaults.colors(
            activeContainerColor = Color.Transparent,
            activeContentColor = scheme.onSurface,
            activeBorderColor = Color.Transparent,
            inactiveContainerColor = Color.Transparent,
            inactiveContentColor = scheme.onSurfaceVariant,
            inactiveBorderColor = Color.Transparent,
            disabledActiveContainerColor = Color.Transparent,
            disabledActiveContentColor = disabled,
            disabledActiveBorderColor = Color.Transparent,
            disabledInactiveContainerColor = Color.Transparent,
            disabledInactiveContentColor = disabled,
            disabledInactiveBorderColor = Color.Transparent,
        ),
        border = SegmentedButtonDefaults.borderStroke(Color.Transparent, 0.dp),
        contentPadding = SegmentPadding,
        interactionSource = source,
        icon = {},
        label = label,
    )
}

/**
 * A whole row that is a switch: its [title] (and [detail]) and Material's switch, which takes no
 * touches of its own. A screen reader hears the row as the switch, its title inside it.
 */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    enabled: Boolean = true,
    padding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
) {
    Row(
        modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .heightIn(min = 56.dp)
            .padding(padding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * A chip that opens a menu of values: a `paper` pill with a hairline edge, its [label] and a drop-down
 * arrow. 40 dp tall in a 48 dp box. The box is the button, with the caller's [modifier] on it (one
 * node, as Material's chip is); the pill shows the press, its tint and focus outline inside its shape.
 */
@Composable
fun DropdownChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = Usugami.colors
    val ink = MaterialTheme.colorScheme.onSurface.let { if (enabled) it else it.copy(alpha = 0.38f) }
    val source = remember { MutableInteractionSource() }
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides ink) {
            Row(
                Modifier
                    .heightIn(min = 40.dp)
                    .clipped(CircleShape)
                    .indication(source, PressIndication.shaped(CircleShape))
                    .background(colors.paper)
                    .border(1.dp, colors.hairlineStrong, CircleShape)
                    .padding(start = 16.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.animateContentSize(Motion.ui(IntSize.VisibilityThreshold)),
                )
                Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/**
 * A chip that is on or off ([selected]): a `paper` pill with a hairline edge, or `lilac` with a
 * `primary` edge when on. 40 dp tall in a 48 dp box; heard as a checkbox, as Material's filter chip is.
 * The box is the choice, with the caller's [modifier] on it; the pill shows the press.
 */
@Composable
fun ChoiceChip(selected: Boolean, onClick: () -> Unit, label: String, modifier: Modifier = Modifier) {
    val colors = Usugami.colors
    val scheme = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    Box(
        modifier
            .heightIn(min = 48.dp)
            .selectable(selected = selected, interactionSource = source, indication = null, role = Role.Checkbox, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .heightIn(min = 40.dp)
                .clipped(CircleShape)
                .indication(source, PressIndication.shaped(CircleShape))
                .background(if (selected) colors.lilac else colors.paper)
                .border(1.dp, if (selected) scheme.primary else colors.hairlineStrong, CircleShape)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) colors.onLilac else scheme.onSurface)
        }
    }
}

/**
 * A slider: a 6 dp track in the stronger hairline (translucent, so it shows on the ground and on a sheet in both themes), filled in `primary` from its start to the thumb, or with
 * [centered] from the middle of the range to the thumb either way (the middle shows no fill); the
 * thumb a white disc ringed in `primary` that grows while held or focused. Material's slider underneath, 48 dp
 * tall; [modifier] (where call sites set the slider's semantics) is passed on untouched.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsugamiSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null,
    centered: Boolean = false,
) {
    val source = remember { MutableInteractionSource() }
    val scheme = MaterialTheme.colorScheme
    val colors = Usugami.colors
    val pressed by source.collectIsPressedAsState()
    val dragged by source.collectIsDraggedAsState()
    // A keyboard's or D-pad's focus shows as a held thumb (a touch does not focus a slider).
    val focused by source.collectIsFocusedAsState()
    val diameter = animateDpAsState(if (pressed || dragged || focused) 26.dp else 22.dp, Motion.press(), label = "slider thumb")
    val thumbFill = if (enabled) colors.thumb else colors.thumb.copy(alpha = 0.38f)
    val ring = if (enabled) scheme.primary else scheme.primary.copy(alpha = 0.38f)
    val filled = if (enabled) scheme.primary else scheme.onSurface.copy(alpha = 0.38f)
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        onValueChangeFinished = onValueChangeFinished,
        colors = SliderDefaults.colors(
            thumbColor = colors.thumb,
            activeTrackColor = scheme.primary,
            inactiveTrackColor = colors.hairlineStrong,
        ),
        interactionSource = source,
        steps = 0,
        thumb = { SliderThumb(diameter, thumbFill, ring) },
        track = { state -> SliderTrack(state, centered, colors.hairlineStrong, filled) },
        valueRange = valueRange,
    )
}

/** The slider's thumb: a disc of [diameter] in its 26 × 44 dp box, [fill] ringed 2 dp in [ring]. */
@Composable
private fun SliderThumb(diameter: State<Dp>, fill: Color, ring: Color) {
    Box(
        Modifier
            .size(26.dp, 44.dp)
            .drawWithCache {
                val width = 2.dp.toPx()
                val stroke = Stroke(width)
                // The ring for the disc as last drawn, made again only when the disc grows or shrinks.
                var ringFor = -1f
                var edge: Outline? = null
                onDrawBehind {
                    val across = diameter.value.toPx()
                    if (across != ringFor || edge == null) {
                        edge = CircleShape.concentric(Size(across, across), layoutDirection, this, width / 2)
                        ringFor = across
                    }
                    drawCircle(fill, across / 2)
                    translate((size.width - across) / 2, (size.height - across) / 2) { drawOutline(edge!!, ring, style = stroke) }
                }
            },
    )
}

/** The slider's track: the track, round at its ends, and the filled part; no gap, no dots. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SliderTrack(state: SliderState, centered: Boolean, track: Color, filled: Color) {
    Spacer(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .drawWithCache {
                val ends = CornerRadius(size.height / 2)
                val rtl = layoutDirection == LayoutDirection.Rtl
                onDrawBehind {
                    drawRoundRect(track, cornerRadius = ends)
                    // Where the thumb's middle is: Material places it at this fraction of the track.
                    val fraction = state.coercedValueAsFraction
                    val thumb = size.width * if (rtl) 1 - fraction else fraction
                    if (centered) {
                        val middle = size.width / 2
                        if (thumb != middle) drawRect(filled, Offset(min(middle, thumb), 0f), Size(abs(thumb - middle), size.height))
                    } else {
                        val from = if (rtl) thumb else 0f
                        val to = if (rtl) size.width else thumb
                        if (to > from) drawRoundRect(filled, Offset(from, 0f), Size(to - from, size.height), ends)
                    }
                }
            },
    )
}

/** An indeterminate progress bar: `primary` on a well, round ends, 4 dp unless [modifier] sets a height. */
@Composable
fun LinearProgress(modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        modifier = modifier,
        color = MaterialTheme.colorScheme.primary,
        trackColor = Usugami.colors.well,
        strokeCap = StrokeCap.Round,
        gapSize = 0.dp,
    )
}

/** A progress bar at [progress]: `primary` on a well, round ends, no stop dot, 4 dp unless [modifier] sets a height. */
@Composable
fun LinearProgress(progress: () -> Float, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = progress,
        modifier = modifier,
        color = MaterialTheme.colorScheme.primary,
        trackColor = Usugami.colors.well,
        strokeCap = StrokeCap.Round,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}

/**
 * A busy ring, in [color] or else `primary`. On a page or a photo pass `Usugami.colors.paperInk`:
 * the dark theme's lilac cannot be seen on white.
 */
@Composable
fun CircularProgress(modifier: Modifier = Modifier, strokeWidth: Dp = 2.5.dp, color: Color = Color.Unspecified) {
    CircularProgressIndicator(
        modifier = modifier,
        color = color.takeOrElse { MaterialTheme.colorScheme.primary },
        strokeWidth = strokeWidth,
        trackColor = Color.Transparent,
        strokeCap = StrokeCap.Round,
        gapSize = 0.dp,
    )
}
