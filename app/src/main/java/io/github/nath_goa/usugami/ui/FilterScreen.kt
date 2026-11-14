package io.github.nath_goa.usugami.ui

import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.graphics.scale
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.core.CropQuad
import io.github.nath_goa.usugami.core.LookAdjustments
import io.github.nath_goa.usugami.core.PageLook
import io.github.nath_goa.usugami.data.PageLooks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
    val preview by produceState<LookPreview?>(null, page, look, adjustments) {
        val base = page?.bitmap ?: return@produceState
        if (value?.let { it.base === base && it.look == look && it.adjustments == adjustments } == true) return@produceState
        if (look == PageLook.ORIGINAL && adjustments.isNone) {
            value = LookPreview(base, look, adjustments, base)
            return@produceState
        }
        if (value?.base === base) delay(LOOK_DELAY_MS)
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
    // Each look on a small copy of the page, shown as each is made. Each look is made on the page at
    // the size the editor shows pages at, and then made small, so it looks as it does on the page.
    val samples by produceState<LookSamples?>(null, photo, corners) {
        val shown = photo ?: return@produceState
        if (value?.corners?.isCloseTo(corners) == true) return@produceState
        val job = coroutineContext.job
        val plain = withContext(Dispatchers.Default) { straightened(shown, corners, SAMPLE_LOOK_MAX) } ?: return@produceState
        var made = LookSamples(corners, emptyMap())
        try {
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
                    made = LookSamples(corners, made.images + (option to sample.asImageBitmap()))
                    value = made
                }
            }
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
            Box(modifier.padding(16.dp), contentAlignment = Alignment.Center) {
                val image = shown?.image ?: base?.bitmap
                val bitmap = remember(image) { image?.asImageBitmap() }
                val description = stringResource(R.string.filter_preview_description)
                if (bitmap != null) TurnedImage(bitmap, rotation, Modifier.fillMaxSize().semantics { contentDescription = description })
                // Being made: the page for the corners, or its look for the choices.
                val busy = base == null || shown == null || shown.look != look || shown.adjustments != adjustments
                if (busy && (base == null || base.bitmap != null)) {
                    CircularProgressIndicator(if (bitmap == null) Modifier else Modifier.align(Alignment.TopEnd).size(24.dp), strokeWidth = if (bitmap == null) 4.dp else 2.dp)
                }
            }
        },
        controls = {
            Text(
                stringResource(R.string.look_title),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp).semantics { heading() },
            )
            // Wider with larger text, so that a name breaks between its words, not in them.
            val width = SAMPLE_WIDTH * LocalDensity.current.fontScale.coerceAtLeast(1f)
            // The names at one size, smaller where a word is wider than its tile (German "Tintensparend"),
            // rather than broken: the size the name that needs it most takes in bold, as when it is
            // chosen, so the names stay alike and none changes size when it is tapped.
            val names = PageLook.shown.map { stringResource(lookName(it)) }
            val style = MaterialTheme.typography.labelMedium
            val nameSize = wholeWordsFontSize(names, style.copy(fontWeight = FontWeight.Bold), width - 8.dp)
            val nameStyle = style.withEmLineHeight().copy(fontSize = nameSize)
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (option in PageLook.shown) LookChoice(option, images[option], rotation, option == look, width, nameStyle) { onLook(option) }
            }
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
 * One of the looks, like a radio button: the page in it ([image], turned [rotation] degrees, once it
 * is made) over its name, in [nameStyle], marked when it is the one chosen. It is [width] wide, its
 * name 8 dp narrower. The one chosen scrolls into view.
 */
@Composable
private fun LookChoice(
    look: PageLook,
    image: ImageBitmap?,
    rotation: Int,
    selected: Boolean,
    width: Dp,
    nameStyle: TextStyle,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(Unit) { if (selected) bringIntoView.bringIntoView() }
    Column(
        Modifier
            .width(width)
            .bringIntoViewRequester(bringIntoView)
            .clip(shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(SAMPLE_WIDTH - 8.dp)
                .clip(shape)
                .background(colors.surfaceContainerHighest)
                .border(if (selected) BorderStroke(3.dp, colors.primary) else BorderStroke(1.dp, colors.outlineVariant), shape),
        ) {
            if (image != null) TurnedImage(image, rotation, Modifier.fillMaxSize().padding(6.dp))
            if (selected) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = null,
                    tint = colors.onPrimary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).background(colors.primary, CircleShape).padding(2.dp).size(14.dp),
                )
            }
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
 * and value over it, and a button beside it ([reset] names it) that puts it back to 0. Close to 0,
 * it holds at 0, so the middle is easy to find.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdjustmentSlider(@StringRes label: Int, @StringRes reset: Int, value: Int, onChange: (Int) -> Unit) {
    val name = stringResource(label)
    val shown = if (value > 0) "+$value" else "$value"
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            // The slider says both to a screen reader.
            Row(Modifier.padding(top = 4.dp).clearAndSetSemantics {}) {
                Text(name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Text(shown, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val range = -LookAdjustments.MAX.toFloat()..LookAdjustments.MAX.toFloat()
            Slider(
                value = value.toFloat(),
                onValueChange = { v -> onChange(v.roundToInt().let { if (abs(it) <= SNAP) 0 else it }) },
                valueRange = range,
                track = { CentredTrack(it) },
                // The slider's own item for a screen reader lies inside, out of reach of a name given
                // here: this one, named, takes its place.
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = name
                    stateDescription = shown
                    progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), range)
                    setProgress { target ->
                        val set = target.roundToInt().coerceIn(-LookAdjustments.MAX, LookAdjustments.MAX)
                        if (set != value) onChange(set)
                        set != value
                    }
                },
            )
        }
        IconButton(onClick = { onChange(0) }, enabled = value != 0) {
            Icon(Icons.Rounded.RestartAlt, contentDescription = stringResource(reset))
        }
    }
}

/**
 * The track of a slider with 0 in its middle: filled from the middle to the thumb, either way, as
 * Material's own track is filled from its start, so 0 shows no fill. As Material's, it leaves a gap
 * round the thumb and has a dot at each end it reaches.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CentredTrack(state: SliderState) {
    val colors = SliderDefaults.colors()
    Canvas(Modifier.fillMaxWidth().height(TRACK_HEIGHT)) {
        val width = size.width
        val fraction = state.coercedValueAsFraction
        val thumb = width * if (layoutDirection == LayoutDirection.Rtl) 1 - fraction else fraction
        val gap = THUMB_GAP.toPx()
        val end = size.height / 2
        val inside = INSIDE_CORNER.toPx()
        trackPart(0f, thumb - gap, colors.inactiveTrackColor, end, inside)
        trackPart(thumb + gap, width, colors.inactiveTrackColor, inside, end)
        val middle = width / 2
        if (thumb - gap > middle) trackPart(middle, thumb - gap, colors.activeTrackColor, inside, inside)
        if (thumb + gap < middle) trackPart(thumb + gap, middle, colors.activeTrackColor, inside, inside)
        val dot = STOP_DOT.toPx() / 2
        if (thumb - gap >= size.height) drawCircle(colors.activeTrackColor, dot, Offset(end, end))
        if (thumb + gap <= width - size.height) drawCircle(colors.activeTrackColor, dot, Offset(width - end, end))
    }
}

/** The track from [from] to [to] across, in [color], its corners rounded [start] there and [end] at [to]. */
private fun DrawScope.trackPart(from: Float, to: Float, color: Color, start: Float, end: Float) {
    if (to <= from) return
    val first = CornerRadius(start)
    val last = CornerRadius(end)
    drawPath(Path().apply { addRoundRect(RoundRect(from, 0f, to, size.height, first, last, last, first)) }, color)
}

/** [image] (the page upright) as large as fits, turned [rotation] degrees clockwise, in the middle. */
@Composable
private fun TurnedImage(image: ImageBitmap, rotation: Int, modifier: Modifier) {
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

/** [base], a straightened page, with [look] and [adjustments]: [image]. */
private class LookPreview(val base: Bitmap, val look: PageLook, val adjustments: LookAdjustments, val image: Bitmap)

/** A small copy of the page inside [corners] in each look made so far. */
private class LookSamples(val corners: CropQuad, val images: Map<PageLook, ImageBitmap>)

/** The large page is made at most this many pixels on its long side. */
private const val PAGE_MAX = 1200

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

/** How long the choices stay put before the page is made again with them. */
private const val LOOK_DELAY_MS = 150L

/** A slider this close to 0 holds at 0. */
private const val SNAP = 2

/** How tall a slider's track is, as Material's. */
private val TRACK_HEIGHT = 16.dp

/** How far the track keeps from the middle of the thumb, as Material's: half the thumb and a gap. */
private val THUMB_GAP = 8.dp

/** How round the track's corners next to the thumb and the middle are, as Material's. */
private val INSIDE_CORNER = 2.dp

/** How wide the dot at each end of the track is, as Material's. */
private val STOP_DOT = 4.dp
