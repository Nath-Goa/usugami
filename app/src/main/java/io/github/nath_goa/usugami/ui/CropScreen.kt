package io.github.nath_goa.usugami.ui

import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.East
import androidx.compose.material.icons.rounded.North
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.South
import androidx.compose.material.icons.rounded.West
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.core.CropPoint
import io.github.nath_goa.usugami.core.CropQuad
import io.github.nath_goa.usugami.core.PageLook
import io.github.nath_goa.usugami.data.ImageItem
import io.github.nath_goa.usugami.data.PageCropper
import io.github.nath_goa.usugami.data.PageLooks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What the crop screen's buttons do. [done] and [takeAnother] get the corners as the screen shows the
 * image, the page's look, and whether every page takes that look.
 */
class CropActions(
    val done: (CropQuad, PageLook, Boolean) -> Unit,
    val takeAnother: (CropQuad, PageLook, Boolean) -> Unit,
    val close: () -> Unit,
)

/**
 * Crop a page out of [item]: the image (turned as on its page) with four corners to drag onto the
 * page's corners, a magnifier under the finger while dragging, and buttons that move the chosen
 * corner a step at a time (a screen reader finds each corner, with the same moves as actions), and
 * the page's look, shown inside the corners as it will be. Done straightens the page inside the
 * corners and gives it the look (every page in the editor, with Apply to all pages, which shows when
 * there are others). For a photo just taken, Take another crops it and opens the camera again, and
 * leaving discards the photo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CropScreen(session: CropSession, item: ImageItem?, pageCount: Int, snackbar: SnackbarHostState, actions: CropActions) {
    // The corners as the screen shows the image, kept through rotation and Android stopping Usugami.
    var corners by rememberSaveable(session.opened, stateSaver = QuadSaver) { mutableStateOf(session.shown(session.start)) }
    var chosen by rememberSaveable(session.opened) { mutableIntStateOf(CropQuad.TOP_LEFT) }
    var look by rememberSaveable(session.opened) { mutableStateOf(session.look) }
    var allPages by rememberSaveable(session.opened) { mutableStateOf(false) }
    val source = item?.source
    val sideways = session.rotation % 180 != 0
    // The image's size as the screen shows it.
    val shownWidth = source?.let { if (sideways) it.uprightHeight else it.uprightWidth } ?: 1
    val shownHeight = source?.let { if (sideways) it.uprightWidth else it.uprightHeight } ?: 1
    val usable = corners.isWhole() || corners.ordered().isUsable(shownWidth, shownHeight)
    val window = LocalWindowInfo.current.containerSize
    val previewSide = min(PREVIEW_MAX, max(window.width, window.height))
    val photo by produceState<Bitmap?>(null, source?.path, session.rotation) {
        val shown = source ?: return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching { PageCropper.upright(shown, session.rotation, previewSide) }.getOrNull()
        }
    }
    val image = remember(photo) { photo?.asImageBitmap() }
    // The page with its look, as it will be: straightened out of the photo shown, given the look and
    // drawn back inside the corners. Made again once the corners have stayed put for a moment; while
    // they move, the photo shows inside them.
    val lookPreview by produceState<LookPreview?>(null, photo, look, corners) {
        val shown = photo
        if (shown == null || look == PageLook.ORIGINAL) {
            value = null
            return@produceState
        }
        if (value?.let { it.look == look && it.corners == corners } == true) return@produceState
        delay(LOOK_DELAY_MS)
        val quad = corners
        val job = coroutineContext.job
        value = withContext(Dispatchers.Default) {
            try {
                val page = PageLooks.straightened(shown, quad.ordered(), LOOK_PREVIEW_MAX) ?: return@withContext null
                try {
                    LookPreview(look, quad, PageLooks.applied(page, look) { job.ensureActive() })
                } finally {
                    page.recycle()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }
        }
    }

    // A move of the chosen corner (or of [index]) by a step, from where it is when the button is
    // pressed: quick presses each move it a step.
    val step = STEP * max(shownWidth, shownHeight)
    fun nudge(index: Int, dx: Int, dy: Int) {
        chosen = index
        corners = corners.moved(index, dx * step / shownWidth, dy * step / shownHeight)
    }
    // Once a drag or a step is over, the corners take the order a page's corners have, so each is
    // named for where it is.
    fun settle() {
        val ordered = corners.ordered()
        if (ordered != corners && ordered.isUsable(shownWidth, shownHeight)) {
            chosen = ordered.corners.indexOf(corners[chosen]).coerceAtLeast(0)
            corners = ordered
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { BarTitle(stringResource(R.string.crop_title)) },
                navigationIcon = {
                    IconButton(onClick = actions.close) {
                        if (session.fromCamera) Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.crop_discard))
                        else Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            val wide = maxWidth > maxHeight
            val controlsHeight = maxHeight / 2
            val hint: @Composable () -> Unit = {
                val page = stringResource(if (session.foundPage) R.string.crop_found else R.string.crop_not_found)
                val notAPage = stringResource(R.string.crop_not_a_page)
                val align = if (wide) TextAlign.Start else TextAlign.Center
                // As tall as the longer of its two texts, whichever shows: the image below stays
                // where it is when they change places under a corner being dragged.
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        if (usable) notAPage else page,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = align,
                        modifier = Modifier.fillMaxWidth().alpha(0f).clearAndSetSemantics {},
                    )
                    Text(
                        if (usable) page else notAPage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (usable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        textAlign = align,
                        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
            val area: @Composable (Modifier) -> Unit = { modifier ->
                CropArea(
                    image = image,
                    lookPreview = lookPreview?.takeIf { it.corners == corners && it.look == look },
                    aspect = shownWidth.toFloat() / shownHeight,
                    corners = corners,
                    chosen = chosen,
                    onChoose = { chosen = it },
                    onMove = { index, point -> corners = corners.with(index, point.x, point.y) },
                    onNudge = { index, dx, dy ->
                        nudge(index, dx, dy)
                        settle()
                    },
                    onDragEnd = ::settle,
                    modifier = modifier,
                )
            }
            val controls: @Composable () -> Unit = {
                LookChoices(
                    look = look,
                    onLook = { look = it },
                    allPages = allPages.takeIf { pageCount > 1 },
                    onAllPages = { allPages = it },
                )
                CropControls(
                    chosen = chosen,
                    onNudge = { dx, dy ->
                        nudge(chosen, dx, dy)
                        settle()
                    },
                    onReset = { corners = session.shown(session.found) },
                    onWhole = { corners = CropQuad.WHOLE },
                )
            }
            val toAll = allPages && pageCount > 1
            val buttons: @Composable () -> Unit = {
                CropButtons(
                    usable = usable,
                    fromCamera = session.fromCamera,
                    onDone = { if (usable) actions.done(corners, look, toAll) },
                    onTakeAnother = { if (usable) actions.takeAnother(corners, look, toAll) },
                )
            }
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    area(Modifier.weight(1f).fillMaxHeight())
                    Column(Modifier.width(320.dp).fillMaxHeight().padding(bottom = 8.dp)) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            hint()
                            controls()
                        }
                        buttons()
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    hint()
                    area(Modifier.weight(1f).fillMaxWidth())
                    // The controls never take more than half the screen, however large the text, and
                    // Done stays in sight below them.
                    Column(Modifier.heightIn(max = controlsHeight).padding(bottom = 8.dp)) {
                        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                            controls()
                        }
                        buttons()
                    }
                }
            }
        }
    }
}

/**
 * The page's looks, one of four like radio buttons, and, when [allPages] isn't null, a check box
 * that gives every page the look.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LookChoices(look: PageLook, onLook: (PageLook) -> Unit, allPages: Boolean?, onAllPages: (Boolean) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.look_title), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 4.dp))
        for (option in PageLook.entries) {
            val selected = option == look
            FilterChip(
                selected = selected,
                onClick = { onLook(option) },
                label = { Text(stringResource(lookName(option))) },
                leadingIcon = if (selected) {
                    { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                } else {
                    null
                },
                // One of four, like a radio button.
                modifier = Modifier.semantics { role = Role.RadioButton },
            )
        }
    }
    if (allPages != null) {
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(value = allPages, role = Role.Checkbox, onValueChange = onAllPages)
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = allPages, onCheckedChange = null)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.look_all_pages), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** A look's name. */
@StringRes
fun lookName(look: PageLook): Int = when (look) {
    PageLook.ORIGINAL -> R.string.look_original
    PageLook.ENHANCED -> R.string.look_enhanced
    PageLook.GRAYSCALE -> R.string.look_grayscale
    PageLook.BLACK_WHITE -> R.string.look_black_white
}

/** The controls under the image: moving the chosen corner, and Reset and Whole image. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CropControls(
    chosen: Int,
    onNudge: (dx: Int, dy: Int) -> Unit,
    onReset: () -> Unit,
    onWhole: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        // Which corner the arrows move; tapping a corner chooses it.
        Text(
            stringResource(CORNER_NAMES[chosen]),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { onNudge(-1, 0) }) { Icon(Icons.Rounded.West, contentDescription = stringResource(R.string.edit_move_left)) }
        IconButton(onClick = { onNudge(0, -1) }) { Icon(Icons.Rounded.North, contentDescription = stringResource(R.string.edit_move_up)) }
        IconButton(onClick = { onNudge(0, 1) }) { Icon(Icons.Rounded.South, contentDescription = stringResource(R.string.edit_move_down)) }
        IconButton(onClick = { onNudge(1, 0) }) { Icon(Icons.Rounded.East, contentDescription = stringResource(R.string.edit_move_right)) }
    }
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.Center) {
        TextButton(onClick = onReset) {
            Icon(Icons.Rounded.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.crop_reset))
        }
        TextButton(onClick = onWhole) {
            Icon(Icons.Rounded.CropFree, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.crop_whole))
        }
    }
}

/** Done, and Take another for a photo just taken. */
@Composable
private fun CropButtons(usable: Boolean, fromCamera: Boolean, onDone: () -> Unit, onTakeAnother: () -> Unit) {
    // The two buttons are as tall as each other when a long label takes two lines.
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (fromCamera) {
            OutlinedButton(onClick = onTakeAnother, enabled = usable, modifier = Modifier.weight(1f).fillMaxHeight(), contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                Icon(Icons.Rounded.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.crop_take_another), textAlign = TextAlign.Center)
            }
        }
        Button(onClick = onDone, enabled = usable, modifier = Modifier.weight(1f).fillMaxHeight(), contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
            Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.crop_done), textAlign = TextAlign.Center)
        }
    }
}

/**
 * The image with the page's outline, the area outside it dimmed, and its four corners. Pressing
 * near a corner drags it, with a magnifier showing what is under it; the corners are also items of
 * their own for a screen reader, which moves them with actions.
 */
@Composable
private fun CropArea(
    image: ImageBitmap?,
    lookPreview: LookPreview?,
    aspect: Float,
    corners: CropQuad,
    chosen: Int,
    onChoose: (Int) -> Unit,
    onMove: (Int, CropPoint) -> Unit,
    onNudge: (index: Int, dx: Int, dy: Int) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier,
) {
    val latest by rememberUpdatedState(corners)
    val move by rememberUpdatedState(onMove)
    val choose by rememberUpdatedState(onChoose)
    val dragEnded by rememberUpdatedState(onDragEnd)
    // The corner being dragged and where the finger is, while one is.
    var dragging by remember { mutableStateOf<Int?>(null) }
    var finger by remember { mutableStateOf(Offset.Zero) }
    val outline = MaterialTheme.colorScheme.primary
    val placeholder = MaterialTheme.colorScheme.surfaceVariant
    val description = stringResource(R.string.crop_image_description)
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val room = with(density) { HANDLE_SIZE.toPx() / 2 }
        val areaWidth = constraints.maxWidth.toFloat()
        val areaHeight = constraints.maxHeight.toFloat()
        // The image, as large as fits with room around it for the corners' handles.
        val fitWidth = max(1f, areaWidth - 2 * room)
        val fitHeight = max(1f, areaHeight - 2 * room)
        val imageWidth = min(fitWidth, fitHeight * aspect)
        val imageHeight = imageWidth / aspect
        val frame = Rect(Offset((areaWidth - imageWidth) / 2, (areaHeight - imageHeight) / 2), Size(imageWidth, imageHeight))
        fun at(p: CropPoint) = Offset(frame.left + p.x * frame.width, frame.top + p.y * frame.height)
        // Read as it is now by the gesture, which a change of layout doesn't stop: a corner being
        // dragged stays under the finger.
        val shownFrame by rememberUpdatedState(frame)

        Canvas(
            Modifier
                .fillMaxSize()
                .semantics { contentDescription = description }
                .pointerInput(Unit) {
                    val reach = HANDLE_REACH.toPx()
                    fun atNow(p: CropPoint) = shownFrame.let { Offset(it.left + p.x * it.width, it.top + p.y * it.height) }
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val quad = latest
                        val index = (0..3).minBy { (atNow(quad[it]) - down.position).getDistance() }
                        if ((atNow(quad[index]) - down.position).getDistance() > reach) return@awaitEachGesture
                        down.consume()
                        choose(index)
                        // Where the corner is from the finger.
                        val grip = atNow(quad[index]) - down.position
                        dragging = index
                        finger = down.position
                        try {
                            drag(down.id) { change ->
                                val p = change.position + grip
                                val now = shownFrame
                                finger = change.position
                                move(index, CropPoint((p.x - now.left) / now.width, (p.y - now.top) / now.height))
                                change.consume()
                            }
                        } finally {
                            dragging = null
                            dragEnded()
                        }
                    }
                },
        ) {
            if (image == null) {
                drawRect(placeholder, frame.topLeft, frame.size)
            } else {
                drawImage(
                    image,
                    dstOffset = IntOffset(frame.left.roundToInt(), frame.top.roundToInt()),
                    dstSize = IntSize(frame.width.roundToInt(), frame.height.roundToInt()),
                    filterQuality = FilterQuality.Medium,
                )
            }
            val points = corners.corners.map(::at)
            val page = Path().apply {
                moveTo(points[0].x, points[0].y)
                for (i in 1..3) lineTo(points[i].x, points[i].y)
                close()
            }
            // The page inside the corners as its look shows it.
            if (lookPreview != null && image != null) {
                val bitmap = lookPreview.page
                val shownCorners = lookPreview.corners.ordered().corners.map(::at)
                val matrix = android.graphics.Matrix()
                val w = bitmap.width.toFloat()
                val h = bitmap.height.toFloat()
                val placed = matrix.setPolyToPoly(
                    floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h),
                    0,
                    FloatArray(8) { i -> shownCorners[i / 2].let { if (i % 2 == 0) it.x else it.y } },
                    0,
                    4,
                )
                if (placed) drawIntoCanvas { it.nativeCanvas.drawBitmap(bitmap, matrix, LOOK_PAINT) }
            }
            // What will be left out is dimmed.
            val outside = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(frame)
                addPath(page)
            }
            drawPath(outside, Color.Black.copy(alpha = 0.45f))
            drawPath(page, outline, style = Stroke(2.dp.toPx()))
            points.forEachIndexed { i, p -> drawHandle(p, i == chosen, outline) }
            val held = dragging
            if (held != null && image != null) drawMagnifier(image, frame, page, points[held], finger, outline)
        }
        if (image == null) CircularProgressIndicator(Modifier.align(Alignment.Center))

        // The corners for a screen reader: each is an item named for where it is, chosen by a double
        // tap, with actions that move it.
        val handleSize = with(density) { HANDLE_SIZE.toPx() }
        for (i in 0..3) {
            val name = stringResource(CORNER_NAMES[i])
            val left = stringResource(R.string.edit_move_left)
            val up = stringResource(R.string.edit_move_up)
            val down = stringResource(R.string.edit_move_down)
            val right = stringResource(R.string.edit_move_right)
            Box(
                Modifier
                    .offset {
                        val p = at(corners[i])
                        IntOffset((p.x - handleSize / 2).roundToInt(), (p.y - handleSize / 2).roundToInt())
                    }
                    .size(HANDLE_SIZE)
                    .semantics {
                        contentDescription = name
                        // Which corner the arrow buttons move: one of four, like a radio button.
                        role = Role.RadioButton
                        selected = i == chosen
                        onClick {
                            onChoose(i)
                            true
                        }
                        customActions = listOf(
                            CustomAccessibilityAction(left) { onNudge(i, -1, 0); true },
                            CustomAccessibilityAction(up) { onNudge(i, 0, -1); true },
                            CustomAccessibilityAction(down) { onNudge(i, 0, 1); true },
                            CustomAccessibilityAction(right) { onNudge(i, 1, 0); true },
                        )
                    },
            )
        }
    }
}

/** A corner's handle: a ring, filled when it is the one the arrow buttons move. */
private fun DrawScope.drawHandle(center: Offset, chosen: Boolean, color: Color) {
    val radius = (if (chosen) 13 else 11).dp.toPx()
    drawCircle(Color.Black.copy(alpha = 0.3f), radius + 2.dp.toPx(), center)
    drawCircle(if (chosen) color else Color.White, radius, center)
    drawCircle(if (chosen) Color.White else color, radius, center, style = Stroke(2.5.dp.toPx()))
}

/**
 * A round magnifier in a top corner away from the finger, showing the image around the corner being
 * dragged, larger, with cross hairs on the corner: the finger hides it otherwise.
 */
private fun DrawScope.drawMagnifier(image: ImageBitmap, frame: Rect, page: Path, corner: Offset, finger: Offset, color: Color) {
    val radius = MAGNIFIER_RADIUS.toPx()
    val margin = 8.dp.toPx()
    val left = Offset(margin + radius, margin + radius)
    val right = Offset(size.width - margin - radius, margin + radius)
    // Out of the finger's way: the top corner further from it.
    val center = if (hypot(finger.x - left.x, finger.y - left.y) > hypot(finger.x - right.x, finger.y - right.y)) left else right
    val lens = Path().apply { addOval(Rect(center, radius)) }
    clipPath(lens) {
        drawRect(Color.Black, Offset(center.x - radius, center.y - radius), Size(2 * radius, 2 * radius))
        withTransform({
            translate(center.x - corner.x * MAGNIFICATION, center.y - corner.y * MAGNIFICATION)
            scale(MAGNIFICATION, MAGNIFICATION, pivot = Offset.Zero)
        }) {
            drawImage(
                image,
                dstOffset = IntOffset(frame.left.roundToInt(), frame.top.roundToInt()),
                dstSize = IntSize(frame.width.roundToInt(), frame.height.roundToInt()),
                filterQuality = FilterQuality.Medium,
            )
            drawPath(page, color, style = Stroke(1.5.dp.toPx() / MAGNIFICATION))
        }
        val hair = 10.dp.toPx()
        val width = 1.5.dp.toPx()
        drawLine(color, Offset(center.x - hair, center.y), Offset(center.x + hair, center.y), width)
        drawLine(color, Offset(center.x, center.y - hair), Offset(center.x, center.y + hair), width)
    }
    drawCircle(Color.White, radius, center, style = Stroke(3.dp.toPx()))
    drawCircle(color, radius + 1.5.dp.toPx(), center, style = Stroke(1.dp.toPx()))
}

/** Each corner's name, in a page's corner order. */
private val CORNER_NAMES = intArrayOf(
    R.string.crop_corner_top_left,
    R.string.crop_corner_top_right,
    R.string.crop_corner_bottom_right,
    R.string.crop_corner_bottom_left,
)

/** A corner's touch target, and the item a screen reader finds for it. */
private val HANDLE_SIZE = 56.dp

/** How far from a corner a press still takes it. */
private val HANDLE_REACH = 40.dp

private val MAGNIFIER_RADIUS = 56.dp
private const val MAGNIFICATION = 2.5f

/** A step of the arrow buttons: this much of the image's long side. */
private const val STEP = 0.004f

/** The image is shown at most this many pixels on its long side. */
private const val PREVIEW_MAX = 2048

/** The page's look is shown made on a straightened copy at most this many pixels on its long side. */
private const val LOOK_PREVIEW_MAX = 1200

/** How long the corners stay put before the page's look is made again for them. */
private const val LOOK_DELAY_MS = 150L

private val LOOK_PAINT = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG)

/** The page straightened out of the photo inside [corners] (as the screen shows the photo), with [look]. */
private class LookPreview(val look: PageLook, val corners: CropQuad, val page: Bitmap)

/** Keeps the corners as eight numbers. */
private val QuadSaver = Saver<CropQuad, FloatArray>(
    save = { quad -> FloatArray(8) { i -> quad[i / 2].let { if (i % 2 == 0) it.x else it.y } } },
    restore = { v -> CropQuad(List(4) { CropPoint(v[2 * it], v[2 * it + 1]) }) },
)
