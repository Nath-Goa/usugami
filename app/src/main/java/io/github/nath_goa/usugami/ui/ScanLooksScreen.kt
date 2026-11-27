package io.github.nath_goa.usugami.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.core.CropQuad
import io.github.nath_goa.usugami.core.LookAdjustments
import io.github.nath_goa.usugami.core.PageLook
import io.github.nath_goa.usugami.data.ImageItem
import io.github.nath_goa.usugami.data.OriginalImage
import io.github.nath_goa.usugami.data.PageCropper
import io.github.nath_goa.usugami.data.Thumbnails
import io.github.nath_goa.usugami.ui.kit.BarLine
import io.github.nath_goa.usugami.ui.kit.CircularProgress
import io.github.nath_goa.usugami.ui.kit.GlyphButton
import io.github.nath_goa.usugami.ui.kit.PressIndication
import io.github.nath_goa.usugami.ui.kit.SwitchRow
import io.github.nath_goa.usugami.ui.kit.TileShape
import io.github.nath_goa.usugami.ui.kit.Usugami
import io.github.nath_goa.usugami.ui.kit.UsugamiTopBar
import io.github.nath_goa.usugami.ui.kit.fadingEdges
import io.github.nath_goa.usugami.ui.kit.paper
import io.github.nath_goa.usugami.ui.kit.tile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What the Filters screen does: [select] shows a page (by its id) large, [look] and [adjust] change
 * the page shown, or every page of the scan while the same filter is kept for all, [sameForAll]
 * turns that on or off, [save] ends the scan, and [back] goes back to cropping.
 */
class ScanLooksActions(
    val select: (Long) -> Unit,
    val look: (PageLook) -> Unit,
    val adjust: (LookAdjustments) -> Unit,
    val sameForAll: (Boolean) -> Unit,
    val save: () -> Unit,
    val back: () -> Unit,
)

/**
 * A scan's last stage: the page [selected] among the scan's [pages] (in order; the first when it
 * isn't among them) large, in its look; under it all the pages in a row, to choose from; under
 * that the looks, each shown on that page, its brightness and contrast, and whether the same filter
 * goes to every page ([sameForAll]; not offered for one page); Save at the foot. A page in
 * [cropping] is still being made. [active] is false while the screen animates away: it then takes
 * no Back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanLooksScreen(
    pages: List<ImageItem>,
    selected: Long,
    sameForAll: Boolean,
    cropping: Set<Long>,
    thumbnails: Thumbnails,
    active: Boolean,
    snackbar: SnackbarHostState,
    actions: ScanLooksActions,
) {
    // While it animates away it keeps showing what it showed.
    val shownPages = freezeWhen(!active, pages)
    val shownSelected = freezeWhen(!active, selected)
    val page = shownPages.firstOrNull { it.id == shownSelected } ?: shownPages.firstOrNull()
    BackHandler(enabled = active, onBack = actions.back)
    val title = stringResource(R.string.filter_title)
    // Which page is shown large, as the crop screen says which image it shows.
    val place = page?.takeIf { shownPages.size > 1 }?.let { stringResource(R.string.scan_place, shownPages.indexOf(it) + 1, shownPages.size) }
    Scaffold(
        modifier = Modifier.semantics { paneTitle = title },
        topBar = {
            UsugamiTopBar(
                title = { StageTitle(title, place) },
                navigationIcon = {
                    GlyphButton(onClick = actions.back) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                line = BarLine.Never,
                subtitle = true,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            if (page != null) {
                PageFilters(
                    page = page,
                    pages = shownPages,
                    sameForAll = freezeWhen(!active, sameForAll),
                    cropping = freezeWhen(!active, cropping),
                    thumbnails = thumbnails,
                    actions = actions,
                )
            }
        }
    }
}

/** The Filters screen's content for [page], the one shown large among the scan's [pages]. */
@Composable
private fun PageFilters(
    page: ImageItem,
    pages: List<ImageItem>,
    sameForAll: Boolean,
    cropping: Set<Long>,
    thumbnails: Thumbnails,
    actions: ScanLooksActions,
) {
    val number = pages.indexOf(page) + 1
    val beingCropped = page.id in cropping
    // A slider held, or let go but not yet the page's: what it shows meanwhile, and what the large page
    // is made with. Every page of the scan gets it as it is let go, not at each step of the finger.
    var held by remember(page.id) { mutableStateOf<LookAdjustments?>(null) }
    LaunchedEffect(page.adjustments) { if (held == page.adjustments) held = null }
    val adjustments = held ?: page.adjustments
    fun hold(change: (LookAdjustments) -> LookAdjustments) {
        held = change(held ?: page.adjustments)
    }
    fun letGo() {
        val chosen = held ?: return
        if (chosen == page.adjustments) held = null else actions.adjust(chosen)
    }

    // The page read from its file, upright, for the large page: one page's at a time. A page still
    // being cropped is the photo it is cropped from until then, which is not shown large.
    val large by produceState<LargePage?>(null, page.path, beingCropped) {
        if (value?.path != page.path) value = null
        if (beingCropped || value != null) return@produceState
        value = withContext(Dispatchers.IO) { LargePage(page.path, pageAt(page, PAGE_MAX)) }
    }
    val base = large?.takeIf { it.path == page.path }?.bitmap
    val preview by rememberLookPreview(base, page.look, adjustments)
    // Each look on the page, made from its plain thumbnail: the size the looks' small pages are made
    // at (see lookSamples), and one the pages row has most likely read already.
    val samples by produceState<PageSamples?>(null, page.path, beingCropped) {
        if (value?.path != page.path) value = null
        if (beingCropped || value != null) return@produceState
        val plain = thumbnails.load(page.copy(look = PageLook.ORIGINAL, adjustments = LookAdjustments.NONE)) ?: return@produceState
        lookSamples(plain) { value = PageSamples(page.path, it) }
    }
    // Shown at once while the large page is read: the page's thumbnail, in its look.
    val thumbnail = key(page.id) { rememberThumbnail(page, thumbnails) }

    FiltersLayout(
        area = { modifier ->
            if (beingCropped) {
                CroppingPage(number, modifier)
            } else {
                val shown = preview?.takeIf { base != null && it.base === base }
                val made = remember(shown?.image) { shown?.image?.asImageBitmap() }
                val busy = shown == null || shown.look != page.look || shown.adjustments != adjustments
                LookedPage(made ?: thumbnail.image, page.rotation, busy, modifier)
            }
        },
        strip = {
            if (pages.size > 1) PagesRow(pages, page.id, cropping, thumbnails, actions.select)
        },
        controls = {
            // No heading over the looks, as the crop screen's look step has: their names say what they
            // are, and the page keeps the room.
            Spacer(Modifier.height(4.dp))
            val images = samples?.takeIf { it.path == page.path }?.images.orEmpty()
            // The look chosen comes into view again for each page shown.
            LooksRow(images, page.rotation, page.look, actions.look, reveal = page.id)
            Sliders(
                adjustments,
                onBrightness = { value -> hold { it.copy(brightness = value) } },
                onContrast = { value -> hold { it.copy(contrast = value) } },
                onRest = ::letGo,
            )
            if (pages.size > 1) SwitchRow(stringResource(R.string.scan_same_look), sameForAll, actions.sameForAll)
        },
        buttons = { StepButtons(StepButton(R.string.save, Icons.Rounded.Check, onClick = actions.save)) },
    )
}

/**
 * Brightness and contrast, each on one line with its name before it while the names take little of
 * the width (with the usual text size), otherwise each name over its slider.
 */
@Composable
private fun Sliders(adjustments: LookAdjustments, onBrightness: (Int) -> Unit, onContrast: (Int) -> Unit, onRest: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val names = listOf(stringResource(R.string.filter_brightness), stringResource(R.string.filter_contrast))
        val style = MaterialTheme.typography.labelLarge
        val measurer = rememberTextMeasurer()
        val widest = with(LocalDensity.current) { names.maxOf { measurer.measure(it, style).size.width }.toDp() }
        val labelWidth = (widest + LABEL_GAP).takeIf { it <= maxWidth * LABEL_SHARE }
        Column {
            AdjustmentSlider(R.string.filter_brightness, R.string.filter_reset_brightness, adjustments.brightness, onBrightness, onRest, labelWidth)
            AdjustmentSlider(R.string.filter_contrast, R.string.filter_reset_contrast, adjustments.contrast, onContrast, onRest, labelWidth)
        }
    }
}

/**
 * The Filters screen's layout: the page large ([area]) with the pages row ([strip]) under it, then
 * the [controls] and the [buttons]; on a wide screen the controls and buttons beside them. The page
 * keeps at least two fifths of the height: past that the controls scroll, the buttons stay in sight.
 */
@Composable
private fun FiltersLayout(
    area: @Composable (Modifier) -> Unit,
    strip: @Composable () -> Unit,
    controls: @Composable ColumnScope.() -> Unit,
    buttons: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val controlsHeight = maxHeight * CONTROLS_SHARE
        if (maxWidth > maxHeight) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxHeight().padding(bottom = 8.dp)) {
                    area(Modifier.weight(1f).fillMaxWidth())
                    strip()
                }
                Column(Modifier.width(SIDE_WIDTH).fillMaxHeight().padding(bottom = 8.dp)) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), content = controls)
                    buttons()
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                area(Modifier.weight(1f).fillMaxWidth())
                Column(Modifier.heightIn(max = controlsHeight).padding(bottom = 8.dp)) {
                    strip()
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), content = controls)
                    buttons()
                }
            }
        }
    }
}

/**
 * The scan's pages in a row that scrolls sideways, each a radio button named after its number,
 * [selected] the one shown large; it comes into view when chosen. A page being cropped ([cropping])
 * shows a ring, and a screen reader hears that it is being cropped.
 */
@Composable
private fun PagesRow(pages: List<ImageItem>, selected: Long, cropping: Set<Long>, thumbnails: Thumbnails, onSelect: (Long) -> Unit) {
    val at = pages.indexOfFirst { it.id == selected }.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (at - 1).coerceAtLeast(0))
    val margin = with(LocalDensity.current) { ROW_MARGIN.roundToPx() }
    // The page chosen comes whole into view, clear of the faded edges, unless it is there already.
    LaunchedEffect(at) {
        val shown = state.layoutInfo
        val tile = shown.visibleItemsInfo.firstOrNull { it.index == at }
        val inView = tile != null && tile.offset >= shown.viewportStartOffset + margin && tile.offset + tile.size <= shown.viewportEndOffset - margin
        if (!inView) state.animateScrollToItem(at, -margin)
    }
    LazyRow(
        state = state,
        modifier = Modifier.fillMaxWidth().fadingEdges(state).selectableGroup(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(pages, key = { _, page -> page.id }) { index, page ->
            PageChoice(index + 1, page, page.id == selected, page.id in cropping, thumbnails) { onSelect(page.id) }
        }
    }
}

/**
 * A page of the pages row, like a radio button: the page in its look and turn on a tile [PAGE_TILE]
 * tall, its [number] over its corner, marked when it is the one [selected], with a ring over it while
 * it is [beingCropped].
 */
@Composable
private fun PageChoice(number: Int, page: ImageItem, selected: Boolean, beingCropped: Boolean, thumbnails: Thumbnails, onClick: () -> Unit) {
    val label = stringResource(if (beingCropped) R.string.cropping_page else R.string.pdf_page_number, number)
    // A page being cropped is still its photo: it takes a page's shape until it is made.
    val aspect = if (beingCropped) PAGE_ASPECT else page.shownWidth.toFloat() / page.shownHeight.coerceAtLeast(1)
    // As wide as the page is on it, within bounds: a page on its side shows wider.
    val width = (PAGE_INSET * 2 + (PAGE_TILE - PAGE_INSET * 2) * aspect).coerceIn(PAGE_TILE_NARROWEST, PAGE_TILE_WIDEST)
    Box(
        Modifier
            .size(width, PAGE_TILE)
            .tile(selected)
            .selectable(
                selected = selected,
                interactionSource = null,
                indication = PressIndication.shaped(TileShape, focusInset = 2.dp),
                role = Role.RadioButton,
                onClick = onClick,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (beingCropped) {
            // Until then it is the photo, which isn't shown: blank paper, with the ring in ink purple,
            // which shows on white in both themes.
            Box(Modifier.padding(PAGE_INSET).fillMaxSize().paper(), contentAlignment = Alignment.Center) {
                CircularProgress(Modifier.size(20.dp), strokeWidth = 2.dp, color = Usugami.colors.paperInk)
            }
        } else {
            PageThumbnail(page, thumbnails, Modifier.padding(PAGE_INSET).aspectRatio(aspect))
        }
        // In the tile's corner, rounded with it: 3 dp in from its 12 dp corner, a pill 18 dp tall (12 - 3 = 9).
        Text(
            "$number",
            style = Usugami.type.tabular(MaterialTheme.typography.labelMedium).withEmLineHeight(),
            color = Usugami.colors.onPill,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(3.dp)
                .clearAndSetSemantics {}
                .background(Usugami.colors.pill, CircleShape)
                .heightIn(min = 18.dp)
                .padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

/** [page]'s thumbnail, in its look and turned as it is, on white paper filling [modifier]'s box. */
@Composable
private fun PageThumbnail(page: ImageItem, thumbnails: Thumbnails, modifier: Modifier) {
    val thumbnail = rememberThumbnail(page, thumbnails)
    val placeholder = MaterialTheme.colorScheme.surfaceVariant
    val rotation = page.rotation
    Canvas(modifier.paper()) {
        drawRect(Color.White)
        drawPageImage(thumbnail.image, thumbnail.alpha, rotation, placeholder, 0f, 0f, size.width, size.height)
    }
}

/** The large page's place while page [number] is still being cropped: a ring, and a line that says so. */
@Composable
private fun CroppingPage(number: Int, modifier: Modifier) {
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgress()
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.cropping_page, number),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/** The bar's title, and under it [place]: which page is shown large. */
@Composable
private fun StageTitle(title: String, place: String?) {
    Column {
        BarTitle(title)
        if (place != null) {
            Text(
                place,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** [page]'s file upright, at most [side] pixels on its long side; null when it can't be read. */
private fun pageAt(page: ImageItem, side: Int): Bitmap? = try {
    PageCropper.upright(OriginalImage(page.path, page.format, page.width, page.height, page.exifOrientation, CropQuad.WHOLE), 0, side)
} catch (e: Exception) {
    null
}

/** The page in the file at [path], upright, for the large page: null when it couldn't be read. */
private class LargePage(val path: String, val bitmap: Bitmap?)

/** Each look made so far on the page in the file at [path]. */
private class PageSamples(val path: String, val images: Map<PageLook, ImageBitmap>)

/** How tall a page's tile is in the pages row. */
private val PAGE_TILE = 64.dp

/** A page's width to its height while it isn't made yet: A4's. */
private const val PAGE_ASPECT = 0.707f

/** How far in from the pages row's ends the page chosen is brought: clear of the faded edges. */
private val ROW_MARGIN = 48.dp

/** Between a tile's edge and its page. */
private val PAGE_INSET = 6.dp

/** The narrowest and widest a page's tile gets, for a tall page and one on its side. */
private val PAGE_TILE_NARROWEST: Dp = 44.dp
private val PAGE_TILE_WIDEST: Dp = 96.dp

/** The controls, the pages row and the buttons together take at most this much of the height. */
private const val CONTROLS_SHARE = 0.6f

/** Between a slider's name and the slider, when they share a line. */
private val LABEL_GAP = 12.dp

/** The most of the width the sliders' names take when they share their sliders' lines. */
private const val LABEL_SHARE = 0.3f

/** How wide the column of controls is beside the page on a wide screen, as on the crop screen. */
private val SIDE_WIDTH = 320.dp
