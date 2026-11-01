package io.github.nath_goa.usugami.ui

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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.core.PageNumberPosition
import io.github.nath_goa.usugami.core.PageNumberSize
import io.github.nath_goa.usugami.core.PdfPageSelection
import io.github.nath_goa.usugami.core.SignaturePlacement
import io.github.nath_goa.usugami.data.PdfCompression
import io.github.nath_goa.usugami.data.PdfInput
import io.github.nath_goa.usugami.data.PdfPageRef
import io.github.nath_goa.usugami.data.WatermarkColor
import io.github.nath_goa.usugami.data.WatermarkOpacity
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
    pickAdd: () -> Unit,
) {
    val inputs by vm.inputs.collectAsStateWithLifecycle()
    val pages by vm.pages.collectAsStateWithLifecycle()
    val locked by vm.locked.collectAsStateWithLifecycle()
    val unlocking by vm.unlocking.collectAsStateWithLifecycle()
    val wrongPassword by vm.wrongPassword.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val processing by vm.processing.collectAsStateWithLifecycle()
    val signature by vm.signature.collectAsStateWithLifecycle()
    val document by vm.document.collectAsStateWithLifecycle()
    val convertProgress by vm.convertProgress.collectAsStateWithLifecycle()
    // Word and PowerPoint to PDF take one document instead of PDFs.
    val officeKind = tool.officeKind
    var splitMode by rememberSaveable(tool) { mutableStateOf(SplitMode.SELECTED) }
    var pageText by rememberSaveable(tool) { mutableStateOf("") }
    var combine by rememberSaveable(tool) { mutableStateOf(true) }
    var pageMode by rememberSaveable(tool) { mutableStateOf(false) }
    var level by rememberSaveable(tool) { mutableStateOf(PdfCompression.RECOMMENDED) }
    var numberPosition by rememberSaveable(tool) { mutableStateOf(PageNumberPosition.BOTTOM_CENTER) }
    var numberSize by rememberSaveable(tool) { mutableStateOf(PageNumberSize.MEDIUM) }
    var skipText by rememberSaveable(tool) { mutableStateOf("") }
    var watermarkText by rememberSaveable(tool) { mutableStateOf("") }
    var watermarkColor by rememberSaveable(tool) { mutableStateOf(WatermarkColor.GRAY) }
    var watermarkOpacity by rememberSaveable(tool) { mutableStateOf(WatermarkOpacity.TRANSLUCENT) }
    // A signature usually goes at the end, so the last page is chosen first.
    var signPage by rememberSaveable(tool, inputs.firstOrNull()?.id) { mutableIntStateOf(maxOf(0, (inputs.firstOrNull()?.pageCount ?: 1) - 1)) }
    var signPlacement by rememberSaveable(tool, inputs.firstOrNull()?.id, stateSaver = SignaturePlacementSaver) { mutableStateOf<SignaturePlacement?>(null) }
    var drawingSignature by rememberSaveable(tool) { mutableStateOf(false) }
    var deleteText by rememberSaveable(tool, inputs.firstOrNull()?.id) { mutableStateOf("") }
    // Reorganize's chosen page, by its key.
    var chosenPage by rememberSaveable(tool, inputs.firstOrNull()?.id) { mutableStateOf<String?>(null) }
    // Saving is off until the pages differ from the PDF's own.
    val originalPages = remember(inputs.firstOrNull()) { vm.originalPages() }
    val reorganized = tool == PdfTool.REORGANIZE && inputs.isNotEmpty() && pages.isNotEmpty() && pages != originalPages
    val inputKey = if (officeKind != null) document?.id else inputs.firstOrNull()?.id
    var fileName by rememberSaveable(tool, inputKey) { mutableStateOf(vm.defaultName(tool)) }
    val ready = when (tool) {
        PdfTool.SPLIT, PdfTool.COMPRESS, PdfTool.PAGE_NUMBERS, PdfTool.FLATTEN -> inputs.size == 1
        PdfTool.MERGE -> inputs.size >= 2 && (!pageMode || pages.isNotEmpty())
        PdfTool.ROTATE -> inputs.size == 1 && pages.any { it.rotation != 0 }
        PdfTool.WATERMARK -> inputs.size == 1 && watermarkText.isNotBlank()
        PdfTool.SIGN -> inputs.size == 1 && signature != null
        PdfTool.WORD, PdfTool.POWERPOINT -> document?.kind == officeKind
        PdfTool.DELETE_PAGES -> inputs.size == 1 && deleteText.isNotBlank()
        PdfTool.REORGANIZE -> reorganized
    }
    val title = tool.title
    val action = when (tool) {
        PdfTool.SPLIT -> R.string.pdf_split_action
        PdfTool.MERGE -> R.string.pdf_merge_action
        PdfTool.COMPRESS -> R.string.pdf_compress_action
        PdfTool.ROTATE -> R.string.pdf_rotate_action
        PdfTool.PAGE_NUMBERS -> R.string.pdf_page_numbers_action
        PdfTool.WATERMARK -> R.string.pdf_watermark_action
        PdfTool.SIGN -> R.string.pdf_sign_action
        PdfTool.WORD, PdfTool.POWERPOINT -> R.string.convert
        PdfTool.DELETE_PAGES -> R.string.pdf_delete_pages_action
        PdfTool.REORGANIZE -> R.string.pdf_reorganize_action
        PdfTool.FLATTEN -> R.string.pdf_flatten_action
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
                        PdfTool.ROTATE -> vm.runRotate(fileName)
                        PdfTool.PAGE_NUMBERS -> vm.runPageNumbers(numberPosition, numberSize, skipText, fileName)
                        PdfTool.WATERMARK -> vm.runWatermark(watermarkText, watermarkColor, watermarkOpacity, fileName)
                        PdfTool.SIGN -> vm.runSign(signPage, signPlacement, fileName)
                        PdfTool.WORD, PdfTool.POWERPOINT -> vm.runConvert(fileName)
                        PdfTool.DELETE_PAGES -> vm.runDeletePages(deleteText, fileName)
                        PdfTool.REORGANIZE -> vm.runReorganize(fileName)
                        PdfTool.FLATTEN -> vm.runFlatten(fileName)
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
                if (if (officeKind != null) document == null else inputs.isEmpty()) {
                    // Picking is off while PDFs are being added: a second pick then would be ignored.
                    OutlinedCard(onClick = if (tool == PdfTool.MERGE) pickMultiple else pickSingle, modifier = Modifier.fillMaxWidth(), enabled = !importing) {
                        Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Rounded.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
                            Spacer(Modifier.height(12.dp))
                            Text(
                                stringResource(
                                    when (tool) {
                                        PdfTool.MERGE -> R.string.add_pdfs
                                        PdfTool.WORD -> R.string.pick_word
                                        PdfTool.POWERPOINT -> R.string.pick_powerpoint
                                        else -> R.string.pick_pdf
                                    },
                                ),
                            )
                        }
                    }
                } else if (officeKind != null) {
                    document?.let { DocumentInputCard(it, onRemove = vm::startOver) }
                    OutlinedButton(onClick = pickSingle, enabled = !importing) { Text(stringResource(R.string.replace_document)) }
                    OfficeNote(officeKind)
                    FileNameField(fileName, onChange = { fileName = it })
                } else {
                    if (tool == PdfTool.MERGE) {
                        MergeInputs(inputs, vm, pageMode, onPageMode = { pageMode = it })
                        // The page list carries the gap above it, so the gap opens and closes with the list.
                        Column {
                            OutlinedButton(onClick = pickMultiple, enabled = !importing) { Text(stringResource(R.string.add_pdfs)) }
                            AnimatedVisibility(visible = pageMode, enter = pdfSectionEnter(), exit = pdfSectionExit(), label = "page list") {
                                Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    MergePages(inputs, pages, vm)
                                }
                            }
                        }
                    } else {
                        PdfInputCard(inputs[0], onRemove = vm::startOver)
                        OutlinedButton(onClick = pickSingle, enabled = !importing) { Text(stringResource(R.string.replace_pdf)) }
                    }
                    when (tool) {
                        PdfTool.SPLIT -> SplitOptions(inputs[0], splitMode, onMode = { splitMode = it }, pageText, onText = { pageText = it }, combine, onCombine = { combine = it })
                        PdfTool.MERGE -> Unit
                        PdfTool.COMPRESS -> CompressOptions(level, onLevel = { level = it })
                        PdfTool.ROTATE -> RotateOptions(inputs[0], pages, vm)
                        PdfTool.PAGE_NUMBERS -> PageNumberOptions(
                            numberPosition, onPosition = { numberPosition = it },
                            numberSize, onSize = { numberSize = it },
                            skipText, onSkip = { skipText = it },
                        )
                        PdfTool.WATERMARK -> WatermarkOptions(
                            inputs[0],
                            watermarkText, onText = { watermarkText = it },
                            watermarkColor, onColor = { watermarkColor = it },
                            watermarkOpacity, onOpacity = { watermarkOpacity = it },
                        )
                        PdfTool.SIGN -> SignOptions(
                            inputs[0],
                            signature, onDraw = { drawingSignature = true },
                            signPage, onPage = { signPage = it },
                            signPlacement, onPlacement = { signPlacement = it },
                        )
                        PdfTool.WORD, PdfTool.POWERPOINT -> Unit
                        PdfTool.DELETE_PAGES -> DeletePagesOptions(inputs[0], deleteText, onText = { deleteText = it })
                        PdfTool.REORGANIZE -> ReorganizeOptions(
                            inputs, pages, vm,
                            changed = reorganized,
                            importing = importing,
                            selected = chosenPage,
                            onSelect = { chosenPage = it },
                            onAddPdf = { after ->
                                vm.prepareInsert(after)
                                pickAdd()
                            },
                        )
                        PdfTool.FLATTEN -> FlattenOptions()
                    }
                    FileNameField(fileName, onChange = { fileName = it })
                }
            }
        }
    }

    if (drawingSignature && tool == PdfTool.SIGN) {
        SignaturePadDialog(
            initial = signature,
            onDone = {
                vm.setSignature(it)
                drawingSignature = false
            },
            onDismiss = { drawingSignature = false },
        )
    }
    // Several locked PDFs picked at once are asked for one at a time.
    locked.firstOrNull()?.let { pending ->
        PasswordDialog(
            input = pending,
            wrongPassword = wrongPassword,
            unlocking = unlocking,
            onUnlock = { vm.unlock(pending, it) },
            onEdit = vm::clearWrongPassword,
            onDismiss = { vm.dismissPassword(pending) },
        )
    }
    if (processing && officeKind != null) {
        OfficeProgressDialog(convertProgress, onCancel = vm::cancel)
    } else if (processing) {
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
private fun FileNameField(fileName: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = fileName,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.file_name)) },
        suffix = { Text(".pdf") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PasswordDialog(
    input: PdfInput,
    wrongPassword: Boolean,
    unlocking: Boolean,
    onUnlock: (String) -> Unit,
    onEdit: () -> Unit,
    onDismiss: () -> Unit,
) {
    var password by rememberSaveable(input.id) { mutableStateOf("") }
    val canUnlock = password.isNotEmpty() && !unlocking
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pdf_password_title)) },
        text = {
            Column {
                Text(stringResource(R.string.pdf_password_body, input.name))
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        onEdit()
                    },
                    label = { Text(stringResource(R.string.pdf_password_hint)) },
                    // Shown here: a message under the dialog would be hidden by it and by the keyboard.
                    isError = wrongPassword,
                    supportingText = if (wrongPassword) {
                        { Text(stringResource(R.string.pdf_wrong_password), Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                    } else null,
                    visualTransformation = PasswordVisualTransformation(),
                    // A password keyboard shows no suggestions and learns nothing typed here.
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (canUnlock) onUnlock(password) }),
                    singleLine = true,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onUnlock(password) }, enabled = canUnlock) { Text(stringResource(R.string.pdf_unlock)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
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
            IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.remove_file, input.name)) }
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
                            // Each file has these buttons, so their labels name the file they act on.
                            IconButton(onClick = { vm.moveInput(input.id, -1) }, enabled = index > 0) {
                                Icon(Icons.Rounded.ArrowUpward, contentDescription = stringResource(R.string.move_file_earlier, input.name))
                            }
                            IconButton(onClick = { vm.moveInput(input.id, 1) }, enabled = index < inputs.lastIndex) {
                                Icon(Icons.Rounded.ArrowDownward, contentDescription = stringResource(R.string.move_file_later, input.name))
                            }
                            IconButton(onClick = { vm.removeInput(input.id) }) {
                                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.remove_file, input.name))
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
                    val moveEarlier = stringResource(R.string.move_page_earlier, index + 1)
                    val moveLater = stringResource(R.string.move_page_later, index + 1)
                    OutlinedCard(
                        Modifier
                            .fillMaxWidth()
                            .longPressDraggableHandle()
                            // Screen reader and switch users can't drag: the card offers the same moves as
                            // actions. Merged, so it is one focusable item that carries them.
                            .semantics(mergeDescendants = true) {
                                customActions = buildList {
                                    if (index > 0) add(CustomAccessibilityAction(moveEarlier) { vm.movePageBy(ref, -1); true })
                                    if (index < pages.lastIndex) add(CustomAccessibilityAction(moveLater) { vm.movePageBy(ref, 1); true })
                                }
                            },
                    ) {
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
                icon = {},
                contentPadding = SegmentPadding,
                label = { SegmentLabel(stringResource(when (item) {
                    PdfCompression.LESS -> R.string.compression_less
                    PdfCompression.RECOMMENDED -> R.string.compression_recommended
                    PdfCompression.EXTREME -> R.string.compression_extreme
                })) })
        }
    }
    Text(stringResource(R.string.pdf_compress_note), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** How optional sections on this screen appear: fading in while opening downwards. */
internal fun pdfSectionEnter(): EnterTransition =
    fadeIn(Motion.medium()) + expandVertically(Motion.medium(), expandFrom = Alignment.Top)

/** How optional sections on this screen disappear: fading out while closing upwards. */
internal fun pdfSectionExit(): ExitTransition =
    fadeOut(Motion.medium()) + shrinkVertically(Motion.medium(), shrinkTowards = Alignment.Top)

/**
 * Animates a page's rotation. Rotations are stored as 0, 90, 180 or 270, so this keeps a running
 * angle instead: turning from 270 to 0 then moves a quarter turn forward rather than three quarters
 * back. Updating the running angle here is idempotent, so recomposing again changes nothing.
 */
@Composable
internal fun animatePdfPageRotationAsState(degrees: Int): State<Float> {
    val running = remember { intArrayOf(degrees) }
    var delta = Math.floorMod(degrees - running[0], 360)
    if (delta > 180) delta -= 360
    running[0] += delta
    return animateFloatAsState(running[0].toFloat(), Motion.medium(), label = "page rotation")
}
