package io.github.nath_goa.usugami.ui.kit

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.atan

// The fanned stack of sheets is the app's own motif, and appears only where there is a document: a
// file's pages lie under its first one.

/** How many under-sheets a file of [pages] pages shows: none for one page, one for two, two for more. */
fun stackCount(pages: Int): Int = when {
    pages <= 1 -> 0
    pages == 2 -> 1
    else -> 2
}

/**
 * Draws [count] under-sheets BEHIND what it is set on, each its size with corners [radius]: the first
 * in `fan1`, the second in `fan2`, each edged with a hairline. Each is turned about the bottom-start
 * corner (anticlockwise; clockwise about the bottom-end corner in right-to-left) so that its far top
 * corner rises [rise1] or [rise2]. [fan] in 0..1 scales the turn and the alpha of each, and is read
 * while drawing. It adds no layout, no semantics and no clip, and draws outside the bounds: it needs
 * 18 dp of room above and some at the start (height × [rise2] / width), with nothing between that
 * clips (an AnimatedVisibility that expands or shrinks, or any layer while it fades); a sheet's own
 * clip is harmless, as the stack goes in the sheet's modifier, before it.
 */
@Suppress("ComposableModifierFactory") // It reads Usugami.colors (Sheet.kt, the note above dashedOutline).
@Composable
fun Modifier.stack(
    count: Int,
    radius: Dp = 16.dp,
    rise1: Dp = 8.dp,
    rise2: Dp = 16.dp,
    fan: (index: Int) -> Float = { 1f },
): Modifier {
    val colors = Usugami.colors
    val fan1 = colors.fan1
    val fan2 = colors.fan2
    val line = colors.hairline
    val turn = rememberUpdatedState(fan)
    val sheets = count.coerceIn(0, 2)
    val stack = remember(sheets, radius, rise1, rise2, fan1, fan2, line) {
        Modifier.drawWithCache {
            val width = 1.dp.toPx()
            val half = width / 2
            val stroke = Stroke(width)
            val corner = CornerRadius(radius.toPx())
            val edgeCorner = CornerRadius((radius.toPx() - half).coerceAtLeast(0f))
            val edgeSize = Size(size.width - width, size.height - width)
            val rtl = layoutDirection == LayoutDirection.Rtl
            val pivot = Offset(if (rtl) size.width else 0f, size.height)
            // Anticlockwise is a negative turn on the screen; mirrored, clockwise.
            val direction = if (rtl) 1f else -1f
            val angle0 = if (size.width > 0f) Math.toDegrees(atan(rise1.toPx() / size.width).toDouble()).toFloat() else 0f
            val angle1 = if (size.width > 0f) Math.toDegrees(atan(rise2.toPx() / size.width).toDouble()).toFloat() else 0f
            onDrawBehind {
                // The farther one first.
                for (i in sheets - 1 downTo 0) {
                    val part = turn.value(i).coerceIn(0f, 1f)
                    if (part <= 0f) continue
                    rotate(direction * part * (if (i == 0) angle0 else angle1), pivot) {
                        drawRoundRect(if (i == 0) fan1 else fan2, cornerRadius = corner, alpha = part)
                        drawRoundRect(line, Offset(half, half), edgeSize, edgeCorner, style = stroke, alpha = part)
                    }
                }
            }
        }
    }
    return if (sheets > 0) this.then(stack) else this
}

/**
 * The seal on something finished: a `primary` disc of [size] with an `onPrimary` check, of which
 * [check] (0..1, read while drawing) is drawn. It has no semantics; its scale and alpha are the
 * caller's graphicsLayer.
 */
@Composable
fun Seal(modifier: Modifier = Modifier, size: Dp = 44.dp, check: () -> Float = { 1f }) {
    val disc = MaterialTheme.colorScheme.primary
    val mark = MaterialTheme.colorScheme.onPrimary
    val drawn = rememberUpdatedState(check)
    // 3 dp at 44.
    val markWidth = size / 14.5f
    Spacer(
        modifier
            .size(size)
            .drawWithCache {
                val w = this.size.width
                val h = this.size.height
                val path = Path().apply {
                    moveTo(0.29f * w, 0.52f * h)
                    lineTo(0.44f * w, 0.67f * h)
                    lineTo(0.72f * w, 0.36f * h)
                }
                val measure = PathMeasure().apply { setPath(path, false) }
                val length = measure.length
                val segment = Path()
                val stroke = Stroke(markWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                onDrawBehind {
                    drawCircle(disc)
                    val part = drawn.value().coerceIn(0f, 1f)
                    if (part >= 1f) {
                        drawPath(path, mark, style = stroke)
                    } else if (part > 0f) {
                        segment.reset()
                        measure.getSegment(0f, length * part, segment, true)
                        drawPath(segment, mark, style = stroke)
                    }
                }
            },
    )
}
