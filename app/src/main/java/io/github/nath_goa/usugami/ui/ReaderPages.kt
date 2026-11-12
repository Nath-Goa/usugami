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
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
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
import io.github.nath_goa.usugami.core.ReaderGeometry
import io.github.nath_goa.usugami.core.RedactBox
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
 * pages, and the pages move to show the match marked whenever another one is.
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

    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .background(MaterialTheme.colorScheme.surfaceContainer)
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
                            if (zoomNow > 1f && dx != 0f) {
                                val pan = ReaderGeometry.clampPan(currentPan(), zoomNow, size.width.toFloat())
                                currentOnView(zoomNow, ReaderGeometry.clampPan(pan - dx, zoomNow, size.width.toFloat()))
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    // Let go near the screen's width: exactly it.
                    if (pinched && focus.isSpecified && abs(currentZoom() - 1f) < 0.05f) zoomTo(1f, focus.x, focus.y, size.width.toFloat())
                }
            }
            .pointerInput(list) {
                awaitEachGesture {
                    // Two taps close together, neither moving: the spot of the second.
                    val first = awaitFirstDown(requireUnconsumed = false)
                    awaitTap(first) ?: return@awaitEachGesture
                    val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) { awaitFirstDown() } ?: return@awaitEachGesture
                    if ((second.position - first.position).getDistance() > viewConfiguration.touchSlop * 4) return@awaitEachGesture
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
        // The matches by page.
        val marks = remember(found.matches) { found.matches.withIndex().groupBy { it.value.first().page } }

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
                )
            }
        }
    }
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
 * and Find text's [matches] on it marked, the match marked last ([current], its boxes) in another colour.
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
) {
    val (shownWidth, shownHeight) = document.sizes[index]
    val spoken = stringResource(R.string.reader_page_of_spoken, index + 1, document.pageCount)
    val zoomIn = stringResource(R.string.reader_zoom_in)
    val zoomOut = stringResource(R.string.reader_zoom_out)
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
                // Zooming without a pinch.
                customActions = listOf(
                    CustomAccessibilityAction(zoomIn) { onZoom(true) },
                    CustomAccessibilityAction(zoomOut) { onZoom(false) },
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
                // Find text's matches, each box a share of the page as shown.
                for (box in matches) drawRect(MatchColor, Offset(box.left * size.width, box.top * size.height), Size(box.width * size.width, box.height * size.height))
                for (box in current) drawRect(CurrentMatchColor, Offset(box.left * size.width, box.top * size.height), Size(box.width * size.width, box.height * size.height))
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
