package io.github.nath_goa.usugami.ui.kit

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.translate
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidatePlacement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.nath_goa.usugami.ui.Motion
import kotlin.math.min

// Sheets of vellum on the ground, each holding one group of related controls divided by hairlines;
// rows inside them; wells cut into the paper; and real paper, the user's own pages. Depth comes from
// what lies on what, never from shadows: a sheet never sits on a sheet.

/**
 * A sheet: solid [color], edged with a hairline [border], no shadow. Its content is in `onSurface`
 * (on a lilac sheet, `onLilac`). With [onClick] the whole sheet is one clickable, with no role (as an
 * outlined card's), tinted while pressed; disabled, it shows at half strength. Without [onClick] it is
 * read as a [group] (one node, as Material's card was), or with `group = false` it is no node at all:
 * for a sheet where 2.0.1 had no card, so the tree a device test was written against stays as it was.
 */
@Composable
fun Sheet(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    color: Color = Usugami.colors.sheet,
    border: BorderStroke? = BorderStroke(1.dp, Usugami.colors.hairline),
    shape: Shape = SheetShape,
    interactionSource: MutableInteractionSource? = null,
    group: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = Usugami.colors
    // A lilac sheet's caller gives its content `onLilac`, inside or around the sheet: either way works.
    val contentColor = if (color == colors.lilac) colors.onLilac else MaterialTheme.colorScheme.onSurface
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Column(
            modifier
                .then(if (onClick != null && !enabled) Modifier.graphicsLayer { alpha = 0.5f } else Modifier)
                .background(color, shape)
                .then(if (border != null) Modifier.border(border, shape) else Modifier)
                .clipped(shape)
                .then(
                    when {
                        // The focus outline runs along the shape, inside it, where the clip cuts none of it.
                        onClick != null -> Modifier.clickable(interactionSource, PressIndication.shaped(shape), enabled, onClick = onClick)
                        group -> Modifier.semantics { isTraversalGroup = true }
                        else -> Modifier
                    },
                ),
            content = content,
        )
    }
}

/**
 * A row of a sheet: an optional [icon] (24 dp, then 16), the [content] column taking the rest, and
 * [trailing]. With [onClick] the row is one clickable whose texts a screen reader hears as one node
 * ([role] only where today's control is a button); disabled, its content shows at 38 %.
 */
@Composable
fun SheetRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    minHeight: Dp = 56.dp,
    padding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    role: Role? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = role, onClick = onClick) else Modifier)
            // After the clickable: the press tint keeps its strength, texts and icon keep their colours.
            .then(if (onClick != null && !enabled) Modifier.graphicsLayer { alpha = 0.38f } else Modifier)
            .heightIn(min = minHeight)
            .padding(padding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(Space.icon))
            Spacer(Modifier.width(Space.iconGap))
        }
        Column(Modifier.weight(1f), content = content)
        if (trailing != null) trailing()
    }
}

/** A 1 dp hairline across a sheet, starting [inset] from the start: between unlike blocks of one sheet. */
@Composable
fun SheetDivider(inset: Dp = 0.dp) {
    Spacer(
        Modifier
            .padding(start = inset)
            .fillMaxWidth()
            .height(1.dp)
            .background(Usugami.colors.hairline),
    )
}

/**
 * Its children one under another, as a column, with a hairline between each two, starting [inset]
 * from the start: for lists of like rows inside a [Sheet]. The lines are drawn over the top of each
 * row after the first; they add no nodes, no height and no semantics.
 */
@Suppress("ModifierParameter") // The order is the design's: the inset is what callers set most.
@Composable
fun DividedColumn(inset: Dp = Space.dividerInset, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val line = Usugami.colors.hairline
    val rows = remember { DividedRows() }
    val measurePolicy = remember(rows) { dividedMeasurePolicy(rows) }
    Layout(
        content = content,
        modifier = modifier.drawWithContent {
            drawContent()
            rows.follow()
            val tops = rows.tops
            val heights = rows.heights
            val width = 1.dp.toPx()
            val start = inset.toPx()
            val rtl = layoutDirection == LayoutDirection.Rtl
            var above = false
            for (i in tops.indices) {
                // A row that takes no room has no line, and the first row that takes some has none above it.
                if (heights[i] <= 0) continue
                if (above) {
                    val y = tops[i] + width / 2
                    drawLine(
                        line,
                        Offset(if (rtl) 0f else start, y),
                        Offset(if (rtl) size.width - start else size.width, y),
                        strokeWidth = width,
                    )
                }
                above = true
            }
        },
        measurePolicy = measurePolicy,
    )
}

/** Where a [DividedColumn]'s rows lie, found in the measure pass and drawn from. */
private class DividedRows {
    var tops = IntArray(0)
    var heights = IntArray(0)
    private var moves = 0
    private val version = mutableIntStateOf(0)

    /** The rows moved (said in the measure pass, which only writes, so it is not measured again for it). */
    fun moved() {
        moves++
        version.intValue = moves
    }

    /** Read while drawing: rows that move while the column keeps its size (layers of their own) are drawn again. */
    fun follow() = version.intValue
}

private fun dividedMeasurePolicy(rows: DividedRows) = MeasurePolicy { measurables, constraints ->
    val count = measurables.size
    var changed = false
    if (rows.tops.size != count) {
        rows.tops = IntArray(count)
        rows.heights = IntArray(count)
        changed = true
    }
    val placeables = ArrayList<Placeable>(count)
    var used = 0
    var width = 0
    for (i in 0 until count) {
        val room = if (constraints.hasBoundedHeight) (constraints.maxHeight - used).coerceAtLeast(0) else Constraints.Infinity
        val placeable = measurables[i].measure(constraints.copy(minWidth = 0, minHeight = 0, maxHeight = room))
        if (rows.tops[i] != used || rows.heights[i] != placeable.height) {
            rows.tops[i] = used
            rows.heights[i] = placeable.height
            changed = true
        }
        placeables += placeable
        used += placeable.height
        width = maxOf(width, placeable.width)
    }
    if (changed) rows.moved()
    layout(width.coerceIn(constraints.minWidth, constraints.maxWidth), used.coerceIn(constraints.minHeight, constraints.maxHeight)) {
        var y = 0
        for (placeable in placeables) {
            placeable.placeRelative(0, y)
            y += placeable.height
        }
    }
}

/**
 * One row ([index] of [count]) of ONE sheet laid out as the items of a lazy list, spaced 0: filled
 * with [color] (the sheet's colour when unspecified), its top corners round on the first row and its
 * bottom corners on the last, a hairline down both sides, over the first and under the last, a
 * divider from [dividerInset] over every row but the first, and its content clipped to that shape.
 */
@Suppress("ComposableModifierFactory") // It reads Usugami.colors (see the note above dashedOutline).
@Composable
fun Modifier.sheetSlice(index: Int, count: Int, dividerInset: Dp = 0.dp, color: Color = Color.Unspecified): Modifier {
    val colors = Usugami.colors
    val fill = color.takeOrElse { colors.sheet }
    val line = colors.hairline
    val first = index == 0
    val last = index == count - 1
    val slice = remember(first, last, dividerInset, fill, line) {
        val corner = SliceCorner
        val shape = RoundedCornerShape(
            topStart = if (first) corner else 0.dp,
            topEnd = if (first) corner else 0.dp,
            bottomEnd = if (last) corner else 0.dp,
            bottomStart = if (last) corner else 0.dp,
        )
        Modifier
            .drawWithCache {
                val outline = shape.createOutline(size, layoutDirection, this)
                val width = 1.dp.toPx()
                val half = width / 2
                val w = size.width
                val h = size.height
                val r = corner.toPx().coerceAtMost(minOf(w, h) / 2)
                val edge = Path()
                when {
                    first && last -> edge.addRoundRect(RoundRect(half, half, w - half, h - half, CornerRadius((r - half).coerceAtLeast(0f))))
                    first -> {
                        edge.moveTo(half, h)
                        edge.lineTo(half, r)
                        edge.arcTo(Rect(half, half, 2 * r - half, 2 * r - half), 180f, 90f, false)
                        edge.lineTo(w - r, half)
                        edge.arcTo(Rect(w - 2 * r + half, half, w - half, 2 * r - half), 270f, 90f, false)
                        edge.lineTo(w - half, h)
                    }
                    last -> {
                        edge.moveTo(half, 0f)
                        edge.lineTo(half, h - r)
                        edge.arcTo(Rect(half, h - 2 * r + half, 2 * r - half, h - half), 180f, -90f, false)
                        edge.lineTo(w - r, h - half)
                        edge.arcTo(Rect(w - 2 * r + half, h - 2 * r + half, w - half, h - half), 90f, -90f, false)
                        edge.lineTo(w - half, 0f)
                    }
                    else -> {
                        edge.moveTo(half, 0f)
                        edge.lineTo(half, h)
                        edge.moveTo(w - half, 0f)
                        edge.lineTo(w - half, h)
                    }
                }
                val stroke = Stroke(width)
                // The divider stops at the side hairlines, so the two never darken each other.
                val inset = dividerInset.toPx().coerceAtLeast(width)
                val rtl = layoutDirection == LayoutDirection.Rtl
                val from = if (rtl) width else inset
                val to = if (rtl) w - inset else w - width
                onDrawWithContent {
                    drawOutline(outline, fill)
                    drawContent()
                    drawPath(edge, line, style = stroke)
                    if (!first && to > from) drawLine(line, Offset(from, half), Offset(to, half), strokeWidth = width)
                }
            }
            .clipped(shape)
    }
    return this.then(slice)
}

/** The corners of a sheet made of slices: a [SheetShape]'s. */
private val SliceCorner = 16.dp

/**
 * A heading over a section, on the ground: [Usugami.type]'s `section` style, 8 dp in from the
 * screen margin, [top] above and [bottom] below. A screen reader hears it as a heading.
 */
@Composable
fun SectionHeading(text: String, modifier: Modifier = Modifier, top: Dp = Space.headingTop, bottom: Dp = Space.headingBottom) {
    Text(
        text,
        style = Usugami.type.section,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .padding(start = 8.dp, end = 8.dp, top = top, bottom = bottom)
            .semantics { heading() },
    )
}

/**
 * "A sheet goes here": no fill, a dashed outline, its [content] in the middle. One clickable with no
 * role; disabled, it shows at half strength.
 */
@Composable
fun BlankSheet(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .then(if (!enabled) Modifier.graphicsLayer { alpha = 0.5f } else Modifier)
            .dashedOutline()
            .clipped(SheetShape)
            .clickable(interactionSource = null, indication = PressIndication.shaped(SheetShape), enabled = enabled, onClick = onClick),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

// The modifiers here read Usugami.colors (and tile animates), so they are composable. Compose's lint
// warns about that (ComposableModifierFactory); the warning is suppressed, and the factories are not
// rewritten with composed { }.

/** The dashed outline of a blank sheet alone, inside the bounds: the picture of an empty state. */
@Suppress("ComposableModifierFactory")
@Composable
fun Modifier.dashedOutline(shape: Shape = SheetShape): Modifier {
    val color = Usugami.colors.hairlineStrong
    val outline = remember(shape, color) {
        Modifier.drawWithCache {
            val width = 1.5.dp.toPx()
            val inner = shape.concentric(size, layoutDirection, this, width / 2)
            val stroke = Stroke(width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
            onDrawBehind { drawOutline(inner, color, style = stroke) }
        }
    }
    return this.then(outline)
}

/** A well cut into the paper: the `well` fill with its 1 dp `wellEdge` (seen in the dark theme only). */
@Suppress("ComposableModifierFactory")
@Composable
fun Modifier.well(shape: Shape = WellShape): Modifier {
    val colors = Usugami.colors
    return this.background(colors.well, shape).border(1.dp, colors.wellEdge, shape)
}

/**
 * The hairline round real paper, drawn 1 dp OUTSIDE the bounds, and nothing else: no layout, no clip,
 * no fill, so a page's node stays exactly the page.
 */
@Suppress("ComposableModifierFactory")
@Composable
fun Modifier.paperEdge(radius: Dp = 0.dp): Modifier {
    val color = Usugami.colors.pageEdge
    val edge = remember(radius, color) {
        val shape = RoundedCornerShape(radius)
        Modifier.drawWithCache {
            val width = 1.dp.toPx()
            val outside = shape.concentric(size, layoutDirection, this, -width / 2)
            val stroke = Stroke(width)
            onDrawBehind { drawOutline(outside, color, style = stroke) }
        }
    }
    return this.then(edge)
}

/** Real paper for thumbnails and previews: white in both themes, its hairline outside, corners [radius]. */
@Suppress("ComposableModifierFactory")
@Composable
fun Modifier.paper(radius: Dp = 2.dp): Modifier {
    val shape = remember(radius) { RoundedCornerShape(radius) }
    return this.paperEdge(radius).clipped(shape).background(Color.White)
}

/**
 * A tile: clipped to [shape], `sheet` with a 1 dp hairline, or `lilac` with a 2 dp `primary` edge
 * when [selected]; while [lifted] (held to be moved) and not selected, `sheetTop` with a
 * `hairlineStrong` edge, lighter than its neighbours in the dark too. The colours change with
 * [Motion.appear]. Put it before the tile's selectable, toggleable or clickable, so the press tint is
 * clipped too. It has no semantics.
 * The selectable, toggleable or clickable that follows it takes
 * `indication = PressIndication.shaped(shape, focusInset = 2.dp)`, so its focus outline follows the
 * corners just inside the chosen tile's edge, which would hide it.
 */
@Suppress("ComposableModifierFactory")
@Composable
fun Modifier.tile(selected: Boolean, shape: Shape = TileShape, lifted: Boolean = false): Modifier {
    val colors = Usugami.colors
    val fill = animateColorAsState(
        when {
            selected -> colors.lilac
            lifted -> colors.sheetTop
            else -> colors.sheet
        },
        Motion.appear(),
        label = "tile fill",
    )
    val edge = animateColorAsState(
        when {
            selected -> MaterialTheme.colorScheme.primary
            lifted -> colors.hairlineStrong
            else -> colors.hairline
        },
        Motion.appear(),
        label = "tile edge",
    )
    val tile = remember(selected, shape, fill, edge) { Modifier.tileDrawing(selected, shape, fill, edge) }
    return this.then(tile)
}

/** A tile's clip, fill and edge; the colours are read while drawing. */
private fun Modifier.tileDrawing(selected: Boolean, shape: Shape, fill: State<Color>, edge: State<Color>): Modifier = this
    .clipped(shape)
    .drawWithCache {
        val width = (if (selected) 2.dp else 1.dp).toPx()
        val outline = shape.createOutline(size, layoutDirection, this)
        val ring = shape.concentric(size, layoutDirection, this, width / 2)
        val stroke = Stroke(width)
        onDrawWithContent {
            drawOutline(outline, fill.value)
            drawContent()
            drawOutline(ring, edge.value, style = stroke)
        }
    }

/**
 * [this] shape's outline at [size], moved [by] in from every side (out when [by] is negative) with
 * its corners following, so that a ring drawn along it is concentric with the shape (R19.1): each
 * corner radius less [by], never below 0, or, outside, more by as much, a square corner staying
 * square. A rectangle is only moved; any other outline is the shape's own at the smaller (or larger)
 * size, moved. The rings and outlines the kit draws from a Shape are drawn along it; the edges it draws
 * by hand (sheetSlice, the action bar's top edge, the stack's under-sheets, the slider's track) work
 * out their own smaller radius.
 */
internal fun Shape.concentric(size: Size, layoutDirection: LayoutDirection, density: Density, by: Float): Outline =
    when (val outline = createOutline(size, layoutDirection, density)) {
        is Outline.Rectangle -> Outline.Rectangle(outline.rect.movedIn(by))
        is Outline.Rounded -> {
            val r = outline.roundRect
            val moved = Rect(r.left, r.top, r.right, r.bottom).movedIn(by)
            Outline.Rounded(
                RoundRect(
                    moved,
                    topLeft = r.topLeftCornerRadius.movedIn(by),
                    topRight = r.topRightCornerRadius.movedIn(by),
                    bottomRight = r.bottomRightCornerRadius.movedIn(by),
                    bottomLeft = r.bottomLeftCornerRadius.movedIn(by),
                ),
            )
        }
        is Outline.Generic -> {
            val shown = Size((size.width - 2 * by).coerceAtLeast(0f), (size.height - 2 * by).coerceAtLeast(0f))
            val at = Offset(by, by)
            when (val inner = createOutline(shown, layoutDirection, density)) {
                is Outline.Rectangle -> Outline.Rectangle(inner.rect.translate(at))
                is Outline.Rounded -> Outline.Rounded(inner.roundRect.translate(at))
                is Outline.Generic -> Outline.Generic(Path().apply { addPath(inner.path, at) })
            }
        }
    }

/** This rectangle moved [by] in from every side (out when negative), never turned inside out. */
private fun Rect.movedIn(by: Float): Rect {
    val l = left + by
    val t = top + by
    return Rect(l, t, maxOf(l, right - by), maxOf(t, bottom - by))
}

/** A corner's radius for a ring [by] inside it (outside when negative); a square corner stays square. */
private fun CornerRadius.movedIn(by: Float): CornerRadius =
    if (x <= 0f && y <= 0f) CornerRadius.Zero else CornerRadius((x - by).coerceAtLeast(0f), (y - by).coerceAtLeast(0f))

/**
 * Clips what it is set on, and everything inside it, to [shape], as Modifier.clip does, without
 * making it an item of the accessibility tree. The layer is taken by placing with it, not with
 * graphicsLayer(): every graphics-layer modifier (clip, alpha, shadow, scale, rotate) makes the node
 * it is set on such an item, and a container that is one while what is inside it changes keeps a
 * device test reading what was there (R17). The kit clips with this, never with clip.
 */
fun Modifier.clipped(shape: Shape): Modifier = this then ClippedElement(shape)

private data class ClippedElement(val shape: Shape) : ModifierNodeElement<ClippedNode>() {
    override fun create() = ClippedNode(shape)

    override fun update(node: ClippedNode) = node.clipTo(shape)

    override fun InspectorInfo.inspectableProperties() {
        name = "clipped"
        properties["shape"] = shape
    }
}

private class ClippedNode(shape: Shape) : Modifier.Node(), LayoutModifierNode {
    private var layer = clipLayer(shape)

    /** A new shape: the node is measured and placed again (the update says so), with the new layer. */
    fun clipTo(shape: Shape) {
        layer = clipLayer(shape)
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) { placeable.placeWithLayer(0, 0, layerBlock = layer) }
    }
}

private fun clipLayer(shape: Shape): GraphicsLayerScope.() -> Unit = {
    this.shape = shape
    clip = true
}

/**
 * Draws what it is set on, and everything inside it, in a layer that [block] sets up (alpha, scale,
 * translation, rotation), as Modifier.graphicsLayer does, without making it an item of the
 * accessibility tree: the layer is taken by placing with it, as [clipped] takes its own. State read
 * inside [block] changes the layer without a recomposition. For anything that is not already a node
 * of its own (R17); a button or a merged tile may keep graphicsLayer.
 */
fun Modifier.layered(block: GraphicsLayerScope.() -> Unit): Modifier = this then LayeredElement(block)

private class LayeredElement(private val block: GraphicsLayerScope.() -> Unit) : ModifierNodeElement<LayeredNode>() {
    override fun create() = LayeredNode(block)

    override fun update(node: LayeredNode) = node.layerWith(block)

    // The block is compared by reference: a block made anew is a new layer.
    override fun equals(other: Any?): Boolean = other is LayeredElement && other.block === block

    override fun hashCode(): Int = System.identityHashCode(block)

    override fun InspectorInfo.inspectableProperties() {
        name = "layered"
        properties["block"] = block
    }
}

private class LayeredNode(private var block: GraphicsLayerScope.() -> Unit) : Modifier.Node(), LayoutModifierNode {
    // A new block changes no size: it needs a new placement, not a new measure.
    override val shouldAutoInvalidate: Boolean
        get() = false

    fun layerWith(block: GraphicsLayerScope.() -> Unit) {
        this.block = block
        invalidatePlacement()
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) { placeable.placeWithLayer(0, 0, layerBlock = block) }
    }
}

/**
 * For a row that scrolls sideways: fades it to nothing over [edge] or 15 % of its width, whichever is
 * less, at a side where there is more of it beyond what shows ([state] can scroll that way; the start
 * is the right-hand side in a right-to-left layout), each side fading in and out with [Motion.appear]. At
 * rest at its start, or when it does not scroll, it is drawn exactly as without this. Set it on the
 * row BEFORE its scroll (`fadingEdges(scroll).horizontalScroll(scroll)`), or on a LazyRow's own
 * modifier with the row's state, so it fades what shows. It is a mask drawn in one drawWithCache:
 * no graphicsLayer and no clip, so it makes no node (R17) and cuts nothing a child draws up to 16 dp
 * above or below the row (a focus outline, a paper edge).
 */
@Suppress("ComposableModifierFactory") // It animates (see the note above dashedOutline).
@Composable
fun Modifier.fadingEdges(state: ScrollableState, edge: Dp = 48.dp): Modifier {
    // A ScrollState not measured yet says it can scroll forward; until it is, there is nothing more to show.
    val measured by remember(state) { derivedStateOf { state !is ScrollState || state.maxValue != Int.MAX_VALUE } }
    val start = animateFloatAsState(if (measured && state.canScrollBackward) 1f else 0f, Motion.appear(), label = "fade at start")
    val end = animateFloatAsState(if (measured && state.canScrollForward) 1f else 0f, Motion.appear(), label = "fade at end")
    val fading = remember(start, end, edge) { Modifier.fadingDrawing(start, end, edge) }
    return this.then(fading)
}

/**
 * The mask of [fadingEdges]; the strengths at the start and the end are read while drawing. Each side
 * takes out a ramp, opaque at the very edge and clear at the fade's inner end, at its strength: what
 * is left runs from 1 less the strength at the edge to all of it, with no brush made while drawing.
 */
private fun Modifier.fadingDrawing(start: State<Float>, end: State<Float>, edge: Dp): Modifier = this.drawWithCache {
    val fade = min(edge.toPx(), size.width * 0.15f)
    val room = FadeRoom.toPx()
    val bounds = Rect(0f, -room, size.width, size.height + room)
    val tall = Size(fade, size.height + 2 * room)
    val from = size.width - fade
    val leftRamp = Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = 0f, endX = fade)
    val rightRamp = Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = from, endX = size.width)
    val layer = Paint()
    onDrawWithContent {
        val atStart = start.value
        val atEnd = end.value
        if ((atStart <= 0f && atEnd <= 0f) || fade <= 0f) {
            drawContent()
            return@onDrawWithContent
        }
        val rtl = layoutDirection == LayoutDirection.Rtl
        val left = if (rtl) atEnd else atStart
        val right = if (rtl) atStart else atEnd
        drawIntoCanvas { it.saveLayer(bounds, layer) }
        drawContent()
        if (left > 0f) drawRect(leftRamp, Offset(0f, -room), tall, alpha = left, blendMode = BlendMode.DstOut)
        if (right > 0f) drawRect(rightRamp, Offset(from, -room), tall, alpha = right, blendMode = BlendMode.DstOut)
        drawIntoCanvas { it.restore() }
    }
}

/** How far above and below a fading row its children may draw and still show. */
private val FadeRoom = 16.dp
