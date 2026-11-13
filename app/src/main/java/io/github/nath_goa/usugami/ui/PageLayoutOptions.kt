package io.github.nath_goa.usugami.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import androidx.lifecycle.SavedStateHandle
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.core.CropBoxes
import io.github.nath_goa.usugami.core.CropHandle
import io.github.nath_goa.usugami.core.CropShare
import io.github.nath_goa.usugami.core.PageSelectionException
import io.github.nath_goa.usugami.core.PageSize
import io.github.nath_goa.usugami.core.PdfPageSelection
import io.github.nath_goa.usugami.core.SheetLayout
import io.github.nath_goa.usugami.data.PdfInput
import io.github.nath_goa.usugami.data.cropPages
import io.github.nath_goa.usugami.data.pagesPerSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Page layout's own state in the view model (see [ToolState]): nothing. Its choices live in the
 * options ([PageLayoutChoices]); Back has nothing to ask about and nothing needs clearing.
 */
internal class PageLayoutState(@Suppress("unused") private val handle: SavedStateHandle) : ToolState

/** The two things Page layout does: crop pages, or put several on each sheet. */
internal enum class PageLayoutMode { CROP, SHEETS }

/** What Page layout is asked to make. */
internal sealed interface PageLayoutRequest {
    /** Crop the pages typed in [pagesText] (null: all of them) to [share] of what each shows now. */
    data class Crop(val share: CropShare, val pagesText: String?) : PageLayoutRequest

    /**
     * [perSheet] pages on each sheet of [paper], with a [border] round each. The screen always sends
     * the paper its row shows; null (as the first page, SheetLayout.paperLike) is the engine's own default.
     */
    data class Sheets(val perSheet: Int, val paper: PageSize?, val border: Boolean) : PageLayoutRequest
}

/**
 * Page layout's choices, kept across rotation, Android closing Usugami and "Back to editing" by
 * [Saver]. They are remembered for one PDF: another starts with the first choices.
 */
@Stable
internal class PageLayoutChoices {
    var mode by mutableStateOf(PageLayoutMode.CROP)
    var crop by mutableStateOf(CropShare.WHOLE)
    /** The page the crop preview shows (zero-based); it doesn't choose which pages are cropped. */
    var pageShown by mutableIntStateOf(0)
    /**
     * Some pages rather than all. Only ever chosen for a PDF of more than one page: the choice isn't
     * offered for one page, and the choices are remembered for one PDF.
     */
    var somePages by mutableStateOf(false)
    var pagesText by mutableStateOf("")
    var perSheet by mutableIntStateOf(SheetLayout.COUNTS.first())
    /**
     * The paper chosen; null while none is, when the paper row shows (and the sheets get) the one
     * [paperFor] gives. It stays null until a paper is tapped, so the first page decides once its size is known.
     */
    var paper by mutableStateOf<PageSize?>(null)
    var border by mutableStateOf(false)

    /** Whether the button is on: a box that crops something (and pages typed, for Some pages), or sheets. */
    val ready: Boolean
        get() = when (mode) {
            PageLayoutMode.CROP -> !crop.isWhole && (!somePages || pagesText.isNotBlank())
            PageLayoutMode.SHEETS -> true
        }

    /** The button's words; null for the tool's own, "Save PDF" (pdf_page_layout_action). */
    @get:StringRes
    val action: Int?
        get() = if (mode == PageLayoutMode.CROP) R.string.pdf_layout_crop_action else null

    /**
     * The paper the paper row shows and the sheets get: the one chosen, else as the first of the pages'
     * shown [sizes] (SheetLayout.paperLike), or A4 while they are unknown (still being read, or unreadable).
     */
    fun paperFor(sizes: List<Pair<Float, Float>>?): PageSize =
        paper ?: sizes?.firstOrNull()?.let { (width, height) -> SheetLayout.paperLike(width, height) } ?: PageSize.A4

    /** What the button asks for; [sizes] as for [paperFor], so the sheets have the paper the screen shows. */
    fun request(sizes: List<Pair<Float, Float>>?): PageLayoutRequest = when (mode) {
        PageLayoutMode.CROP -> PageLayoutRequest.Crop(crop, if (somePages) pagesText else null)
        PageLayoutMode.SHEETS -> PageLayoutRequest.Sheets(perSheet, paperFor(sizes), border)
    }

    companion object {
        /** Anything saved that can't be read back gives the first choice for it. */
        val Saver: Saver<PageLayoutChoices, Any> = listSaver(
            save = {
                listOf(
                    it.mode.name, CropBoxes.pack(it.crop), it.pageShown, it.somePages,
                    it.pagesText, it.perSheet, it.paper?.name.orEmpty(), it.border,
                )
            },
            restore = { saved ->
                PageLayoutChoices().apply {
                    mode = PageLayoutMode.entries.firstOrNull { it.name == saved.getOrNull(0) } ?: PageLayoutMode.CROP
                    crop = (saved.getOrNull(1) as? FloatArray)?.let(CropBoxes::unpack) ?: CropShare.WHOLE
                    pageShown = (saved.getOrNull(2) as? Int)?.coerceAtLeast(0) ?: 0
                    somePages = saved.getOrNull(3) as? Boolean ?: false
                    pagesText = saved.getOrNull(4) as? String ?: ""
                    perSheet = (saved.getOrNull(5) as? Int)?.takeIf { it in SheetLayout.COUNTS } ?: SheetLayout.COUNTS.first()
                    paper = SheetLayout.PAPERS.firstOrNull { it.name == saved.getOrNull(6) }
                    border = saved.getOrNull(7) as? Boolean ?: false
                }
            },
        )
    }
}

/**
 * Page layout's choices, for [input]; its pages are drawn from [shown] (see
 * PdfToolsEngine.previewView). They tell [action] when the button is on, what it says (Crop PDF,
 * or the tool's own Save PDF) and what it does. The name field stays the screen's.
 */
@Composable
internal fun PageLayoutOptions(input: PdfInput, shown: PdfInput, vm: PdfToolsViewModel, action: ToolAction) {
    val state = rememberSaveable(input.id, saver = PageLayoutChoices.Saver) { PageLayoutChoices() }
    // Every page's size as shown, exactly, from the PDF the previews are drawn from.
    val sizes by produceState<List<Pair<Float, Float>>?>(null, shown.path) { value = vm.shownPageSizes(shown) }
    // Once, here: the button follows the choices for as long as these options show. The sizes are
    // read at the tap, so the paper asked for is the one the paper row shows then.
    action.Bind(ready = state.ready, label = state.action?.let { stringResource(it) }) { fileName ->
        vm.runPageLayout(input, state.request(sizes), fileName)
    }

    // The sections that come and go carry the gap above them, so each gap opens and closes with its section.
    Column {
        Segments(PageLayoutMode.entries, state.mode, onSelect = { state.mode = it }) {
            stringResource(if (it == PageLayoutMode.CROP) R.string.layout_mode_crop else R.string.layout_mode_sheets)
        }
        AnimatedVisibility(visible = state.mode == PageLayoutMode.CROP, enter = pdfSectionEnter(), exit = pdfSectionExit(), label = "crop") {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                CropChoices(input, shown, sizes, state)
            }
        }
        AnimatedVisibility(visible = state.mode == PageLayoutMode.SHEETS, enter = pdfSectionEnter(), exit = pdfSectionExit(), label = "sheets") {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SheetChoices(input, shown, sizes, state)
            }
        }
    }
}

/** Crop's choices: the note, the page shown, the box with its buttons, and the pages to crop. */
@Composable
private fun CropChoices(input: PdfInput, shown: PdfInput, sizes: List<Pair<Float, Float>>?, state: PageLayoutChoices) {
    val pageIndex = state.pageShown.coerceIn(0, maxOf(0, input.pageCount - 1))
    Note(R.string.layout_crop_note)
    if (input.pageCount > 1) {
        Text(stringResource(R.string.layout_crop_page), style = MaterialTheme.typography.titleMedium)
        PageStrip(shown, pageIndex, onSelect = { state.pageShown = it })
    }
    Text(stringResource(R.string.layout_crop_box), style = MaterialTheme.typography.titleMedium)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CropEditor(shown, pageIndex, sizes, state.crop, onCrop = { state.crop = it })
        Text(
            stringResource(R.string.layout_crop_box_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
        )
        // Each press steps the box as it is then, which may be further on than the screen shows yet.
        CropSteps(state.crop, onStep = { step -> state.crop = step(state.crop) }, Modifier.padding(top = 8.dp))
    }
    if (input.pageCount > 1) {
        Column {
            Text(stringResource(R.string.layout_crop_pages), style = MaterialTheme.typography.titleMedium)
            Segments(listOf(false, true), state.somePages, Modifier.padding(top = 8.dp), onSelect = { some ->
                // "Only this page" is one tap: an empty field starts with the page shown.
                if (some && state.pagesText.isBlank()) state.pagesText = "${pageIndex + 1}"
                state.somePages = some
            }) { stringResource(if (it) R.string.layout_pages_some else R.string.layout_pages_all) }
            AnimatedVisibility(visible = state.somePages, enter = pdfSectionEnter(), exit = pdfSectionExit(), label = "pages to crop") {
                OutlinedTextField(
                    value = state.pagesText,
                    onValueChange = { state.pagesText = it },
                    label = { Text(stringResource(R.string.layout_pages_field)) },
                    supportingText = { Text(stringResource(R.string.layout_pages_hint)) },
                    singleLine = true,
                    modifier = Modifier.padding(top = 16.dp).fillMaxWidth(),
                )
            }
        }
    }
}

/** Pages per sheet's choices: how many, the paper, the border, the first sheet as it will be, and the note. */
@Composable
private fun SheetChoices(input: PdfInput, shown: PdfInput, sizes: List<Pair<Float, Float>>?, state: PageLayoutChoices) {
    // The paper the sheets will have, also before one is chosen: the button asks for this one.
    val paper = state.paperFor(sizes)
    Text(stringResource(R.string.layout_mode_sheets), style = MaterialTheme.typography.titleMedium)
    val counts = SheetLayout.COUNTS.associateWith { pluralStringResource(R.plurals.layout_sheets_count, it, it) }
    Segments(SheetLayout.COUNTS, state.perSheet, onSelect = { state.perSheet = it }, described = { counts.getValue(it) }) { "$it" }
    Column {
        Text(stringResource(R.string.layout_sheets_paper), style = MaterialTheme.typography.titleMedium)
        Segments(SheetLayout.PAPERS, paper, Modifier.padding(top = 8.dp), onSelect = { state.paper = it }) {
            stringResource(if (it == PageSize.LETTER) R.string.size_letter else R.string.size_a4)
        }
        Note(R.string.layout_sheets_auto_note, Modifier.padding(top = 6.dp))
    }
    // The whole row flips the switch.
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = state.border, role = Role.Switch, onValueChange = { state.border = it })
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.layout_sheets_border), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = state.border, onCheckedChange = null, modifier = Modifier.padding(start = 16.dp))
    }
    Text(stringResource(R.string.layout_sheets_preview), style = MaterialTheme.typography.titleMedium)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        SheetPreview(shown, input.pageCount, sizes, state.perSheet, paper, state.border)
        val sheets = SheetLayout.sheets(input.pageCount, state.perSheet)
        Text(
            pluralStringResource(R.plurals.layout_sheets_made, sheets, sheets),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp).fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    Note(R.string.layout_sheets_note)
}

/**
 * Page [pageIndex] of [shown] as it shows, with the crop box [crop] on it: the part outside is
 * dimmed. Dragging a corner moves its two sides, dragging a side moves that side. A touch that
 * starts on neither scrolls the screen, so the large preview doesn't trap scrolling.
 */
@Composable
private fun CropEditor(shown: PdfInput, pageIndex: Int, sizes: List<Pair<Float, Float>>?, crop: CropShare, onCrop: (CropShare) -> Unit) {
    // The page last drawn stays while the next one is drawn, instead of flashing blank. It is drawn
    // exactly as big as the PDF has it once that is known, as Sign PDF's and Redact PDF's are.
    var page by remember(shown.path) { mutableStateOf<RenderedPage?>(null) }
    LaunchedEffect(shown.path, pageIndex, sizes) {
        page = withContext(Dispatchers.IO) { renderShownPage(shown, pageIndex, 1000, sizes?.getOrNull(pageIndex)) }
    }
    val latest by rememberUpdatedState(crop)
    val move by rememberUpdatedState(onCrop)
    val frame = MaterialTheme.colorScheme.primary
    // Until the page has been drawn, its size stands in for it, or an A4 page's.
    val aspect = (page?.let { it.width / it.height } ?: sizes?.getOrNull(pageIndex)?.let { it.first / it.second })
        ?.takeIf { it.isFinite() && it > 0f } ?: (595f / 842f)
    val description = stringResource(R.string.layout_crop_preview_description, pageIndex + 1)
    val kept = stringResource(R.string.layout_crop_preview_state, (crop.width * 100).roundToInt(), (crop.height * 100).roundToInt())
    // A tall page is at most 440 dp high, and less in landscape, so the whole page fits on the screen.
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val tallest = min(440.dp, max(240.dp, windowHeight - 180.dp))
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val width = min(maxWidth - PAGE_INSET * 2, tallest * aspect)
        val height = width / aspect
        Box(Modifier.size(width + PAGE_INSET * 2, height + PAGE_INSET * 2)) {
            // The page with its shadow; the box is drawn over it and a little beyond, so that the
            // handles on the page's edges, where the box starts, show whole.
            Box(Modifier.padding(PAGE_INSET).fillMaxSize().shadow(2.dp, RectangleShape).background(Color.White))
            Canvas(
                Modifier
                    .matchParentSize()
                    // A side dragged from the page's edge moves, instead of going back.
                    .systemGestureExclusion()
                    .semantics {
                        contentDescription = description
                        stateDescription = kept
                    }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val inset = PAGE_INSET.toPx()
                            val w = size.width - 2 * inset
                            val h = size.height - 2 * inset
                            if (w <= 0f || h <= 0f) return@awaitEachGesture
                            val start = latest
                            val reach = 24.dp.toPx()
                            // Not on a handle: not taken, so the screen scrolls.
                            val handle = CropBoxes.grab(start, (down.position.x - inset) / w, (down.position.y - inset) / h, reach / w, reach / h)
                                ?: return@awaitEachGesture
                            down.consume()
                            drag(down.id) { change ->
                                val moved = change.position - down.position
                                move(CropBoxes.dragged(start, handle, moved.x / w, moved.y / h))
                                change.consume()
                            }
                        }
                    },
            ) {
                val inset = PAGE_INSET.toPx()
                val pageSize = Size(size.width - 2 * inset, size.height - 2 * inset)
                page?.let {
                    drawImage(
                        it.image,
                        dstOffset = IntOffset(inset.roundToInt(), inset.roundToInt()),
                        dstSize = IntSize(pageSize.width.roundToInt().coerceAtLeast(1), pageSize.height.roundToInt().coerceAtLeast(1)),
                    )
                }
                translate(inset, inset) { drawCropBox(crop, pageSize, frame) }
            }
        }
    }
}

/** Room round the crop preview's page, for the handles on its edges. */
private val PAGE_INSET = 8.dp

/**
 * [crop] on a page of [page] size: what is outside dimmed, the frame, a round handle on each corner
 * and a bar in the middle of each side.
 */
private fun DrawScope.drawCropBox(crop: CropShare, page: Size, frame: Color) {
    val left = crop.left * page.width
    val top = crop.top * page.height
    val right = crop.right * page.width
    val bottom = crop.bottom * page.height
    // One dimmed sheet with the box cut out: four strips side by side would show seams where they meet.
    clipRect(left, top, right, bottom, clipOp = ClipOp.Difference) {
        drawRect(Color.Black.copy(alpha = 0.45f), Offset.Zero, page)
    }
    drawRect(frame, Offset(left, top), Size(right - left, bottom - top), style = Stroke(1.5.dp.toPx()))
    for (corner in listOf(Offset(left, top), Offset(right, top), Offset(right, bottom), Offset(left, bottom))) {
        drawCircle(Color.White, radius = 7.dp.toPx(), center = corner)
        drawCircle(frame, radius = 5.dp.toPx(), center = corner)
    }
    val long = 24.dp.toPx()
    val thick = 4.dp.toPx()
    val edge = 2.dp.toPx()
    val midX = (left + right) / 2
    val midY = (top + bottom) / 2
    for ((center, across) in listOf(Offset(midX, top) to true, Offset(midX, bottom) to true, Offset(left, midY) to false, Offset(right, midY) to false)) {
        val barWidth = if (across) long else thick
        val barHeight = if (across) thick else long
        drawRoundRect(
            Color.White,
            topLeft = Offset(center.x - barWidth / 2 - edge, center.y - barHeight / 2 - edge),
            size = Size(barWidth + 2 * edge, barHeight + 2 * edge),
            cornerRadius = CornerRadius(thick / 2 + edge),
        )
        drawRoundRect(
            frame,
            topLeft = Offset(center.x - barWidth / 2, center.y - barHeight / 2),
            size = Size(barWidth, barHeight),
            cornerRadius = CornerRadius(thick / 2),
        )
    }
}

/**
 * Buttons that crop a side 1% more or less, for those who can't drag and for fine steps, then
 * Whole page. [onStep] applies a step to the box as it is at that moment: quick presses each take
 * a step.
 */
@Composable
private fun CropSteps(crop: CropShare, onStep: ((CropShare) -> CropShare) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        // Each side's two buttons stay together, the sides one under another on a narrow screen.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            SideSteps(CropHandle.LEFT, R.string.layout_crop_left, R.string.layout_crop_less_left, R.string.layout_crop_more_left, crop, onStep)
            SideSteps(CropHandle.TOP, R.string.layout_crop_top, R.string.layout_crop_less_top, R.string.layout_crop_more_top, crop, onStep)
            SideSteps(CropHandle.RIGHT, R.string.layout_crop_right, R.string.layout_crop_less_right, R.string.layout_crop_more_right, crop, onStep)
            SideSteps(CropHandle.BOTTOM, R.string.layout_crop_bottom, R.string.layout_crop_less_bottom, R.string.layout_crop_more_bottom, crop, onStep)
        }
        TextButton(onClick = { onStep { CropShare.WHOLE } }, enabled = !crop.isWhole) {
            Text(stringResource(R.string.layout_crop_whole))
        }
    }
}

/** A side's name with its − and + buttons; a screen reader hears the buttons' own names. */
@Composable
private fun SideSteps(
    side: CropHandle,
    @StringRes name: Int,
    @StringRes less: Int,
    @StringRes more: Int,
    crop: CropShare,
    onStep: ((CropShare) -> CropShare) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(name), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.clearAndSetSemantics {})
        StepButton(Icons.Rounded.Remove, less, side, more = false, crop, onStep)
        StepButton(Icons.Rounded.Add, more, side, more = true, crop, onStep)
    }
}

/** Moves [side] a step; off once that side can't go further that way. */
@Composable
private fun StepButton(icon: ImageVector, @StringRes label: Int, side: CropHandle, more: Boolean, crop: CropShare, onStep: ((CropShare) -> CropShare) -> Unit) {
    IconButton(onClick = { onStep { CropBoxes.stepped(it, side, more) } }, enabled = CropBoxes.stepped(crop, side, more) != crop) {
        Icon(icon, contentDescription = stringResource(label))
    }
}

/**
 * The first sheet as the PDF will have it: a white sheet of [paper], turned as the pages come out
 * biggest, with the first [perSheet] of the [pageCount] pages of [shown] where they will land (and
 * their borders when [border]). The pages come in one after another; each is kept while the
 * choices change, and drawn again sharper when fewer to a sheet make it bigger.
 */
@Composable
private fun SheetPreview(shown: PdfInput, pageCount: Int, sizes: List<Pair<Float, Float>>?, perSheet: Int, paper: PageSize, border: Boolean) {
    // Every page counts in which way round the sheet goes, as for the PDF made; until their sizes are known, A4 pages stand in.
    val grid = remember(sizes, pageCount, perSheet, paper) {
        SheetLayout.grid(perSheet, paper, sizes ?: List(minOf(pageCount, perSheet).coerceAtLeast(1)) { 595f to 842f })
    }
    val onSheet = minOf(perSheet, pageCount)
    val longSide = when (perSheet) {
        2 -> 700
        4 -> 500
        else -> 350
    }
    // Page index to its picture and the long side it was drawn at.
    val pictures = remember(shown.path) { mutableStateMapOf<Int, Pair<Int, ImageBitmap>>() }
    LaunchedEffect(shown.path, onSheet, longSide) {
        for (index in 0 until onSheet) {
            if ((pictures[index]?.first ?: 0) >= longSide) continue
            val image = withContext(Dispatchers.IO) { renderPdfPage(shown, index, longSide) } ?: continue
            pictures[index] = longSide to image
        }
    }
    val placeholder = MaterialTheme.colorScheme.surfaceVariant
    val description = pluralStringResource(R.plurals.layout_sheets_preview_description, onSheet, onSheet)
    val aspect = grid.width / grid.height
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val tallest = min(440.dp, max(240.dp, windowHeight - 180.dp))
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val width = min(maxWidth, tallest * aspect)
        Canvas(
            Modifier
                .size(width, width / aspect)
                .shadow(2.dp, RectangleShape)
                .background(Color.White)
                .semantics { contentDescription = description },
        ) {
            val scale = size.width / grid.width
            for (index in 0 until onSheet) {
                val (pageWidth, pageHeight) = sizes?.getOrNull(index) ?: (595f to 842f)
                val slot = SheetLayout.slot(grid, index, pageWidth, pageHeight) ?: continue
                // The sheet's y runs up from its bottom; the screen's down from its top.
                val topLeft = Offset(slot.x * scale, (grid.height - slot.y - slot.height) * scale)
                val slotSize = Size(slot.width * scale, slot.height * scale)
                val picture = pictures[index]?.second
                if (picture != null) {
                    drawImage(
                        picture,
                        dstOffset = IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()),
                        dstSize = IntSize(slotSize.width.roundToInt().coerceAtLeast(1), slotSize.height.roundToInt().coerceAtLeast(1)),
                    )
                } else {
                    drawRect(placeholder, topLeft, slotSize)
                }
                if (border) {
                    drawRect(Color(0xFF808080), topLeft, slotSize, style = Stroke(maxOf(1f, SheetLayout.BORDER_WIDTH * scale)))
                }
            }
        }
    }
}

/** A small grey line saying more about the choices around it. */
@Composable
private fun Note(@StringRes text: Int, modifier: Modifier = Modifier) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * A row of segments, one for each of [items], [selected] chosen. A screen reader says
 * [described] for a segment, when given, in place of its label.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Segments(
    items: List<T>,
    selected: T,
    modifier: Modifier = Modifier,
    onSelect: (T) -> Unit,
    described: ((T) -> String)? = null,
    label: @Composable (T) -> String,
) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        items.forEachIndexed { index, item ->
            SegmentedButton(
                selected = item == selected,
                onClick = { onSelect(item) },
                shape = SegmentedButtonDefaults.itemShape(index, items.size),
                icon = {},
                contentPadding = SegmentPadding,
                modifier = if (described != null) Modifier.semantics { contentDescription = described(item) } else Modifier,
                label = { SegmentLabel(label(item)) },
            )
        }
    }
}

/**
 * Saves [input] as [request] asks, named after [fileName]: its pages cropped, or several of them
 * on each sheet. Pages typed that can't be read are said with the other tools' words, and nothing
 * is made.
 */
internal fun PdfToolsViewModel.runPageLayout(input: PdfInput, request: PageLayoutRequest, fileName: String) {
    // As the tools in the view model do (see runExportImages): not for a PDF put away between the
    // screen's last drawing and the tap.
    if (inputs.value.singleOrNull()?.id != input.id) return
    fun pages(text: String?): List<Int>? = text?.let { PdfPageSelection.selected(it, input.pageCount).sorted() }
    // process only starts the job, so this covers just reading the pages typed.
    try {
        when (request) {
            is PageLayoutRequest.Crop -> {
                // The button is off for it: saving would crop nothing.
                if (request.share.isWhole) return
                val pages = pages(request.pagesText)
                process(fileName, listOf(input)) { check -> listOf(engine.cropPages(input, request.share, pages, fileName, check)) }
            }
            is PageLayoutRequest.Sheets ->
                process(fileName, listOf(input)) { check ->
                    listOf(engine.pagesPerSheet(input, request.perSheet, request.paper, request.border, fileName, check))
                }
        }
    } catch (e: PageSelectionException) {
        message(pageSelectionMessage(e))
    }
}
