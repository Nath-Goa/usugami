package io.github.nath_goa.usugami.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
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
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.core.CropPoint
import io.github.nath_goa.usugami.core.CropQuad
import io.github.nath_goa.usugami.core.LookAdjustments
import io.github.nath_goa.usugami.core.PageLook
import io.github.nath_goa.usugami.data.ImageItem
import io.github.nath_goa.usugami.data.OriginalImage
import io.github.nath_goa.usugami.data.PageCropper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What the crop screen chose for its page: [corners] as the screen shows the image turned [rotation]
 * degrees clockwise (the page's turn), the page's [look] and [adjustments], and whether every page
 * takes that look and those adjustments ([allPages]).
 */
class CropChoices(
    val corners: CropQuad,
    val rotation: Int,
    val look: PageLook,
    val adjustments: LookAdjustments,
    val allPages: Boolean,
)

/**
 * What the crop screen's buttons do: [done] finishes the page (an image of a scan goes on to the
 * next), [takeAnother] finishes a photo just taken and opens the camera again, and [close] leaves.
 */
class CropActions(
    val done: (CropChoices) -> Unit,
    val takeAnother: (CropChoices) -> Unit,
    val close: () -> Unit,
)

/**
 * Crops a page out of its image in two steps. First the page itself: the image (turned as on its
 * page) with four corners and four sides to drag onto the page's edges, a magnifier under the finger
 * while dragging, and buttons that move the chosen handle a step at a time (a screen reader finds
 * each handle, with the same moves as actions). Then its look: the page straightened as it will be,
 * the looks to choose from, its brightness and contrast (see [FilterStep]). On both, Rotate left and
 * right in the bar turn the image, the corners with it. Back on the second step goes back to the
 * first, with the corners kept. A photo just taken can be finished with Take another, and leaving
 * discards it; the images of a scan ([scan]) come one after another, and while the next is awaited
 * the screen says so. What the steps chose is kept through rotation, Android stopping Usugami, and a
 * tool opened over the screen for files shared to Usugami (UsugamiApp keeps it then).
 * [active] is false while the screen animates away: it then takes no Back. [arrived] is true once it
 * has come in.
 */
@Composable
fun CropScreen(
    session: CropSession?,
    items: List<ImageItem>,
    scan: ScanQueue?,
    active: Boolean,
    arrived: Boolean,
    snackbar: SnackbarHostState,
    actions: CropActions,
) {
    // Given to a page's screen while it animates away, as UsugamiApp does for screens.
    val idleSnackbar = remember { SnackbarHostState() }
    AnimatedContent(
        targetState = session,
        // Each opening is a screen of its own: one image of a scan slides away as the next comes.
        contentKey = { it?.opened },
        transitionSpec = { Motion.screenTransition(forward = true) },
        label = "crop page",
    ) { shown ->
        val live = active && transition.targetState == EnterExitState.Visible
        val settled = arrived && live && transition.currentState == EnterExitState.Visible
        Inert(!live) {
            val messages = if (live) snackbar else idleSnackbar
            if (shown == null) {
                ScanWaiting(freezeWhen(!live, scan), live, messages, actions.close)
            } else {
                val shownScan = freezeWhen(!live, scan)?.takeIf { shown.queued }
                CropSteps(
                    session = shown,
                    item = items.firstOrNull { it.id == shown.itemId },
                    // The images of the scan still being added are pages too: Apply to all pages reaches them.
                    pageCount = items.size + (shownScan?.coming ?: 0),
                    scan = shownScan,
                    live = live,
                    settled = settled,
                    snackbar = messages,
                    actions = actions,
                )
            }
        }
    }
}

/**
 * The crop screen's two steps for [session]'s page. [live] is false while the page's screen animates
 * away, [settled] true once it has come in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CropSteps(
    session: CropSession,
    item: ImageItem?,
    pageCount: Int,
    scan: ScanQueue?,
    live: Boolean,
    settled: Boolean,
    snackbar: SnackbarHostState,
    actions: CropActions,
) {
    // Kept through rotation and Android stopping Usugami.
    val state = rememberSaveable(session.opened, saver = CropState.Saver) {
        CropState(session.shown(session.start), session.rotation, session.look, session.adjustments)
    }
    val source = item?.source
    val window = LocalWindowInfo.current.containerSize
    val previewSide = min(PREVIEW_MAX, max(window.width, window.height))
    // The image upright, read once: the steps show it turned as the page is.
    val photo by produceState<Bitmap?>(null, source?.path) {
        val shown = source ?: return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching { PageCropper.upright(shown, 0, previewSide) }.getOrNull()
        }
    }
    BackHandler(enabled = live) { if (state.step == CropStep.FILTERS) state.step = CropStep.CROP else actions.close() }
    fun choices() = CropChoices(state.corners, state.rotation, state.look, state.adjustments, state.allPages && pageCount > 1)
    fun turn(degrees: Int) = state.turn(degrees, source?.uprightWidth ?: 1, source?.uprightHeight ?: 1)
    val idleSnackbar = remember { SnackbarHostState() }

    AnimatedContent(
        targetState = state.step,
        // A number: an enum's hash isn't the same each time Usugami starts, which would lose what
        // the step keeps for when Android stops it.
        contentKey = { it.ordinal },
        transitionSpec = { Motion.screenTransition(forward = targetState.ordinal > initialState.ordinal) },
        label = "crop step",
    ) { step ->
        val filters = step == CropStep.FILTERS
        val stepLive = transition.targetState == EnterExitState.Visible
        val here = settled && stepLive && transition.currentState == EnterExitState.Visible
        val title = stringResource(if (filters) R.string.filter_title else R.string.crop_title)
        // Which image of a scan this is goes with the name, so the next image is heard coming in.
        val pane = if (scan == null) title else stringResource(R.string.scan_pane_title, title, stringResource(R.string.scan_place, scan.place, scan.total))
        // Each step is a pane, which a screen reader names once it has come in. While it fades in, a
        // screen reader leaves it out, and only a change, such as this, makes it look again.
        Inert(!stepLive, Modifier.semantics { if (here) paneTitle = pane }) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { StepTitle(title, scan) },
                        navigationIcon = {
                            when {
                                filters -> IconButton(onClick = { state.step = CropStep.CROP }) {
                                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                                }
                                session.fromCamera || session.queued -> IconButton(onClick = actions.close) {
                                    Icon(Icons.Rounded.Close, contentDescription = stringResource(if (session.fromCamera) R.string.crop_discard else R.string.crop_stop))
                                }
                                else -> IconButton(onClick = actions.close) {
                                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                                }
                            }
                        },
                        // Rotate left and right: a quarter turn of the image, and of the page with it.
                        actions = {
                            IconButton(onClick = { turn(-90) }) {
                                TurnIcon(clockwise = false, description = stringResource(R.string.crop_rotate_left))
                            }
                            IconButton(onClick = { turn(90) }) {
                                TurnIcon(clockwise = true, description = stringResource(R.string.crop_rotate_right))
                            }
                        },
                    )
                },
                snackbarHost = { SnackbarHost(if (stepLive) snackbar else idleSnackbar) },
            ) { padding ->
                Box(Modifier.padding(padding)) {
                    if (!filters) {
                        CornersStep(session, source, photo, state, onNext = { state.step = CropStep.FILTERS })
                    } else {
                        FilterStep(
                            photo = photo,
                            corners = session.upright(state.corners, state.rotation),
                            rotation = state.rotation,
                            look = state.look,
                            adjustments = state.adjustments,
                            allPages = state.allPages.takeIf { pageCount > 1 },
                            onLook = { state.look = it },
                            onBrightness = { state.adjustments = state.adjustments.copy(brightness = it) },
                            onContrast = { state.adjustments = state.adjustments.copy(contrast = it) },
                            onAllPages = { state.allPages = it },
                            buttons = {
                                when {
                                    session.fromCamera -> StepButtons(
                                        main = StepButton(R.string.crop_done, Icons.Rounded.Check) { actions.done(choices()) },
                                        other = StepButton(R.string.crop_take_another, Icons.Rounded.PhotoCamera) { actions.takeAnother(choices()) },
                                    )
                                    scan != null && !scan.isLast -> StepButtons(
                                        StepButton(R.string.crop_next, Icons.AutoMirrored.Rounded.ArrowForward, iconAfter = true) { actions.done(choices()) },
                                    )
                                    else -> StepButtons(StepButton(R.string.crop_done, Icons.Rounded.Check) { actions.done(choices()) })
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** The steps of the crop screen, in order. */
private enum class CropStep { CROP, FILTERS }

/** A step's title, and under it, for an image of a scan, which of its images this is. */
@Composable
private fun StepTitle(title: String, scan: ScanQueue?) {
    Column {
        BarTitle(title)
        if (scan != null) {
            Text(
                stringResource(R.string.scan_place, scan.place, scan.total),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The crop screen while a scan waits for its next image: one still being added, or its page being
 * looked for. Closing it stops the scan.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScanWaiting(scan: ScanQueue?, live: Boolean, snackbar: SnackbarHostState, onClose: () -> Unit) {
    BackHandler(enabled = live, onBack = onClose)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { StepTitle(stringResource(R.string.crop_title), scan) },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.crop_stop)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
            if (scan != null && scan.waiting.isEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.adding_images),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

/**
 * What the crop screen's steps have chosen so far: the [corners] as the screen shows the image
 * turned [rotation] degrees clockwise, the handle the arrow buttons move ([chosen]: a corner, 0 to 3,
 * or a side, [CORNERS] + 0 to 3), the page's [look] and [adjustments], and whether every page takes
 * them ([allPages]).
 */
@Stable
private class CropState(
    corners: CropQuad,
    rotation: Int,
    look: PageLook,
    adjustments: LookAdjustments,
    step: CropStep = CropStep.CROP,
    chosen: Int = CropQuad.TOP_LEFT,
    allPages: Boolean = false,
) {
    var corners by mutableStateOf(corners)
    var rotation by mutableIntStateOf(rotation)
    var look by mutableStateOf(look)
    var adjustments by mutableStateOf(adjustments)
    var step by mutableStateOf(step)
    var chosen by mutableIntStateOf(chosen)
    var allPages by mutableStateOf(allPages)

    /**
     * Turns the image [degrees] clockwise (a quarter turn either way), the corners and the handle
     * chosen with it ([width] × [height]: the image upright).
     */
    fun turn(degrees: Int, width: Int, height: Int) {
        corners = corners.turned(degrees)
        val quarters = Math.floorMod(degrees, 360) / 90
        chosen = chosen / CORNERS * CORNERS + (chosen % CORNERS + quarters) % CORNERS
        rotation = Math.floorMod(rotation + degrees, 360)
        val sideways = rotation % 180 != 0
        settle(if (sideways) height else width, if (sideways) width else height)
    }

    /** Moves handle [handle] by ([dx], [dy]), as fractions of the image shown, [width] × [height] pixels. */
    fun move(handle: Int, dx: Float, dy: Float, width: Int, height: Int) {
        corners = if (handle < CORNERS) corners.moved(handle, dx, dy) else corners.movedSide(handle - CORNERS, dx, dy, width, height)
    }

    /**
     * Once a drag or a step is over, the corners take the order a page's corners have, so each is
     * named for where it is, and the handle chosen stays the same corner or side ([width] × [height]:
     * the image as shown).
     */
    fun settle(width: Int, height: Int) {
        val ordered = corners.ordered(width, height)
        if (ordered == corners || !ordered.isUsable(width, height)) return
        chosen = if (chosen < CORNERS) {
            ordered.corners.indexOf(corners[chosen]).coerceAtLeast(0)
        } else {
            val side = chosen - CORNERS
            val ends = setOf(corners[side], corners[(side + 1) % 4])
            CORNERS + ((0..3).firstOrNull { setOf(ordered[it], ordered[(it + 1) % 4]) == ends } ?: side)
        }
        corners = ordered
    }

    companion object {
        /** Keeps the state as numbers. */
        val Saver = listSaver<CropState, Any>(
            save = { state ->
                listOf(
                    FloatArray(8) { i -> state.corners[i / 2].let { if (i % 2 == 0) it.x else it.y } },
                    state.rotation,
                    state.look.ordinal,
                    state.adjustments.brightness,
                    state.adjustments.contrast,
                    state.step.ordinal,
                    state.chosen,
                    state.allPages,
                )
            },
            restore = { v ->
                val corners = v[0] as FloatArray
                CropState(
                    corners = CropQuad(List(4) { CropPoint(corners[2 * it], corners[2 * it + 1]) }),
                    rotation = v[1] as Int,
                    look = PageLook.entries[v[2] as Int],
                    adjustments = LookAdjustments(v[3] as Int, v[4] as Int),
                    step = CropStep.entries[v[5] as Int],
                    chosen = v[6] as Int,
                    allPages = v[7] as Boolean,
                )
            },
        )
    }
}

/**
 * The crop screen's first step: the image with the page's outline to drag onto the page, the hint
 * above it, the controls that move the chosen handle or reset the corners, and Next.
 */
@Composable
private fun CornersStep(session: CropSession, source: OriginalImage?, photo: Bitmap?, state: CropState, onNext: () -> Unit) {
    val sideways = state.rotation % 180 != 0
    // The image's size as the screen shows it, turned.
    val shownWidth = source?.let { if (sideways) it.uprightHeight else it.uprightWidth } ?: 1
    val shownHeight = source?.let { if (sideways) it.uprightWidth else it.uprightHeight } ?: 1
    val corners = state.corners
    val usable = corners.isWhole() || corners.ordered(shownWidth, shownHeight).isUsable(shownWidth, shownHeight)
    val image = remember(photo) { photo?.asImageBitmap() }

    // A move of the chosen handle (or of [handle]) by a step, from where it is when the button is
    // pressed: quick presses each move it a step.
    val step = STEP * max(shownWidth, shownHeight)
    fun nudge(handle: Int, dx: Int, dy: Int) {
        state.chosen = handle
        state.move(handle, dx * step / shownWidth, dy * step / shownHeight, shownWidth, shownHeight)
        state.settle(shownWidth, shownHeight)
    }

    StepLayout(
        hint = { wide ->
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
        },
        area = { modifier ->
            CropArea(
                image = image,
                rotation = state.rotation,
                aspect = shownWidth.toFloat() / shownHeight,
                corners = corners,
                chosen = state.chosen,
                onChoose = { state.chosen = it },
                onMoveCorner = { index, point -> state.corners = state.corners.with(index, point.x, point.y) },
                onMoveSide = { side, dx, dy -> state.move(CORNERS + side, dx, dy, shownWidth, shownHeight) },
                onNudge = ::nudge,
                onDragEnd = { state.settle(shownWidth, shownHeight) },
                modifier = modifier,
            )
        },
        controls = {
            CropControls(
                chosen = state.chosen,
                onNudge = { dx, dy -> nudge(state.chosen, dx, dy) },
                onReset = { state.corners = session.shown(session.found, state.rotation) },
                onWhole = { state.corners = CropQuad.WHOLE },
            )
        },
        buttons = {
            StepButtons(StepButton(R.string.crop_next, Icons.AutoMirrored.Rounded.ArrowForward, enabled = usable, iconAfter = true) {
                if (usable) {
                    state.settle(shownWidth, shownHeight)
                    onNext()
                }
            })
        },
    )
}

/**
 * A step's layout: the [area] (the image) as large as there is room for, with [hint] over it, and
 * [controls] under it or, on a wide screen, beside it. The controls never take more than half the
 * screen, however large the text, and scroll; [buttons] stay in sight below them.
 */
@Composable
internal fun StepLayout(
    hint: (@Composable (wide: Boolean) -> Unit)?,
    area: @Composable (Modifier) -> Unit,
    controls: @Composable () -> Unit,
    buttons: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > maxHeight
        val controlsHeight = maxHeight / 2
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                area(Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.width(320.dp).fillMaxHeight().padding(bottom = 8.dp)) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        hint?.invoke(true)
                        controls()
                    }
                    buttons()
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                hint?.invoke(false)
                area(Modifier.weight(1f).fillMaxWidth())
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

/** A step's button: its label and icon (after the label, for one that goes on), and what it does. */
internal class StepButton(
    @param:StringRes val label: Int,
    val icon: ImageVector,
    val enabled: Boolean = true,
    val iconAfter: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * A step's buttons at its foot: [main], filled, and before it [other], if any. Two are side by side,
 * as tall as each other, while each label there breaks only between its words; otherwise (large text
 * on a narrow column) one above the other, [main] last, each as wide as the column.
 */
@Composable
internal fun StepButtons(main: StepButton, other: StepButton? = null) {
    val padding = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    val mainButton = @Composable {
        Button(onClick = main.onClick, enabled = main.enabled, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
            StepButtonContent(main)
        }
    }
    if (other == null) {
        Box(padding, propagateMinConstraints = true) { mainButton() }
        return
    }
    Layout(
        content = {
            OutlinedButton(onClick = other.onClick, enabled = other.enabled, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                StepButtonContent(other)
            }
            mainButton()
        },
        modifier = padding,
    ) { buttons, constraints ->
        val width = constraints.maxWidth
        val half = (width - 12.dp.roundToPx()) / 2
        // A button is as narrow as it gets without breaking a word when its label's longest word fits.
        if (buttons.all { it.minIntrinsicWidth(Constraints.Infinity) <= half }) {
            val height = buttons.maxOf { it.maxIntrinsicHeight(half) }
            val placed = buttons.map { it.measure(Constraints.fixed(half, height)) }
            layout(width, height) {
                placed[0].placeRelative(0, 0)
                placed[1].placeRelative(width - half, 0)
            }
        } else {
            val placed = buttons.map { it.measure(Constraints(minWidth = width, maxWidth = width)) }
            val gap = 8.dp.roundToPx()
            layout(width, placed[0].height + gap + placed[1].height) {
                placed[0].placeRelative(0, 0)
                placed[1].placeRelative(0, placed[0].height + gap)
            }
        }
    }
}

@Composable
private fun StepButtonContent(button: StepButton) {
    val icon = @Composable { Icon(button.icon, contentDescription = null, modifier = Modifier.size(18.dp)) }
    if (!button.iconAfter) {
        icon()
        Spacer(Modifier.width(8.dp))
    }
    Text(stringResource(button.label), textAlign = TextAlign.Center)
    if (button.iconAfter) {
        Spacer(Modifier.width(8.dp))
        icon()
    }
}

/**
 * [content], which takes no touches and which a screen reader no longer finds while [inert]: a
 * screen or step animating away, whose buttons could otherwise still be pressed.
 */
@Composable
private fun Inert(inert: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(if (inert) Modifier.clearAndSetSemantics {} else modifier) {
        content()
        if (inert) {
            Box(
                Modifier
                    .matchParentSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    },
            )
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
    PageLook.LIGHTEN -> R.string.look_lighten
    PageLook.SUPER_ENHANCED -> R.string.look_super_enhanced
    PageLook.INK_SAVER -> R.string.look_ink_saver
}

/** The controls under the image: moving the chosen handle (a side only across itself), and Reset and Whole image. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CropControls(
    chosen: Int,
    onNudge: (dx: Int, dy: Int) -> Unit,
    onReset: () -> Unit,
    onWhole: () -> Unit,
) {
    // A side moves only across itself: the top and bottom up and down, the others left and right.
    val across = chosen < CORNERS || (chosen - CORNERS) % 2 == 1
    val upDown = chosen < CORNERS || (chosen - CORNERS) % 2 == 0
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        // Which handle the arrows move; tapping a handle chooses it.
        Text(
            stringResource(HANDLE_NAMES[chosen]),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { onNudge(-1, 0) }, enabled = across) { Icon(Icons.Rounded.West, contentDescription = stringResource(R.string.edit_move_left)) }
        IconButton(onClick = { onNudge(0, -1) }, enabled = upDown) { Icon(Icons.Rounded.North, contentDescription = stringResource(R.string.edit_move_up)) }
        IconButton(onClick = { onNudge(0, 1) }, enabled = upDown) { Icon(Icons.Rounded.South, contentDescription = stringResource(R.string.edit_move_down)) }
        IconButton(onClick = { onNudge(1, 0) }, enabled = across) { Icon(Icons.Rounded.East, contentDescription = stringResource(R.string.edit_move_right)) }
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

/**
 * The image (turned [rotation] degrees) with the page's outline, the area outside it dimmed, and its
 * handles: the four corners, and a bar in the middle of each side. Pressing near a handle drags it,
 * with a magnifier showing what is under it: a corner goes where the finger takes it, a side moves
 * across itself (see [CropQuad.movedSide]). The handles are also items of their own for a screen
 * reader, which moves them with actions.
 */
@Composable
private fun CropArea(
    image: ImageBitmap?,
    rotation: Int,
    aspect: Float,
    corners: CropQuad,
    chosen: Int,
    onChoose: (Int) -> Unit,
    onMoveCorner: (Int, CropPoint) -> Unit,
    onMoveSide: (side: Int, dx: Float, dy: Float) -> Unit,
    onNudge: (handle: Int, dx: Int, dy: Int) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier,
) {
    val latest by rememberUpdatedState(corners)
    val moveCorner by rememberUpdatedState(onMoveCorner)
    val moveSide by rememberUpdatedState(onMoveSide)
    val choose by rememberUpdatedState(onChoose)
    val dragEnded by rememberUpdatedState(onDragEnd)
    // The handle being dragged and where the finger is, while one is.
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
        // The image, as large as fits with room around it for the handles.
        val fitWidth = max(1f, areaWidth - 2 * room)
        val fitHeight = max(1f, areaHeight - 2 * room)
        val imageWidth = min(fitWidth, fitHeight * aspect)
        val imageHeight = imageWidth / aspect
        val frame = Rect(Offset((areaWidth - imageWidth) / 2, (areaHeight - imageHeight) / 2), Size(imageWidth, imageHeight))
        fun at(p: CropPoint) = Offset(frame.left + p.x * frame.width, frame.top + p.y * frame.height)
        // Read as it is now by the gesture, which a change of layout doesn't stop: a handle being
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
                        // The nearest handle; a corner before a side as near.
                        val index = (0 until HANDLES).minBy { (atNow(quad.handle(it)) - down.position).getDistance() }
                        if ((atNow(quad.handle(index)) - down.position).getDistance() > reach) return@awaitEachGesture
                        down.consume()
                        choose(index)
                        // Where the corner is from the finger.
                        val grip = atNow(quad.handle(index)) - down.position
                        dragging = index
                        finger = down.position
                        try {
                            drag(down.id) { change ->
                                val now = shownFrame
                                finger = change.position
                                if (index < CORNERS) {
                                    val p = change.position + grip
                                    moveCorner(index, CropPoint((p.x - now.left) / now.width, (p.y - now.top) / now.height))
                                } else {
                                    // A side moves as far as the finger did since the last move.
                                    val moved = change.position - change.previousPosition
                                    moveSide(index - CORNERS, moved.x / now.width, moved.y / now.height)
                                }
                                change.consume()
                            }
                        } finally {
                            dragging = null
                            dragEnded()
                        }
                    }
                },
        ) {
            if (image == null) drawRect(placeholder, frame.topLeft, frame.size) else drawTurned(image, frame, rotation)
            val points = corners.corners.map(::at)
            val page = Path().apply {
                moveTo(points[0].x, points[0].y)
                for (i in 1..3) lineTo(points[i].x, points[i].y)
                close()
            }
            // What will be left out is dimmed.
            val outside = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(frame)
                addPath(page)
            }
            drawPath(outside, Color.Black.copy(alpha = 0.45f))
            drawPath(page, outline, style = Stroke(2.dp.toPx()))
            for (side in 0..3) {
                val from = points[side]
                val to = points[(side + 1) % 4]
                drawSideHandle((from + to) / 2f, to - from, chosen == CORNERS + side, outline)
            }
            points.forEachIndexed { i, p -> drawHandle(p, i == chosen, outline) }
            val held = dragging
            if (held != null && image != null) drawMagnifier(image, frame, rotation, page, at(corners.handle(held)), finger, outline)
        }
        if (image == null) CircularProgressIndicator(Modifier.align(Alignment.Center))

        // The handles for a screen reader: each is an item named for where it is, chosen by a double
        // tap, with actions that move it (a side only across itself).
        val handleSize = with(density) { HANDLE_SIZE.toPx() }
        val left = stringResource(R.string.edit_move_left)
        val up = stringResource(R.string.edit_move_up)
        val down = stringResource(R.string.edit_move_down)
        val right = stringResource(R.string.edit_move_right)
        for (i in 0 until HANDLES) {
            val name = stringResource(HANDLE_NAMES[i])
            val across = i < CORNERS || (i - CORNERS) % 2 == 1
            val upDown = i < CORNERS || (i - CORNERS) % 2 == 0
            Box(
                Modifier
                    .offset {
                        val p = at(corners.handle(i))
                        IntOffset((p.x - handleSize / 2).roundToInt(), (p.y - handleSize / 2).roundToInt())
                    }
                    .size(HANDLE_SIZE)
                    .semantics {
                        contentDescription = name
                        // Which handle the arrow buttons move: one of eight, like a radio button.
                        role = Role.RadioButton
                        selected = i == chosen
                        onClick {
                            onChoose(i)
                            true
                        }
                        customActions = listOfNotNull(
                            CustomAccessibilityAction(left) { onNudge(i, -1, 0); true }.takeIf { across },
                            CustomAccessibilityAction(up) { onNudge(i, 0, -1); true }.takeIf { upDown },
                            CustomAccessibilityAction(down) { onNudge(i, 0, 1); true }.takeIf { upDown },
                            CustomAccessibilityAction(right) { onNudge(i, 1, 0); true }.takeIf { across },
                        )
                    },
            )
        }
    }
}

/** Where handle [handle] is: a corner, or the middle of a side (see [CropState.chosen]). */
private fun CropQuad.handle(handle: Int): CropPoint {
    if (handle < CORNERS) return this[handle]
    val from = this[handle - CORNERS]
    val to = this[(handle - CORNERS + 1) % 4]
    return CropPoint((from.x + to.x) / 2, (from.y + to.y) / 2)
}

/** [image], the photo upright, drawn turned [rotation] degrees clockwise to fill [frame]. */
internal fun DrawScope.drawTurned(image: ImageBitmap, frame: Rect, rotation: Int) {
    val sideways = rotation % 180 != 0
    val width = if (sideways) frame.height else frame.width
    val height = if (sideways) frame.width else frame.height
    rotate(rotation.toFloat(), pivot = frame.center) {
        drawImage(
            image,
            dstOffset = IntOffset((frame.center.x - width / 2).roundToInt(), (frame.center.y - height / 2).roundToInt()),
            dstSize = IntSize(width.roundToInt(), height.roundToInt()),
            filterQuality = FilterQuality.Medium,
        )
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
 * A side's handle: a short rounded bar along the side ([along]: the side's direction) at its middle,
 * filled when it is the one the arrow buttons move.
 */
private fun DrawScope.drawSideHandle(center: Offset, along: Offset, chosen: Boolean, color: Color) {
    val length = 28.dp.toPx()
    val thickness = (if (chosen) 10 else 8).dp.toPx()
    val shadow = 2.dp.toPx()
    rotate((atan2(along.y, along.x) * 180 / PI).toFloat(), pivot = center) {
        // The bar lying along the x axis through its middle, [grow] larger all round.
        fun bar(grow: Float) = Rect(
            Offset(center.x - length / 2 - grow, center.y - thickness / 2 - grow),
            Size(length + 2 * grow, thickness + 2 * grow),
        )
        val outer = bar(shadow)
        val inner = bar(0f)
        drawRoundRect(Color.Black.copy(alpha = 0.3f), outer.topLeft, outer.size, CornerRadius(outer.height / 2))
        drawRoundRect(if (chosen) color else Color.White, inner.topLeft, inner.size, CornerRadius(inner.height / 2))
        drawRoundRect(if (chosen) Color.White else color, inner.topLeft, inner.size, CornerRadius(inner.height / 2), style = Stroke(2.dp.toPx()))
    }
}

/**
 * A round magnifier in a top corner away from the finger, showing the image around the handle being
 * dragged ([handle]), larger, with cross hairs on it: the finger hides it otherwise.
 */
private fun DrawScope.drawMagnifier(image: ImageBitmap, frame: Rect, rotation: Int, page: Path, handle: Offset, finger: Offset, color: Color) {
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
            translate(center.x - handle.x * MAGNIFICATION, center.y - handle.y * MAGNIFICATION)
            scale(MAGNIFICATION, MAGNIFICATION, pivot = Offset.Zero)
        }) {
            drawTurned(image, frame, rotation)
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

/** A page's four corners, then its four sides: the handles of the crop step (see [CropState.chosen]). */
private const val CORNERS = 4
private const val HANDLES = 8

/** Each handle's name: the corners in a page's corner order, then the sides, each from its corner. */
private val HANDLE_NAMES = intArrayOf(
    R.string.crop_corner_top_left,
    R.string.crop_corner_top_right,
    R.string.crop_corner_bottom_right,
    R.string.crop_corner_bottom_left,
    R.string.crop_side_top,
    R.string.crop_side_right,
    R.string.crop_side_bottom,
    R.string.crop_side_left,
)

/** A handle's touch target, and the item a screen reader finds for it. */
private val HANDLE_SIZE = 56.dp

/** How far from a handle a press still takes it. */
private val HANDLE_REACH = 40.dp

private val MAGNIFIER_RADIUS = 56.dp
private const val MAGNIFICATION = 2.5f

/** A step of the arrow buttons: this much of the image's long side. */
private const val STEP = 0.004f

/** The image is shown at most this many pixels on its long side. */
private const val PREVIEW_MAX = 2048
