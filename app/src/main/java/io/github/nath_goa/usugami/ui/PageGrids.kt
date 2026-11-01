package io.github.nath_goa.usugami.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.NoteAdd
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Rotate90DegreesCcw
import androidx.compose.material.icons.rounded.Rotate90DegreesCw
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.core.PdfPageSelection
import io.github.nath_goa.usugami.data.PdfInput
import io.github.nath_goa.usugami.data.PdfPageRef
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState

// Delete pages and Reorganize PDF show a PDF's pages in a grid. Their options sit in
// PdfToolScreen's column, between the PDF and the file name.

/** Pages in the grids are at least this wide: three across on most phones. */
private val TileMinWidth = 96.dp

/** The space a page's picture gets in its tile, width over height: about a portrait A4 page. */
private const val TILE_ASPECT = 0.75f

/** The grids scroll inside the screen past this height, so the file name stays near. */
private val GridMaxHeight = 480.dp

/**
 * The pages to delete, typed like "2,5-7" or marked by tapping their pictures; the two stay in
 * step. The count says how many are marked, and marking every page says one must stay.
 */
@Composable
internal fun DeletePagesOptions(input: PdfInput, text: String, onText: (String) -> Unit) {
    val marked = remember(text, input.pageCount) {
        try {
            PdfPageSelection.selected(text, input.pageCount).toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = onText,
        label = { Text(stringResource(R.string.delete_pages_field)) },
        supportingText = { Text(stringResource(R.string.delete_pages_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Column {
        // A screen reader says the new count as pages are marked.
        Text(
            stringResource(R.string.delete_pages_count, marked.size, input.pageCount),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        AnimatedVisibility(visible = marked.size == input.pageCount, enter = pdfSectionEnter(), exit = pdfSectionExit(), label = "every page marked") {
            Text(
                stringResource(R.string.pdf_need_page),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(TileMinWidth),
        modifier = Modifier.fillMaxWidth().heightIn(max = GridMaxHeight),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(input.pageCount, key = { it }) { index ->
            val isMarked = index in marked
            DeleteTile(input, index, isMarked) {
                onText(PdfPageSelection.format(if (isMarked) marked - index else marked + index))
            }
        }
    }
}

/** A page to keep or delete: tapping marks it, and a marked page fades behind a delete sign. */
@Composable
private fun DeleteTile(input: PdfInput, index: Int, marked: Boolean, onToggle: () -> Unit) {
    val borderWidth by animateDpAsState(if (marked) 2.dp else 1.dp, Motion.short(), label = "page border width")
    val borderColor by animateColorAsState(
        if (marked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant,
        Motion.short(),
        label = "page border color",
    )
    val cross by animateFloatAsState(if (marked) 1f else 0f, Motion.short(), label = "page marked")
    val label = stringResource(R.string.pdf_page_number, index + 1)
    val state = stringResource(if (marked) R.string.page_marked_delete else R.string.page_kept)
    OutlinedCard(
        border = BorderStroke(borderWidth, borderColor),
        modifier = Modifier
            .clip(CardDefaults.outlinedShape)
            .toggleable(value = marked, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics {
                contentDescription = label
                stateDescription = state
            },
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(TILE_ASPECT), contentAlignment = Alignment.Center) {
            PdfThumbnail(input, index, Modifier.fillMaxSize().graphicsLayer { alpha = 1f - 0.6f * cross })
            Icon(
                Icons.Rounded.Delete,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onError,
                modifier = Modifier
                    .graphicsLayer {
                        alpha = cross
                        scaleX = 0.6f + 0.4f * cross
                        scaleY = 0.6f + 0.4f * cross
                    }
                    .background(MaterialTheme.colorScheme.error, CircleShape)
                    .padding(8.dp),
            )
        }
        Text(
            "${index + 1}",
            style = MaterialTheme.typography.labelLarge,
            color = if (marked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(4.dp)
                .clearAndSetSemantics {},
        )
    }
}

/**
 * The PDF's pages in their new order. A page is chosen by tapping it; the bar above the grid then
 * moves, turns or removes it, and pages added go after it. Pages can also be dragged after a long
 * press, and a screen reader finds the bar's actions on each page.
 *
 * [inputs] starts with the PDF being reorganized; the others are PDFs pages were added from.
 * [changed] says the pages differ from the PDF's own. [onAddPdf] picks a PDF to add pages from.
 */
@Composable
internal fun ReorganizeOptions(
    inputs: List<PdfInput>,
    pages: List<PdfPageRef>,
    vm: PdfToolsViewModel,
    changed: Boolean,
    importing: Boolean,
    selected: String?,
    onSelect: (String?) -> Unit,
    onAddPdf: (after: PdfPageRef?) -> Unit,
) {
    val byId = remember(inputs) { inputs.associateBy { it.id } }
    val mainId = inputs.first().id
    val canUndo by vm.canUndo.collectAsStateWithLifecycle()
    val selectedIndex = pages.indexOfFirst { it.key == selected }
    val selectedRef = pages.getOrNull(selectedIndex)
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
    // A page moved out of sight with the buttons is scrolled back into it.
    LaunchedEffect(selectedIndex) {
        if (selectedIndex < 0 || reorder.isAnyItemDragging) return@LaunchedEffect
        if (gridState.layoutInfo.visibleItemsInfo.none { it.index == selectedIndex }) gridState.animateScrollToItem(selectedIndex)
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // A screen reader hears the new count when pages are added or removed.
            Text(
                pluralStringResource(R.plurals.page_count, pages.size, pages.size),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            )
            IconButton(onClick = vm::undo, enabled = canUndo) {
                Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = stringResource(R.string.undo))
            }
        }
        Text(stringResource(R.string.reorganize_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        // The bar carries the gap above it, so the gap opens and closes with the bar.
        val barRef = freezeWhen(selectedRef == null, selectedRef)
        val barIndex = freezeWhen(selectedRef == null, selectedIndex)
        AnimatedVisibility(visible = selectedRef != null, enter = pdfSectionEnter(), exit = pdfSectionExit(), label = "page actions") {
            if (barRef != null) {
                PageActions(barRef, barIndex, pages.size, vm, onSelect, Modifier.padding(top = 12.dp))
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(TileMinWidth),
            state = gridState,
            modifier = Modifier
                .padding(top = 12.dp)
                .fillMaxWidth()
                .heightIn(max = GridMaxHeight)
                .selectableGroup(),
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
                        ref = ref,
                        number = number,
                        fromOther = !ref.isBlank && ref.inputId != mainId,
                        isSelected = ref.key == selected,
                        dragging = dragging,
                        onClick = { onSelect(if (ref.key == selected) null else ref.key) },
                        // Screen reader and switch users can't drag: each page offers the bar's actions.
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

        FlowRow(
            Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            AddPagesButton(enabled = !importing, onAddPdf = { onAddPdf(selectedRef) }, onAddBlank = { vm.addBlankPage(selectedRef) })
            TextButton(onClick = {
                vm.resetToOriginal()
                onSelect(null)
            }, enabled = changed) { Text(stringResource(R.string.pdf_reset_pages)) }
        }
    }
}

/** What can be done to the chosen page, with buttons that say which page they act on. */
@Composable
private fun PageActions(ref: PdfPageRef, index: Int, count: Int, vm: PdfToolsViewModel, onSelect: (String?) -> Unit, modifier: Modifier) {
    val number = index + 1
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.pdf_page_number, number), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.movePageBy(ref, -1) }, enabled = index > 0) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.move_page_earlier, number))
            }
            IconButton(onClick = { vm.movePageBy(ref, 1) }, enabled = index < count - 1) {
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = stringResource(R.string.move_page_later, number))
            }
            IconButton(onClick = { vm.rotatePage(ref, -90) }) {
                Icon(Icons.Rounded.Rotate90DegreesCcw, contentDescription = stringResource(R.string.rotate_page_left, number))
            }
            IconButton(onClick = { vm.rotatePage(ref, 90) }) {
                Icon(Icons.Rounded.Rotate90DegreesCw, contentDescription = stringResource(R.string.rotate_page_right, number))
            }
            // A PDF keeps at least one page.
            IconButton(onClick = {
                vm.removePage(ref)
                // The page that takes its place is chosen next, so several can go in a row.
                val after = vm.pages.value
                onSelect((after.getOrNull(index) ?: after.getOrNull(index - 1))?.key)
            }, enabled = count > 1) {
                Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.remove_page, number))
            }
        }
    }
}

/** A page in Reorganize's grid: its picture, turned as it will be, and its place in the new PDF. */
@Composable
private fun ReorganizeTile(
    input: PdfInput?,
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
            .selectable(selected = isSelected, onClick = onClick)
            .semantics {
                contentDescription = if (turned == null) page else "$page, $turned"
                customActions = actions
            },
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(TILE_ASPECT), contentAlignment = Alignment.Center) {
            if (input == null || ref.isBlank) BlankPage(ref, Modifier.fillMaxSize())
            else TurnedThumbnail(input, ref.page, ref.rotation, Modifier.fillMaxSize(), aspect = TILE_ASPECT)
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

/** "Add pages": from another PDF or a blank one, after the chosen page or at the end. */
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
            DropdownMenuItem(
                text = { Text(stringResource(R.string.reorganize_add_pdf)) },
                leadingIcon = { Icon(Icons.Rounded.PictureAsPdf, contentDescription = null) },
                onClick = {
                    open = false
                    onAddPdf()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.reorganize_add_blank)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.NoteAdd, contentDescription = null) },
                onClick = {
                    open = false
                    onAddBlank()
                },
            )
        }
    }
}
