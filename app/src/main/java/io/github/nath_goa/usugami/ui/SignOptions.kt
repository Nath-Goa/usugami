package io.github.nath_goa.usugami.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.East
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.North
import androidx.compose.material.icons.rounded.South
import androidx.compose.material.icons.rounded.West
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.core.SignatureLayout
import io.github.nath_goa.usugami.core.SignaturePlacement
import io.github.nath_goa.usugami.data.PdfInput
import io.github.nath_goa.usugami.data.Signature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min

// Sign PDF's options: the signature, the page it goes on, and where on that page. They sit in
// PdfToolScreen's column, between the PDF and the file name.

/** Each nudge button moves the signature by this share of the page. */
private const val NUDGE = 0.02f

/** Keeps a placement across rotation and process death; null (not placed yet) isn't saved. */
internal val SignaturePlacementSaver = Saver<SignaturePlacement?, FloatArray>(
    save = { placement -> placement?.let { floatArrayOf(it.centerX, it.centerY, it.width) } },
    restore = { SignaturePlacement(it[0], it[1], it[2]) },
)

/**
 * [placement] null means the signature hasn't been moved: it then sits where
 * [SignatureLayout.default] puts it on whichever page is chosen.
 */
@Composable
internal fun SignOptions(
    input: PdfInput,
    signature: Signature?,
    onDraw: () -> Unit,
    page: Int,
    onPage: (Int) -> Unit,
    placement: SignaturePlacement?,
    onPlacement: (SignaturePlacement) -> Unit,
) {
    Text(stringResource(R.string.sign_signature), style = MaterialTheme.typography.titleMedium)
    Column {
        SignatureCard(signature, onDraw)
        Text(
            stringResource(R.string.sign_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        // Choosing the page and the place comes once there is a signature to place.
        val shown = freezeWhen(signature == null, signature)
        AnimatedVisibility(visible = signature != null, enter = pdfSectionEnter(), exit = pdfSectionExit(), label = "placement") {
            if (shown != null) {
                Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (input.pageCount > 1) {
                        Text(stringResource(R.string.sign_page), style = MaterialTheme.typography.titleMedium)
                        PageStrip(input, page, onPage)
                    }
                    SignaturePlacer(input, page, shown, placement, onPlacement)
                }
            }
        }
    }
}

/** Opens the pad: to draw a signature, or to change the one shown. */
@Composable
private fun SignatureCard(signature: Signature?, onDraw: () -> Unit) {
    if (signature == null) {
        OutlinedCard(onClick = onDraw, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.Draw, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.sign_draw))
            }
        }
    } else {
        val change = stringResource(R.string.sign_change)
        OutlinedCard(onClick = onDraw, modifier = Modifier.fillMaxWidth().semantics { contentDescription = change }) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                // On white, like the pad, so black ink shows in the dark theme too.
                SignatureDrawing(
                    signature,
                    Modifier
                        .weight(1f)
                        .height(72.dp)
                        .background(Color.White, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
                Spacer(Modifier.width(12.dp))
                Icon(Icons.Rounded.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** [signature] as big as fits, centred. */
@Composable
private fun SignatureDrawing(signature: Signature, modifier: Modifier) {
    val path = remember(signature) { signature.path().asComposePath() }
    val ink = Color(signature.ink.argb)
    Canvas(modifier) {
        val bounds = signature.bounds ?: return@Canvas
        val scale = min(size.width / bounds.width, size.height / bounds.height)
        withTransform({
            translate((size.width - bounds.width * scale) / 2, (size.height - bounds.height * scale) / 2)
            scale(scale, scale, pivot = Offset.Zero)
            translate(-bounds.x, -bounds.y)
        }) {
            drawPath(path, ink, style = SignaturePen)
        }
    }
}

/**
 * The pages in a row, each a radio button named after its number. Pages in [marked] have a dot
 * under their number, and a screen reader says [markedLabel] for them.
 */
@Composable
internal fun PageStrip(input: PdfInput, selected: Int, onSelect: (Int) -> Unit, marked: Set<Int> = emptySet(), markedLabel: String? = null) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = maxOf(0, selected - 1))
    LazyRow(state = state, modifier = Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(input.pageCount) { index ->
            val active = index == selected
            val borderWidth by animateDpAsState(if (active) 2.dp else 1.dp, Motion.short(), label = "page border width")
            val borderColor by animateColorAsState(
                if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                Motion.short(),
                label = "page border color",
            )
            val label = stringResource(R.string.pdf_page_number, index + 1)
            val isMarked = index in marked
            OutlinedCard(
                border = BorderStroke(borderWidth, borderColor),
                modifier = Modifier
                    .width(72.dp)
                    .clip(CardDefaults.outlinedShape)
                    .selectable(selected = active, role = Role.RadioButton, onClick = { onSelect(index) })
                    .semantics {
                        contentDescription = label
                        if (isMarked && markedLabel != null) stateDescription = markedLabel
                    },
            ) {
                PdfThumbnail(input, index, Modifier.fillMaxWidth().height(92.dp))
                Row(Modifier.align(Alignment.CenterHorizontally).padding(4.dp).clearAndSetSemantics {}, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    if (isMarked) {
                        Spacer(Modifier.width(4.dp))
                        Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                    }
                }
            }
        }
    }
}

/**
 * The chosen page with the signature on it, to drag into place, then a size slider and buttons that
 * move it a step at a time (for those who can't drag).
 */
@Composable
private fun SignaturePlacer(
    input: PdfInput,
    pageIndex: Int,
    signature: Signature,
    placement: SignaturePlacement?,
    onPlacement: (SignaturePlacement) -> Unit,
) {
    // The page last drawn stays while the next one is drawn, instead of flashing blank.
    var page by remember(input.path) { mutableStateOf<RenderedPage?>(null) }
    LaunchedEffect(input.path, pageIndex) {
        page = withContext(Dispatchers.IO) { renderShownPage(input, pageIndex, 1000) }
    }
    // Until the page has been drawn, an A4 page stands in for it.
    val pageWidth = page?.width ?: 595f
    val pageHeight = page?.height ?: 842f
    val aspect = signature.aspect
    fun fit(wanted: SignaturePlacement) = SignatureLayout.clamp(wanted, pageWidth, pageHeight, aspect)
    val current = fit(placement ?: SignatureLayout.default(pageWidth, pageHeight, aspect))

    Text(stringResource(R.string.sign_place), style = MaterialTheme.typography.titleMedium)
    Column {
        PlacementPreview(page, pageWidth / pageHeight, signature, current, onMove = { onPlacement(fit(it)) }, stringResource(R.string.sign_preview_description, pageIndex + 1))
        Text(
            stringResource(R.string.sign_place_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp).align(Alignment.CenterHorizontally),
        )
    }
    val range = SignatureLayout.widthRange(pageWidth, pageHeight, aspect)
    val sizeDescription = stringResource(R.string.sign_size_description)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.sign_size), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.clearAndSetSemantics {})
        Spacer(Modifier.width(16.dp))
        Slider(
            value = current.width,
            onValueChange = { onPlacement(fit(current.copy(width = it))) },
            // A page too small to choose a size on has one size.
            valueRange = if (range.endInclusive > range.start) range else range.start..range.start + 1f,
            enabled = range.endInclusive > range.start,
            modifier = Modifier.weight(1f).semantics { contentDescription = sizeDescription },
        )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        NudgeButton(Icons.Rounded.West, R.string.sign_move_left, fit(current.copy(centerX = current.centerX - NUDGE)), current, onPlacement)
        NudgeButton(Icons.Rounded.North, R.string.sign_move_up, fit(current.copy(centerY = current.centerY - NUDGE)), current, onPlacement)
        NudgeButton(Icons.Rounded.South, R.string.sign_move_down, fit(current.copy(centerY = current.centerY + NUDGE)), current, onPlacement)
        NudgeButton(Icons.Rounded.East, R.string.sign_move_right, fit(current.copy(centerX = current.centerX + NUDGE)), current, onPlacement)
    }
}

/** Moves the signature to [target]; off once it is against that edge of the page. */
@Composable
private fun NudgeButton(icon: ImageVector, @StringRes label: Int, target: SignaturePlacement, current: SignaturePlacement, onPlacement: (SignaturePlacement) -> Unit) {
    IconButton(onClick = { onPlacement(target) }, enabled = target != current) {
        Icon(icon, contentDescription = stringResource(label))
    }
}

/**
 * [page] with [signature] where [placement] puts it, drawn as the PDF will have it. Dragging the
 * signature moves it; tapping elsewhere on the page moves it there. A swipe that starts off the
 * signature scrolls the screen, so the large preview doesn't trap scrolling.
 */
@Composable
private fun PlacementPreview(
    page: RenderedPage?,
    pageAspect: Float,
    signature: Signature,
    placement: SignaturePlacement,
    onMove: (SignaturePlacement) -> Unit,
    description: String,
) {
    val latest by rememberUpdatedState(placement)
    val move by rememberUpdatedState(onMove)
    val signatureAspect by rememberUpdatedState(signature.aspect)
    var dragging by remember { mutableStateOf(false) }
    // A tap or a nudge glides the signature to its new place; a drag follows the finger exactly.
    val x by animateFloatAsState(placement.centerX, if (dragging) snap() else Motion.short(), label = "signature x")
    val y by animateFloatAsState(placement.centerY, if (dragging) snap() else Motion.short(), label = "signature y")
    val path = remember(signature) { signature.path().asComposePath() }
    val ink = Color(signature.ink.argb)
    val frame = MaterialTheme.colorScheme.primary
    // A tall page is at most 440 dp high, and less in landscape, so the whole page fits on the screen.
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val tallest = min(440.dp, max(240.dp, windowHeight - 180.dp))
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val width = min(maxWidth, tallest * pageAspect)
        Canvas(
            Modifier
                .size(width, width / pageAspect)
                .shadow(2.dp, RectangleShape)
                .background(Color.White)
                .semantics { contentDescription = description }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = latest
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val halfWidth = start.width * w / 2
                        val halfHeight = halfWidth / signatureAspect
                        val reach = 16.dp.toPx()
                        val onSignature = abs(down.position.x - start.centerX * w) <= halfWidth + reach &&
                            abs(down.position.y - start.centerY * h) <= halfHeight + reach
                        if (onSignature) {
                            down.consume()
                            dragging = true
                            try {
                                drag(down.id) { change ->
                                    val moved = change.position - down.position
                                    move(start.copy(centerX = start.centerX + moved.x / w, centerY = start.centerY + moved.y / h))
                                    change.consume()
                                }
                            } finally {
                                dragging = false
                            }
                        } else {
                            val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                            up.consume()
                            move(start.copy(centerX = up.position.x / w, centerY = up.position.y / h))
                        }
                    }
                },
        ) {
            page?.let { drawImage(it.image, dstSize = IntSize(size.width.toInt(), size.height.toInt())) }
            val bounds = signature.bounds ?: return@Canvas
            val boxWidth = placement.width * size.width
            val boxHeight = boxWidth / signature.aspect
            val left = x * size.width - boxWidth / 2
            val top = y * size.height - boxHeight / 2
            val scale = boxWidth / bounds.width
            withTransform({
                translate(left, top)
                scale(scale, scale, pivot = Offset.Zero)
                translate(-bounds.x, -bounds.y)
            }) {
                drawPath(path, ink, style = SignaturePen)
            }
            // A dashed frame shows what can be dragged. It isn't part of the PDF.
            val gap = 4.dp.toPx()
            drawRect(
                frame,
                topLeft = Offset(left - gap, top - gap),
                size = Size(boxWidth + 2 * gap, boxHeight + 2 * gap),
                style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
            )
        }
    }
}
