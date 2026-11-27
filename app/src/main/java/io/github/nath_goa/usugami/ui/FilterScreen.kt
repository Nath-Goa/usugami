package io.github.nath_goa.usugami.ui

import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.scale
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.core.CropQuad
import io.github.nath_goa.usugami.core.LookAdjustments
import io.github.nath_goa.usugami.core.PageLook
import io.github.nath_goa.usugami.data.PageLooks
import io.github.nath_goa.usugami.ui.kit.CircularProgress
import io.github.nath_goa.usugami.ui.kit.GlyphButton
import io.github.nath_goa.usugami.ui.kit.Seal
import io.github.nath_goa.usugami.ui.kit.SectionHeading
import io.github.nath_goa.usugami.ui.kit.Usugami
import io.github.nath_goa.usugami.ui.kit.UsugamiSlider
import io.github.nath_goa.usugami.ui.kit.fadingEdges
import io.github.nath_goa.usugami.ui.kit.rememberHaptics
import io.github.nath_goa.usugami.ui.kit.tile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The crop screen's second step: the page straightened out of [photo] (the image upright) inside
 * [corners] (on it, in a page's order) and turned [rotation] degrees, large, with its [look] and
 * [adjustments], as it will be in the PDF; under it the looks, each shown on the page itself, its
 * brightness and contrast, Apply to all pages ([allPages], null with no other page) and [buttons],
 * which finish the page. The large page is made again in the background as the choices change, once
 * a slider rests; the looks' small pages are made once for the corners.
 */
@Composable
internal fun FilterStep(
    photo: Bitmap?,
    corners: CropQuad,
    rotation: Int,
    look: PageLook,
    adjustments: LookAdjustments,
    allPages: Boolean?,
    onLook: (PageLook) -> Unit,
    onBrightness: (Int) -> Unit,
    onContrast: (Int) -> Unit,
    onAllPages: (Boolean) -> Unit,
    buttons: @Composable () -> Unit,
) {
    // The page without its look, straightened once for the corners. (Turned and back, they may be
    // a hair from where they were: that is the same page.)
    val page by produceState<StraightPage?>(null, photo, corners) {
        val shown = photo ?: return@produceState
        if (value?.corners?.isCloseTo(corners) == true) return@produceState
        value = withContext(Dispatchers.Default) { StraightPage(corners, straightened(shown, corners, PAGE_MAX)) }
    }
    // The page with its look and adjustments, made again once they have stayed put for a moment.
    val preview by rememberLookPreview(page?.bitmap, look, adjustments)
    // Each look on a small copy of the page, shown as each is made. Each look is made on the page at
    // the size the editor shows pages at, and then made small, so it looks as it does on the page.
    val samples by produceState<LookSamples?>(null, photo, corners) {
        val shown = photo ?: return@produceState
        if (value?.corners?.isCloseTo(corners) == true) return@produceState
        val plain = withContext(Dispatchers.Default) { straightened(shown, corners, SAMPLE_LOOK_MAX) } ?: return@produceState
        try {
            lookSamples(plain) { value = LookSamples(corners, it) }
        } finally {
            // Once no look is being made from it, unless it is small enough to be Original's own (see small).
            if (max(plain.width, plain.height) > SAMPLE_MAX) plain.recycle()
        }
    }
    val base = page?.takeIf { it.corners.isCloseTo(corners) }
    val shown = preview?.takeIf { base != null && it.base === base.bitmap }
    val images = samples?.takeIf { it.corners.isCloseTo(corners) }?.images.orEmpty()

    StepLayout(
        hint = null,
        area = { modifier ->
            val image = shown?.image ?: base?.bitmap
            // Being made: the page for the corners, or its look for the choices.
            val busy = base == null || shown == null || shown.look != look || shown.adjustments != adjustments
            LookedPage(remember(image) { image?.asImageBitmap() }, rotation, busy && (base == null || base.bitmap != null), modifier)
        },
        controls = {
            // The controls show whole above the buttons on a phone at the usual text size, as in 2.0.1:
            // the gaps between them are no larger than that leaves room for. The heading stands 8 + its own 8
            // from the edge, in line with the labels under it.
            SectionHeading(stringResource(R.string.look_title), Modifier.padding(horizontal = 8.dp), top = 2.dp, bottom = 4.dp)
            LooksRow(images, rotation, look, onLook)
            AdjustmentSlider(R.string.filter_brightness, R.string.filter_reset_brightness, adjustments.brightness, onBrightness)
            AdjustmentSlider(R.string.filter_contrast, R.string.filter_reset_contrast, adjustments.contrast, onContrast)
            if (allPages != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(value = allPages, role = Role.Checkbox, onValueChange = onAllPages)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = allPages, onCheckedChange = null)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.look_all_pages), style = MaterialTheme.typography.bodyLarge)
                }
            }
        },
        buttons = buttons,
    )
}

/**
 * The page large, as it will be in the PDF: [image] (the page upright, null while it is made) turned
 * [rotation] degrees, as large as fits with 16 dp round it, and while it is [busy] a ring: in the
 * middle while there is no page yet, over its corner while it is made again.
 */
@Composable
internal fun LookedPage(image: ImageBitmap?, rotation: Int, busy: Boolean, modifier: Modifier) {
    Box(modifier.padding(16.dp), contentAlignment = Alignment.Center) {
        val description = stringResource(R.string.filter_preview_description)
        if (image != null) TurnedImage(image, rotation, Modifier.fillMaxSize().semantics { contentDescription = description })
        if (busy) {
            // Over the preview it is ink purple, which shows on the page in both themes.
            CircularProgress(
                if (image == null) Modifier else Modifier.align(Alignment.TopEnd).size(24.dp),
                color = if (image == null) MaterialTheme.colorScheme.primary else Usugami.colors.paperInk,
            )
        }
    }
}

/**
 * The looks in a row that scrolls sideways, each shown on the page ([images], turned [rotation]
 * degrees), [look] the one chosen. The one chosen scrolls into view as the row comes, as it is chosen,
 * and again each time [reveal] changes (another page shown).
 */
@Composable
internal fun LooksRow(images: Map<PageLook, ImageBitmap>, rotation: Int, look: PageLook, onLook: (PageLook) -> Unit, reveal: Any? = Unit) {
    // Wider with larger text, so that a name breaks between its words, not in them.
    val width = SAMPLE_WIDTH * LocalDensity.current.fontScale.coerceAtLeast(1f)
    // The names at one size, smaller where a word is wider than its tile (German "Tintensparend"),
    // rather than broken: the size the name that needs it most takes in bold, as when it is
    // chosen, so the names stay alike and none changes size when it is tapped.
    val names = PageLook.shown.map { stringResource(lookName(it)) }
    val style = MaterialTheme.typography.labelMedium
    val nameSize = wholeWordsFontSize(names, style.copy(fontWeight = FontWeight.Bold), width - 8.dp)
    val nameStyle = style.withEmLineHeight().copy(fontSize = nameSize)
    val scroll = rememberScrollState()
    Row(
        Modifier
            .fillMaxWidth()
            .fadingEdges(scroll)
            .horizontalScroll(scroll)
            .padding(horizontal = 12.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (option in PageLook.shown) LookChoice(option, images[option], rotation, option == look, width, nameStyle, reveal) { onLook(option) }
    }
}

/**
 * [base] (a page, upright) with [look] and [adjustments], made in the background: at once for a new
 * page, and once the choices have stayed put for a moment when only they change. The page as it is
 * when it can't be made (no memory for it); null while there is no page, and none made from a page
 * that has gone is kept, so that only one page's large pictures are held at a time.
 */
@Composable
internal fun rememberLookPreview(base: Bitmap?, look: PageLook, adjustments: LookAdjustments): State<LookPreview?> =
    produceState<LookPreview?>(null, base, look, adjustments) {
        // One made from another page goes as soon as that page has.
        if (value?.base !== base) value = null
        if (base == null) return@produceState
        if (value?.let { it.look == look && it.adjustments == adjustments } == true) return@produceState
        if (look == PageLook.ORIGINAL && adjustments.isNone) {
            value = LookPreview(base, look, adjustments, base)
            return@produceState
        }
        // Only the choices changed: once they have stayed put.
        if (value != null) delay(LOOK_DELAY_MS)
        val job = coroutineContext.job
        val made = withContext(Dispatchers.Default) {
            try {
                PageLooks.applied(base, look, adjustments) { job.ensureActive() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }
        }
        // Without memory for it, the page shows as it is.
        value = LookPreview(base, look, adjustments, made ?: base)
    }

/**
 * Each look on [plain] (a page the size the editor shows pages at: see [SAMPLE_LOOK_MAX]), made
 * small, handed to [onMade] with those made before as each one is made. [plain] is left as it is.
 */
internal suspend fun lookSamples(plain: Bitmap, onMade: (Map<PageLook, ImageBitmap>) -> Unit) {
    val job = currentCoroutineContext().job
    var made = emptyMap<PageLook, ImageBitmap>()
    for (option in PageLook.shown) {
        val sample = withContext(Dispatchers.Default) {
            try {
                val look = if (option == PageLook.ORIGINAL) plain else PageLooks.applied(plain, option) { job.ensureActive() }
                // The look at full size goes once it is made small; the plain page stays for the next.
                small(look).also { if (look !== plain && it !== look) look.recycle() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }
        }
        if (sample != null) {
            made = made + (option to sample.asImageBitmap())
            onMade(made)
        }
    }
}

/**
 * One of the looks, like a radio button: the page in it ([image], turned [rotation] degrees, once it
 * is made) over its name, in [nameStyle], marked when it is the one chosen. It is [width] wide, its
 * name 8 dp narrower. The one chosen scrolls into view, clear of the row's faded edges, and again
 * when [reveal] changes.
 */
@Composable
internal fun LookChoice(
    look: PageLook,
    image: ImageBitmap?,
    rotation: Int,
    selected: Boolean,
    width: Dp,
    nameStyle: TextStyle,
    reveal: Any? = Unit,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    val bringIntoView = remember { BringIntoViewRequester() }
    // Whole, and clear of the row's faded edges, so that one tapped at an edge shows whole once chosen.
    val margin = with(LocalDensity.current) { LOOK_MARGIN.toPx() }
    val size = remember { IntArray(2) }
    LaunchedEffect(reveal, selected) {
        if (!selected) return@LaunchedEffect
        // Not laid out yet as the row first comes: its size is known once a frame has been drawn.
        if (size[1] == 0) withFrameNanos {}
        bringIntoView.bringIntoView(Rect(-margin, 0f, size[0] + margin, size[1].toFloat()))
    }
    Column(
        Modifier
            .width(width)
            .onSizeChanged {
                size[0] = it.width
                size[1] = it.height
            }
            .bringIntoViewRequester(bringIntoView)
            .clip(shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(SAMPLE_WIDTH - 8.dp).tile(selected)) {
            if (image != null) TurnedImage(image, rotation, Modifier.fillMaxSize().padding(6.dp))
            if (selected) Seal(Modifier.align(Alignment.TopEnd).padding(4.dp), size = 20.dp)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(lookName(look)),
            style = nameStyle,
            fontWeight = if (selected) FontWeight.Bold else null,
            color = if (selected) colors.primary else colors.onSurface,
            textAlign = TextAlign.Center,
            autoSize = WholeWordsAutoSize(nameStyle.fontSize),
        )
    }
}

/**
 * A slider from -[LookAdjustments.MAX] to [LookAdjustments.MAX], 0 in the middle, with its [label]
 * and value over it, or with [labelWidth] its label before it and its value after it, on one line,
 * and a button beside it ([reset] names it) that puts it back to 0. Close to 0, it holds at 0, so the
 * middle is easy to find. [onChange] follows the finger; [onRest] comes once the value is let go: as
 * the finger lifts, or at once for a screen reader's change or a reset.
 */
@Composable
internal fun AdjustmentSlider(
    @StringRes label: Int,
    @StringRes reset: Int,
    value: Int,
    onChange: (Int) -> Unit,
    onRest: () -> Unit = {},
    labelWidth: Dp? = null,
) {
    val name = stringResource(label)
    val shown = if (value > 0) "+$value" else "$value"
    val haptics = rememberHaptics()
    val range = -LookAdjustments.MAX.toFloat()..LookAdjustments.MAX.toFloat()
    val valueStyle = Usugami.type.tabular(MaterialTheme.typography.labelLarge)
    val slider = @Composable { modifier: Modifier ->
        UsugamiSlider(
            value = value.toFloat(),
            onValueChange = { v ->
                val set = v.roundToInt().let { if (abs(it) <= SNAP) 0 else it }
                if (set == 0 && value != 0) haptics.snap()
                onChange(set)
            },
            valueRange = range,
            onValueChangeFinished = onRest,
            centered = true,
            // The slider's own item for a screen reader lies inside, out of reach of a name given
            // here: this one, named, takes its place.
            modifier = modifier.clearAndSetSemantics {
                contentDescription = name
                stateDescription = shown
                progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), range)
                setProgress { target ->
                    val set = target.roundToInt().coerceIn(-LookAdjustments.MAX, LookAdjustments.MAX)
                    if (set != value) {
                        onChange(set)
                        onRest()
                    }
                    set != value
                }
            },
        )
    }
    val resetButton = @Composable {
        GlyphButton(onClick = {
            onChange(0)
            onRest()
        }, enabled = value != 0) {
            Icon(Icons.Rounded.RestartAlt, contentDescription = stringResource(reset))
        }
    }
    if (labelWidth != null) {
        // The value as wide as its widest, so the slider keeps its length as it changes.
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        // Measured once, not at each step of the slider.
        val valueWidth = remember(valueStyle, density, measurer) {
            with(density) { measurer.measure("-${LookAdjustments.MAX}", valueStyle).size.width.toDp() }
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            // The slider says both to a screen reader.
            Text(name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(labelWidth).clearAndSetSemantics {})
            slider(Modifier.weight(1f))
            Text(
                shown,
                style = valueStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.padding(start = 8.dp).width(valueWidth).clearAndSetSemantics {},
            )
            resetButton()
        }
        return
    }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            // The slider says both to a screen reader.
            Row(Modifier.clearAndSetSemantics {}) {
                Text(name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Text(shown, style = valueStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            slider(Modifier)
        }
        resetButton()
    }
}

/** [image] (the page upright) as large as fits, turned [rotation] degrees clockwise, in the middle. */
@Composable
internal fun TurnedImage(image: ImageBitmap, rotation: Int, modifier: Modifier) {
    Canvas(modifier) {
        val sideways = rotation % 180 != 0
        val width = (if (sideways) image.height else image.width).toFloat()
        val height = (if (sideways) image.width else image.height).toFloat()
        val scale = min(size.width / width, size.height / height)
        val shown = Size(width * scale, height * scale)
        drawTurned(image, Rect(Offset((size.width - shown.width) / 2, (size.height - shown.height) / 2), shown), rotation)
    }
}

/**
 * The page inside [quad] straightened out of [photo], at most [maxSide] pixels long, or smaller when
 * memory is short; null when it can't be made.
 */
private fun straightened(photo: Bitmap, quad: CropQuad, maxSide: Int): Bitmap? {
    var side = maxSide
    while (side >= 64) {
        try {
            return PageLooks.straightened(photo, quad, side)
        } catch (e: OutOfMemoryError) {
            side /= 2
        }
    }
    return null
}

/**
 * [bitmap] made [SAMPLE_MAX] pixels on its long side, as a new bitmap; [bitmap] itself when it is no
 * larger.
 */
private fun small(bitmap: Bitmap): Bitmap {
    val long = max(bitmap.width, bitmap.height)
    if (long <= SAMPLE_MAX) return bitmap
    val scale = SAMPLE_MAX.toFloat() / long
    return bitmap.scale(max(1, (bitmap.width * scale).roundToInt()), max(1, (bitmap.height * scale).roundToInt()), filter = true)
}

/** The page straightened out of the photo inside [corners]: null when it couldn't be made. */
private class StraightPage(val corners: CropQuad, val bitmap: Bitmap?)

/** [base], a page upright, with [look] and [adjustments]: [image]. */
internal class LookPreview(val base: Bitmap, val look: PageLook, val adjustments: LookAdjustments, val image: Bitmap)

/** A small copy of the page inside [corners] in each look made so far. */
private class LookSamples(val corners: CropQuad, val images: Map<PageLook, ImageBitmap>)

/** The large page is made at most this many pixels on its long side. */
internal const val PAGE_MAX = 1200

/** The looks' small pages are this many pixels on their long side. */
private const val SAMPLE_MAX = 200

/**
 * The looks are made on the page this many pixels on its long side, as on the editor's pages, and
 * then made small. The looks weigh each pixel against blocks of pixels round it, which stop getting
 * smaller below this size: on a smaller page, a look would come out unlike the page's.
 */
private const val SAMPLE_LOOK_MAX = 512

/** How wide each look is in the row, its name under it. */
private val SAMPLE_WIDTH = 80.dp

/** How far in from the looks row's ends the look chosen is brought: clear of the faded edges (see fadingEdges). */
private val LOOK_MARGIN = 48.dp

/** How long the choices stay put before the page is made again with them. */
private const val LOOK_DELAY_MS = 150L

/** A slider this close to 0 holds at 0. */
private const val SNAP = 2
