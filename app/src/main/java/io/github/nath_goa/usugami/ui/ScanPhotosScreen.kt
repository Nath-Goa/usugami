package io.github.nath_goa.usugami.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PhotoCamera
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.core.LookAdjustments
import io.github.nath_goa.usugami.core.PageLook
import io.github.nath_goa.usugami.data.ImageItem
import io.github.nath_goa.usugami.data.Thumbnails
import io.github.nath_goa.usugami.ui.kit.BarLine
import io.github.nath_goa.usugami.ui.kit.CircularProgress
import io.github.nath_goa.usugami.ui.kit.GlyphButton
import io.github.nath_goa.usugami.ui.kit.PaperGlyphButton
import io.github.nath_goa.usugami.ui.kit.QuietButton
import io.github.nath_goa.usugami.ui.kit.Usugami
import io.github.nath_goa.usugami.ui.kit.UsugamiDialog
import io.github.nath_goa.usugami.ui.kit.UsugamiTopBar
import io.github.nath_goa.usugami.ui.kit.dashedOutline
import io.github.nath_goa.usugami.ui.kit.paper
import io.github.nath_goa.usugami.ui.kit.tile

/**
 * What the Photos screen's buttons do: [takeAnother] opens the camera for the next page, [done]
 * goes on to cropping, [remove] takes a photo (by its id) out of the scan, and [discard] ends the
 * scan without its photos.
 */
class ScanPhotosActions(
    val takeAnother: () -> Unit,
    val done: () -> Unit,
    val remove: (Long) -> Unit,
    val discard: () -> Unit,
)

/**
 * A scan's first stage: the [photos] taken so far, in the order taken, as they were taken, each
 * with its number and a button that removes it, and Take another and Done at the foot. [adding]
 * photos have been taken and are still being added. Leaving with photos asks first whether to
 * discard them. [active] is false while the screen animates away: it then takes no Back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanPhotosScreen(
    photos: List<ImageItem>,
    adding: Int,
    thumbnails: Thumbnails,
    active: Boolean,
    snackbar: SnackbarHostState,
    actions: ScanPhotosActions,
) {
    // While it animates away it keeps showing what it showed: not the photos going as it is left.
    val shown = freezeWhen(!active, photos)
    val coming = freezeWhen(!active, adding)
    val empty = shown.isEmpty() && coming == 0
    var asking by rememberSaveable { mutableStateOf(false) }
    // With nothing to lose, leaving just ends the scan.
    val leave = { if (empty) actions.discard() else asking = true }
    BackHandler(enabled = active, onBack = leave)
    val title = stringResource(R.string.scan_photos_title)
    Scaffold(
        modifier = Modifier.semantics { paneTitle = title },
        topBar = {
            UsugamiTopBar(
                title = { BarTitle(title) },
                navigationIcon = {
                    GlyphButton(onClick = leave) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                line = BarLine.Never,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            PhotosLayout(
                photos = { hint, modifier ->
                    if (empty) {
                        // A photo goes here: the camera is opening, or every photo was taken out. The hint
                        // stays where it is when the first photo comes.
                        // In from the sides as the photos are, so the hint keeps its lines when they come.
                        Column(modifier.padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            hint?.invoke()
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                Box(Modifier.size(88.dp, 112.dp).dashedOutline(), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.PhotoCamera, contentDescription = null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    } else {
                        PhotoGrid(shown, coming, thumbnails, actions.remove, hint, modifier)
                    }
                },
                hint = { wide ->
                    Text(
                        stringResource(R.string.scan_photos_hint),
                        // In lines of about the same length, rather than a last word alone.
                        style = MaterialTheme.typography.bodyMedium.copy(lineBreak = LineBreak.Heading),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = if (wide) TextAlign.Start else TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                },
                buttons = {
                    StepButtons(
                        main = StepButton(R.string.crop_done, Icons.Rounded.Check, enabled = shown.isNotEmpty() && coming == 0) {
                            if (shown.isNotEmpty() && coming == 0) actions.done()
                        },
                        // With no photo yet, the first one.
                        other = StepButton(if (empty) R.string.add_from_camera else R.string.crop_take_another, Icons.Rounded.PhotoCamera) {
                            actions.takeAnother()
                        },
                    )
                },
            )
        }
    }

    if (asking && active) {
        UsugamiDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(R.string.scan_discard_title)) },
            text = { Text(stringResource(R.string.scan_discard_body)) },
            confirmButton = {
                QuietButton(danger = true, onClick = {
                    // Its buttons can still be tapped as it fades out: only the first tap counts.
                    if (asking) {
                        asking = false
                        actions.discard()
                    }
                }) { Text(stringResource(R.string.discard)) }
            },
            dismissButton = { QuietButton(onClick = { asking = false }) { Text(stringResource(R.string.keep)) } },
        )
    }
}

/**
 * The Photos screen's layout: the [photos] as large as there is room for, [buttons] under them; on a
 * phone the [hint] is above the photos and scrolls away with them, on a wide screen it stands beside
 * them, over the buttons.
 */
@Composable
private fun PhotosLayout(
    photos: @Composable (hint: (@Composable () -> Unit)?, modifier: Modifier) -> Unit,
    hint: @Composable (wide: Boolean) -> Unit,
    buttons: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth > maxHeight) {
            Row(Modifier.fillMaxSize()) {
                photos(null, Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.width(SIDE_WIDTH).fillMaxHeight().padding(bottom = 8.dp)) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { hint(true) }
                    buttons()
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                photos({ hint(false) }, Modifier.weight(1f).fillMaxWidth())
                Box(Modifier.padding(bottom = 8.dp)) { buttons() }
            }
        }
    }
}

/**
 * The [photos] in rows, numbered in their order, each with a button that takes it out ([onRemove]),
 * then a place for each of the [coming] ones being added; over them the [hint], if any. A photo that
 * arrives scrolls into view.
 */
@Composable
private fun PhotoGrid(
    photos: List<ImageItem>,
    coming: Int,
    thumbnails: Thumbnails,
    onRemove: (Long) -> Unit,
    hint: (@Composable () -> Unit)?,
    modifier: Modifier,
) {
    val grid = rememberLazyGridState()
    val count = photos.size + coming
    // The newest photo is the last, which may be below the fold when it comes.
    var seen by rememberSaveable { mutableIntStateOf(count) }
    LaunchedEffect(count) {
        if (count > seen) grid.animateScrollToItem(grid.layoutInfo.totalItemsCount - 1)
        seen = count
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = PHOTO_MIN_WIDTH),
        state = grid,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = if (hint != null) 0.dp else 16.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier,
    ) {
        if (hint != null) item(key = "hint", span = { GridItemSpan(maxLineSpan) }) { hint() }
        itemsIndexed(photos, key = { _, photo -> photo.id }) { index, photo ->
            val number = index + 1
            PhotoTile(number, photo, thumbnails, Modifier.animateItem()) {
                PaperGlyphButton(onClick = { onRemove(photo.id) }, modifier = Modifier.align(Alignment.TopEnd)) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.remove_page, number))
                }
            }
        }
        items(coming, key = { "coming $it" }) { index ->
            PhotoTile(photos.size + index + 1, null, thumbnails, Modifier.animateItem()) {
                val label = stringResource(R.string.scan_adding_photo)
                // On the paper: ink purple, which shows on white in both themes.
                CircularProgress(Modifier.align(Alignment.Center).semantics { contentDescription = label }, color = Usugami.colors.paperInk)
            }
        }
    }
}

/**
 * A photo's tile: [photo] as it was taken (white paper while it is being added, when null), with its
 * [number] over its top corner, and [over] it the tile's button or ring.
 */
@Composable
private fun PhotoTile(number: Int, photo: ImageItem?, thumbnails: Thumbnails, modifier: Modifier, over: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxWidth().aspectRatio(TILE_ASPECT).tile(selected = false).padding(8.dp)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (photo == null) {
                Box(Modifier.aspectRatio(TILE_ASPECT).paper())
            } else {
                val taken = remember(photo) { asTaken(photo) }
                TakenPhoto(taken, thumbnails, Modifier.aspectRatio(taken.shownWidth.toFloat() / taken.shownHeight.coerceAtLeast(1)))
            }
        }
        PageNumberBadge(number, Modifier.align(Alignment.TopStart))
        over()
    }
}

/** [photo] (as taken: see [asTaken]) on white paper, filling [modifier]'s box. */
@Composable
private fun TakenPhoto(photo: ImageItem, thumbnails: Thumbnails, modifier: Modifier) {
    val thumbnail = rememberThumbnail(photo, thumbnails)
    val placeholder = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier.paper()) {
        drawRect(Color.White)
        drawPageImage(thumbnail.image, thumbnail.alpha, 0, placeholder, 0f, 0f, size.width, size.height)
    }
}

/**
 * [item] as it was taken: the photo itself (a page already cropped from it shows whole again), upright,
 * neither turned nor in a look. The Photos screen shows a scan's photos so, before they are cropped.
 */
private fun asTaken(item: ImageItem): ImageItem {
    val source = item.source
    return item.copy(
        path = source.path,
        format = source.format,
        width = source.width,
        height = source.height,
        exifOrientation = source.exifOrientation,
        rotation = 0,
        original = null,
        look = PageLook.ORIGINAL,
        adjustments = LookAdjustments.NONE,
    )
}

/** The narrowest a photo's tile gets: three in a row on a phone, also a narrow one. */
private val PHOTO_MIN_WIDTH = 96.dp

/** How wide the column beside the photos is on a wide screen, as on the crop screen. */
private val SIDE_WIDTH = 320.dp

/** A tile's width to its height: a photo held upright, as pages are taken. */
private const val TILE_ASPECT = 3f / 4f
