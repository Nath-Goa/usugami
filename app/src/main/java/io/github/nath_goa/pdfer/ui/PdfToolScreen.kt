package io.github.nath_goa.pdfer.ui

import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Rotate90DegreesCw
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nath_goa.pdfer.R
import io.github.nath_goa.pdfer.core.PdfPageSelection
import io.github.nath_goa.pdfer.data.PdfCompression
import io.github.nath_goa.pdfer.data.PdfInput
import io.github.nath_goa.pdfer.data.PdfPageRef
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfToolScreen(
    tool: PdfTool,
    vm: PdfToolsViewModel,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    pickSingle: () -> Unit,
    pickMultiple: () -> Unit,
) {
    val inputs by vm.inputs.collectAsStateWithLifecycle()
    val pages by vm.pages.collectAsStateWithLifecycle()
    val locked by vm.locked.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val processing by vm.processing.collectAsStateWithLifecycle()
    var splitMode by rememberSaveable(tool) { mutableStateOf(SplitMode.SELECTED) }
    var pageText by rememberSaveable(tool) { mutableStateOf("") }
    var combine by rememberSaveable(tool) { mutableStateOf(true) }
    var pageMode by rememberSaveable(tool) { mutableStateOf(false) }
    var level by rememberSaveable(tool) { mutableStateOf(PdfCompression.RECOMMENDED) }
    var fileName by rememberSaveable(tool, inputs.firstOrNull()?.id) { mutableStateOf(vm.defaultName(tool)) }
    val ready = when (tool) {
        PdfTool.SPLIT -> inputs.size == 1
        PdfTool.MERGE -> inputs.size >= 2 && (!pageMode || pages.isNotEmpty())
        PdfTool.COMPRESS -> inputs.size == 1
    }
    val title = when (tool) {
        PdfTool.SPLIT -> R.string.tool_split
        PdfTool.MERGE -> R.string.tool_merge
        PdfTool.COMPRESS -> R.string.tool_compress
    }
    val action = when (tool) {
        PdfTool.SPLIT -> R.string.pdf_split_action
        PdfTool.MERGE -> R.string.pdf_merge_action
        PdfTool.COMPRESS -> R.string.pdf_compress_action
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back)) } },
            )
        },
        bottomBar = {
            Button(
                onClick = {
                    when (tool) {
                        PdfTool.SPLIT -> vm.runSplit(splitMode, pageText, combine, fileName)
                        PdfTool.MERGE -> vm.runMerge(pageMode, fileName)
                        PdfTool.COMPRESS -> vm.runCompress(level, fileName)
                    }
                },
                enabled = ready && !importing && !processing,
                // Edge to edge: keep the button clear of the navigation bar.
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) { Text(stringResource(action)) }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        // The keyboard covers the bottom bar; the form ends above the keyboard instead, so the
        // file name field can be scrolled into view while typing.
        Column(
            Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            // The bar sits outside the spaced column below and carries its own gap, so the gap opens and
            // closes with the bar instead of snapping once the bar has gone.
            AnimatedVisibility(visible = importing, enter = pdfSectionEnter(), exit = pdfSectionExit(), label = "importing") {
                LinearProgressIndicator(Modifier.padding(bottom = 16.dp).fillMaxWidth())
            }
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (inputs.isEmpty()) {
                    OutlinedCard(onClick = if (tool == PdfTool.MERGE) pickMultiple else pickSingle, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Rounded.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
                            Spacer(Modifier.height(12.dp))
                            Text(stringResource(if (tool == PdfTool.MERGE) R.string.add_pdfs else R.string.pick_pdf))
                        }
                    }
                } else {
                    if (tool == PdfTool.MERGE) {
                        MergeInputs(inputs, vm, pageMode, onPageMode = { pageMode = it })
                        // The page list carries the gap above it, so the gap opens and closes with the list.
                        Column {
                            OutlinedButton(onClick = pickMultiple) { Text(stringResource(R.string.add_pdfs)) }
                            AnimatedVisibility(visible = pageMode, enter = pdfSectionEnter(), exit = pdfSectionExit(), label = "page list") {
                                Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    MergePages(inputs, pages, vm)
                                }
                            }
                        }
                    } else {
                        PdfInputCard(inputs[0], onRemove = vm::startOver)
                        OutlinedButton(onClick = pickSingle) { Text(stringResource(R.string.replace_pdf)) }
                    }
                    when (tool) {
                        PdfTool.SPLIT -> SplitOptions(inputs[0], splitMode, onMode = { splitMode = it }, pageText, onText = { pageText = it }, combine, onCombine = { combine = it })
                        PdfTool.MERGE -> Unit
                        PdfTool.COMPRESS -> CompressOptions(level, onLevel = { level = it })
                    }
                    OutlinedTextField(
                        value = fileName,
                        onValueChange = { fileName = it },
                        label = { Text(stringResource(R.string.file_name)) },
                        suffix = { Text(".pdf") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    if (locked != null) {
        var password by rememberSaveable(locked?.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = vm::dismissPassword,
            title = { Text(stringResource(R.string.pdf_password_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.pdf_password_body))
                    OutlinedTextField(password, onValueChange = { password = it }, label = { Text(stringResource(R.string.pdf_password_hint)) },
                        visualTransformation = PasswordVisualTransformation(), singleLine = true)
                }
            },
            confirmButton = { TextButton(onClick = { vm.unlock(password) }) { Text(stringResource(R.string.pdf_unlock)) } },
            dismissButton = { TextButton(onClick = vm::dismissPassword) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (processing) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.pdf_working)) },
            text = { LinearProgressIndicator(Modifier.fillMaxWidth()) },
            confirmButton = {},
            dismissButton = { TextButton(onClick = vm::cancel) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun PdfInputCard(input: PdfInput, onRemove: () -> Unit) {
    val context = LocalContext.current
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PdfThumbnail(input, 0, Modifier.size(width = 58.dp, height = 78.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(input.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.result_details, pluralStringResource(R.plurals.page_count, input.pageCount, input.pageCount), Formatter.formatShortFileSize(context, input.file.length())),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.remove)) }
        }
    }
}

@Composable
private fun MergeInputs(inputs: List<PdfInput>, vm: PdfToolsViewModel, pageMode: Boolean, onPageMode: (Boolean) -> Unit) {
    // The whole row flips the switch, and a screen reader hears what the switch turns on.
    val switchLabel = stringResource(R.string.pdf_page_mode_switch)
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = pageMode, role = Role.Switch, onValueChange = onPageMode)
            .semantics { contentDescription = switchLabel }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.pdf_file_mode), modifier = Modifier.weight(1f))
        Switch(checked = pageMode, onCheckedChange = null)
        Text(stringResource(R.string.pdf_page_mode), modifier = Modifier.padding(start = 8.dp))
    }
    // The file list carries the gap above it, so the gap opens and closes with the list.
    Column {
        Text(stringResource(if (pageMode) R.string.pdf_page_mode_hint else R.string.pdf_file_mode_hint),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AnimatedVisibility(visible = !pageMode, enter = pdfSectionEnter(), exit = pdfSectionExit(), label = "file list") {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                inputs.forEachIndexed { index, input ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${index + 1}.", modifier = Modifier.padding(horizontal = 8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(input.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(pluralStringResource(R.plurals.page_count, input.pageCount, input.pageCount), style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { vm.moveInput(input.id, -1) }, enabled = index > 0) {
                                Icon(Icons.Rounded.ArrowUpward, contentDescription = stringResource(R.string.move_earlier))
                            }
                            IconButton(onClick = { vm.moveInput(input.id, 1) }, enabled = index < inputs.lastIndex) {
                                Icon(Icons.Rounded.ArrowDownward, contentDescription = stringResource(R.string.move_later))
                            }
                            IconButton(onClick = { vm.removeInput(input.id) }) {
                                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.remove))
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { vm.sortInputs() }) { Text(stringResource(R.string.pdf_sort_az)) }
                    TextButton(onClick = { vm.sortInputs(true) }) { Text(stringResource(R.string.pdf_sort_za)) }
                }
            }
        }
    }
}

@Composable
private fun MergePages(inputs: List<PdfInput>, pages: List<PdfPageRef>, vm: PdfToolsViewModel) {
    val byId = remember(inputs) { inputs.associateBy { it.id } }
    TextButton(onClick = vm::resetPages) { Text(stringResource(R.string.pdf_reset_pages)) }
    val state = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(state) { from, to ->
        val a = pages.firstOrNull { "${it.inputId}:${it.page}" == from.key }
        val b = pages.firstOrNull { "${it.inputId}:${it.page}" == to.key }
        if (a != null && b != null) vm.movePage(a, b)
    }
    // The message under the list carries the gap above it, so the gap opens and closes with the message.
    Column {
        LazyColumn(state = state, modifier = Modifier.fillMaxWidth().height(450.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(pages, key = { _, ref -> "${ref.inputId}:${ref.page}" }) { index, ref ->
                val input = byId[ref.inputId] ?: return@itemsIndexed
                ReorderableItem(reorder, key = "${ref.inputId}:${ref.page}") { _ ->
                    val angle by animatePdfPageRotationAsState(ref.rotation)
                    OutlinedCard(Modifier.fillMaxWidth().longPressDraggableHandle()) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            PdfThumbnail(input, ref.page, Modifier.size(width = 55.dp, height = 72.dp).graphicsLayer { rotationZ = angle })
                            Spacer(Modifier.width(8.dp))
                            Text("${index + 1}. ${input.name} · ${ref.page + 1}", modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = { vm.rotatePage(ref, 90) }) {
                                Icon(Icons.Rounded.Rotate90DegreesCw, contentDescription = stringResource(R.string.rotate_page_right, index + 1))
                            }
                            IconButton(onClick = { vm.removePage(ref) }) {
                                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.remove_page, index + 1))
                            }
                        }
                    }
                }
            }
        }
        AnimatedVisibility(visible = pages.isEmpty(), enter = pdfSectionEnter(), exit = pdfSectionExit(), label = "no pages error") {
            Text(stringResource(R.string.pdf_need_page), modifier = Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.error)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SplitOptions(input: PdfInput, mode: SplitMode, onMode: (SplitMode) -> Unit, text: String, onText: (String) -> Unit, combine: Boolean, onCombine: (Boolean) -> Unit) {
    Text(stringResource(R.string.tool_split), style = MaterialTheme.typography.titleMedium)
    val modes = listOf(
        SplitMode.SELECTED to R.string.pdf_selected,
        SplitMode.CUSTOM to R.string.pdf_custom,
        SplitMode.FIXED to R.string.pdf_fixed,
        SplitMode.EVERY_PAGE to R.string.pdf_every_page,
    )
    // The sections under the chips carry the gap above them, so each gap opens and closes with its
    // section instead of snapping once the section has gone.
    Column {
        modes.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (item, label) ->
                    FilterChip(selected = mode == item, onClick = { onMode(item); onText("") }, label = { Text(stringResource(label)) })
                }
            }
        }
        val showRange = mode == SplitMode.SELECTED || mode == SplitMode.CUSTOM || mode == SplitMode.FIXED
        // While a section animates away it keeps its old labels instead of switching to another mode's.
        val rangeMode = freezeWhen(!showRange, mode)
        AnimatedVisibility(visible = showRange, enter = pdfSectionEnter(), exit = pdfSectionExit(), label = "range field") {
            OutlinedTextField(
                value = text, onValueChange = onText,
                label = { Text(stringResource(if (rangeMode == SplitMode.FIXED) R.string.pdf_fixed_hint else if (rangeMode == SplitMode.CUSTOM) R.string.pdf_custom else R.string.pdf_selected)) },
                supportingText = { Text(stringResource(if (rangeMode == SplitMode.CUSTOM) R.string.pdf_custom_hint else if (rangeMode == SplitMode.FIXED) R.string.pdf_fixed_hint else R.string.pdf_selected_hint)) },
                singleLine = true, modifier = Modifier.padding(top = 16.dp).fillMaxWidth(),
            )
        }
        val showCombine = mode == SplitMode.SELECTED || mode == SplitMode.CUSTOM
        val combineMode = freezeWhen(!showCombine, mode)
        AnimatedVisibility(visible = showCombine, enter = pdfSectionEnter(), exit = pdfSectionExit(), label = "combine switch") {
            // The whole row flips the switch, and its text labels it for a screen reader.
            Row(
                Modifier
                    .padding(top = 16.dp)
                    .fillMaxWidth()
                    .toggleable(value = combine, role = Role.Switch, onValueChange = onCombine)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(if (combineMode == SplitMode.CUSTOM) R.string.pdf_combine_ranges else R.string.pdf_combine_selected), modifier = Modifier.weight(1f))
                Spacer(Modifier.width(16.dp))
                Switch(checked = combine, onCheckedChange = null)
            }
        }
        AnimatedVisibility(visible = mode == SplitMode.SELECTED, enter = pdfSectionEnter(), exit = pdfSectionExit(), label = "page picker") {
            val selected = try { PdfPageSelection.selected(text, input.pageCount).toSet() } catch (_: Exception) { emptySet() }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 16.dp).fillMaxWidth()) {
                items(input.pageCount) { i ->
                    val active = i in selected
                    val borderWidth by animateDpAsState(if (active) 2.dp else 1.dp, Motion.short(), label = "page border width")
                    val borderColor by animateColorAsState(
                        if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        Motion.short(),
                        label = "page border color",
                    )
                    val pageLabel = stringResource(R.string.pdf_page_number, i + 1)
                    OutlinedCard(
                        onClick = {
                            val new = selected.toMutableSet()
                            if (active) new.remove(i) else new.add(i)
                            onText(new.sorted().joinToString(",") { (it + 1).toString() })
                        },
                        border = BorderStroke(borderWidth, borderColor),
                        modifier = Modifier.width(105.dp).semantics { this.selected = active },
                    ) {
                        PdfThumbnail(input, i, Modifier.fillMaxWidth().height(130.dp))
                        // A screen reader says "Page 3" rather than a bare "3".
                        Text(
                            "${i + 1}",
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .semantics { contentDescription = pageLabel }
                                .padding(6.dp),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompressOptions(level: PdfCompression, onLevel: (PdfCompression) -> Unit) {
    Text(stringResource(R.string.compression_title), style = MaterialTheme.typography.titleMedium)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        PdfCompression.entries.forEachIndexed { index, item ->
            SegmentedButton(selected = level == item, onClick = { onLevel(item) },
                shape = SegmentedButtonDefaults.itemShape(index, PdfCompression.entries.size),
                label = { Text(stringResource(when (item) {
                    PdfCompression.LESS -> R.string.compression_less
                    PdfCompression.RECOMMENDED -> R.string.compression_recommended
                    PdfCompression.EXTREME -> R.string.compression_extreme
                }), maxLines = 1) })
        }
    }
    Text(stringResource(R.string.pdf_compress_note), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** How optional sections on this screen appear: fading in while opening downwards. */
private fun pdfSectionEnter(): EnterTransition =
    fadeIn(Motion.medium()) + expandVertically(Motion.medium(), expandFrom = Alignment.Top)

/** How optional sections on this screen disappear: fading out while closing upwards. */
private fun pdfSectionExit(): ExitTransition =
    fadeOut(Motion.medium()) + shrinkVertically(Motion.medium(), shrinkTowards = Alignment.Top)

/**
 * Animates a page's rotation. Rotations are stored as 0, 90, 180 or 270, so this keeps a running
 * angle instead: turning from 270 to 0 then moves a quarter turn forward rather than three quarters
 * back. Updating the running angle here is idempotent, so recomposing again changes nothing.
 */
@Composable
private fun animatePdfPageRotationAsState(degrees: Int): State<Float> {
    val running = remember { intArrayOf(degrees) }
    var delta = Math.floorMod(degrees - running[0], 360)
    if (delta > 180) delta -= 360
    running[0] += delta
    return animateFloatAsState(running[0].toFloat(), Motion.medium(), label = "page rotation")
}
