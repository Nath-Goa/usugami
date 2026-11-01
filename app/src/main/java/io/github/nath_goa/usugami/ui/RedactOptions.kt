package io.github.nath_goa.usugami.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.East
import androidx.compose.material.icons.rounded.North
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.South
import androidx.compose.material.icons.rounded.West
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.core.RedactBox
import io.github.nath_goa.usugami.core.Redaction
import io.github.nath_goa.usugami.data.PdfInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.hypot

// Redact PDF's options: Find text, the page, and the boxes on it. They sit in PdfToolScreen's
// column, between the PDF and the file name. The boxes, the page and the chosen box live in the
// view model's saved state, so they survive rotation, process death and the result screen.

/** A box drawn smaller than this on the screen, either way, is taken for a slip and dropped. */
private val MIN_DRAWN = 6.dp

@Composable
internal fun RedactOptions(input: PdfInput, vm: PdfToolsViewModel) {
    val view by vm.redactView.collectAsStateWithLifecycle()
    val boxes by vm.redactBoxes.collectAsStateWithLifecycle()
    val page by vm.redactPage.collectAsStateWithLifecycle()
    val selected by vm.redactSelected.collectAsStateWithLifecycle()
    val finding by vm.finding.collectAsStateWithLifecycle()
    LaunchedEffect(input.id) { vm.prepareRedaction(input) }
    val pageIndex = page.coerceIn(0, input.pageCount - 1)
    var query by rememberSaveable(input.id) { mutableStateOf("") }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    // The page last drawn stays while the next one is drawn; the boxes show only on their own page.
    var rendered by remember(view?.path) { mutableStateOf<RenderedPage?>(null) }
    // A page Android can't draw: it can't be redacted either, so the preview says so.
    var failedPage by remember(view?.path) { mutableIntStateOf(-1) }
    LaunchedEffect(view?.path, pageIndex) {
        val source = view ?: return@LaunchedEffect
        val drawn = withContext(Dispatchers.IO) { renderShownPage(source, pageIndex, 1400) }
        if (drawn != null) rendered = drawn else failedPage = pageIndex
    }
    val current = rendered?.takeIf { it.index == pageIndex }

    Text(
        stringResource(R.string.redact_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FindText(query, onQuery = { query = it }, finding, enabled = view != null, onFind = { vm.findText(query) })
    if (input.pageCount > 1) {
        Text(stringResource(R.string.redact_page), style = MaterialTheme.typography.titleMedium)
        PageStrip(
            view ?: input, pageIndex, onSelect = vm::showRedactPage,
            marked = boxes.mapTo(HashSet()) { it.page }, markedLabel = stringResource(R.string.redact_page_marked),
        )
    }
    Text(stringResource(R.string.redact_mark), style = MaterialTheme.typography.titleMedium)
    val chosen = boxes.getOrNull(selected)?.takeIf { it.page == pageIndex }
    // While the controls animate away they keep showing the box they were for.
    val shown = freezeWhen(chosen == null, chosen)
    // The controls for the chosen box come right under the page, and carry the gap above them, so
    // the gap opens and closes with them.
    Column {
        BoxEditor(
            rendered, ready = current != null, failed = current == null && failedPage == pageIndex, pageIndex, boxes, selected,
            onSelect = vm::selectRedactBox, onAdd = vm::addRedactBox, onChange = vm::changeRedactBox,
        )
        Text(
            stringResource(R.string.redact_mark_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
        )
        AnimatedVisibility(visible = chosen != null, enter = pdfSectionEnter(), exit = pdfSectionExit(), label = "box controls") {
            if (shown != null) {
                BoxControls(
                    shown,
                    onChange = { if (chosen != null) vm.changeRedactBox(selected, it) },
                    onDelete = { if (chosen != null) vm.deleteRedactBox(selected) },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // Boxes can be added and then moved and sized with buttons, without drawing.
        OutlinedButton(
            onClick = {
                val width = current?.width ?: 595f
                val height = current?.height ?: 842f
                vm.addRedactBox(Redaction.default(pageIndex, width, height, boxes.count { it.page == pageIndex }))
            },
            enabled = current != null,
            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.redact_add_box))
        }
        Spacer(Modifier.weight(1f))
        AnimatedVisibility(visible = boxes.isNotEmpty(), enter = fadeIn(Motion.short()), exit = fadeOut(Motion.short()), label = "remove all") {
            TextButton(onClick = { confirmClear = true }) { Text(stringResource(R.string.redact_remove_all)) }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.redact_remove_all_title)) },
            text = { Text(pluralStringResource(R.plurals.redact_remove_all_body, boxes.size, boxes.size)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearRedactBoxes()
                    confirmClear = false
                }) { Text(stringResource(R.string.redact_remove_all)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** A word or phrase to box on every page, with a button and the keyboard's search key to find it. */
@Composable
private fun FindText(query: String, onQuery: (String) -> Unit, finding: Boolean, enabled: Boolean, onFind: () -> Unit) {
    val focus = LocalFocusManager.current
    val canFind = enabled && !finding && query.isNotBlank()
    val find = {
        if (canFind) {
            focus.clearFocus()
            onFind()
        }
    }
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        label = { Text(stringResource(R.string.redact_find)) },
        supportingText = {
            // While searching, a bar takes the place of the hint, so the field doesn't jump.
            if (finding) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp))
            else Text(stringResource(R.string.redact_find_hint))
        },
        trailingIcon = {
            IconButton(onClick = find, enabled = canFind) {
                Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.redact_find_action))
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { find() }),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A box being drawn ([index] -1), moved or resized, shown as it goes and saved when the finger lifts. */
private class Draft(val index: Int, val box: RedactBox)

/**
 * [rendered] (once [ready]; [failed] when it can't be drawn) with the boxes on page [pageIndex].
 * Dragging on the page draws a box; dragging a box moves it, and dragging the chosen box's corner
 * handle resizes it; tapping a box chooses it, and tapping elsewhere lets it go. The boxes are
 * see-through here, so what they cover can be checked; they are solid black in the PDF.
 */
@Composable
private fun BoxEditor(
    rendered: RenderedPage?,
    ready: Boolean,
    failed: Boolean,
    pageIndex: Int,
    boxes: List<RedactBox>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onAdd: (RedactBox) -> Unit,
    onChange: (Int, RedactBox) -> Unit,
) {
    val latestBoxes by rememberUpdatedState(boxes)
    val latestSelected by rememberUpdatedState(selected)
    var draft by remember { mutableStateOf<Draft?>(null) }
    val onPage = boxes.indices.filter { boxes[it].page == pageIndex }
    val chosen = selected.takeIf { it in onPage }
    val frame = MaterialTheme.colorScheme.primary
    val description = pluralStringResource(R.plurals.redact_preview_description, onPage.size, pageIndex + 1, onPage.size)
    val chosenLabel = chosen?.let { stringResource(R.string.redact_box_chosen, onPage.indexOf(it) + 1, onPage.size) }
    val nextLabel = stringResource(R.string.redact_next_box)
    // Until the page has been drawn, an A4 page stands in for it.
    val aspect = rendered?.let { it.width / it.height } ?: (595f / 842f)
    // A tall page is at most 440 dp high, and less in landscape, so the whole page fits on the screen.
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val tallest = min(440.dp, max(240.dp, windowHeight - 180.dp))
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val width = min(maxWidth, tallest * aspect)
        Box(Modifier.size(width, width / aspect).shadow(2.dp, RectangleShape).background(Color.White)) {
            Canvas(
                Modifier
                    .matchParentSize()
                    .semantics {
                        contentDescription = description
                        if (chosenLabel != null) stateDescription = chosenLabel
                        // Choosing a box without seeing it: a screen reader steps through them.
                        if (onPage.isNotEmpty()) {
                            customActions = listOf(
                                CustomAccessibilityAction(nextLabel) {
                                    val at = onPage.indexOf(latestSelected)
                                    onSelect(onPage[(at + 1) % onPage.size])
                                    true
                                },
                            )
                        }
                    }
                    .pointerInput(pageIndex, ready) {
                        if (!ready) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            val start = Offset(down.position.x / w, down.position.y / h)
                            val all = latestBoxes
                            val current = all.getOrNull(latestSelected)?.takeIf { it.page == pageIndex }
                            val handle = current != null &&
                                hypot(down.position.x - current.right * w, down.position.y - current.bottom * h) <= 24.dp.toPx()
                            val reach = 12.dp.toPx()
                            val hit = if (handle) latestSelected else Redaction.hit(all, pageIndex, start.x, start.y, reach / w, reach / h)
                            // The page doesn't scroll the screen: drags on it draw.
                            down.consume()
                            val moved = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                            if (moved == null) {
                                onSelect(hit)
                                return@awaitEachGesture
                            }
                            fun at(position: Offset) = Offset(position.x / w, position.y / h)
                            fun shaped(position: Offset): Draft {
                                val p = at(position)
                                return when {
                                    handle -> Draft(hit, Redaction.withCorner(all[hit], p.x, p.y))
                                    hit >= 0 -> Draft(hit, Redaction.moved(all[hit], p.x - start.x, p.y - start.y))
                                    else -> Draft(-1, Redaction.between(pageIndex, start.x, start.y, p.x, p.y))
                                }
                            }
                            if (hit >= 0) onSelect(hit)
                            draft = shaped(moved.position)
                            try {
                                val finished = drag(moved.id) { change ->
                                    draft = shaped(change.position)
                                    change.consume()
                                }
                                val result = draft
                                if (finished && result != null) {
                                    if (result.index >= 0) {
                                        onChange(result.index, result.box)
                                    } else if (result.box.width * w >= MIN_DRAWN.toPx() && result.box.height * h >= MIN_DRAWN.toPx()) {
                                        onAdd(result.box)
                                    }
                                }
                            } finally {
                                draft = null
                            }
                        }
                    },
            ) {
                if (ready && rendered != null) drawImage(rendered.image, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
                if (!ready) return@Canvas
                val editing = draft
                for (index in onPage) {
                    val box = if (editing?.index == index) editing.box else boxes[index]
                    drawBox(box, chosen = index == chosen, frame)
                }
                if (editing != null && editing.index < 0) drawBox(editing.box, chosen = true, frame)
            }
            if (failed) {
                Text(
                    stringResource(R.string.pdf_import_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Black,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            } else if (!ready) {
                LinearProgressIndicator(Modifier.align(Alignment.Center).width(120.dp))
            }
        }
    }
}

/** A box as the preview shows it: dark but see-through, outlined, and framed with a handle when chosen. */
private fun DrawScope.drawBox(box: RedactBox, chosen: Boolean, frame: Color) {
    val topLeft = Offset(box.left * size.width, box.top * size.height)
    val boxSize = Size(box.width * size.width, box.height * size.height)
    drawRect(Color.Black.copy(alpha = 0.55f), topLeft, boxSize)
    drawRect(Color.Black, topLeft, boxSize, style = Stroke(1.dp.toPx()))
    if (!chosen) return
    val gap = 3.dp.toPx()
    drawRect(
        frame,
        topLeft = Offset(topLeft.x - gap, topLeft.y - gap),
        size = Size(boxSize.width + 2 * gap, boxSize.height + 2 * gap),
        style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
    )
    // The corner to drag to resize it.
    val corner = Offset(topLeft.x + boxSize.width, topLeft.y + boxSize.height)
    drawCircle(Color.White, radius = 7.dp.toPx(), center = corner)
    drawCircle(frame, radius = 5.dp.toPx(), center = corner)
}

/** Buttons that move, size and delete the chosen box, a step at a time: for those who can't drag. */
@Composable
private fun BoxControls(box: RedactBox, onChange: (RedactBox) -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    val step = Redaction.STEP
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.Center) {
            StepButton(Icons.Rounded.West, R.string.redact_move_left, Redaction.moved(box, -step, 0f), box, onChange)
            StepButton(Icons.Rounded.North, R.string.redact_move_up, Redaction.moved(box, 0f, -step), box, onChange)
            StepButton(Icons.Rounded.South, R.string.redact_move_down, Redaction.moved(box, 0f, step), box, onChange)
            StepButton(Icons.Rounded.East, R.string.redact_move_right, Redaction.moved(box, step, 0f), box, onChange)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.redact_width), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.clearAndSetSemantics {})
            StepButton(Icons.Rounded.Remove, R.string.redact_narrower, Redaction.resized(box, -step, 0f), box, onChange)
            StepButton(Icons.Rounded.Add, R.string.redact_wider, Redaction.resized(box, step, 0f), box, onChange)
            Spacer(Modifier.width(16.dp))
            Text(stringResource(R.string.redact_height), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.clearAndSetSemantics {})
            StepButton(Icons.Rounded.Remove, R.string.redact_shorter, Redaction.resized(box, 0f, -step), box, onChange)
            StepButton(Icons.Rounded.Add, R.string.redact_taller, Redaction.resized(box, 0f, step), box, onChange)
        }
        TextButton(onClick = onDelete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
            Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.redact_delete_box))
        }
    }
}

/** Changes the box to [target]; off once the box can't go further that way. */
@Composable
private fun StepButton(icon: ImageVector, @StringRes label: Int, target: RedactBox, current: RedactBox, onChange: (RedactBox) -> Unit) {
    IconButton(onClick = { onChange(target) }, enabled = !Redaction.same(target, current)) {
        Icon(icon, contentDescription = stringResource(label))
    }
}
