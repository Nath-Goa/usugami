package io.github.nath_goa.usugami.ui

import android.graphics.Bitmap
import android.view.Choreographer
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.core.GlyphBox
import io.github.nath_goa.usugami.core.PageItem
import io.github.nath_goa.usugami.core.ReaderGeometry
import io.github.nath_goa.usugami.core.RedactBox
import io.github.nath_goa.usugami.core.TextPlace
import io.github.nath_goa.usugami.core.TextSelection
import io.github.nath_goa.usugami.data.ReaderDocument
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** The margin beside each page, and the gap between two. */
private val PageMargin = 8.dp

/**
 * The tallest a page item is laid out, in pixels: a layout can't measure much over 260,000. Only a
 * page over about 200 times as tall as it is wide comes near it; it is shown narrower than the other
 * pages, in its shape, at most that tall, and centred.
 */
private const val MAX_ITEM_HEIGHT = 200_000

/** Room kept under the last page for the page indicator, which floats over the pages. */
internal val ReaderIndicatorRoom = 56.dp

/** The zooms the screen reader's Zoom in and Zoom out go between: half, the screen's width, a double-tap's, the most. */
private val ZoomSteps = listOf(ReaderGeometry.MIN_ZOOM, 1f, ReaderGeometry.DOUBLE_TAP_ZOOM, ReaderGeometry.MAX_ZOOM)

/** How long after the pages stop moving their sharp parts are drawn. */
private const val TILE_DELAY_MS = 150L

/** How far from a long-press a word is still taken (TextSelection.wordAt), on the screen. */
private val LongPressReach = 8.dp

/** A selection handle: a filled circle this big, its centre this far under the end it hangs from. */
private val HandleRadius = 9.dp
private val HandleHang = 12.dp

/** A finger down this near a handle's centre takes it. */
private val HandleTouch = 24.dp

/** A handle held this near the top or bottom of the page area scrolls the pages. */
private val ScrollEdge = 56.dp

/**
 * The text selected, and its handles: Usugami's red of the light theme in both themes, as pages are
 * white in both (the dark theme's pale red would hardly show on them).
 */
private val SelectionColor = Color(0x4DB3261E)
private val HandleColor = Color(0xFFB3261E)

/** Find text's matches on the pages, and the match marked: fixed colours, as pages are white in both themes. */
private val MatchColor = Color(0x66FFE600)
private val CurrentMatchColor = Color(0x99FF9800)

/**
 * How wide a page [shownWidth] × [shownHeight] points is laid out when the others are [itemWidth]
 * pixels wide: as wide, but for a page too tall to be laid out so (see MAX_ITEM_HEIGHT), which is
 * narrower, in its shape, and centred. Whole pixels, so that its height stays within what a layout
 * can measure.
 */
private fun pageWidth(shownWidth: Float, shownHeight: Float, itemWidth: Int): Int {
    val shape = shownWidth / shownHeight
    return if (itemWidth / shape > MAX_ITEM_HEIGHT) maxOf(1, (MAX_ITEM_HEIGHT * shape).toInt()) else itemWidth
}

/** How tall a page [shownWidth] × [shownHeight] points is laid out at [ownWidth] pixels wide (see pageWidth): in its shape, at most MAX_ITEM_HEIGHT. */
private fun pageHeight(shownWidth: Float, shownHeight: Float, ownWidth: Int): Int =
    (ownWidth * shownHeight / shownWidth).roundToInt().coerceIn(1, MAX_ITEM_HEIGHT)

/**
 * The reader's pages ([session]'s PDF), one under another in one scroll ([list]), each as wide as
 * the screen but for its margins. Only the pages near the screen are composed and drawn (see
 * [ReaderBitmaps]). The list is laid out [zoom] times smaller than the screen and scaled up by it in
 * a layer, moved left by [panX] (see ReaderGeometry), so it scrolls and flings by itself at any zoom.
 * Two fingers zoom and move it; zoomed in, one finger also moves it sideways; a double-tap zooms in
 * about the spot, or back to the screen's width. [onView] is told the new zoom and pan. Once the
 * pages stop moving, the part of each page that shows is drawn again sharp when its picture is
 * coarser than the screen (see [ReaderTiles]). [zoom] and [panX] are read where they are used, so a
 * pinch lays the list out again without composing it again. What Find text [found] is marked on the
 * pages, and the pages move to show the match marked whenever another one is. What Annotate added
 * ([items]) is drawn on its page as the PDF will have it; no touch reaches it here. [barBelow]:
 * something under the pages (Save PDF, Copy) already clears the navigation bar, so the list's end needn't.
 *
 * Text: a long-press tells [onLongPress] the page and the point on it (points on the page as shown),
 * and how far from it, in points, a word is still taken; a tap that is no double-tap tells [onTap].
 * The [selection] is marked on its pages, with a round handle hanging under each end, drawn over the
 * pages at the same size at any zoom. A finger down on a handle drags that end, glyph by glyph, onto
 * other pages too; held near the top or bottom of the pages, it scrolls them, faster nearer the edge.
 * A screen reader's "Copy the text of this page" on a page tells [onCopyPage].
 */
@Composable
internal fun ReaderPages(
    session: ReaderSession,
    list: LazyListState,
    zoom: () -> Float,
    panX: () -> Float,
    onView: (zoom: Float, panX: Float) -> Unit,
    found: Found,
    modifier: Modifier = Modifier,
    items: List<PageItem> = emptyList(),
    barBelow: Boolean = false,
    selection: ReaderSelection? = null,
    onLongPress: (page: Int, x: Float, y: Float, reach: Float) -> Unit = { _, _, _, _ -> },
    onTap: () -> Unit = {},
    onCopyPage: ((page: Int) -> Unit)? = null,
) {
    val document = session.document
    // The pictures of this PDF's pages, and the sharp parts: all recycled once it no longer shows.
    val bitmaps = remember(document) { ReaderBitmaps() }
    val tiles = remember(document) { ReaderTiles() }
    DisposableEffect(bitmaps, tiles) {
        onDispose {
            bitmaps.clear()
            tiles.clear()
        }
    }
    val currentZoom by rememberUpdatedState(zoom)
    val currentPan by rememberUpdatedState(panX)
    val currentOnView by rememberUpdatedState(onView)
    val currentSelection by rememberUpdatedState(selection)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val currentOnTap by rememberUpdatedState(onTap)
    val scope = rememberCoroutineScope()
    // A double-tap's zoom, while it runs: two fingers stop it.
    val animation = remember { arrayOfNulls<Job>(1) }
    // Zoomed out, one finger beside the pages, where the list takes no touches, scrolls them too.
    val beside = remember(list) { BesideScroll(list) { currentZoom() } }

    /** Zooms to [target] about the screen point ([focusX], [focusY]) of a screen [width] wide, at once. */
    fun zoomTo(target: Float, focusX: Float, focusY: Float, width: Float) {
        val zoomNow = currentZoom()
        val pan = ReaderGeometry.clampPan(currentPan(), zoomNow, width)
        val zoomed = ReaderGeometry.zoomAbout(zoomNow, pan, target / zoomNow, focusX, focusY, width)
        // The screen's width is exactly 1, with nothing moved sideways.
        if (target == 1f) currentOnView(1f, 0f) else currentOnView(zoomed.zoom, zoomed.panX)
        list.dispatchRawDelta(zoomed.scrollBy)
    }

    val bottom = if (barBelow) 0.dp else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            // A selection handle dragged: before the pinch, on the box around the list, so that neither
            // the list nor a pan sideways takes the finger meanwhile. A second finger lets go of it.
            .pointerInput(list, document) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val held = currentSelection?.takeIf { it.active } ?: return@awaitEachGesture
                    val margin = PageMargin.roundToPx()
                    val width = size.width
                    val hang = HandleHang.toPx()
                    val startAt = handleCentre(held, true, document, list, currentZoom(), currentPan(), width, margin, hang)
                    val endAt = handleCentre(held, false, document, list, currentZoom(), currentPan(), width, margin, hang)
                    val toStart = startAt?.let { (it - down.position).getDistance() } ?: Float.MAX_VALUE
                    val toEnd = endAt?.let { (it - down.position).getDistance() } ?: Float.MAX_VALUE
                    val startEnd = when {
                        toStart <= HandleTouch.toPx() && toStart <= toEnd -> true
                        toEnd <= HandleTouch.toPx() -> false
                        else -> return@awaitEachGesture
                    }
                    val place = (if (startEnd) held.start else held.end) ?: return@awaitEachGesture
                    // The finger moves the end from the middle of its glyph: held still, it stays on it.
                    val centre = glyphCentre(held, place, document, list, currentZoom(), currentPan(), width, margin) ?: return@awaitEachGesture
                    down.consume()
                    held.take(startEnd)
                    val grab = centre - down.position
                    var finger = down.position
                    fun follow() {
                        val point = pageUnder(finger + grab, document, list, currentZoom(), currentPan(), width, margin, nearest = true) ?: return
                        held.dragTo(point.page, point.x, point.y)
                    }
                    // Near the top or bottom of the pages, they scroll under the finger each frame, up to a
                    // screen's height a second at the very edge, and the end follows what comes under it.
                    val edge = ScrollEdge.toPx()
                    val height = size.height.toFloat()
                    val scrolling = scope.launch {
                        var last = -1L
                        while (true) {
                            withFrameNanos { now ->
                                val seconds = if (last < 0) 0f else ((now - last) / 1_000_000_000f).coerceAtMost(0.05f)
                                last = now
                                val y = finger.y
                                val speed = when {
                                    y < edge -> -(1f - maxOf(0f, y) / edge)
                                    y > height - edge -> 1f - maxOf(0f, height - y) / edge
                                    else -> 0f
                                } * height
                                if (speed != 0f && seconds > 0f) {
                                    // Forward is down the pages; list pixels are screen pixels over the zoom.
                                    list.dispatchRawDelta(speed * seconds / currentZoom())
                                    follow()
                                }
                            }
                        }
                    }
                    try {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.count { it.pressed } > 1) break
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            if (!change.pressed) break
                            if (change.position != finger) {
                                finger = change.position
                                follow()
                            }
                        }
                    } finally {
                        scrolling.cancel()
                        held.drop()
                    }
                }
            }
            // On the box around the list, so that they see the touches before the list does; the list scrolls by itself.
            .pointerInput(list) {
                awaitEachGesture {
                    var pinched = false
                    var focus = Offset.Unspecified
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val down = event.changes.count { it.pressed }
                        if (down >= 2 || pinched) {
                            // Two fingers zoom and move the pages; the list doesn't scroll meanwhile, nor
                            // with the finger left down after them, until every finger is up.
                            if (!pinched) animation[0]?.cancel()
                            pinched = true
                            val centroid = event.calculateCentroid(useCurrent = true)
                            if (down >= 2 && centroid.isSpecified) {
                                val factor = event.calculateZoom()
                                val moved = event.calculatePan()
                                val zoomNow = currentZoom()
                                val pan = ReaderGeometry.clampPan(currentPan(), zoomNow, size.width.toFloat())
                                // About where the fingers are now, the pages moved as far as they moved.
                                val zoomed = ReaderGeometry.zoomAbout(zoomNow, pan - moved.x, factor, centroid.x, centroid.y, size.width.toFloat())
                                currentOnView(zoomed.zoom, zoomed.panX)
                                list.dispatchRawDelta(zoomed.scrollBy - moved.y / zoomNow)
                                focus = centroid
                            }
                            event.changes.forEach(PointerInputChange::consume)
                        } else if (down == 1) {
                            // Zoomed in, one finger moves the pages sideways too; the list takes the rest.
                            val zoomNow = currentZoom()
                            val change = event.changes.first { it.pressed }
                            val dx = change.position.x - change.previousPosition.x
                            // Not while a selection handle is dragged: the finger is the handle's.
                            if (zoomNow > 1f && dx != 0f && currentSelection?.dragging != true) {
                                val pan = ReaderGeometry.clampPan(currentPan(), zoomNow, size.width.toFloat())
                                currentOnView(zoomNow, ReaderGeometry.clampPan(pan - dx, zoomNow, size.width.toFloat()))
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    // Let go near the screen's width: exactly it.
                    if (pinched && focus.isSpecified && abs(currentZoom() - 1f) < 0.05f) zoomTo(1f, focus.x, focus.y, size.width.toFloat())
                }
            }
            .pointerInput(list, document) {
                awaitEachGesture {
                    // A finger held still a moment: a long-press. Lifted at once: a tap, or the first of two.
                    val first = awaitFirstDown(requireUnconsumed = false)
                    try {
                        withTimeout(viewConfiguration.longPressTimeoutMillis) { awaitTap(first) } ?: return@awaitEachGesture
                    } catch (_: PointerEventTimeoutCancellationException) {
                        // Not a handle held still, which waits to be dragged.
                        if (currentSelection?.dragging == true) return@awaitEachGesture
                        val point = pageUnder(first.position, document, list, currentZoom(), currentPan(), size.width, PageMargin.roundToPx(), nearest = false)
                        if (point != null) currentOnLongPress(point.page, point.x, point.y, LongPressReach.toPx() * point.pointsPerPixel)
                        return@awaitEachGesture
                    }
                    // Two taps close together, neither moving: the spot of the second. One alone: a tap.
                    val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) { awaitFirstDown() }
                    if (second == null || (second.position - first.position).getDistance() > viewConfiguration.touchSlop * 4) {
                        currentOnTap()
                        return@awaitEachGesture
                    }
                    awaitTap(second) ?: return@awaitEachGesture
                    val spot = second.position
                    val from = currentZoom()
                    val to = if (abs(from - 1f) < 0.05f) ReaderGeometry.DOUBLE_TAP_ZOOM else 1f
                    animation[0]?.cancel()
                    animation[0] = scope.launch {
                        val zooming = Animatable(from)
                        zooming.animateTo(to, Motion.medium()) { if (value != currentZoom()) zoomTo(value, spot.x, spot.y, size.width.toFloat()) }
                        zoomTo(to, spot.x, spot.y, size.width.toFloat())
                    }
                }
            },
    ) {
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val margin = with(LocalDensity.current) { PageMargin.roundToPx() }
        val itemWidth = width - 2 * margin

        /** The screen reader's Zoom in ([larger]) or Zoom out on page [index]: to the next step, about the middle of what shows of the page. */
        fun zoomStep(index: Int, larger: Boolean): Boolean {
            val zoomNow = currentZoom()
            val target = (if (larger) ZoomSteps.firstOrNull { it > zoomNow + 0.01f } else ZoomSteps.lastOrNull { it < zoomNow - 0.01f }) ?: return false
            val info = list.layoutInfo
            val focusY = info.visibleItemsInfo.firstOrNull { it.index == index }?.let {
                val top = (it.offset - info.viewportStartOffset) * zoomNow
                (maxOf(top, 0f) + minOf(top + it.size * zoomNow, height.toFloat())) / 2f
            } ?: (height / 2f)
            animation[0]?.cancel()
            zoomTo(target, width / 2f, focusY.coerceIn(0f, height.toFloat()), width.toFloat())
            return true
        }

        // The sharp parts: drawn for the pages showing once the pages have stopped moving for a moment.
        LaunchedEffect(document, tiles, width, height) {
            snapshotFlow { listOf(currentZoom(), currentPan(), list.firstVisibleItemIndex.toFloat(), list.firstVisibleItemScrollOffset.toFloat()) }
                .collectLatest {
                    delay(TILE_DELAY_MS)
                    drawTiles(document, tiles, list, currentZoom(), currentPan(), margin, itemWidth, width, height)
                }
        }

        // The match marked, shown a third of the way down the screen and, zoomed in, a quarter of the
        // screen in from the left: whenever a match is marked (Found.moves; also the same one again, by
        // Next with one match, or the same word searched again), not when its number grows as matches of
        // earlier pages come in before it, and again when the phone is turned, which lays the pages out anew.
        val target = found.matches.getOrNull(found.current)?.firstOrNull()
        LaunchedEffect(target, found.moves) {
            val box = target ?: return@LaunchedEffect
            if (itemWidth <= 0) return@LaunchedEffect
            val (shownWidth, shownHeight) = document.sizes.getOrNull(box.page) ?: return@LaunchedEffect
            // Heights from the sizes, as the pages are laid out: so for a page not yet laid out too.
            val ownWidth = pageWidth(shownWidth, shownHeight, itemWidth)
            val zoomNow = currentZoom()
            val below = box.top * pageHeight(shownWidth, shownHeight, ownWidth) - height / zoomNow / 3f
            list.scrollToItem(box.page)
            if (below != 0f) list.scrollBy(below)
            if (zoomNow > 1f) {
                val left = margin + (itemWidth - ownWidth) / 2 + box.left * ownWidth
                currentOnView(zoomNow, ReaderGeometry.clampPan(left * zoomNow - width / 4f, zoomNow, width.toFloat()))
            }
        }
        // Text just selected low on the screen: the bar with Copy that comes with it makes the page area
        // shorter, so the pages move up until the end handle shows above the bar and can be taken.
        val selected = selection?.active == true
        val currentHeight by rememberUpdatedState(height)
        val handleHang = with(LocalDensity.current) { HandleHang.toPx() }
        val handleTouch = with(LocalDensity.current) { HandleTouch.toPx() }
        LaunchedEffect(selection, selected) {
            if (!selected || selection == null) return@LaunchedEffect
            // Once the page area is laid out above the bar.
            withFrameNanos {}
            val centre = handleCentre(selection, false, document, list, currentZoom(), currentPan(), width, margin, handleHang) ?: return@LaunchedEffect
            val over = centre.y + handleTouch - currentHeight
            if (over > 0f) list.scrollBy(over / currentZoom())
        }
        // The matches by page, and what Annotate added.
        val marks = remember(found.matches) { found.matches.withIndex().groupBy { it.value.first().page } }
        val added = remember(items) { items.groupBy { it.page } }

        // Behind the list, so touched only where it isn't: beside the pages, zoomed out.
        Box(
            Modifier
                .fillMaxSize()
                .scrollable(beside, Orientation.Vertical, reverseDirection = true),
        )
        LazyColumn(
            state = list,
            // The last page can be scrolled clear of the navigation bar and the page indicator.
            contentPadding = PaddingValues(top = PageMargin, bottom = PageMargin + bottom + ReaderIndicatorRoom),
            verticalArrangement = Arrangement.spacedBy(PageMargin),
            modifier = Modifier
                .layout { measurable, _ ->
                    // As tall as the screen shows at this zoom: the list's end can be reached at any zoom.
                    val placeable = measurable.measure(Constraints.fixed(width, ceil(height / currentZoom()).toInt()))
                    layout(width, height) {
                        placeable.placeWithLayer(IntOffset.Zero) {
                            val scale = currentZoom()
                            transformOrigin = TransformOrigin(0f, 0f)
                            scaleX = scale
                            scaleY = scale
                            translationX = -ReaderGeometry.clampPan(currentPan(), scale, width.toFloat())
                        }
                    }
                },
        ) {
            items(document.pageCount, key = { it }) { index ->
                val onPage = marks[index].orEmpty()
                ReaderPage(
                    document,
                    bitmaps,
                    tiles,
                    index,
                    itemWidth,
                    matches = onPage.filter { it.index != found.current }.flatMap { it.value },
                    current = onPage.firstOrNull { it.index == found.current }?.value.orEmpty(),
                    onZoom = { larger -> zoomStep(index, larger) },
                    items = added[index].orEmpty(),
                    selected = selectedBoxes(selection, index),
                    onCopyPage = onCopyPage?.let { copy -> { copy(index) } },
                )
                if (selection != null) {
                    // The selection's text of this page, read when the selection runs over it; forgotten when it goes.
                    if (selection.covers(index) && selection.pages[index] == null) LaunchedEffect(selection, index) { selection.need(index) }
                    DisposableEffect(selection, index) { onDispose { selection.forget(index) } }
                }
            }
        }
        // The selection's handles, over the pages, in screen pixels: the same size at any zoom.
        if (selection != null) {
            Canvas(Modifier.fillMaxSize()) {
                if (!selection.active) return@Canvas
                val hang = HandleHang.toPx()
                for (startEnd in listOf(true, false)) {
                    val centre = handleCentre(selection, startEnd, document, list, currentZoom(), currentPan(), width, margin, hang) ?: continue
                    drawCircle(HandleColor, HandleRadius.toPx(), centre)
                }
            }
        }
    }
}

/** The selection's boxes on page [index], one a line (TextSelection.lineBoxes), in points; none when nothing of it is selected or its text isn't read yet. */
private fun selectedBoxes(selection: ReaderSelection?, index: Int): List<GlyphBox> {
    val glyphs = selection?.pages?.get(index) ?: return emptyList()
    val range = selection.on(index) ?: return emptyList()
    return TextSelection.lineBoxes(glyphs, range)
}

/** A point on page [page]: ([x], [y]) points from its top-left corner as shown, [pointsPerPixel] points to the screen pixel there. */
private class PagePoint(val page: Int, val x: Float, val y: Float, val pointsPerPixel: Float)

/**
 * The page laid out under the point [at] of the page area ([width] pixels wide, the pages [margin]
 * pixels in from its sides, at [zoom] and [panX]), and the point on it. With [nearest] (a handle
 * dragged), also the page level with [at] beside it, or the nearest page laid out when [at] is between
 * two or past the last; without it, a point beside the pages is on none. Null when there is none.
 */
private fun pageUnder(at: Offset, document: ReaderDocument, list: LazyListState, zoom: Float, panX: Float, width: Int, margin: Int, nearest: Boolean): PagePoint? {
    val itemWidth = width - 2 * margin
    if (itemWidth <= 0 || !(zoom > 0f)) return null
    val (listX, listY) = ReaderGeometry.toList(at.x, at.y, zoom, ReaderGeometry.clampPan(panX, zoom, width.toFloat()))
    val info = list.layoutInfo
    val items = info.visibleItemsInfo.map { ReaderGeometry.Item(it.index, it.offset - info.viewportStartOffset, it.size) }
    val item = ReaderGeometry.itemAt(items, listY)
        ?: items.takeIf { nearest }?.minByOrNull { if (listY < it.top) it.top - listY else listY - (it.top + it.height) }
        ?: return null
    val (shownWidth, shownHeight) = document.sizes.getOrNull(item.index) ?: return null
    if (item.height <= 0) return null
    val ownWidth = pageWidth(shownWidth, shownHeight, itemWidth)
    val ownLeft = margin + (itemWidth - ownWidth) / 2
    // A long-press beside a page, on the background, is on no page.
    if (!nearest && (listX < ownLeft || listX >= ownLeft + ownWidth)) return null
    val perListPixel = shownWidth / ownWidth
    return PagePoint(item.index, (listX - ownLeft) * perListPixel, (listY - item.top) * shownHeight / item.height, perListPixel / zoom)
}

/** Where the point ([x], [y]) of page [page] (points) is in the page area (see pageUnder); null when the page isn't laid out. */
private fun screenOf(page: Int, x: Float, y: Float, document: ReaderDocument, list: LazyListState, zoom: Float, panX: Float, width: Int, margin: Int): Offset? {
    val itemWidth = width - 2 * margin
    if (itemWidth <= 0) return null
    val info = list.layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == page } ?: return null
    val (shownWidth, shownHeight) = document.sizes.getOrNull(page) ?: return null
    val ownWidth = pageWidth(shownWidth, shownHeight, itemWidth)
    val ownLeft = margin + (itemWidth - ownWidth) / 2
    val listX = ownLeft + x * ownWidth / shownWidth
    val listY = item.offset - info.viewportStartOffset + y * item.size / shownHeight
    val (screenX, screenY) = ReaderGeometry.toScreen(listX, listY, zoom, ReaderGeometry.clampPan(panX, zoom, width.toFloat()))
    return Offset(screenX, screenY)
}

/** The centre of the selection's start handle ([startEnd]) or end handle in the page area, [hang] pixels under its end; null when its page isn't laid out. */
private fun handleCentre(selection: ReaderSelection, startEnd: Boolean, document: ReaderDocument, list: LazyListState, zoom: Float, panX: Float, width: Int, margin: Int, hang: Float): Offset? {
    val place = (if (startEnd) selection.start else selection.end) ?: return null
    val glyphs = selection.pages[place.page] ?: return null
    if (place.glyph !in glyphs.glyphs.indices) return null
    val (x, y) = if (startEnd) TextSelection.startHandle(glyphs, place.glyph) else TextSelection.endHandle(glyphs, place.glyph)
    val at = screenOf(place.page, x, y, document, list, zoom, panX, width, margin) ?: return null
    return Offset(at.x, at.y + hang)
}

/** The middle of the glyph at [place] in the page area; null when its page isn't laid out or read. */
private fun glyphCentre(selection: ReaderSelection, place: TextPlace, document: ReaderDocument, list: LazyListState, zoom: Float, panX: Float, width: Int, margin: Int): Offset? {
    val box = selection.pages[place.page]?.glyphs?.getOrNull(place.glyph)?.box ?: return null
    return screenOf(place.page, (box.left + box.right) / 2f, (box.top + box.bottom) / 2f, document, list, zoom, panX, width, margin)
}

/**
 * The rest of a tap that went down as [down]: its lifting, or null when it moved past the touch slop,
 * another finger came, or something else took it (the list scrolling).
 */
private suspend fun AwaitPointerEventScope.awaitTap(down: PointerInputChange): PointerInputChange? {
    while (true) {
        val event = awaitPointerEvent()
        if (event.changes.count { it.pressed } > 1) return null
        val change = event.changes.firstOrNull { it.id == down.id } ?: return null
        if (change.isConsumed) return null
        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) return null
        if (!change.pressed) return change
    }
}

/**
 * Draws the sharp part of each page showing whose picture is coarser than the screen at [zoom], and
 * whose part drawn last no longer serves (ReaderGeometry.serves); forgets the parts of pages no longer
 * showing or no longer needing one. One part at a time; a part being drawn is finished and kept even
 * when the pages move meanwhile (it is drawn where it belongs), then the next round starts.
 */
private suspend fun drawTiles(
    document: ReaderDocument,
    tiles: ReaderTiles,
    list: LazyListState,
    zoom: Float,
    panX: Float,
    margin: Int,
    itemWidth: Int,
    width: Int,
    height: Int,
) {
    if (itemWidth <= 0 || width <= 0 || height <= 0) return
    val pan = ReaderGeometry.clampPan(panX, zoom, width.toFloat())
    val info = list.layoutInfo
    val showing = info.visibleItemsInfo.map { ReaderGeometry.Item(it.index, it.offset - info.viewportStartOffset, it.size) }
    tiles.keepOnly(showing.map { it.index }.toSet())
    for (item in showing) {
        currentCoroutineContext().ensureActive()
        // A page whose picture couldn't be drawn says so, and gets no sharp part.
        if (item.index !in document.sizes.indices || tiles.hasFailed(item.index)) continue
        val (shownWidth, shownHeight) = document.sizes[item.index]
        // The page's own left and width: narrower than the others for a very tall page.
        val ownWidth = pageWidth(shownWidth, shownHeight, itemWidth)
        val ownLeft = margin + (itemWidth - ownWidth) / 2
        // A page needs a sharp part when the screen has more pixels across it than its picture.
        val (pictureWidth, _) = ReaderGeometry.baseSize(shownWidth, shownHeight, itemWidth)
        if (ownWidth * zoom <= 1.05f * pictureWidth) {
            tiles.drop(item.index)
            continue
        }
        val old = tiles[item.index]
        if (old != null && old.pageWidth == ownWidth && ReaderGeometry.serves(old.tile, item, ownLeft.toFloat(), ownWidth.toFloat(), zoom, pan, width.toFloat(), height.toFloat())) continue
        val tile = ReaderGeometry.tile(item, ownLeft.toFloat(), ownWidth.toFloat(), zoom, pan, width.toFloat(), height.toFloat()) ?: continue
        // Pixels to the point: the tile's pixels to the list pixel, and the page's list pixels to the point.
        val scale = tile.scale * ownWidth / shownWidth
        // Finished even when not wanted any more: a bitmap dropped on the way back would never be recycled.
        val bitmap = withContext(NonCancellable) {
            document.part(item.index, scale, tile.pixelLeft, tile.pixelTop, tile.pixelWidth, tile.pixelHeight)
        } ?: continue
        tiles.put(item.index, ReaderTiles.Drawn(tile, bitmap, ownWidth))
    }
}

/**
 * Page [index] of [document], white, in its shape, with its picture once drawn, its sharp part over it,
 * what Annotate added to it ([items]), Find text's [matches] on it marked, the match marked last
 * ([current], its boxes) in another colour, and the text [selected] on it (a box a line, in points).
 * [onCopyPage]: the screen reader's "Copy the text of this page".
 */
@Composable
private fun ReaderPage(
    document: ReaderDocument,
    bitmaps: ReaderBitmaps,
    tiles: ReaderTiles,
    index: Int,
    itemWidth: Int,
    matches: List<RedactBox>,
    current: List<RedactBox>,
    onZoom: (larger: Boolean) -> Boolean,
    items: List<PageItem>,
    selected: List<GlyphBox>,
    onCopyPage: (() -> Unit)?,
) {
    val (shownWidth, shownHeight) = document.sizes[index]
    // What was added, drawn as Annotate draws it, its pictures decoded as the page shows.
    val pictures = rememberItemPictures(items)
    val painter = remember(document, index) { PageItemsPainter() }
    val spoken = stringResource(R.string.reader_page_of_spoken, index + 1, document.pageCount)
    val zoomIn = stringResource(R.string.reader_zoom_in)
    val zoomOut = stringResource(R.string.reader_zoom_out)
    val copyPage = stringResource(R.string.reader_copy_page)
    // The picture this page holds while it is composed; given back when it goes (see ReaderBitmaps).
    var picture by remember(document, index) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(document, index) { mutableStateOf(false) }
    LaunchedEffect(document, index, itemWidth) {
        val (width, height) = ReaderGeometry.baseSize(shownWidth, shownHeight, itemWidth)
        val drawn = bitmaps.take(index, width, height) ?: document.page(index, width, height)
        failed = drawn == null
        tiles.pictureFailed(index, failed)
        if (drawn == null) return@LaunchedEffect
        picture = drawn
        try {
            awaitCancellation()
        } finally {
            // Not drawn any more from here on, before it is given back.
            picture = null
            bitmaps.giveBack(index, drawn)
        }
    }
    val image = remember(picture) { picture?.asImageBitmap() }
    val ownWidth = pageWidth(shownWidth, shownHeight, itemWidth)
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = PageMargin)
            .then(
                if (ownWidth == itemWidth) {
                    Modifier.aspectRatio(shownWidth / shownHeight)
                } else {
                    // In its shape at its width in whole pixels (rounded down, so at most MAX_ITEM_HEIGHT), as
                    // its sharp part is drawn: a box any taller would leave the part short of its end.
                    val ownHeight = pageHeight(shownWidth, shownHeight, ownWidth)
                    Modifier.layout { measurable, constraints ->
                        val placeable = measurable.measure(Constraints.fixed(ownWidth, ownHeight))
                        layout(constraints.maxWidth, ownHeight) { placeable.place((constraints.maxWidth - ownWidth) / 2, 0) }
                    }
                },
            )
            // Pages are white in their own colours, also in the dark theme.
            .background(Color.White)
            .semantics {
                contentDescription = spoken
                // Zooming without a pinch, and copying the page's text without selecting it.
                customActions = listOfNotNull(
                    CustomAccessibilityAction(zoomIn) { onZoom(true) },
                    CustomAccessibilityAction(zoomOut) { onZoom(false) },
                    onCopyPage?.let { copy ->
                        CustomAccessibilityAction(copyPage) {
                            copy()
                            true
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (failed) {
            Text(
                stringResource(R.string.reader_page_failed),
                color = Color.Black,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            Canvas(Modifier.fillMaxSize()) {
                if (image != null) drawImage(image, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
                // The sharp part at its own place, which may lag behind what shows: drawn by floats, so it
                // sits exactly over the picture at any zoom.
                tiles[index]?.takeIf { it.pageWidth == ownWidth }?.let { drawn ->
                    val tile = drawn.tile
                    withTransform({
                        translate(tile.left, tile.top)
                        scale(tile.width / tile.pixelWidth, tile.height / tile.pixelHeight, pivot = Offset.Zero)
                    }) {
                        drawImage(drawn.image)
                    }
                }
                // What was added, in points on the page as shown, cut at the page's edges as the PDF cuts it.
                if (items.isNotEmpty()) {
                    val scale = size.width / shownWidth
                    clipRect {
                        withTransform({ scale(scale, scale, pivot = Offset.Zero) }) {
                            with(painter) { draw(items, pictures) }
                        }
                    }
                }
                // Find text's matches, each box a share of the page as shown.
                for (box in matches) drawRect(MatchColor, Offset(box.left * size.width, box.top * size.height), Size(box.width * size.width, box.height * size.height))
                for (box in current) drawRect(CurrentMatchColor, Offset(box.left * size.width, box.top * size.height), Size(box.width * size.width, box.height * size.height))
                // The text selected, a box a line, in points on the page as shown.
                if (selected.isNotEmpty()) {
                    val scaleX = size.width / shownWidth
                    val scaleY = size.height / shownHeight
                    for (box in selected) drawRect(SelectionColor, Offset(box.left * scaleX, box.top * scaleY), Size((box.right - box.left) * scaleX, (box.bottom - box.top) * scaleY))
                }
            }
        }
    }
}

/**
 * The sharp parts of one PDF's pages, one per page showing at most, drawn when zoomed in (see
 * drawTiles). A part replaced or forgotten, or every part once the PDF no longer shows, is recycled a
 * couple of frames later, as ReaderBitmaps does. Used on the main thread only.
 */
internal class ReaderTiles {
    /**
     * A sharp part: [tile] drawn as [bitmap], for the page laid out [pageWidth] pixels wide. Its
     * rectangle is in that page's pixels: once the page is laid out at another width (the phone
     * turned), it no longer fits and isn't drawn.
     */
    class Drawn(val tile: ReaderGeometry.Tile, val bitmap: Bitmap, val pageWidth: Int) {
        val image: ImageBitmap = bitmap.asImageBitmap()
    }

    private val drawn = mutableStateMapOf<Int, Drawn>()
    private var cleared = false

    /** The pages whose picture couldn't be drawn: they say so instead, and get no sharp part. */
    private val failed = HashSet<Int>()

    /** Page [index]'s sharp part, if it has one; read while drawing, so a new one is drawn at once. */
    operator fun get(index: Int): Drawn? = drawn[index]

    /** [part] is page [index]'s sharp part now; the one before it goes. */
    fun put(index: Int, part: Drawn) {
        if (cleared || index in failed) return recycleLater(part.bitmap)
        drawn.put(index, part)?.let { recycleLater(it.bitmap) }
    }

    /** Whether page [index]'s picture [failed] to be drawn; a page whose picture failed gives up its sharp part. */
    fun pictureFailed(index: Int, failed: Boolean) {
        if (failed) {
            this.failed += index
            drop(index)
        } else {
            this.failed -= index
        }
    }

    /** Whether page [index]'s picture failed to be drawn (pictureFailed). */
    fun hasFailed(index: Int): Boolean = index in failed

    /** Page [index] needs no sharp part. */
    fun drop(index: Int) {
        drawn.remove(index)?.let { recycleLater(it.bitmap) }
    }

    /** Only the pages [showing] keep theirs. */
    fun keepOnly(showing: Set<Int>) {
        for (index in drawn.keys.filter { it !in showing }) drop(index)
    }

    /** The PDF no longer shows: every part goes, and those drawn from now on. */
    fun clear() {
        cleared = true
        drawn.values.forEach { recycleLater(it.bitmap) }
        drawn.clear()
    }
}

/**
 * One finger beside the pages, zoomed out to [zoom], where [list] itself takes no touches: it scrolls
 * the list as far as the finger moves on the screen, and flings it. It scrolls through the list's own
 * scrolling, so a touch on the pages stops its fling, and a touch beside them stops the list's.
 */
private class BesideScroll(private val list: LazyListState, private val zoom: () -> Float) : ScrollableState {
    override val isScrollInProgress: Boolean get() = list.isScrollInProgress
    override val canScrollForward: Boolean get() = list.canScrollForward
    override val canScrollBackward: Boolean get() = list.canScrollBackward

    override fun dispatchRawDelta(delta: Float): Float = inList(delta, list::dispatchRawDelta)

    override suspend fun scroll(scrollPriority: MutatePriority, block: suspend ScrollScope.() -> Unit) {
        list.scroll(scrollPriority) {
            val listScope = this
            object : ScrollScope {
                override fun scrollBy(pixels: Float): Float = inList(pixels, listScope::scrollBy)
            }.block()
        }
    }

    /** [delta] screen pixels scrolled by [scroll] in list pixels; what it took, in screen pixels. */
    private inline fun inList(delta: Float, scroll: (Float) -> Float): Float {
        val scale = zoom()
        return scroll(delta / scale) * scale
    }
}

/**
 * The pictures of one PDF's pages. A page holds its picture while it is composed; given back, the
 * picture waits among at most [WAITING_BYTES] of pictures not showing, so a page scrolled back to
 * shows at once. Pushed out of those, or once the PDF no longer shows, a picture is recycled, a
 * couple of frames later: a picture is never recycled while a page could still be drawing it.
 * Used on the main thread only.
 */
internal class ReaderBitmaps {
    private class Waiting(val index: Int, val bitmap: Bitmap)

    /** The pictures not showing, the one given back last at the end. */
    private val waiting = ArrayList<Waiting>()
    private var waitingBytes = 0L
    private var cleared = false

    /** Page [index]'s picture, [width] × [height], if one waits; it is the caller's until given back. */
    fun take(index: Int, width: Int, height: Int): Bitmap? {
        val at = waiting.indexOfFirst { it.index == index && it.bitmap.width == width && it.bitmap.height == height && !it.bitmap.isRecycled }
        if (at < 0) return null
        val taken = waiting.removeAt(at)
        waitingBytes -= taken.bitmap.allocationByteCount
        return taken.bitmap
    }

    /** Page [index]'s picture, no longer showing. */
    fun giveBack(index: Int, bitmap: Bitmap) {
        if (cleared) return recycleLater(bitmap)
        waiting += Waiting(index, bitmap)
        waitingBytes += bitmap.allocationByteCount
        while (waitingBytes > WAITING_BYTES && waiting.isNotEmpty()) {
            val oldest = waiting.removeAt(0)
            waitingBytes -= oldest.bitmap.allocationByteCount
            recycleLater(oldest.bitmap)
        }
    }

    /** The PDF no longer shows: every picture goes, and those given back from now on. */
    fun clear() {
        cleared = true
        waiting.forEach { recycleLater(it.bitmap) }
        waiting.clear()
        waitingBytes = 0
    }

    private companion object {
        /** About six pictures of a phone's width. */
        const val WAITING_BYTES = 24L * 1024 * 1024
    }
}

/** Recycles [bitmap] two frames from now, when no frame drawn with it can still be on its way to the screen. */
private fun recycleLater(bitmap: Bitmap) {
    val choreographer = Choreographer.getInstance()
    choreographer.postFrameCallback { choreographer.postFrameCallback { bitmap.recycle() } }
}
