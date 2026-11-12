package io.github.nath_goa.usugami.ui

import android.graphics.Bitmap
import android.view.Choreographer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.core.ReaderGeometry
import io.github.nath_goa.usugami.data.ReaderDocument
import kotlinx.coroutines.awaitCancellation

/** The margin beside each page, and the gap between two. */
private val PageMargin = 8.dp

/**
 * The tallest a page item is laid out, in pixels: a layout can't measure much over 260,000. Only a
 * page over about 200 times as tall as it is wide comes near it; it is shown that tall, in its shape,
 * narrower than the other pages and centred.
 */
private const val MAX_ITEM_HEIGHT = 200_000

/** Room kept under the last page for the page indicator, which floats over the pages. */
internal val ReaderIndicatorRoom = 56.dp

/**
 * The reader's pages ([session]'s PDF), one under another in one scroll ([list]), each as wide as
 * the screen but for its margins. Only the pages near the screen are composed and drawn (see
 * [ReaderBitmaps]). The list is laid out in a layer, so that zooming (a later step) scales it as a
 * whole; here the layer is at zoom 1.
 */
@Composable
internal fun ReaderPages(
    session: ReaderSession,
    list: LazyListState,
    modifier: Modifier = Modifier,
) {
    val document = session.document
    // The pictures of this PDF's pages: all recycled once it no longer shows.
    val bitmaps = remember(document) { ReaderBitmaps() }
    DisposableEffect(bitmaps) { onDispose { bitmaps.clear() } }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val itemWidth = width - 2 * with(LocalDensity.current) { PageMargin.roundToPx() }
        LazyColumn(
            state = list,
            // The last page can be scrolled clear of the navigation bar and the page indicator.
            contentPadding = PaddingValues(top = PageMargin, bottom = PageMargin + bottom + ReaderIndicatorRoom),
            verticalArrangement = Arrangement.spacedBy(PageMargin),
            modifier = Modifier.layout { measurable, _ ->
                val placeable = measurable.measure(Constraints.fixed(width, height))
                layout(width, height) {
                    placeable.placeWithLayer(IntOffset.Zero) {
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = 1f
                        scaleY = 1f
                        translationX = 0f
                    }
                }
            },
        ) {
            items(document.pageCount, key = { it }) { index ->
                ReaderPage(document, bitmaps, index, itemWidth)
            }
        }
    }
}

/** Page [index] of [document], white, in its shape, with its picture once drawn. */
@Composable
private fun ReaderPage(document: ReaderDocument, bitmaps: ReaderBitmaps, index: Int, itemWidth: Int) {
    val (shownWidth, shownHeight) = document.sizes[index]
    val spoken = stringResource(R.string.reader_page_of_spoken, index + 1, document.pageCount)
    // The picture this page holds while it is composed; given back when it goes (see ReaderBitmaps).
    var picture by remember(document, index) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(document, index) { mutableStateOf(false) }
    LaunchedEffect(document, index, itemWidth) {
        val (width, height) = ReaderGeometry.baseSize(shownWidth, shownHeight, itemWidth)
        val drawn = bitmaps.take(index, width, height) ?: document.page(index, width, height)
        failed = drawn == null
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
    val shape = shownWidth / shownHeight
    // The width of a page too tall to be laid out as wide as the others (see MAX_ITEM_HEIGHT); null for
    // every other page. Whole pixels, so that its height stays within what a layout can measure.
    val narrowWidth = if (itemWidth / shape > MAX_ITEM_HEIGHT) maxOf(1, (MAX_ITEM_HEIGHT * shape).toInt()) else null
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = PageMargin)
            .then(
                if (narrowWidth == null) {
                    Modifier.aspectRatio(shape)
                } else {
                    Modifier.layout { measurable, constraints ->
                        val placeable = measurable.measure(Constraints.fixed(narrowWidth, MAX_ITEM_HEIGHT))
                        layout(constraints.maxWidth, MAX_ITEM_HEIGHT) { placeable.place((constraints.maxWidth - narrowWidth) / 2, 0) }
                    }
                },
            )
            // Pages are white in their own colours, also in the dark theme.
            .background(Color.White)
            .semantics { contentDescription = spoken },
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Canvas(Modifier.fillMaxSize()) {
                drawImage(image, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
            }
        } else if (failed) {
            Text(
                stringResource(R.string.reader_page_failed),
                color = Color.Black,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(16.dp),
            )
        }
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

        /** Recycles [bitmap] two frames from now, when no frame drawn with it can still be on its way to the screen. */
        fun recycleLater(bitmap: Bitmap) {
            val choreographer = Choreographer.getInstance()
            choreographer.postFrameCallback { choreographer.postFrameCallback { bitmap.recycle() } }
        }
    }
}
