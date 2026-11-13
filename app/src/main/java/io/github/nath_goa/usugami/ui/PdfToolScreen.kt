package io.github.nath_goa.usugami.ui

import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.activity.compose.BackHandler
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.core.BatchChoices
import io.github.nath_goa.usugami.core.PageNumberPosition
import io.github.nath_goa.usugami.core.PageNumberSize
import io.github.nath_goa.usugami.core.PageSize
import io.github.nath_goa.usugami.core.PdfPageSelection
import io.github.nath_goa.usugami.core.SignaturePlacement
import io.github.nath_goa.usugami.data.PdfCompression
import io.github.nath_goa.usugami.data.PdfInput
import io.github.nath_goa.usugami.data.PdfPageRef
import io.github.nath_goa.usugami.data.WatermarkColor
import io.github.nath_goa.usugami.data.WatermarkOpacity
import io.github.nath_goa.usugami.data.office.OfficeKind
import io.github.nath_goa.usugami.data.office.SheetOptions
import kotlinx.coroutines.delay
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfToolScreen(
    tool: PdfTool,
    vm: PdfToolsViewModel,
    receiving: Boolean,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onRemove: () -> Unit,
    pickSingle: () -> Unit,
    pickMultiple: () -> Unit,
    pickAdd: () -> Unit,
) {
    val inputs by vm.inputs.collectAsStateWithLifecycle()
    val pages by vm.pages.collectAsStateWithLifecycle()
    val locked by vm.locked.collectAsStateWithLifecycle()
    val unlocking by vm.unlocking.collectAsStateWithLifecycle()
    val wrongPassword by vm.wrongPassword.collectAsStateWithLifecycle()
    val adding by vm.importing.collectAsStateWithLifecycle()
    // Files shared to Usugami that are still being copied in for the tool are being added too.
    val importing = adding || receiving
    val processing by vm.processing.collectAsStateWithLifecycle()
    val signature by vm.signature.collectAsStateWithLifecycle()
    val documents by vm.documents.collectAsStateWithLifecycle()
    val convertProgress by vm.convertProgress.collectAsStateWithLifecycle()
        val redactBoxes by vm.redactBoxes.collectAsStateWithLifecycle()
    val finding by vm.finding.collectAsStateWithLifecycle()
    val previewView by vm.previewView.collectAsStateWithLifecycle()
    val views by vm.views.collectAsStateWithLifecycle()
    val batch by vm.batch.collectAsStateWithLifecycle()
    val batchProgress by vm.batchProgress.collectAsStateWithLifecycle()
    val markProgress by vm.markProgress.collectAsStateWithLifecycle()
    // Word, PowerPoint, Excel and EPUB to PDF take documents instead of PDFs.
    val officeKind = tool.officeKind
    // A tool that also takes several PDFs or documents ([isBatch]) lists them, each with its own name and choices.
    val many = tool.isBatch && (if (officeKind != null) documents.size else inputs.size) > 1
    // Split's pages belong to the PDF they were chosen in.
    var splitMode by rememberSaveable(tool, inputs.firstOrNull()?.id) { mutableStateOf(SplitMode.SELECTED) }
    var pageText by rememberSaveable(tool, inputs.firstOrNull()?.id) { mutableStateOf("") }
    var combine by rememberSaveable(tool) { mutableStateOf(true) }
    var pageMode by rememberSaveable(tool) { mutableStateOf(false) }
    var numberPosition by rememberSaveable(tool) { mutableStateOf(PageNumberPosition.BOTTOM_CENTER) }
    var numberSize by rememberSaveable(tool) { mutableStateOf(PageNumberSize.MEDIUM) }
    // Compress PDF's Black and white, for one PDF or all: off each time the tool opens.
    var blackAndWhite by rememberSaveable(tool) { mutableStateOf(false) }
    var skipText by rememberSaveable(tool) { mutableStateOf("") }
    var watermarkText by rememberSaveable(tool) { mutableStateOf("") }
    var watermarkColor by rememberSaveable(tool) { mutableStateOf(WatermarkColor.GRAY) }
    var watermarkOpacity by rememberSaveable(tool) { mutableStateOf(WatermarkOpacity.TRANSLUCENT) }
    // A signature usually goes at the end, so the last page is chosen first.
    var signPage by rememberSaveable(tool, inputs.firstOrNull()?.id) { mutableIntStateOf(maxOf(0, (inputs.firstOrNull()?.pageCount ?: 1) - 1)) }
    var signPlacement by rememberSaveable(tool, inputs.firstOrNull()?.id, stateSaver = SignaturePlacementSaver) { mutableStateOf<SignaturePlacement?>(null) }
    var drawingSignature by rememberSaveable(tool) { mutableStateOf(false) }
    // Saving is off until the pages differ from the PDF's own.
    val originalPages = remember(inputs.firstOrNull()) { vm.originalPages() }
    val reorganized = tool == PdfTool.REORGANIZE && inputs.isNotEmpty() && pages.isNotEmpty() && pages != originalPages
    var bookPage by rememberSaveable(tool) { mutableStateOf(PageSize.A5) }
    // Excel to PDF's choices, kept apart so each is saved as it is.
    var sheetPaper by rememberSaveable(tool) { mutableStateOf<PageSize?>(null) }
    var sheetLandscape by rememberSaveable(tool) { mutableStateOf<Boolean?>(null) }
    var sheetFitWidth by rememberSaveable(tool) { mutableStateOf(true) }
    var sheetGridlines by rememberSaveable(tool) { mutableStateOf(false) }
    val sheetOptions = SheetOptions(sheetPaper, sheetLandscape, sheetFitWidth, sheetGridlines)
    val onSheetOptions = { options: SheetOptions ->
        sheetPaper = options.paper
        sheetLandscape = options.landscape
        sheetFitWidth = options.fitWidth
        sheetGridlines = options.gridlines
    }
    // Merge's name doesn't depend on which PDF comes first: sorting them keeps what was typed.
    val inputKey: Any? = when {
        officeKind != null -> documents.firstOrNull()?.id
        tool == PdfTool.MERGE -> inputs.isNotEmpty()
        else -> inputs.firstOrNull()?.id
    }
    var fileName by rememberSaveable(tool, inputKey) { mutableStateOf(vm.defaultName(tool)) }
    // What the options of a tool kept in a file of its own tell this screen (see ToolAction).
    // PDF to images or text names its files itself: its name field isn't there even for the one
    // frame before its options are first drawn and say so.
    val toolAction = remember(tool) { ToolAction(suffix = if (tool == PdfTool.PDF_TO_IMAGES_OR_TEXT) null else ".pdf") }
    val toolProgress by vm.toolProgress.collectAsStateWithLifecycle()
    val ready = when (tool) {
        PdfTool.SPLIT -> inputs.size == 1
        PdfTool.COMPRESS, PdfTool.PAGE_NUMBERS, PdfTool.FLATTEN -> inputs.isNotEmpty()
        PdfTool.MERGE -> inputs.size >= 2 && (!pageMode || pages.isNotEmpty())
        PdfTool.WATERMARK -> inputs.isNotEmpty() && watermarkText.isNotBlank()
        PdfTool.SIGN -> inputs.size == 1 && signature != null
        PdfTool.WORD, PdfTool.POWERPOINT, PdfTool.EXCEL, PdfTool.EPUB -> documents.isNotEmpty() && documents.all { it.kind == officeKind }
        PdfTool.REORGANIZE -> reorganized
        PdfTool.REDACT -> inputs.size == 1 && redactBoxes.isNotEmpty() && !finding
        PdfTool.PDF_TO_IMAGES_OR_TEXT, PdfTool.PAGE_LAYOUT, PdfTool.DOCUMENT_INFO -> inputs.size == 1 && toolAction.ready
        // The reader has a screen of its own (ReaderScreen); this screen is never shown for it.
        PdfTool.READER -> false
    }
    val title = tool.title
    val action = when (tool) {
        PdfTool.SPLIT -> R.string.pdf_split_action
        PdfTool.MERGE -> R.string.pdf_merge_action
        PdfTool.COMPRESS -> R.string.pdf_compress_action
        PdfTool.PAGE_NUMBERS -> R.string.pdf_page_numbers_action
        PdfTool.WATERMARK -> R.string.pdf_watermark_action
        PdfTool.SIGN -> R.string.pdf_sign_action
        PdfTool.WORD, PdfTool.POWERPOINT, PdfTool.EXCEL, PdfTool.EPUB -> R.string.convert
        PdfTool.REORGANIZE -> R.string.pdf_reorganize_action
        PdfTool.FLATTEN -> R.string.pdf_flatten_action
        PdfTool.REDACT -> R.string.pdf_redact_action
        PdfTool.PDF_TO_IMAGES_OR_TEXT -> R.string.pdf_to_images_or_text_action
        PdfTool.PAGE_LAYOUT -> R.string.pdf_page_layout_action
        PdfTool.DOCUMENT_INFO -> R.string.pdf_document_info_action
        // The reader has a screen of its own (ReaderScreen); this screen is never shown for it.
        PdfTool.READER -> R.string.pdf_edit_action
    }
    // The pages are drawn from a copy without the password and with annotations drawn in, when the
    // PDF has either: Android's renderer can't open the one and leaves the others out. Add watermark
    // previews the first of several PDFs.
    val single = if (tool == PdfTool.WATERMARK) inputs.firstOrNull() else inputs.singleOrNull()
    LaunchedEffect(tool, single?.id) {
        if (single != null && tool in PREVIEWED) vm.preparePreview(single, required = false)
    }
    val shown = single?.let { input -> previewView?.takeIf { it.id == input.id } ?: input }
    // What each PDF's thumbnails are drawn from, on screens with several PDFs too.
    val drawn: (PdfInput) -> PdfInput = { input -> previewView?.takeIf { it.id == input.id } ?: views[input.id] ?: input }
    // The bar takes a tint of its own once the page scrolls under it.
    val barScroll = TopAppBarDefaults.pinnedScrollBehavior()
    Box {
        Scaffold(
            modifier = Modifier.nestedScroll(barScroll.nestedScrollConnection),
            topBar = {
                TopAppBar(
                    title = { BarTitle(stringResource(title)) },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back)) } },
                    scrollBehavior = barScroll,
                )
            },
            bottomBar = {
                Button(
                    onClick = {
                        when (tool) {
                            PdfTool.SPLIT -> vm.runSplit(splitMode, pageText, combine, fileName)
                            PdfTool.MERGE -> vm.runMerge(pageMode, fileName)
                            // Each PDF at its level, into a PDF of its name.
                            PdfTool.COMPRESS -> vm.runCompressAll(blackAndWhite)
                            // These too, each PDF or document into a PDF of its name.
                            PdfTool.PAGE_NUMBERS -> vm.runPageNumbersAll(numberPosition, numberSize, skipText)
                            PdfTool.WATERMARK -> vm.runWatermarkAll(watermarkText, watermarkColor, watermarkOpacity)
                            PdfTool.SIGN -> vm.runSign(signPage, signPlacement, fileName)
                            PdfTool.WORD, PdfTool.POWERPOINT -> vm.runConvertAll()
                            PdfTool.REORGANIZE -> vm.runReorganize(fileName)
                            PdfTool.FLATTEN -> vm.runFlattenAll()
                            PdfTool.EPUB -> vm.runConvertAll(bookPage)
                            PdfTool.EXCEL -> vm.runConvertAll(sheets = sheetOptions)
                            PdfTool.REDACT -> vm.runRedact(fileName)
                            // What their options told the screen they do.
                            PdfTool.PDF_TO_IMAGES_OR_TEXT, PdfTool.PAGE_LAYOUT, PdfTool.DOCUMENT_INFO -> toolAction.run(fileName)
                            // The reader has a screen of its own (ReaderScreen); this screen is never shown for it.
                            PdfTool.READER -> Unit
                        }
                    },
                    enabled = ready && !importing && !processing,
                    // Edge to edge: keep the button clear of the navigation bar.
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                ) {
                    val count = if (officeKind != null) documents.size else inputs.size
                    Text(if (many) pluralStringResource(manyAction(tool), count, count) else toolAction.label ?: stringResource(action))
                }
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
                    if (if (officeKind != null) documents.isEmpty() else inputs.isEmpty()) {
                        // Picking is off while PDFs are being added: a second pick then would be ignored.
                        OutlinedCard(onClick = if (tool == PdfTool.MERGE || tool.isBatch) pickMultiple else pickSingle, modifier = Modifier.fillMaxWidth(), enabled = !importing) {
                            Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Rounded.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    stringResource(
                                        when {
                                            tool == PdfTool.MERGE -> R.string.add_pdfs
                                            tool == PdfTool.WORD -> R.string.pick_word
                                            tool == PdfTool.POWERPOINT -> R.string.pick_powerpoint
                                            tool == PdfTool.EXCEL -> R.string.pick_excel
                                            tool == PdfTool.EPUB -> R.string.pick_epub
                                            tool.isBatch -> R.string.pick_pdfs
                                            else -> R.string.pick_pdf
                                        },
                                    ),
                                )
                                if (tool.isBatch) {
                                    Text(
                                        stringResource(R.string.pick_several_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                }
                            }
                        }
                    } else if (officeKind != null) {
                        val add = @Composable { OutlinedButton(onClick = pickMultiple, enabled = !importing) { Text(stringResource(addDocuments(officeKind))) } }
                        if (many) {
                            // What goes for all of them comes first, then each with the name its PDF gets.
                            OfficeNote(officeKind)
                            if (officeKind == OfficeKind.EPUB) BookPageOptions(bookPage, onPage = { bookPage = it })
                            if (officeKind == OfficeKind.EXCEL) SheetPageOptions(sheetOptions, onSheetOptions)
                            BatchList(
                                entries = documents.map { documentBatchEntry(it) },
                                outputName = { id -> documents.firstOrNull { it.id == id }?.let(vm::outputName).orEmpty() },
                                onOutputName = vm::setBatchName,
                                onRemove = vm::removeDocument,
                                thumbnail = { DocumentIcon(officeKind, Modifier.size(width = 44.dp, height = 58.dp)) },
                            )
                            add()
                        } else {
                            val document = documents[0]
                            DocumentInputCard(document, onRemove = vm::startOver)
                            // More can be added, to convert all of them at once.
                            add()
                            OfficeNote(officeKind)
                            if (officeKind == OfficeKind.EPUB) BookPageOptions(bookPage, onPage = { bookPage = it })
                            if (officeKind == OfficeKind.EXCEL) SheetPageOptions(sheetOptions, onSheetOptions)
                            OutputNameField(document.id, vm.outputName(document), onChange = { vm.setBatchName(document.id, it) })
                        }
                    } else {
                        if (tool == PdfTool.MERGE) {
                            MergeInputs(inputs, vm, pageMode, onPageMode = { pageMode = it })
                            // The page list carries the gap above it, so the gap opens and closes with the list.
                            Column {
                                OutlinedButton(onClick = pickMultiple, enabled = !importing) { Text(stringResource(R.string.add_pdfs)) }
                                AnimatedVisibility(visible = pageMode, enter = pdfSectionEnter(), exit = pdfSectionExit(), label = "page list") {
                                    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                        MergePages(inputs, pages, vm, drawn)
                                    }
                                }
                            }
                        } else if (many) {
                            when (tool) {
                                PdfTool.COMPRESS -> CompressBatch(inputs, vm, batch, drawn, importing, pickMultiple, blackAndWhite, onBlackAndWhite = { blackAndWhite = it })
                                // The same choices for every PDF come first, then each PDF with its name.
                                PdfTool.PAGE_NUMBERS -> {
                                    PageNumberOptions(
                                        numberPosition, onPosition = { numberPosition = it },
                                        numberSize, onSize = { numberSize = it },
                                        skipText, onSkip = { skipText = it },
                                    )
                                    ForAllHint(R.string.page_numbers_for_all_hint)
                                    PdfBatchList(tool, inputs, vm, drawn, importing, pickMultiple)
                                }
                                PdfTool.WATERMARK -> {
                                    WatermarkOptions(
                                        shown ?: inputs[0],
                                        watermarkText, onText = { watermarkText = it },
                                        watermarkColor, onColor = { watermarkColor = it },
                                        watermarkOpacity, onOpacity = { watermarkOpacity = it },
                                    )
                                    ForAllHint(R.string.watermark_for_all_hint)
                                    PdfBatchList(tool, inputs, vm, drawn, importing, pickMultiple)
                                }
                                else -> {
                                    FlattenOptions()
                                    PdfBatchList(tool, inputs, vm, drawn, importing, pickMultiple)
                                }
                            }
                        } else {
                            PdfInputCard(inputs[0], thumbnail = drawn(inputs[0]), onRemove = onRemove)
                            // More can be added, to work on all of them at once.
                            if (tool.isBatch) OutlinedButton(onClick = pickMultiple, enabled = !importing) { Text(stringResource(R.string.add_pdfs)) }
                        }
                        if (!many) when (tool) {
                            PdfTool.SPLIT -> SplitOptions(
                                shown ?: inputs[0],
                                splitMode, onMode = { splitMode = it },
                                pageText, onText = { pageText = it },
                                // Read and written at once, so a second page tapped before the screen redraws adds to the first.
                                onTap = { change -> pageText = change(pageText) },
                                combine, onCombine = { combine = it },
                            )
                            PdfTool.MERGE -> Unit
                            // Its level is the one for all: a PDF added gets it too.
                            PdfTool.COMPRESS -> CompressOptions(
                                batch.compressionOf(inputs[0].id), onLevel = { vm.setBatchChoiceForAll(it.ordinal) },
                                blackAndWhite, onBlackAndWhite = { blackAndWhite = it },
                            )
                            PdfTool.PAGE_NUMBERS -> PageNumberOptions(
                                numberPosition, onPosition = { numberPosition = it },
                                numberSize, onSize = { numberSize = it },
                                skipText, onSkip = { skipText = it },
                            )
                            PdfTool.WATERMARK -> WatermarkOptions(
                                shown ?: inputs[0],
                                watermarkText, onText = { watermarkText = it },
                                watermarkColor, onColor = { watermarkColor = it },
                                watermarkOpacity, onOpacity = { watermarkOpacity = it },
                            )
                            PdfTool.SIGN -> SignOptions(
                                shown ?: inputs[0],
                                signature, onDraw = { drawingSignature = true },
                                signPage, onPage = { signPage = it },
                                signPlacement, onPlacement = { signPlacement = it },
                                // Read and written at once, so a second press before the screen redraws moves on from the first.
                                onNudge = { move -> signPlacement = move(signPlacement) },
                                pageSizes = vm::shownPageSizes,
                            )
                            PdfTool.WORD, PdfTool.POWERPOINT, PdfTool.EXCEL, PdfTool.EPUB -> Unit
                            PdfTool.REORGANIZE -> ReorganizeOptions(
                                inputs, pages, vm, drawn,
                                changed = reorganized,
                                importing = importing,
                                onAddPdf = { after ->
                                    vm.prepareInsert(after)
                                    pickAdd()
                                },
                            )
                            PdfTool.FLATTEN -> FlattenOptions()
                            PdfTool.REDACT -> RedactOptions(inputs[0], vm)
                            PdfTool.PDF_TO_IMAGES_OR_TEXT -> FromPdfOptions(inputs[0], drawn(inputs[0]), vm, toolAction)
                            PdfTool.PAGE_LAYOUT -> PageLayoutOptions(inputs[0], drawn(inputs[0]), vm, toolAction)
                            PdfTool.DOCUMENT_INFO -> DocumentInfoOptions(inputs[0], drawn(inputs[0]), vm, toolAction)
                            // The reader has a screen of its own (ReaderScreen); this screen is never shown for it.
                            PdfTool.READER -> Unit
                        }
                        // A tool for several PDFs keeps each one's name, also while there is only one.
                        if (tool.isBatch) {
                            if (!many) OutputNameField(inputs[0].id, vm.outputName(tool, inputs[0]), onChange = { vm.setBatchName(inputs[0].id, it) })
                        } else {
                            toolAction.suffix?.let { FileNameField(fileName, onChange = { fileName = it }, suffix = it) }
                        }
                    }
                }
            }
        }
        // While the PDF is being made, nothing on the screen can be touched, as behind the dialog.
        if (processing) {
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
    if (processing) {
        // Most PDFs are done before a dialog could be read: it only shows for one that takes a
        // while, like the ZIP dialog. Until then Back does nothing, as it would behind the dialog.
        var showWorking by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            delay(WORKING_DIALOG_DELAY_MS)
            showWorking = true
        }
        BackHandler {}
        val marking = markProgress
        if (showWorking && marking != null) {
            // The PDFs are made: the Usugami mark goes on them, one after another when there are several.
            AlertDialog(
                onDismissRequest = {},
                // The screen stays on while it shows, as for every job (see ProgressDialog).
                modifier = Modifier.keepScreenOn(),
                title = { Text(stringResource(R.string.mark_adding)) },
                text = { if (marking.total > 1) BatchProgressText(marking) else LinearProgressIndicator(Modifier.fillMaxWidth()) },
                confirmButton = {},
                dismissButton = { TextButton(onClick = vm::cancel) { Text(stringResource(R.string.cancel)) } },
            )
        } else if (showWorking && officeKind != null) {
            OfficeProgressDialog(convertProgress, officeKind, onCancel = vm::cancel, many = many, batch = batchProgress)
        } else if (showWorking) {
            AlertDialog(
                onDismissRequest = {},
                modifier = Modifier.keepScreenOn(),
                title = { Text(stringResource(if (many) R.string.pdf_working_many else R.string.pdf_working)) },
                text = {
                    when {
                        many -> BatchProgressText(batchProgress)
                        toolProgress != null -> ToolProgressText(toolProgress)
                        else -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = vm::cancel) { Text(stringResource(R.string.cancel)) } },
            )
        }
    }
}

/** Which of several PDFs is being made ("PDF 2 of 5") and its name; until the first starts, just a bar. */
@Composable
private fun BatchProgressText(progress: BatchProgress?) {
    val fraction by animateFloatAsState(
        if (progress == null) 0f else (progress.number - 1f) / progress.total,
        Motion.medium(),
        label = "batch progress",
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (progress == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Column {
                // A screen reader says it as it moves on to the next PDF.
                Text(
                    stringResource(R.string.batch_progress, progress.number, progress.total),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Text(
                    progress.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * How far a job on one PDF has got, when it says (see PdfToolsViewModel.reportProgress): a bar that
 * fills, over the line the job gives ("Page 3 of 40"). Not a live region: a line that changes with
 * every page would have a screen reader talk without end.
 */
@Composable
private fun ToolProgressText(progress: ToolProgress?) {
    val fraction by animateFloatAsState(
        if (progress == null || progress.total <= 0) 0f else (progress.done.toFloat() / progress.total).coerceIn(0f, 1f),
        Motion.medium(),
        label = "tool progress",
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        if (progress != null) Text(progress.text)
    }
}

/**
 * Compress PDF with several PDFs: a level for all of them at once and Black and white ([blackAndWhite])
 * for all of them, then each PDF with a level and a name of its own (see [BatchList]), and a button to
 * add more.
 */
@Composable
private fun CompressBatch(
    inputs: List<PdfInput>,
    vm: PdfToolsViewModel,
    batch: BatchChoices,
    drawn: (PdfInput) -> PdfInput,
    importing: Boolean,
    pickMultiple: () -> Unit,
    blackAndWhite: Boolean,
    onBlackAndWhite: (Boolean) -> Unit,
) {
    val common = batch.common?.let { PdfCompression.entries.getOrNull(it) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.compression_for_all), style = MaterialTheme.typography.titleMedium)
        // None is chosen while the PDFs' levels differ.
        CompressionLevels(common, onLevel = { vm.setBatchChoiceForAll(it.ordinal) })
        Text(
            stringResource(R.string.compression_for_all_hint) + " " + stringResource(R.string.pdf_compress_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // One switch for all the PDFs: none has one of its own.
        OptionSwitch(R.string.compress_gray, stringResource(R.string.compress_gray_detail_many), blackAndWhite, onBlackAndWhite)
    }
    PdfBatchList(PdfTool.COMPRESS, inputs, vm, drawn, importing, pickMultiple) { id ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.compression_title), style = MaterialTheme.typography.labelLarge)
            CompressionLevels(batch.compressionOf(id), onLevel = { vm.setBatchChoice(id, it.ordinal) })
        }
    }
}

/**
 * The PDFs [tool] works on, each with the name its PDF gets and what [options] puts above that (see
 * [BatchList]), and a button to add more.
 */
@Composable
private fun PdfBatchList(
    tool: PdfTool,
    inputs: List<PdfInput>,
    vm: PdfToolsViewModel,
    drawn: (PdfInput) -> PdfInput,
    importing: Boolean,
    pickMultiple: () -> Unit,
    options: @Composable (Long) -> Unit = {},
) {
    val byId = remember(inputs) { inputs.associateBy { it.id } }
    BatchList(
        entries = inputs.map { pdfBatchEntry(it) },
        outputName = { id -> byId[id]?.let { vm.outputName(tool, it) }.orEmpty() },
        onOutputName = vm::setBatchName,
        onRemove = vm::removeInput,
        thumbnail = { id -> byId[id]?.let { PdfThumbnail(drawn(it), 0, Modifier.size(width = 44.dp, height = 58.dp)) } },
        options = options,
    )
    OutlinedButton(onClick = pickMultiple, enabled = !importing) { Text(stringResource(R.string.add_pdfs)) }
}

/** A line under the choices a tool makes for several PDFs, saying they go for all of them. */
@Composable
private fun ForAllHint(@StringRes text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The run button's words when a tool works on several PDFs or documents: "Compress 3 PDFs", say. */
@PluralsRes
private fun manyAction(tool: PdfTool): Int = when (tool) {
    PdfTool.PAGE_NUMBERS -> R.plurals.pdf_page_numbers_action_many
    PdfTool.WATERMARK -> R.plurals.pdf_watermark_action_many
    PdfTool.FLATTEN -> R.plurals.pdf_flatten_action_many
    PdfTool.WORD, PdfTool.POWERPOINT, PdfTool.EXCEL, PdfTool.EPUB -> R.plurals.convert_many
    else -> R.plurals.pdf_compress_action_many
}

/** The button that adds more files of [kind] to convert. */
@StringRes
private fun addDocuments(kind: OfficeKind): Int = when (kind) {
    OfficeKind.WORD -> R.string.add_word
    OfficeKind.POWERPOINT -> R.string.add_powerpoint
    OfficeKind.EXCEL -> R.string.add_excel
    OfficeKind.EPUB -> R.string.add_epub
}

/** How long a tool works before its progress dialog shows. */
private const val WORKING_DIALOG_DELAY_MS = 400L

/** The tools that draw the PDF's pages for the user to work on. Redact PDF prepares its own. */
private val PREVIEWED = setOf(
    PdfTool.SPLIT, PdfTool.WATERMARK, PdfTool.SIGN, PdfTool.REORGANIZE,
    PdfTool.PDF_TO_IMAGES_OR_TEXT, PdfTool.PAGE_LAYOUT,
)

/** The name the file made gets, typed; [suffix] is how it ends, shown after it. */
@Composable
internal fun FileNameField(fileName: String, onChange: (String) -> Unit, suffix: String = ".pdf") {
    OutlinedTextField(
        value = fileName,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.file_name)) },
        suffix = { Text(suffix) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun PasswordDialog(
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
            // Scrolls: with large text in landscape, a long name would push the password field out of the dialog.
            Column(Modifier.verticalScroll(rememberScrollState())) {
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

/** The tool's PDF, [input]; its first page is drawn from [thumbnail] (see [PdfToolsViewModel.views]). */
@Composable
private fun PdfInputCard(input: PdfInput, thumbnail: PdfInput, onRemove: () -> Unit) {
    val context = LocalContext.current
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PdfThumbnail(thumbnail, 0, Modifier.size(width = 58.dp, height = 78.dp))
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
        // Says why Merge can't be pressed yet, and a screen reader says it as it shows.
        AnimatedVisibility(visible = inputs.size == 1, enter = pdfSectionEnter(), exit = pdfSectionExit(), label = "need two") {
            Text(
                stringResource(R.string.pdf_need_two),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
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

/** Merge's pages; each is drawn from [drawn] of its PDF. */
@Composable
private fun MergePages(inputs: List<PdfInput>, pages: List<PdfPageRef>, vm: PdfToolsViewModel, drawn: (PdfInput) -> PdfInput) {
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
                    // The page is only drawn turned: a screen reader hears it, also right after Rotate.
                    val turned = turnedLabel(ref.rotation)?.let { stringResource(it) }
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
                                if (turned != null) stateDescription = turned
                            },
                    ) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            PdfThumbnail(drawn(input), ref.page, Modifier.size(width = 55.dp, height = 72.dp).graphicsLayer { rotationZ = angle })
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.merge_page_line, index + 1, input.name, ref.page + 1), modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = { vm.rotatePage(ref, 90) }) {
                                TurnIcon(clockwise = true, description = stringResource(R.string.rotate_page_right, index + 1))
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
private fun SplitOptions(
    input: PdfInput,
    mode: SplitMode,
    onMode: (SplitMode) -> Unit,
    text: String,
    onText: (String) -> Unit,
    onTap: (change: (String) -> String) -> Unit,
    combine: Boolean,
    onCombine: (Boolean) -> Unit,
) {
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
        // As many to a line as fit, so a long label (a translation, a large font size) moves to the next line.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            modes.forEach { (item, label) ->
                FilterChip(selected = mode == item, onClick = { if (mode != item) { onMode(item); onText("") } }, label = { Text(stringResource(label)) })
            }
        }
        val showRange = mode == SplitMode.SELECTED || mode == SplitMode.CUSTOM || mode == SplitMode.FIXED
        // While a section animates away it keeps its old labels instead of switching to another mode's.
        val rangeMode = freezeWhen(!showRange, mode)
        AnimatedVisibility(visible = showRange, enter = pdfSectionEnter(), exit = pdfSectionExit(), label = "range field") {
            OutlinedTextField(
                value = text, onValueChange = onText,
                label = { Text(stringResource(if (rangeMode == SplitMode.FIXED) R.string.pdf_fixed_hint else if (rangeMode == SplitMode.CUSTOM) R.string.pdf_custom else R.string.pdf_selected)) },
                supportingText = { Text(stringResource(if (rangeMode == SplitMode.CUSTOM) R.string.pdf_custom_hint else if (rangeMode == SplitMode.FIXED) R.string.pdf_fixed_example else R.string.pdf_selected_hint)) },
                // Pages per PDF is a number.
                keyboardOptions = KeyboardOptions(keyboardType = if (rangeMode == SplitMode.FIXED) KeyboardType.Number else KeyboardType.Text),
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
            // In the order typed, which a combined PDF keeps.
            val selected = typedPages(text, input.pageCount).toSet()
            // A page tapped goes at the end; the others keep their order, and ranges stay short.
            // From the pages chosen now, not as last drawn: two tapped at once are both chosen.
            PageRowPicker(input, selected, Modifier.padding(top = 16.dp).fillMaxWidth()) { i ->
                onTap tap@{ current ->
                    val now = tappedOnto(current, input.pageCount) ?: return@tap current
                    PdfPageSelection.formatInOrder(if (i in now) now - i else now + i)
                }
            }
        }
    }
}

/** Compress PDF with one PDF: its level, and Black and white ([blackAndWhite]), which turns its colours gray. */
@Composable
private fun CompressOptions(level: PdfCompression, onLevel: (PdfCompression) -> Unit, blackAndWhite: Boolean, onBlackAndWhite: (Boolean) -> Unit) {
    Text(stringResource(R.string.compression_title), style = MaterialTheme.typography.titleMedium)
    CompressionLevels(level, onLevel)
    Text(stringResource(R.string.pdf_compress_note), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    OptionSwitch(R.string.compress_gray, stringResource(R.string.compress_gray_detail), blackAndWhite, onBlackAndWhite)
}

/** Less, Recommended and Extreme, with [level] chosen (none when it is null). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompressionLevels(level: PdfCompression?, onLevel: (PdfCompression) -> Unit) {
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
