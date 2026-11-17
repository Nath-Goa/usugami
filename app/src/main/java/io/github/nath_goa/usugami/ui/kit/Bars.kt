package io.github.nath_goa.usugami.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max

/** When a top bar shows the hairline along its lower edge. */
enum class BarLine {
    /** As content scrolls under the bar: fully once it has moved 12 dp. */
    OnScroll,

    /** All the time (the reader). */
    Always,

    /** Never. */
    Never,
}

/**
 * The top bar: solid ground, so at rest it cannot be told from the screen and what scrolls beneath it
 * is hidden, never seen through the title. 60 dp tall below the status bar; with [subtitle] (a title
 * over a second line), tall enough for both lines at any font size. A hairline along its lower edge
 * shows as [line] says. The [title] is a BarTitle and is never clickable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsugamiTopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
    line: BarLine = BarLine.OnScroll,
    subtitle: Boolean = false,
) {
    val ground = Usugami.colors.ground
    val hairline = Usugami.colors.hairline
    val onSurface = MaterialTheme.colorScheme.onSurface
    val height = if (subtitle) {
        val typography = MaterialTheme.typography
        // Follows the font scale and Hindi's taller lines, so a title over a second line is not cut.
        val lines = with(LocalDensity.current) { typography.titleLarge.lineHeight.toDp() + typography.labelMedium.lineHeight.toDp() }
        max(60.dp, lines + 12.dp)
    } else {
        60.dp
    }
    TopAppBar(
        title = title,
        // After the bar's own content: Material paints its container over whatever is drawn behind.
        modifier = modifier.drawWithContent {
            drawContent()
            val shown = when (line) {
                // Read while drawing: it follows the scroll without recomposing.
                BarLine.OnScroll -> if (scrollBehavior == null) 0f else (-scrollBehavior.state.contentOffset / 12.dp.toPx()).coerceIn(0f, 1f)
                BarLine.Always -> 1f
                BarLine.Never -> 0f
            }
            if (shown > 0f) {
                val width = 1.dp.toPx()
                val y = size.height - width / 2
                drawLine(hairline, Offset(0f, y), Offset(size.width, y), strokeWidth = width, alpha = shown)
            }
        },
        navigationIcon = navigationIcon,
        actions = actions,
        expandedHeight = height,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = ground,
            scrolledContainerColor = ground,
            navigationIconContentColor = onSurface,
            titleContentColor = onSurface,
            actionIconContentColor = onSurface,
        ),
        scrollBehavior = scrollBehavior,
    )
}

/**
 * The bottom action bar, a top sheet: solid `actionBar`, round at its two top corners, a hairline
 * along its top edge and round those corners only. It clears the navigation bar
 * ([clearNavigationBar]), and holds its [content] at most 480 dp wide in the middle. It goes in a
 * Scaffold's bottomBar; it has no semantics, takes no clicks and never animates out. Where it comes
 * in (the editor), its caller paints the way in; it is laid out in its place from the first frame.
 */
@Composable
fun ActionBar(modifier: Modifier = Modifier, clearNavigationBar: Boolean = true, content: @Composable () -> Unit) {
    val fill = Usugami.colors.actionBar
    val line = Usugami.colors.hairline
    val sheet = remember(fill, line) {
        Modifier.drawWithCache {
            val outline = ActionBarShape.createOutline(size, layoutDirection, this)
            val width = 1.dp.toPx()
            val half = width / 2
            val r = ActionBarCorner.toPx().coerceAtMost(minOf(size.width, size.height) / 2)
            val w = size.width
            val edge = Path().apply {
                moveTo(half, r)
                arcTo(Rect(half, half, 2 * r - half, 2 * r - half), 180f, 90f, false)
                lineTo(w - r, half)
                arcTo(Rect(w - 2 * r + half, half, w - half, 2 * r - half), 270f, 90f, false)
            }
            val stroke = Stroke(width)
            onDrawBehind {
                drawOutline(outline, fill)
                drawPath(edge, line, style = stroke)
            }
        }
    }
    Box(
        modifier
            .fillMaxWidth()
            .then(sheet)
            .then(if (clearNavigationBar) Modifier.windowInsetsPadding(WindowInsets.navigationBars) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.widthIn(max = 480.dp).fillMaxWidth()) { content() }
    }
}

/** The top corners of [ActionBarShape]. */
private val ActionBarCorner = 20.dp

/**
 * The reader's page pill, ONE node: [text] ("3 of 12") in tabular figures on ink purple over the
 * pages, heard as [spoken]; a button that opens Go to page ([onClickLabel]). The caller's [modifier]
 * (alignment, the navigation inset, the room below) stays outside the pill.
 */
@Composable
fun PagePill(text: String, spoken: String, onClickLabel: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    Text(
        text,
        style = Usugami.type.tabular(MaterialTheme.typography.labelLarge),
        color = Usugami.colors.onPill,
        modifier = modifier
            // The row tint is ink on ink, invisible here: the pill presses as a button does.
            .indication(source, PressIndication.button(CircleShape, Usugami.colors.onPill))
            .clip(CircleShape)
            .background(Usugami.colors.pill)
            .border(1.dp, Usugami.colors.pillEdge, CircleShape)
            .clickable(interactionSource = source, indication = null, onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { contentDescription = spoken },
    )
}
