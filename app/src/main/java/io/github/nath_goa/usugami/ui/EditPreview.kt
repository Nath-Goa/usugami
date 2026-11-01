package io.github.nath_goa.usugami.ui

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.core.graphics.withTranslation
import io.github.nath_goa.usugami.core.EditLayout
import io.github.nath_goa.usugami.core.EditStyle
import io.github.nath_goa.usugami.core.InkItem
import io.github.nath_goa.usugami.core.PageItem
import io.github.nath_goa.usugami.core.PathOp
import io.github.nath_goa.usugami.core.PictureItem
import io.github.nath_goa.usugami.core.ShapeItem
import io.github.nath_goa.usugami.core.TextItem
import io.github.nath_goa.usugami.data.PagePictures
import io.github.nath_goa.usugami.data.PageText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

// Edit PDF's page: what was added drawn as the PDF will have it, and the touches that choose,
// move, resize and draw.

/**
 * The page with [items] drawn on it as the PDF will have them. In [EditMode.SELECT], touching an
 * item chooses it and dragging moves it, the chosen item's handles change its size or shape, and a
 * tap elsewhere lets go of it; a swipe that starts off the items scrolls the screen. In the drawing
 * modes, a finger draws. Each item is also a node a screen reader can find and choose.
 */
@Composable
internal fun EditPreview(
    page: RenderedPage?,
    pageWidth: Float,
    pageHeight: Float,
    items: List<PageItem>,
    selected: PageItem?,
    mode: EditMode,
    style: EditStyle,
    description: String,
    onSelect: (Long?) -> Unit,
    onBegin: () -> Unit,
    onStep: (PageItem) -> Unit,
    onEnd: () -> Unit,
    onStroke: (FloatArray) -> Boolean,
    inkRoom: () -> Int,
    onFull: () -> Unit,
) {
    val latestItems by rememberUpdatedState(items)
    val latestSelected by rememberUpdatedState(selected)
    val select by rememberUpdatedState(onSelect)
    val begin by rememberUpdatedState(onBegin)
    val step by rememberUpdatedState(onStep)
    val end by rememberUpdatedState(onEnd)
    val stroke by rememberUpdatedState(onStroke)
    // The stroke being drawn, in points on the page: x, y, x, y, …
    val live = remember { mutableStateListOf<Float>() }
    val pictures = remember { mutableStateMapOf<String, ImageBitmap>() }
    val wanted = items.filterIsInstance<PictureItem>().filter { it.path !in pictures }
    LaunchedEffect(wanted.map { it.path }) {
        for (picture in wanted) {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    PagePictures.decode(picture.path, picture.orientation, 1024)
                } catch (_: Exception) {
                    null
                } catch (_: OutOfMemoryError) {
                    null
                }
            }
            if (bitmap != null) pictures[picture.path] = bitmap.asImageBitmap()
        }
    }
    val textPaint = remember { PageText.paint(0) }
    val layouts = remember { HashMap<String, PageText.Layout>() }
    val paths = remember { HashMap<PageItem, Path>() }
    val frame = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // As wide as the screen, but at most half as tall, so the tools above or the chosen item's
        // controls below show with it.
        val tallest = with(density) { (window.height * 0.5f).toDp() }.coerceIn(240.dp, 560.dp)
        val aspect = pageWidth / pageHeight
        val width = min(maxWidth, tallest * aspect)
        val height = width / aspect
        val pxPerPoint = with(density) { width.toPx() } / pageWidth
        Box(Modifier.size(width, height)) {
            Canvas(
                Modifier
                    .size(width, height)
                    .shadow(2.dp, RectangleShape)
                    .background(Color.White)
                    // While drawing, a stroke from the page's edge draws, instead of going back.
                    .then(if (mode != EditMode.SELECT) Modifier.systemGestureExclusion() else Modifier)
                    .semantics { contentDescription = description }
                    .pointerInput(mode, pageWidth, pageHeight) {
                        val toPage = pageWidth / size.width
                        if (mode == EditMode.SELECT) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val x = down.position.x * toPage
                                val y = down.position.y * toPage
                                val chosen = latestSelected
                                val reach = 12.dp.toPx() * toPage
                                val handle = chosen?.let { item ->
                                    handleSpots(item, HANDLE_GAP.toPx() * toPage).firstOrNull { (_, at) -> hypot(at.first - x, at.second - y) <= 1.5f * reach }?.first
                                }
                                val hit = if (handle == null) EditLayout.pick(latestItems, x, y, reach, chosen?.id) else chosen
                                if (hit == null) {
                                    // Off every item: a tap lets go of the chosen one, a swipe scrolls.
                                    val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                                    up.consume()
                                    select(null)
                                    return@awaitEachGesture
                                }
                                down.consume()
                                if (hit.id != chosen?.id) select(hit.id)
                                begin()
                                try {
                                    drag(down.id) { change ->
                                        val moved = (change.position - down.position) * toPage
                                        step(
                                            if (handle != null) EditLayout.dragHandle(hit, handle, change.position.x * toPage, change.position.y * toPage, pageWidth, pageHeight)
                                            else EditLayout.moved(hit, moved.x, moved.y, pageWidth, pageHeight),
                                        )
                                        change.consume()
                                    }
                                } finally {
                                    end()
                                }
                            }
                        } else {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                down.consume()
                                var room = inkRoom()
                                if (room <= 0) {
                                    onFull()
                                    return@awaitEachGesture
                                }
                                // Points closer together than about a millimetre on the screen add nothing.
                                val minStep = 2.dp.toPx() * toPage
                                fun add(position: Offset) {
                                    val px = (position.x * toPage).coerceIn(0f, pageWidth)
                                    val py = (position.y * toPage).coerceIn(0f, pageHeight)
                                    val count = live.size
                                    if (room <= 0 || (count >= 2 && hypot(px - live[count - 2], py - live[count - 1]) < minStep)) return
                                    live.addAll(listOf(px, py))
                                    room--
                                }
                                live.clear()
                                add(down.position)
                                // One finger draws; others are ignored. Points between frames make the curve smoother.
                                while (true) {
                                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                    change.historical.forEach { add(it.position) }
                                    add(change.position)
                                    change.consume()
                                    if (!change.pressed) break
                                }
                                if (live.isNotEmpty()) stroke(live.toFloatArray())
                                live.clear()
                            }
                        }
                    },
            ) {
                page?.let { drawImage(it.image, dstSize = IntSize(size.width.toInt(), size.height.toInt())) }
                if (paths.size > 4 * items.size + 16) paths.clear()
                withTransform({ scale(pxPerPoint, pxPerPoint, pivot = Offset.Zero) }) {
                    for (item in items) {
                        when (item) {
                            is TextItem -> drawIntoCanvas { canvas ->
                                val layout = layouts.getOrPut(item.text) { PageText.layout(item.text, textPaint) }
                                textPaint.color = item.color
                                canvas.nativeCanvas.withTranslation(item.x, item.y) {
                                    scale(item.size / PageText.REFERENCE, item.size / PageText.REFERENCE)
                                    PageText.draw(this, layout, textPaint)
                                }
                            }
                            is PictureItem -> pictures[item.path]?.let { bitmap ->
                                drawIntoCanvas { canvas ->
                                    canvas.nativeCanvas.drawBitmap(
                                        bitmap.asAndroidBitmap(),
                                        null,
                                        android.graphics.RectF(item.x, item.y, item.x + item.width, item.y + item.height),
                                        android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG),
                                    )
                                }
                            }
                            is InkItem -> drawInk(paths.getOrPut(item) { EditLayout.outline(item).toPath() }, item.color, item.width, item.highlighter)
                            is ShapeItem -> {
                                val path = paths.getOrPut(item) { EditLayout.outline(item).toPath() }
                                if (item.isBox && item.filled) drawPath(path, Color(item.color), style = Fill)
                                else drawInk(path, item.color, item.width, highlighter = false)
                            }
                        }
                    }
                    if (live.isNotEmpty()) {
                        val highlighter = mode == EditMode.HIGHLIGHTER
                        val points = live.toFloatArray()
                        drawInk(
                            EditLayout.outline(InkItem(0, 0, points, 1f, 0, highlighter)).toPath(),
                            if (highlighter) style.highlighterColor else style.penColor,
                            if (highlighter) style.highlighterWidth else style.penWidth,
                            highlighter,
                        )
                    }
                }
                // The chosen item's frame and handles show what can be dragged. They aren't part of the PDF.
                if (selected != null && mode == EditMode.SELECT) {
                    val bounds = selected.bounds
                    val gap = HANDLE_GAP.toPx()
                    drawRect(
                        frame,
                        topLeft = Offset(bounds.x * pxPerPoint - gap, bounds.y * pxPerPoint - gap),
                        size = Size(bounds.width * pxPerPoint + 2 * gap, bounds.height * pxPerPoint + 2 * gap),
                        style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
                    )
                    for ((_, at) in handleSpots(selected, gap / pxPerPoint)) {
                        val center = Offset(at.first * pxPerPoint, at.second * pxPerPoint)
                        drawCircle(Color.White, radius = 6.dp.toPx(), center = center)
                        drawCircle(frame, radius = 6.dp.toPx(), center = center, style = Stroke(2.dp.toPx()))
                    }
                }
            }
            // For a screen reader: each item, where it is drawn, to find by touch and choose. The
            // smaller ones come last, on top, so one inside a bigger one (text in a box) can be reached.
            for (item in items.sortedByDescending { it.bounds.width * it.bounds.height }) {
                // Keyed, so each node stays with its item as items come, go and change size.
                key(item.id) {
                    val bounds = item.bounds
                    val label = describe(item)
                    val isChosen = item.id == selected?.id
                    Box(
                        Modifier
                            .offset { IntOffset((bounds.x * pxPerPoint).roundToInt(), (bounds.y * pxPerPoint).roundToInt()) }
                            .size(with(density) { (bounds.width * pxPerPoint).toDp() }, with(density) { (bounds.height * pxPerPoint).toDp() })
                            .semantics {
                                contentDescription = label
                                this.selected = isChosen
                                onClick { select(item.id); true }
                            },
                    )
                }
            }
        }
    }
}

/** How far the chosen item's frame stands off what it covers. */
private val HANDLE_GAP = 4.dp

/**
 * Where [item]'s handles show, in points: a box's and a line's on its corners and ends, and text's
 * and a drawing's on the corner of the frame around them, [gap] points out, clear of the ink.
 */
private fun handleSpots(item: PageItem, gap: Float) = EditLayout.handles(item).map { (handle, at) ->
    if (item is TextItem || item is InkItem) handle to (at.first + gap to at.second + gap) else handle to at
}

/** A stroke as the PDF draws it: round ends and corners; a highlighter's see-through and multiplied with the page. */
private fun DrawScope.drawInk(path: Path, color: Int, width: Float, highlighter: Boolean) {
    val stroke = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round)
    if (!highlighter) {
        drawPath(path, Color(color), style = stroke)
    } else {
        // Multiplying needs Android 10's blend modes; before, the ink is only see-through.
        val multiply = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        drawPath(path, Color(color), alpha = EditLayout.HIGHLIGHTER_ALPHA, style = stroke, blendMode = if (multiply) BlendMode.Multiply else BlendMode.SrcOver)
    }
}

private fun List<PathOp>.toPath(): Path = Path().also { path ->
    for (op in this) {
        when (op) {
            is PathOp.Move -> path.moveTo(op.x, op.y)
            is PathOp.Line -> path.lineTo(op.x, op.y)
            is PathOp.Cubic -> path.cubicTo(op.x1, op.y1, op.x2, op.y2, op.x, op.y)
            PathOp.Close -> path.close()
        }
    }
}
