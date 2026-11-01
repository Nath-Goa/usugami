package io.github.nath_goa.usugami.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowRightAlt
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.BorderColor
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.CropSquare
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.East
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.HorizontalRule
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.North
import androidx.compose.material.icons.rounded.PanoramaFishEye
import androidx.compose.material.icons.rounded.South
import androidx.compose.material.icons.rounded.Square
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.West
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.core.EditLayout
import io.github.nath_goa.usugami.core.EditStyle
import io.github.nath_goa.usugami.core.InkItem
import io.github.nath_goa.usugami.core.PageItem
import io.github.nath_goa.usugami.core.PictureItem
import io.github.nath_goa.usugami.core.ShapeItem
import io.github.nath_goa.usugami.core.ShapeKind
import io.github.nath_goa.usugami.core.TextItem
import io.github.nath_goa.usugami.data.PageText
import io.github.nath_goa.usugami.data.PdfInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min

// Edit PDF: one page at a time, large, with what was added drawn on it. A row of tools adds text,
// shapes and pictures (each put in the middle of the page and chosen, ready to move) or turns on
// drawing with a pen or highlighter. The chosen item's controls sit under the page. Everything but
// drawing itself also works with buttons, for those who can't drag.

/** What a touch on the page does: choose and move what was added, or draw. */
internal enum class EditMode { SELECT, PEN, HIGHLIGHTER }

/** Edit PDF's colours, as ARGB. */
internal enum class EditColor(val argb: Int) {
    BLACK(0xFF000000.toInt()),
    BLUE(0xFF1565C0.toInt()),
    RED(0xFFD32F2F.toInt()),
    GREEN(0xFF2E7D32.toInt()),
    YELLOW(0xFFFFE600.toInt()),
    WHITE(0xFFFFFFFF.toInt()),
}

@StringRes
private fun editColorLabel(color: EditColor): Int = when (color) {
    EditColor.BLACK -> R.string.color_black
    EditColor.BLUE -> R.string.color_blue
    EditColor.RED -> R.string.color_red
    EditColor.GREEN -> R.string.color_green
    EditColor.YELLOW -> R.string.color_yellow
    EditColor.WHITE -> R.string.color_white
}

/** The text dialog adds new text when it is open with this, instead of an item's id. */
private const val NEW_TEXT = -1L

/** Each move button moves the chosen item by this share of the page's shorter side. */
private const val NUDGE = 0.02f

@Composable
internal fun EditPdfOptions(input: PdfInput, vm: PdfToolsViewModel, importing: Boolean, onPickPicture: (Int) -> Unit) {
    val items by vm.editItems.collectAsStateWithLifecycle()
    val selectedId by vm.editSelected.collectAsStateWithLifecycle()
    val canUndo by vm.canUndoEdit.collectAsStateWithLifecycle()
    val canRedo by vm.canRedoEdit.collectAsStateWithLifecycle()
    var pageIndex by rememberSaveable(input.id) { mutableIntStateOf(0) }
    val page = pageIndex.coerceIn(0, input.pageCount - 1)
    var mode by rememberSaveable { mutableStateOf(EditMode.SELECT) }
    var style by rememberSaveable { mutableStateOf(EditStyle()) }
    // Open with NEW_TEXT or the id of the text being changed.
    var textDialog by rememberSaveable { mutableStateOf<Long?>(null) }

    // Every page's size as shown, exactly, from the PDF itself: items are placed in points on it.
    val sizes by produceState<List<Pair<Float, Float>>?>(null, input.path) { value = vm.shownPageSizes(input) }
    // The page last drawn stays while the next one is drawn, instead of flashing blank.
    var rendered by remember(input.path) { mutableStateOf<RenderedPage?>(null) }
    LaunchedEffect(input.path, page) {
        rendered = withContext(Dispatchers.IO) { renderShownPage(input, page, 1400) }
    }
    val size = sizes?.getOrNull(page) ?: rendered?.takeIf { it.index == page }?.let { it.width to it.height }
    // Until the page's size is known, an A4 page stands in, and nothing can be added.
    val (pageWidth, pageHeight) = size ?: (595f to 842f)
    val known = size != null

    val onPage = items.filter { it.page == page }
    val selected = onPage.firstOrNull { it.id == selectedId }
    fun showPage(index: Int) {
        pageIndex = index
        if (items.firstOrNull { it.id == selectedId }?.page != index) vm.selectEditItem(null)
    }

    if (input.pageCount > 1) {
        PageStrip(input, page, onSelect = ::showPage, marked = items.mapTo(HashSet()) { it.page }, markedLabel = stringResource(R.string.edit_page_edited))
    }
    EditToolbar(
        mode = mode,
        enabled = known,
        importing = importing,
        onText = {
            mode = EditMode.SELECT
            textDialog = NEW_TEXT
        },
        onMode = { wanted ->
            mode = if (mode == wanted) EditMode.SELECT else wanted
            vm.selectEditItem(null)
        },
        onShape = { kind, filled ->
            mode = EditMode.SELECT
            val shape = EditLayout.newShape(vm.newEditItemId(), page, kind, filled, style.shapeWidth, style.shapeColor, pageWidth, pageHeight)
            vm.addEditItem(EditLayout.clearOf(shape, onPage, pageWidth, pageHeight))
        },
        onPicture = {
            mode = EditMode.SELECT
            onPickPicture(page)
        },
    )
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val hint = when {
                mode != EditMode.SELECT -> R.string.edit_hint_draw
                selected != null -> R.string.edit_hint_selected
                onPage.isNotEmpty() -> R.string.edit_hint_select
                else -> R.string.edit_hint_start
            }
            Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.undoEdit()?.let(::showPage) }, enabled = canUndo) {
                Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = stringResource(R.string.undo))
            }
            IconButton(onClick = { vm.redoEdit()?.let(::showPage) }, enabled = canRedo) {
                Icon(Icons.AutoMirrored.Rounded.Redo, contentDescription = stringResource(R.string.redo))
            }
        }
        EditPreview(
            page = rendered,
            pageWidth = pageWidth,
            pageHeight = pageHeight,
            items = onPage,
            selected = selected,
            mode = if (known) mode else EditMode.SELECT,
            style = style,
            description = stringResource(R.string.edit_preview_description, page + 1),
            onSelect = vm::selectEditItem,
            onBegin = vm::beginEditChange,
            onStep = vm::stepEditItem,
            onEnd = vm::endEditChange,
            onStroke = { points ->
                val highlighter = mode == EditMode.HIGHLIGHTER
                vm.addEditItem(
                    InkItem(
                        vm.newEditItemId(), page, points,
                        if (highlighter) style.highlighterWidth else style.penWidth,
                        if (highlighter) style.highlighterColor else style.penColor,
                        highlighter,
                    ),
                )
            },
            inkRoom = vm::inkRoom,
            onFull = vm::editFull,
        )
    }
    // Covering text with a filled box hides it only on the page as drawn: say so, and what really removes it.
    val covering = onPage.any { it is ShapeItem && it.isBox && it.filled }
    AnimatedVisibility(visible = covering, enter = pdfSectionEnter(), exit = pdfSectionExit(), label = "cover hint") {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.edit_cover_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    EditProperties(
        selected = selected,
        mode = mode,
        style = style,
        pageWidth = pageWidth,
        pageHeight = pageHeight,
        onChange = vm::changeEditItem,
        onBegin = vm::beginEditChange,
        onStep = vm::stepEditItem,
        onEnd = vm::endEditChange,
        onDelete = { selected?.let { vm.removeEditItem(it.id) } },
        onEditText = { selected?.let { textDialog = it.id } },
        onStyle = { style = it },
    )

    val editing = textDialog
    val existing = items.firstOrNull { it.id == editing } as? TextItem
    if (editing != null && editing != NEW_TEXT && existing == null) {
        // The text went (undone, say) while the dialog was open.
        LaunchedEffect(Unit) { textDialog = null }
    } else if (editing != null) {
        EditTextDialog(
            initial = existing?.text.orEmpty(),
            title = if (existing == null) R.string.edit_text_add else R.string.edit_text_change,
            onDone = { text ->
                textDialog = null
                val (unitWidth, unitHeight) = PageText.unitSize(text)
                if (existing == null) {
                    val added = EditLayout.newText(vm.newEditItemId(), page, text, style.textSize, style.textColor, unitWidth, unitHeight, pageWidth, pageHeight)
                    vm.addEditItem(EditLayout.clearOf(added, onPage, pageWidth, pageHeight))
                } else {
                    val (width, height) = sizes?.getOrNull(existing.page) ?: (pageWidth to pageHeight)
                    vm.changeEditItem(EditLayout.onPage(existing.copy(text = text, unitWidth = unitWidth, unitHeight = unitHeight), width, height))
                }
            },
            onDismiss = { textDialog = null },
        )
    }
}

/** Text, Draw, Highlight, Shape and Picture, each an icon over its name. */
@Composable
private fun EditToolbar(
    mode: EditMode,
    enabled: Boolean,
    importing: Boolean,
    onText: () -> Unit,
    onMode: (EditMode) -> Unit,
    onShape: (ShapeKind, Boolean) -> Unit,
    onPicture: () -> Unit,
) {
    Row(Modifier.fillMaxWidth()) {
        val tool = Modifier.weight(1f)
        EditToolButton(Icons.Rounded.TextFields, stringResource(R.string.edit_text), active = null, enabled = enabled, onClick = onText, modifier = tool)
        EditToolButton(Icons.Rounded.Gesture, stringResource(R.string.edit_draw), active = mode == EditMode.PEN, enabled = enabled, onClick = { onMode(EditMode.PEN) }, modifier = tool)
        EditToolButton(Icons.Rounded.BorderColor, stringResource(R.string.edit_highlight), active = mode == EditMode.HIGHLIGHTER, enabled = enabled, onClick = { onMode(EditMode.HIGHLIGHTER) }, modifier = tool)
        Box(tool) {
            var open by remember { mutableStateOf(false) }
            EditToolButton(Icons.Rounded.Category, stringResource(R.string.edit_shape), active = null, enabled = enabled, onClick = { open = true }, modifier = Modifier.fillMaxWidth())
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for ((kind, filled, label, icon) in listOf(
                    ShapeChoice(ShapeKind.RECTANGLE, false, R.string.edit_item_rectangle, Icons.Rounded.CropSquare),
                    ShapeChoice(ShapeKind.RECTANGLE, true, R.string.edit_item_filled_rectangle, Icons.Rounded.Square),
                    ShapeChoice(ShapeKind.ELLIPSE, false, R.string.edit_item_ellipse, Icons.Rounded.PanoramaFishEye),
                    ShapeChoice(ShapeKind.LINE, false, R.string.edit_item_line, Icons.Rounded.HorizontalRule),
                    ShapeChoice(ShapeKind.ARROW, false, R.string.edit_item_arrow, Icons.AutoMirrored.Rounded.ArrowRightAlt),
                )) {
                    DropdownMenuItem(
                        text = { Text(stringResource(label)) },
                        leadingIcon = { Icon(icon, contentDescription = null) },
                        onClick = {
                            open = false
                            onShape(kind, filled)
                        },
                    )
                }
            }
        }
        EditToolButton(Icons.Rounded.AddPhotoAlternate, stringResource(R.string.edit_picture), active = null, enabled = enabled && !importing, onClick = onPicture, modifier = tool)
    }
}

private data class ShapeChoice(val kind: ShapeKind, val filled: Boolean, @param:StringRes val label: Int, val icon: ImageVector)

/**
 * A tool: its icon in a pill that fills while it is on, over its name. [active] is null for a tool
 * that acts at once, and says whether a tool that stays on (drawing) is on.
 */
@Composable
private fun EditToolButton(icon: ImageVector, label: String, active: Boolean?, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val on = active == true
    val pill by animateColorAsState(if (on) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, Motion.short(), label = "tool pill")
    val content = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        on -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val action = if (active != null) {
        Modifier.toggleable(value = on, enabled = enabled, role = Role.Checkbox, onValueChange = { onClick() })
    } else {
        Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    }
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .then(action)
            .padding(vertical = 6.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(width = 52.dp, height = 32.dp).background(pill, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = content)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = content, textAlign = TextAlign.Center, maxLines = 2)
    }
}

/** What a screen reader calls [item]. */
@Composable
internal fun describe(item: PageItem): String = when (item) {
    is TextItem -> stringResource(R.string.edit_item_text, item.text)
    is InkItem -> stringResource(if (item.highlighter) R.string.edit_item_highlight else R.string.edit_item_drawing)
    is PictureItem -> stringResource(R.string.edit_picture)
    is ShapeItem -> stringResource(
        when (item.kind) {
            ShapeKind.RECTANGLE -> if (item.filled) R.string.edit_item_filled_rectangle else R.string.edit_item_rectangle
            ShapeKind.ELLIPSE -> if (item.filled) R.string.edit_item_filled_ellipse else R.string.edit_item_ellipse
            ShapeKind.LINE -> R.string.edit_item_line
            ShapeKind.ARROW -> R.string.edit_item_arrow
        },
    )
}

/**
 * The chosen item's controls: what it is (said by a screen reader as it is chosen), delete, change
 * the text, move it a step at a time, its size, line width, fill and colour. While drawing, the
 * pen's or highlighter's line width and colour.
 */
@Composable
private fun EditProperties(
    selected: PageItem?,
    mode: EditMode,
    style: EditStyle,
    pageWidth: Float,
    pageHeight: Float,
    onChange: (PageItem) -> Unit,
    onBegin: () -> Unit,
    onStep: (PageItem) -> Unit,
    onEnd: () -> Unit,
    onDelete: () -> Unit,
    onEditText: () -> Unit,
    onStyle: (EditStyle) -> Unit,
) {
    Column(Modifier.fillMaxWidth().animateContentSize(Motion.medium()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (selected != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // A screen reader says it is chosen as soon as it is.
                val said = stringResource(R.string.edit_selected, describe(selected))
                Text(
                    describe(selected),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).semantics {
                        contentDescription = said
                        liveRegion = LiveRegionMode.Polite
                    },
                )
                if (selected is TextItem) {
                    TextButton(onClick = onEditText) {
                        Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.edit_text_change))
                    }
                }
                TextButton(onClick = onDelete) {
                    Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.edit_delete))
                }
            }
            val step = NUDGE * min(pageWidth, pageHeight)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                for ((icon, label, dx, dy) in listOf(
                    Nudge(Icons.Rounded.West, R.string.edit_move_left, -step, 0f),
                    Nudge(Icons.Rounded.North, R.string.edit_move_up, 0f, -step),
                    Nudge(Icons.Rounded.South, R.string.edit_move_down, 0f, step),
                    Nudge(Icons.Rounded.East, R.string.edit_move_right, step, 0f),
                )) {
                    val target = EditLayout.moved(selected, dx, dy, pageWidth, pageHeight)
                    // Off once the item is against that edge of the page.
                    IconButton(onClick = { onChange(target) }, enabled = target != selected) {
                        Icon(icon, contentDescription = stringResource(label))
                    }
                }
            }
            val range = EditLayout.sizeRange(selected, pageWidth, pageHeight)
            LabeledSlider(
                label = stringResource(R.string.sign_size),
                value = EditLayout.size(selected),
                range = range,
                onValue = { onStep(EditLayout.resized(selected, it, pageWidth, pageHeight)) },
                onBegin = onBegin,
                onEnd = {
                    onEnd()
                    if (selected is TextItem) onStyle(style.copy(textSize = selected.size))
                },
            )
            val lineWidth = when (selected) {
                is InkItem -> selected.width
                is ShapeItem -> if (selected.isBox && selected.filled) null else selected.width
                else -> null
            }
            if (lineWidth != null) {
                val highlighter = (selected as? InkItem)?.highlighter == true
                LabeledSlider(
                    label = stringResource(R.string.edit_thickness),
                    value = lineWidth,
                    range = if (highlighter) EditLayout.HIGHLIGHTER_WIDTHS else EditLayout.PEN_WIDTHS,
                    onValue = { width ->
                        val changed = when (selected) {
                            is InkItem -> selected.copy(width = width)
                            is ShapeItem -> selected.copy(width = width)
                            else -> selected
                        }
                        onStep(EditLayout.onPage(changed, pageWidth, pageHeight))
                    },
                    onBegin = onBegin,
                    onEnd = {
                        onEnd()
                        onStyle(
                            when {
                                selected is ShapeItem -> style.copy(shapeWidth = lineWidth)
                                highlighter -> style.copy(highlighterWidth = lineWidth)
                                else -> style.copy(penWidth = lineWidth)
                            },
                        )
                    },
                )
            }
            if (selected is ShapeItem && selected.isBox) {
                val fill = stringResource(R.string.edit_fill)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(value = selected.filled, role = Role.Switch, onValueChange = { onChange(selected.copy(filled = it)) })
                        .semantics { contentDescription = fill }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(fill, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).clearAndSetSemantics {})
                    Switch(checked = selected.filled, onCheckedChange = null)
                }
            }
            val color = when (selected) {
                is TextItem -> selected.color
                is InkItem -> selected.color
                is ShapeItem -> selected.color
                is PictureItem -> null
            }
            if (color != null) {
                EditColors(color) { argb ->
                    when (selected) {
                        is TextItem -> {
                            onChange(selected.copy(color = argb))
                            onStyle(style.copy(textColor = argb))
                        }
                        is InkItem -> {
                            onChange(selected.copy(color = argb))
                            onStyle(if (selected.highlighter) style.copy(highlighterColor = argb) else style.copy(penColor = argb))
                        }
                        is ShapeItem -> {
                            onChange(selected.copy(color = argb))
                            onStyle(style.copy(shapeColor = argb))
                        }
                        is PictureItem -> Unit
                    }
                }
            }
        } else if (mode != EditMode.SELECT) {
            // The next stroke's line width and colour.
            val highlighter = mode == EditMode.HIGHLIGHTER
            LabeledSlider(
                label = stringResource(R.string.edit_thickness),
                value = if (highlighter) style.highlighterWidth else style.penWidth,
                range = if (highlighter) EditLayout.HIGHLIGHTER_WIDTHS else EditLayout.PEN_WIDTHS,
                onValue = { width -> onStyle(if (highlighter) style.copy(highlighterWidth = width) else style.copy(penWidth = width)) },
                onBegin = {},
                onEnd = {},
            )
            EditColors(if (highlighter) style.highlighterColor else style.penColor) { argb ->
                onStyle(if (highlighter) style.copy(highlighterColor = argb) else style.copy(penColor = argb))
            }
        }
    }
}

private data class Nudge(val icon: ImageVector, @param:StringRes val label: Int, val dx: Float, val dy: Float)

/** A slider with its name before it; a screen reader hears the name on the slider itself. */
@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValue: (Float) -> Unit,
    onBegin: () -> Unit,
    onEnd: () -> Unit,
) {
    var moving by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(96.dp).clearAndSetSemantics {})
        Slider(
            value = value.coerceIn(range),
            onValueChange = {
                // A drag along the slider, or a screen reader's step, is one change for Undo.
                if (!moving) {
                    moving = true
                    onBegin()
                }
                onValue(it)
            },
            onValueChangeFinished = {
                moving = false
                onEnd()
            },
            // A range of one value has nothing to choose.
            valueRange = if (range.endInclusive > range.start) range else range.start..range.start + 1f,
            enabled = range.endInclusive > range.start,
            modifier = Modifier.weight(1f).semantics { contentDescription = label },
        )
    }
}

/** The colours, each a radio button named after its colour. Six fit across a phone. */
@Composable
private fun EditColors(argb: Int, onSelect: (Int) -> Unit) {
    val current = EditColor.entries.firstOrNull { it.argb == argb } ?: EditColor.BLACK
    ColorSwatches(EditColor.entries, current, onSelect = { onSelect(it.argb) }, argb = { it.argb }, label = ::editColorLabel, swatchSize = 48.dp)
}

/** Asks for the text to add, or the new text of a text already on the page. */
@Composable
private fun EditTextDialog(initial: String, @StringRes title: Int, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val drawable = remember(text) { PageText.canDraw(text) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(PageText.MAX_LENGTH) },
                label = { Text(stringResource(R.string.edit_text)) },
                isError = !drawable,
                // Shown here: a message under the dialog would be hidden by it and by the keyboard.
                supportingText = if (!drawable) {
                    { Text(stringResource(R.string.watermark_no_font), Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                } else null,
                minLines = 2,
                maxLines = 6,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { onDone(text.trim()) }, enabled = text.isNotBlank() && drawable) { Text(stringResource(R.string.sign_done)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
