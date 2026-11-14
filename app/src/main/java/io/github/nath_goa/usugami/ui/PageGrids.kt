package io.github.nath_goa.usugami.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.NoteAdd
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.core.PageChoice
import io.github.nath_goa.usugami.core.PdfPageSelection
import io.github.nath_goa.usugami.data.PdfInput
import io.github.nath_goa.usugami.data.PdfPageRef
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState

// Reorganize PDF shows a PDF's pages in a grid. Its options sit in PdfToolScreen's column, between
// the PDF and the file name. Split PDF and PDF to images or text read the pages typed into their
// fields with typedPages and tappedOnto.

/** Pages in the grid are at least this wide: three across on most phones. */
private val TileMinWidth = 96.dp

/** The space a page's picture gets in its tile, width over height: about a portrait A4 page. */
private const val TILE_ASPECT = 0.75f

/** The grid scrolls inside the screen past this height, so the file name stays near. */
private val GridMaxHeight = 480.dp

/** The pages [text] names, zero-based and in the order typed; none while it can't be read. */
internal fun typedPages(text: String, pageCount: Int): List<Int> = tappedOnto(text, pageCount).orEmpty()

/**
 * The pages [text] names, for a page tapped to go in or out of them: null while it can't be read
 * and isn't blank, as with a typo or a range half typed. The tap then leaves the text as it is
 * rather than putting just that page in place of all that was typed.
 */
internal fun tappedOnto(text: String, pageCount: Int): List<Int>? = PdfPageSelection.read(text, pageCount)

/**
 * The PDF's pages in their new order. Pages are selected by tapping them, by typing their numbers
 * like "2,5-7" or with Select all; the field and the pages stay in step. The bar above the grid
 * turns or removes every selected page at once and, while one page is selected, moves it too;
 * pages added go after the last selected page. Pages can also be dragged after a long press, and a
 * screen reader finds the bar's actions for one page on each page.
 *
 * [inputs] starts with the PDF being reorganized; the others are PDFs pages were added from. Each
 * one's pages are drawn from [drawn] of it. [changed] says the pages differ from the PDF's own.
 * [onAddPdf] picks a PDF to add pages from.
 */
@Composable
internal fun ReorganizeOptions(
    inputs: List<PdfInput>,
    pages: List<PdfPageRef>,
    vm: PdfToolsViewModel,
    drawn: (PdfInput) -> PdfInput,
    changed: Boolean,
    importing: Boolean,
    onAddPdf: (after: PdfPageRef?) -> Unit,
) {
    val byId = remember(inputs) { inputs.associateBy { it.id } }
    val mainId = inputs.first().id
    val canUndo by vm.canUndo.collectAsStateWithLifecycle()
    // The selection is the view model's, by the pages' keys: it follows the pages wherever they go.
    val chosen by vm.chosenPages.collectAsStateWithLifecycle()
    val places = remember(pages, chosen) { PageChoice.places(pages.map { it.key }, chosen) }
    // What was typed. Keyed on the page count: when pages come or go, numbers typed for the old list
    // name other pages now, and the field shows the selection as ranges instead.
    var typed by rememberSaveable(mainId, pages.size) { mutableStateOf("") }
    // What the field shows: what was typed while it names the pages selected, or can't be read with
    // none selected; otherwise the selection, written as ranges. Typing never has its text changed
    // by this: right after a change the selection is the pages typed (none while they can't be read).
    val text = PageChoice.fieldText(typed, places, pages.size)
    // Later typing goes on from the text shown.
    SideEffect { if (typed != text) typed = text }
    // Why what is typed can't be read, once the user has finished typing (Done, the field left, or
    // a page tapped): not while a range is half typed.
    var showProblem by rememberSaveable(mainId) { mutableStateOf(false) }
    // Remove was pressed with every page selected: a PDF keeps at least one.
    var keepOne by rememberSaveable(mainId) { mutableStateOf(false) }
    val everySelected = pages.isNotEmpty() && places.size == pages.size
    val problem = if (showProblem) vm.pagesProblem(text, pages.size) else null
    // The one place for what is wrong with the selection: under the field, where the hint is otherwise.
    val said = problem ?: if (keepOne && everySelected) stringResource(R.string.pdf_need_page) else null
    // "Keep at least one page." goes for good once not every page is selected: pages added and then
    // undone, say, don't bring it back with no Remove pressed again.
    SideEffect { if (keepOne && !everySelected) keepOne = false }
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val haptic = LocalHapticFeedback.current
    val gridState = rememberLazyGridState()
    val reorder = rememberReorderableLazyGridState(gridState) { from, to ->
        val list = vm.pages.value
        val a = list.firstOrNull { it.key == from.key }
        val b = list.firstOrNull { it.key == to.key }
        if (a != null && b != null) {
            vm.movePage(a, b)
            haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        }
    }
    // A page moved out of sight (with the buttons, by Undo or by pages moved past it), or typed
    // alone, is scrolled into it. A page left selected alone by taps is not: the grid never moves
    // under the finger that taps a page, also when the one left is out of sight.
    val single = places.singleOrNull() ?: -1
    val singleKey = pages.getOrNull(single)?.key
    // The page selected alone when the effect last ran, and whether the selection was last typed.
    var lastSingle by remember { mutableStateOf<String?>(null) }
    var typedLast by remember { mutableStateOf(false) }
    LaunchedEffect(single, singleKey) {
        val moved = singleKey != null && singleKey == lastSingle
        lastSingle = singleKey
        if (single < 0 || reorder.isAnyItemDragging || !(moved || typedLast)) return@LaunchedEffect
        if (gridState.layoutInfo.visibleItemsInfo.none { it.index == single }) gridState.animateScrollToItem(single)
    }
    // The handlers read the pages and the selection as they are when called, so that two taps
    // before the screen redraws go on one from the other.
    fun tapped(ref: PdfPageRef) {
        keepOne = false
        typedLast = false
        if (PdfPageSelection.read(typed, vm.pages.value.size) == null) showProblem = true else vm.toggleChosenPage(ref)
    }
    fun selectAllOrNone() {
        typed = ""
        showProblem = false
        keepOne = false
        typedLast = false
        val list = vm.pages.value
        val now = vm.chosenPages.value
        val all = list.isNotEmpty() && list.all { it.key in now }
        vm.choosePages(if (all) emptySet() else list.mapTo(HashSet()) { it.key })
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // A screen reader hears the new count when pages are added or removed.
            Text(
                pluralStringResource(R.plurals.page_count, pages.size, pages.size),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            )
            TextButton(onClick = ::selectAllOrNone, enabled = pages.isNotEmpty()) {
                Text(stringResource(if (everySelected) R.string.reorganize_select_none else R.string.reorganize_select_all))
            }
            IconButton(onClick = vm::undo, enabled = canUndo) {
                Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = stringResource(R.string.undo))
            }
        }
        OutlinedTextField(
            value = text,
            onValueChange = { new ->
                typed = new
                showProblem = false
                keepOne = false
                typedLast = true
                val list = vm.pages.value
                vm.choosePages(PdfPageSelection.read(new, list.size).orEmpty().mapTo(HashSet()) { list[it].key })
            },
            label = { Text(stringResource(R.string.reorganize_pages_field)) },
            singleLine = true,
            isError = said != null,
            // A screen reader hears what is wrong as it shows.
            supportingText = {
                Text(said ?: stringResource(R.string.reorganize_pages_hint), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            },
            trailingIcon = if (text.isEmpty()) null else {
                {
                    IconButton(onClick = {
                        typed = ""
                        showProblem = false
                        keepOne = false
                        typedLast = false
                        vm.choosePages(emptySet())
                    }) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.reorganize_pages_clear)) }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                showProblem = true
                keyboard?.hide()
                focus.clearFocus()
            }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .onFocusChanged {
                    if (focused && !it.isFocused) showProblem = true
                    focused = it.isFocused
                },
        )

        // Always there, the same height whatever is selected, with nothing between it and the grid:
        // the grid below never moves under the finger that taps a page.
        PageActions(pages, places, vm, onRemove = { if (!vm.removeChosen()) keepOne = true }, Modifier.padding(top = 8.dp))

        LazyVerticalGrid(
            columns = GridCells.Adaptive(TileMinWidth),
            state = gridState,
            modifier = Modifier
                .padding(top = 12.dp)
                .fillMaxWidth()
                .heightIn(max = GridMaxHeight),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(pages, key = { _, ref -> ref.key }) { index, ref ->
                ReorderableItem(reorder, key = ref.key) { dragging ->
                    val number = index + 1
                    val moveEarlier = stringResource(R.string.move_page_earlier, number)
                    val moveLater = stringResource(R.string.move_page_later, number)
                    val rotateLeft = stringResource(R.string.rotate_page_left, number)
                    val rotateRight = stringResource(R.string.rotate_page_right, number)
                    val remove = stringResource(R.string.remove_page, number)
                    ReorganizeTile(
                        input = byId[ref.inputId],
                        picture = byId[ref.inputId]?.let(drawn),
                        ref = ref,
                        number = number,
                        fromOther = !ref.isBlank && ref.inputId != mainId,
                        isSelected = ref.key in chosen,
                        dragging = dragging,
                        onClick = { tapped(ref) },
                        // Screen reader and switch users can't drag: each page offers the bar's actions,
                        // for that page alone.
                        actions = buildList {
                            if (index > 0) add(CustomAccessibilityAction(moveEarlier) { vm.movePageBy(ref, -1); true })
                            if (index < pages.lastIndex) add(CustomAccessibilityAction(moveLater) { vm.movePageBy(ref, 1); true })
                            add(CustomAccessibilityAction(rotateLeft) { vm.rotatePage(ref, -90); true })
                            add(CustomAccessibilityAction(rotateRight) { vm.rotatePage(ref, 90); true })
                            if (pages.size > 1) add(CustomAccessibilityAction(remove) { vm.removePage(ref); true })
                        },
                        modifier = Modifier.longPressDraggableHandle(
                            onDragStarted = {
                                vm.beginPageDrag()
                                haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                            },
                            onDragStopped = {
                                vm.endPageDrag()
                                haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                            },
                        ),
                    )
                }
            }
        }
        Text(
            stringResource(R.string.reorganize_move_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )

        // Pages added go after the last selected page, or at the end.
        val after = places.lastOrNull()?.let(pages::getOrNull)
        FlowRow(
            Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            AddPagesButton(enabled = !importing, onAddPdf = { onAddPdf(after) }, onAddBlank = { vm.addBlankPage(after) })
            TextButton(onClick = vm::resetToOriginal, enabled = changed) { Text(stringResource(R.string.pdf_reset_pages)) }
        }
    }
}

/**
 * What can be done to the selected pages, the pages of [pages] at [places]. With one selected:
 * move it, turn it or remove it, with buttons that say which page they act on. With several: turn
 * them or remove them, saying how many there are. With none, it says how to select them.
 */
@Composable
private fun PageActions(pages: List<PdfPageRef>, places: List<Int>, vm: PdfToolsViewModel, onRemove: () -> Unit, modifier: Modifier) {
    val any = places.isNotEmpty()
    val one = places.singleOrNull()?.let(pages::getOrNull)
    val color by animateColorAsState(
        if (any) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        Motion.short(),
        label = "page bar",
    )
    Surface(
        color = color,
        contentColor = if (any) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.heightIn(min = 48.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            // A screen reader hears it as pages are selected. Beside five buttons with a large font size,
            // it shrinks rather than breaking a word (Indonesian "Halaman").
            Text(
                when {
                    !any -> stringResource(R.string.reorganize_tap_pages)
                    one != null -> stringResource(R.string.pdf_page_number, places[0] + 1)
                    else -> stringResource(R.string.reorganize_selected_count, places.size, pages.size)
                },
                style = MaterialTheme.typography.labelLarge.withEmLineHeight(),
                autoSize = WholeWordsAutoSize(MaterialTheme.typography.labelLarge.fontSize),
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (one != null) {
                val index = places[0]
                val number = index + 1
                IconButton(onClick = { vm.movePageBy(one, -1) }, enabled = index > 0) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.move_page_earlier, number))
                }
                IconButton(onClick = { vm.movePageBy(one, 1) }, enabled = index < pages.lastIndex) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = stringResource(R.string.move_page_later, number))
                }
                IconButton(onClick = { vm.rotateChosen(-90) }) {
                    TurnIcon(clockwise = false, description = stringResource(R.string.rotate_page_left, number))
                }
                IconButton(onClick = { vm.rotateChosen(90) }) {
                    TurnIcon(clockwise = true, description = stringResource(R.string.rotate_page_right, number))
                }
                // Also for the only page: pressed, it says a PDF keeps at least one.
                IconButton(onClick = onRemove) {
                    Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.remove_page, number))
                }
            } else if (any) {
                IconButton(onClick = { vm.rotateChosen(-90) }) {
                    TurnIcon(clockwise = false, description = stringResource(R.string.rotate_selected_left))
                }
                IconButton(onClick = { vm.rotateChosen(90) }) {
                    TurnIcon(clockwise = true, description = stringResource(R.string.rotate_selected_right))
                }
                // With every page selected, pressed, it says a PDF keeps at least one.
                IconButton(onClick = onRemove) {
                    Icon(Icons.Rounded.Delete, contentDescription = pluralStringResource(R.plurals.remove_selected_pages, places.size, places.size))
                }
            }
        }
    }
}

/**
 * A page in Reorganize's grid: its picture, turned as it will be, and its place in the new PDF. The
 * picture is drawn from [picture]: [input] itself, or a copy of it Android's renderer can open. It
 * is a check box: tapping it puts it into the selection or takes it out, and a selected page has a
 * check mark in its corner.
 */
@Composable
private fun ReorganizeTile(
    input: PdfInput?,
    picture: PdfInput?,
    ref: PdfPageRef,
    number: Int,
    fromOther: Boolean,
    isSelected: Boolean,
    dragging: Boolean,
    onClick: () -> Unit,
    actions: List<CustomAccessibilityAction>,
    modifier: Modifier,
) {
    val borderWidth by animateDpAsState(if (isSelected) 3.dp else 1.dp, Motion.short(), label = "page border width")
    val borderColor by animateColorAsState(
        if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        Motion.short(),
        label = "page border color",
    )
    val check by animateFloatAsState(if (isSelected) 1f else 0f, Motion.short(), label = "page check")
    val elevation by animateDpAsState(if (dragging) 8.dp else 0.dp, label = "drag elevation")
    val page = when {
        ref.isBlank || input == null -> stringResource(R.string.reorganize_page_blank, number)
        fromOther -> stringResource(R.string.reorganize_page_from, number, input.name)
        else -> stringResource(R.string.pdf_page_number, number)
    }
    val turned = turnedLabel(ref.rotation)?.let { stringResource(it) }
    OutlinedCard(
        border = BorderStroke(borderWidth, borderColor),
        elevation = CardDefaults.outlinedCardElevation(defaultElevation = elevation),
        modifier = modifier
            .clip(CardDefaults.outlinedShape)
            .toggleable(value = isSelected, role = Role.Checkbox, onValueChange = { onClick() })
            .semantics {
                contentDescription = if (turned == null) page else "$page, $turned"
                customActions = actions
            },
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(TILE_ASPECT), contentAlignment = Alignment.Center) {
            if (picture == null || ref.isBlank) BlankPage(ref, Modifier.fillMaxSize())
            else TurnedThumbnail(picture, ref.page, ref.rotation, Modifier.fillMaxSize(), aspect = TILE_ASPECT)
            Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .graphicsLayer {
                        alpha = check
                        scaleX = 0.6f + 0.4f * check
                        scaleY = 0.6f + 0.4f * check
                    }
                    .size(20.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                    .padding(3.dp),
            )
        }
        Text(
            "$number",
            style = MaterialTheme.typography.labelLarge,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(4.dp)
                .clearAndSetSemantics {},
        )
    }
}

/** A blank page's picture: white, its own shape, saying so, and turned like the others. */
@Composable
private fun BlankPage(ref: PdfPageRef, modifier: Modifier) {
    val angle by animatePdfPageRotationAsState(ref.rotation)
    val aspect = (ref.width / ref.height).takeIf { it.isFinite() && it > 0f } ?: TILE_ASPECT
    Box(modifier.turnedBy(angle, TILE_ASPECT), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .aspectRatio(aspect, matchHeightConstraintsFirst = aspect < TILE_ASPECT)
                .background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(R.string.reorganize_blank),
                style = MaterialTheme.typography.labelMedium,
                color = Color.Gray,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}

/** "Add pages": from another PDF or a blank one, after the last selected page or at the end. */
@Composable
private fun AddPagesButton(enabled: Boolean, onAddPdf: () -> Unit, onAddBlank: () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, enabled = enabled, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.reorganize_add))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            // An entry tapped again, or the other one, while the menu fades away adds nothing more.
            DropdownMenuItem(
                text = { Text(stringResource(R.string.reorganize_add_pdf)) },
                leadingIcon = { Icon(Icons.Rounded.PictureAsPdf, contentDescription = null) },
                onClick = {
                    if (open) {
                        open = false
                        onAddPdf()
                    }
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.reorganize_add_blank)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.NoteAdd, contentDescription = null) },
                onClick = {
                    if (open) {
                        open = false
                        onAddBlank()
                    }
                },
            )
        }
    }
}
