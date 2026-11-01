package io.github.nath_goa.usugami.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Rotate90DegreesCcw
import androidx.compose.material.icons.rounded.Rotate90DegreesCw
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.withTranslation
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.core.PageNumberPosition
import io.github.nath_goa.usugami.core.PageNumberSize
import io.github.nath_goa.usugami.core.WatermarkLayout
import io.github.nath_goa.usugami.data.PdfInput
import io.github.nath_goa.usugami.data.PdfPageRef
import io.github.nath_goa.usugami.data.WatermarkColor
import io.github.nath_goa.usugami.data.WatermarkOpacity
import io.github.nath_goa.usugami.data.WatermarkText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.sin

// Options of the tools that change one PDF: Rotate, Page numbers, Watermark and Flatten. They sit in
// PdfToolScreen's column, between the PDF and the file name.

@Composable
internal fun RotateOptions(input: PdfInput, pages: List<PdfPageRef>, vm: PdfToolsViewModel) {
    // The whole document at once; each page below has its own buttons.
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.rotate_all), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.rotateAll(-90) }) {
                Icon(Icons.Rounded.Rotate90DegreesCcw, contentDescription = stringResource(R.string.rotate_all_left))
            }
            IconButton(onClick = { vm.rotateAll(90) }) {
                Icon(Icons.Rounded.Rotate90DegreesCw, contentDescription = stringResource(R.string.rotate_all_right))
            }
        }
        Text(stringResource(R.string.rotate_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    // At most this tall, scrolling inside: a long PDF would otherwise push the file name far down.
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 450.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(pages, key = { "${it.inputId}:${it.page}" }) { ref ->
            RotatePageCard(input, ref, onRotate = { degrees -> vm.rotatePage(ref, degrees) })
        }
    }
    TextButton(onClick = vm::resetPages, enabled = pages.any { it.rotation != 0 }) { Text(stringResource(R.string.pdf_reset_pages)) }
}

/** A page with buttons to turn it, like a page in Merge's page mode. */
@Composable
private fun RotatePageCard(input: PdfInput, ref: PdfPageRef, onRotate: (Int) -> Unit) {
    val number = ref.page + 1
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TurnedThumbnail(input, ref.page, ref.rotation, Modifier.size(width = 55.dp, height = 72.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.pdf_page_number, number))
                // Says how the page has been turned, for a screen reader as much as for the eye.
                val turned = turnedLabel(ref.rotation)
                val shown = freezeWhen(turned == null, turned)
                AnimatedVisibility(visible = turned != null, enter = pdfSectionEnter(), exit = pdfSectionExit(), label = "turned") {
                    if (shown != null) Text(stringResource(shown), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            IconButton(onClick = { onRotate(-90) }) {
                Icon(Icons.Rounded.Rotate90DegreesCcw, contentDescription = stringResource(R.string.rotate_page_left, number))
            }
            IconButton(onClick = { onRotate(90) }) {
                Icon(Icons.Rounded.Rotate90DegreesCw, contentDescription = stringResource(R.string.rotate_page_right, number))
            }
        }
    }
}

@StringRes
internal fun turnedLabel(rotation: Int): Int? = when (rotation) {
    90 -> R.string.rotated_right
    180 -> R.string.rotated_upside_down
    270 -> R.string.rotated_left
    else -> null
}

/**
 * A page thumbnail turned by [rotation], animated. Turned sideways it shrinks, so a portrait page
 * lying on its side still fits the space of an upright one; [aspect] is that space's width over its
 * height.
 */
@Composable
internal fun TurnedThumbnail(input: PdfInput, page: Int, rotation: Int, modifier: Modifier, aspect: Float = 55f / 72f) {
    val angle by animatePdfPageRotationAsState(rotation)
    PdfThumbnail(input, page, modifier.turnedBy(angle, aspect))
}

/** Turns the content [angle] degrees, shrinking it as it goes sideways so it stays in a box of [aspect]. */
internal fun Modifier.turnedBy(angle: Float, aspect: Float): Modifier = graphicsLayer {
    rotationZ = angle
    val shrink = 1f - (1f - aspect) * abs(sin(Math.toRadians(angle.toDouble()))).toFloat()
    scaleX = shrink
    scaleY = shrink
}

/** Flatten PDF has no choices: one line says what it does. */
@Composable
internal fun FlattenOptions() {
    Text(stringResource(R.string.flatten_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PageNumberOptions(
    position: PageNumberPosition,
    onPosition: (PageNumberPosition) -> Unit,
    size: PageNumberSize,
    onSize: (PageNumberSize) -> Unit,
    skip: String,
    onSkip: (String) -> Unit,
) {
    Text(stringResource(R.string.page_numbers_position), style = MaterialTheme.typography.titleMedium)
    PositionPicker(position, onPosition)
    Text(stringResource(R.string.page_numbers_size), style = MaterialTheme.typography.titleMedium)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        PageNumberSize.entries.forEachIndexed { index, item ->
            SegmentedButton(
                selected = size == item,
                onClick = { onSize(item) },
                shape = SegmentedButtonDefaults.itemShape(index, PageNumberSize.entries.size),
                icon = {},
                contentPadding = SegmentPadding,
                label = {
                    SegmentLabel(stringResource(when (item) {
                        PageNumberSize.SMALL -> R.string.number_size_small
                        PageNumberSize.MEDIUM -> R.string.number_size_medium
                        PageNumberSize.LARGE -> R.string.number_size_large
                    }))
                },
            )
        }
    }
    Column {
        OutlinedTextField(
            value = skip,
            onValueChange = onSkip,
            label = { Text(stringResource(R.string.page_numbers_skip)) },
            supportingText = { Text(stringResource(R.string.page_numbers_skip_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(R.string.page_numbers_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // Lined up with the hint above it.
            modifier = Modifier.padding(start = 16.dp, top = 4.dp),
        )
    }
}

@StringRes
private fun positionLabel(position: PageNumberPosition): Int = when (position) {
    PageNumberPosition.TOP_LEFT -> R.string.position_top_left
    PageNumberPosition.TOP_CENTER -> R.string.position_top_center
    PageNumberPosition.TOP_RIGHT -> R.string.position_top_right
    PageNumberPosition.BOTTOM_LEFT -> R.string.position_bottom_left
    PageNumberPosition.BOTTOM_CENTER -> R.string.position_bottom_center
    PageNumberPosition.BOTTOM_RIGHT -> R.string.position_bottom_right
}

/**
 * A small page with a spot in each of the six places a number can go. The chosen spot shows a
 * number; each spot is a radio button named after its place.
 */
@Composable
private fun PositionPicker(selected: PageNumberPosition, onSelect: (PageNumberPosition) -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(width = 156.dp, height = 200.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerLow, shape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                .selectableGroup(),
        ) {
            // Grey bars stand for the page's text.
            Column(
                Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 28.dp)
                    .clearAndSetSemantics {},
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (width in listOf(1f, 1f, 0.85f, 1f, 0.6f)) {
                    Box(
                        Modifier
                            .fillMaxWidth(width)
                            .height(6.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(3.dp)),
                    )
                }
            }
            for (position in PageNumberPosition.entries) {
                val alignment = when (position) {
                    PageNumberPosition.TOP_LEFT -> Alignment.TopStart
                    PageNumberPosition.TOP_CENTER -> Alignment.TopCenter
                    PageNumberPosition.TOP_RIGHT -> Alignment.TopEnd
                    PageNumberPosition.BOTTOM_LEFT -> Alignment.BottomStart
                    PageNumberPosition.BOTTOM_CENTER -> Alignment.BottomCenter
                    PageNumberPosition.BOTTOM_RIGHT -> Alignment.BottomEnd
                }
                PositionSpot(
                    selected = position == selected,
                    label = stringResource(positionLabel(position)),
                    onClick = { onSelect(position) },
                    modifier = Modifier.align(alignment),
                )
            }
        }
        Spacer(Modifier.width(20.dp))
        // The chosen place in words; the spots already tell a screen reader.
        Text(
            stringResource(positionLabel(selected)),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).clearAndSetSemantics {},
        )
    }
}

@Composable
private fun PositionSpot(selected: Boolean, label: String, onClick: () -> Unit, modifier: Modifier) {
    val dot by animateDpAsState(if (selected) 26.dp else 10.dp, Motion.short(), label = "spot size")
    val color by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        Motion.short(),
        label = "spot color",
    )
    val numberAlpha by animateFloatAsState(if (selected) 1f else 0f, Motion.short(), label = "spot number")
    Box(
        modifier
            .size(52.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(dot).background(color, CircleShape), contentAlignment = Alignment.Center) {
            Text(
                "1",
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.graphicsLayer { alpha = numberAlpha }.clearAndSetSemantics {},
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WatermarkOptions(
    input: PdfInput,
    text: String,
    onText: (String) -> Unit,
    color: WatermarkColor,
    onColor: (WatermarkColor) -> Unit,
    opacity: WatermarkOpacity,
    onOpacity: (WatermarkOpacity) -> Unit,
) {
    OutlinedTextField(
        value = text,
        onValueChange = { onText(it.take(WatermarkText.MAX_LENGTH)) },
        label = { Text(stringResource(R.string.watermark_text)) },
        supportingText = { Text(stringResource(R.string.watermark_text_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier.fillMaxWidth(),
    )
    Text(stringResource(R.string.watermark_color), style = MaterialTheme.typography.titleMedium)
    ColorSwatches(WatermarkColor.entries, color, onColor, argb = { it.argb }, label = ::colorLabel)
    Text(stringResource(R.string.watermark_opacity), style = MaterialTheme.typography.titleMedium)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        WatermarkOpacity.entries.forEachIndexed { index, item ->
            SegmentedButton(
                selected = opacity == item,
                onClick = { onOpacity(item) },
                shape = SegmentedButtonDefaults.itemShape(index, WatermarkOpacity.entries.size),
                icon = {},
                contentPadding = SegmentPadding,
                label = {
                    SegmentLabel(stringResource(when (item) {
                        WatermarkOpacity.TRANSLUCENT -> R.string.opacity_translucent
                        WatermarkOpacity.SEMI -> R.string.opacity_semi
                        WatermarkOpacity.OPAQUE -> R.string.opacity_opaque
                    }))
                },
            )
        }
    }
    Text(stringResource(R.string.watermark_preview), style = MaterialTheme.typography.titleMedium)
    WatermarkPreview(input, text, color, opacity)
}

@StringRes
private fun colorLabel(color: WatermarkColor): Int = when (color) {
    WatermarkColor.GRAY -> R.string.color_gray
    WatermarkColor.BLACK -> R.string.color_black
    WatermarkColor.RED -> R.string.color_red
    WatermarkColor.BLUE -> R.string.color_blue
    WatermarkColor.GREEN -> R.string.color_green
}

/** One round swatch per colour in [options], each a radio button named by [label] (a string resource). */
@Composable
internal fun <T> ColorSwatches(options: List<T>, selected: T, onSelect: (T) -> Unit, argb: (T) -> Int, label: (T) -> Int) {
    Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (color in options) {
            val chosen = color == selected
            val ring by animateColorAsState(
                if (chosen) MaterialTheme.colorScheme.primary else Color.Transparent,
                Motion.short(),
                label = "swatch ring",
            )
            val check by animateFloatAsState(if (chosen) 1f else 0f, Motion.short(), label = "swatch check")
            val name = stringResource(label(color))
            Box(
                Modifier
                    .size(52.dp)
                    .selectable(selected = chosen, role = Role.RadioButton, onClick = { onSelect(color) })
                    .semantics { contentDescription = name }
                    .border(2.dp, ring, CircleShape)
                    .padding(6.dp)
                    .background(Color(argb(color)), CircleShape)
                    // So black shows on the dark theme.
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.graphicsLayer {
                        alpha = check
                        scaleX = 0.6f + 0.4f * check
                        scaleY = 0.6f + 0.4f * check
                    },
                )
            }
        }
    }
}

/**
 * The first page with the watermark drawn on it as the PDF will have it: with the same paint, at
 * the same place and angle, and as big compared with the page.
 */
@Composable
private fun WatermarkPreview(input: PdfInput, text: String, color: WatermarkColor, opacity: WatermarkOpacity) {
    val page by produceState<ImageBitmap?>(null, input.path) {
        value = withContext(Dispatchers.IO) { renderPdfPage(input, 0, 600) }
    }
    val paint = remember { WatermarkText.paint(0) }
    val trimmed = text.trim()
    val ink = remember(trimmed) { if (trimmed.isEmpty()) null else WatermarkText.inkBounds(trimmed, paint).takeUnless { it.isEmpty } }
    val tint by animateColorAsState(Color(color.argb), Motion.short(), label = "watermark color")
    val alpha by animateFloatAsState(opacity.alpha, Motion.short(), label = "watermark opacity")
    val description = stringResource(R.string.watermark_preview_description)
    val bitmap = page
    // Until page 1 has been drawn, an A4-shaped blank stands in for it. A tall page is 220 dp high,
    // a wide one 280 dp wide.
    val aspect = bitmap?.let { it.width.toFloat() / it.height } ?: (210f / 297f)
    val width = minOf(280.dp, 220.dp * aspect)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .size(width, width / aspect)
                .shadow(2.dp, RectangleShape)
                .background(Color.White)
                .semantics { contentDescription = description },
        ) {
            if (bitmap != null) drawImage(bitmap, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
            if (ink == null) return@Canvas
            val place = WatermarkLayout.place(size.width, size.height, ink.width().toFloat(), ink.height().toFloat())
            paint.color = tint.copy(alpha = alpha).toArgb()
            drawIntoCanvas { canvas ->
                // The layout's y points up; the screen's points down, so the turn is the other way.
                canvas.nativeCanvas.withTranslation(place.centerX, size.height - place.centerY) {
                    rotate(-place.degrees)
                    scale(place.scale, place.scale)
                    translate(-ink.exactCenterX(), -ink.exactCenterY())
                    drawText(trimmed, 0f, 0f, paint)
                }
            }
        }
    }
}
