package io.github.nath_goa.usugami.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pageview
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.core.EditStyle
import io.github.nath_goa.usugami.core.ReaderGeometry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * What the reader's buttons do outside the screen. Null: not offered (in a preview, for a PDF made of
 * a document opened, on a phone that can't print). Share, Print and Open with another app are not
 * offered either while Annotate's additions can be saved: the pages show them and the file doesn't.
 */
internal class ReaderActions(
    /** Leaves the reader, as Back does. */
    val back: () -> Unit,
    /** Android's share sheet, with the file as it was opened. */
    val share: (() -> Unit)? = null,
    /** Android's print screen, with the PDF as the reader shows it. */
    val print: (() -> Unit)? = null,
    /** Leaves the reader for Choose a tool, with its PDF in it. */
    val tools: (() -> Unit)? = null,
    /** Android's chooser of the other apps that open the file. */
    val openElsewhere: (() -> Unit)? = null,
    /** Opens the photo picker for Annotate's Picture. */
    val pickPicture: () -> Unit = {},
)

/**
 * The reader ([preview] false): the tool slot's PDF in one scroll, zoomed by pinching or double-tapping
 * (see ReaderPages), with the page showing counted at the bottom; tapping the count opens Go to page.
 * Find text turns the top bar into a search bar, which searches from the page showing when the
 * keyboard's search key is pressed and moves between the matches; closing it (its arrow, or Back)
 * takes the matches away. More has Share, Print, Tools, Go to page and Open with another app, those of
 * [actions] that are given. A Word, Excel, PowerPoint or EPUB file opened is first made into a PDF
 * (a dialog with Cancel), which shows with a note saying so and Save as PDF. [receiving]: a file opened
 * is being copied in, or waits to be shown. While the file comes in, "Opening…" shows with Cancel,
 * which leaves as Back does; a PDF with a password asks for it, and Cancel there leaves too.
 *
 * Annotate (what Edit PDF was until 2.0.0, see ReaderAnnotate) shows the page that was showing with
 * Edit PDF's tools; Back to reading (its arrow, or Back) returns to that page, with what was added
 * drawn on the pages. Once something is added, Save PDF shows at the bottom, while reading and in
 * Annotate: it makes a new PDF with the additions, named as Annotate's File name says, and shows the
 * result, which has Share and Print for it; meanwhile More hides Share, Print and Open with another
 * app, which would give the file without them, and a PDF made of a document hides its Save as PDF.
 *
 * A long-press on a word selects it (ReaderSelection), with a handle at each end to drag; while text
 * is selected, a bar under the pages has Copy, and a tap elsewhere, Back or Annotate lets go of it. A
 * screen reader's "Copy the text of this page" on a page copies it whole.
 *
 * Or a preview ([preview] true): a finished PDF looked at from a result screen (ReaderState.preview),
 * read-only, with its name in the bar, Find text, text to select and copy, and More with Go to page and
 * Open with another app.
 *
 * [active]: the screen is the one showing, not one sliding away. Only then does it open the slot's PDF,
 * make a document into a PDF or ask anything: the slot may meanwhile hold another tool's file.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderScreen(vm: PdfToolsViewModel, preview: Boolean, active: Boolean, receiving: Boolean, snackbar: SnackbarHostState, actions: ReaderActions) {
    val inputs by vm.inputs.collectAsStateWithLifecycle()
    val locked by vm.locked.collectAsStateWithLifecycle()
    val documents by vm.documents.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val unlocking by vm.unlocking.collectAsStateWithLifecycle()
    val wrongPassword by vm.wrongPassword.collectAsStateWithLifecycle()
    val session by vm.reader.session.collectAsStateWithLifecycle()
    val previewSession by vm.reader.preview.collectAsStateWithLifecycle()
    val source by vm.reader.source.collectAsStateWithLifecycle()
    val convertProgress by vm.convertProgress.collectAsStateWithLifecycle()
    val processing by vm.processing.collectAsStateWithLifecycle()
    val markProgress by vm.markProgress.collectAsStateWithLifecycle()
    val handingOver by vm.reader.handingOver.collectAsStateWithLifecycle()
    val editItems by vm.editItems.collectAsStateWithLifecycle()
    // Kept while the result shows and through recreation (the screen's state sits with the tool's).
    val list = rememberLazyListState()
    // How far the pages are zoomed (1: the screen's width) and moved left (screen pixels), and Go to page.
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    var panX by rememberSaveable { mutableFloatStateOf(0f) }
    var goingToPage by rememberSaveable { mutableStateOf(false) }
    // The page Go to page went to (0 for the first), and the list's first item and offset it left: the
    // indicator says that page while the list stays there, as a page near the end can't come to the top
    // and the one before it may fill more of the screen.
    var wentTo by rememberSaveable { mutableStateOf<IntArray?>(null) }
    // Find text: whether the search bar shows, what is typed in it, and what was searched for last (searched
    // again after Android closed Usugami: a search isn't kept in the saved state).
    var finding by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var searched by rememberSaveable { mutableStateOf("") }
    // Whether Find text was tapped since this screen was made: only then does the field take the focus and
    // bring up the keyboard, not when the bar shows again after rotation, over what was found.
    var typing by remember { mutableStateOf(false) }
    // The More menu is open.
    val menu = remember { mutableStateOf(false) }
    // Annotate: whether it shows, the page it shows (zero-based), and the style the next item added gets.
    var annotating by rememberSaveable { mutableStateOf(false) }
    var annotatePage by rememberSaveable { mutableIntStateOf(0) }
    var editStyle by rememberSaveable { mutableStateOf(EditStyle()) }
    // The page Back to reading returns to, once the pages show again.
    var backTo by rememberSaveable { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    // A preview shows nothing of the tool slot: its PDF is a result's, which it reads where it is.
    val input = if (preview) null else inputs.firstOrNull()
    // The name of the PDF Save PDF makes ("Report_annotated"), taken once the PDF is in.
    var fileName by rememberSaveable(input?.id) { mutableStateOf(vm.defaultName(PdfTool.READER)) }
    // Not while the screen slides away: a file shared to Usugami meanwhile is the next tool's.
    LaunchedEffect(input?.id, active) { if (active) input?.let(vm.reader::show) }
    // A document opened is made into the PDF the reader shows; also again after Android closed Usugami
    // while it was being made (the document's copy is kept in the saved state).
    LaunchedEffect(preview, active, documents.firstOrNull()?.id, importing) {
        if (!preview && active && documents.isNotEmpty() && inputs.isEmpty() && !importing) vm.convertForReader()
    }
    // Leaving for Choose a tool closes the session while the PDF stays in the slot, and Back closes a
    // preview at once: the screen sliding away keeps the pages it showed rather than "Opening…". A
    // preview opened again shows "Opening…" until it is ready (each opening is a screen of its own).
    val live = if (preview) previewSession else session?.takeIf { it.id == input?.id }
    val shown = if (preview) freezeWhen(!active, live) else freezeWhen(live == null && input != null, live)?.takeIf { it.id == input?.id }
    val opening = if (preview) {
        shown == null
    } else {
        (inputs.isEmpty() && locked.isEmpty() && documents.isEmpty() && (receiving || importing)) || (input != null && shown == null)
    }
    // The name of the document the PDF showing was made of, if it was ("Report.docx").
    val document = if (preview) null else source?.takeIf { it.first == input?.id }?.second
    val found by remember(shown) { shown?.found ?: MutableStateFlow(Found()) }.collectAsStateWithLifecycle()
    // Text selected on the pages: one selection for each PDF shown, not kept through rotation, and let
    // go when the screen goes (the session may then close the text's document).
    val selection = remember(shown) { shown?.let { ReaderSelection(it, vm, scope) } }
    DisposableEffect(selection) { onDispose { selection?.letGo() } }
    val haptic = LocalHapticFeedback.current
    // What the text copied is called on the clipboard: the name in the bar.
    val copyLabel = (if (preview) shown?.shown?.name else document ?: input?.name).orEmpty()
    // The search again, from the page showing, once the PDF shows again after Android closed Usugami.
    LaunchedEffect(live) {
        if (finding && searched.isNotBlank() && live != null && live.found.value.query != searched) live.find(searched, pageShowing(list, wentTo))
    }
    val keyboard = LocalSoftwareKeyboardController.current
    fun closeSearch() {
        finding = false
        typing = false
        query = ""
        searched = ""
        shown?.stopFinding()
    }
    // Only while the search bar shows: while the PDF is opened again, Back leaves as it does then.
    BackHandler(enabled = finding && shown != null) { closeSearch() }
    // Annotate shows over the pages once they show: after Android closed Usugami, "Opening…" first.
    val annotate = !preview && annotating && shown != null
    fun startAnnotating() {
        annotatePage = pageShowing(list, wentTo)
        selection?.letGo()
        if (finding) closeSearch()
        annotating = true
    }
    fun backToReading() {
        vm.selectEditItem(null)
        annotating = false
        backTo = annotatePage
    }
    BackHandler(enabled = annotate, onBack = ::backToReading)
    // While text is selected, Back lets go of it first (also before closing the search).
    val selecting = !annotate && shown != null && selection?.active == true
    BackHandler(enabled = selecting) { selection?.letGo() }
    // Save PDF: once something was added to the PDF's pages, while reading and in Annotate.
    val canSave = !preview && input != null && shown != null && editItems.any { it.page < input.pageCount }
    // Annotate, and the pages above Save PDF, end where the bars do; the pages alone run under the
    // navigation bar and clear it at their end.
    val aboveBar = annotate || canSave || selecting
    // While a PDF is being made of the one showing (Save as PDF, Save PDF), Back does nothing, as behind its dialog.
    val working = !preview && processing
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                if (annotate) {
                    TopAppBar(
                        title = { BarTitle(stringResource(R.string.reader_annotate)) },
                        navigationIcon = {
                            IconButton(onClick = ::backToReading) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.reader_back_to_reading)) }
                        },
                    )
                } else if (finding && shown != null) {
                    SearchBar(
                        query = query,
                        onQuery = { query = it },
                        onSearch = {
                            searched = query
                            shown.find(query, pageShowing(list, wentTo))
                            // The pages, not the keyboard, show what was found.
                            if (query.isNotBlank()) keyboard?.hide()
                        },
                        found = found,
                        onStep = shown::step,
                        onClose = ::closeSearch,
                        takeFocus = typing,
                    )
                } else {
                    val title = if (preview) shown?.shown?.name else document ?: input?.name
                    TopAppBar(
                        title = { BarTitle(title ?: stringResource(R.string.tool_reader)) },
                        navigationIcon = { IconButton(onClick = actions.back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back)) } },
                        actions = {
                            if (shown != null) {
                                IconButton(onClick = {
                                    finding = true
                                    typing = true
                                }) { Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.redact_find)) }
                                if (!preview) {
                                    IconButton(onClick = ::startAnnotating) { Icon(Icons.Rounded.EditNote, contentDescription = stringResource(R.string.reader_annotate)) }
                                }
                                MoreMenu(
                                    open = menu,
                                    // Hidden while the additions can be saved: Save PDF saves the PDF with them.
                                    saveAsPdf = if (document != null && !canSave) vm::runSaveCopy else null,
                                    // So are Share, Print and Open with another app, which give the file without the
                                    // additions the pages show: Save PDF's result has Share and Print for it.
                                    actions = if (canSave) ReaderActions(back = actions.back, tools = actions.tools, pickPicture = actions.pickPicture) else actions,
                                    goToPage = { goingToPage = true },
                                )
                            }
                        },
                    )
                }
            },
            bottomBar = {
                Column {
                    // While text is selected: Copy, over Save PDF when that shows too, which then clears the navigation bar.
                    if (selecting) ReaderCopyBar(onCopy = { selection?.copy(copyLabel) }, clearNavigationBar = !canSave)
                    if (canSave) {
                        Button(
                            onClick = { vm.runEdit(fileName) },
                            enabled = !importing && !processing,
                            // Edge to edge: clear of the navigation bar, as every tool's button.
                            modifier = Modifier
                                .fillMaxWidth()
                                .windowInsetsPadding(WindowInsets.navigationBars)
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                        ) {
                            Text(stringResource(R.string.pdf_edit_action))
                        }
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            val direction = LocalLayoutDirection.current
            // The pages alone run under the navigation bar, and clear it at their end.
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(
                        top = padding.calculateTopPadding(),
                        start = padding.calculateStartPadding(direction),
                        end = padding.calculateEndPadding(direction),
                        bottom = if (aboveBar) padding.calculateBottomPadding() else 0.dp,
                    )
                    .then(if (aboveBar) Modifier.consumeWindowInsets(padding) else Modifier),
            ) {
                when {
                    annotate -> ReaderAnnotate(
                        shown = shown!!.shown,
                        vm = vm,
                        importing = importing,
                        page = annotatePage,
                        onPage = { annotatePage = it },
                        style = editStyle,
                        onStyle = { editStyle = it },
                        fileName = fileName,
                        onFileName = { fileName = it },
                        onPickPicture = { page ->
                            vm.prepareEditPicture(page)
                            actions.pickPicture()
                        },
                    )
                    shown != null -> Column(Modifier.fillMaxSize()) {
                        // Back from Annotate: the page it showed, once the pages are laid out again.
                        LaunchedEffect(backTo) {
                            val page = (backTo ?: return@LaunchedEffect).coerceIn(0, maxOf(0, shown.document.pageCount - 1))
                            withFrameNanos {}
                            list.scrollToItem(page)
                            wentTo = intArrayOf(page, list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
                            backTo = null
                        }
                        // A PDF made of a document opened says so, for as long as it shows; its Save as PDF
                        // hides while the additions can be saved (Save PDF).
                        if (document != null) ConvertedNote(document, onSave = if (canSave) null else vm::runSaveCopy)
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        ) {
                            ReaderPages(
                                session = shown,
                                list = list,
                                zoom = { zoom },
                                panX = { panX },
                                onView = { newZoom, newPan ->
                                    zoom = newZoom
                                    panX = newPan
                                },
                                found = found,
                                items = if (preview) emptyList() else editItems,
                                barBelow = canSave || selecting,
                                selection = selection,
                                onLongPress = { page, x, y, reach ->
                                    selection?.select(
                                        page, x, y, reach,
                                        noText = { vm.message(vm.strings.getString(R.string.reader_no_text)) },
                                        selected = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                                    )
                                },
                                onTap = { selection?.letGo() },
                                onCopyPage = { page -> selection?.copyPage(page, copyLabel) },
                            )
                            PageIndicator(list, shown.document.pageCount, wentTo, onClick = { goingToPage = true }, modifier = Modifier.align(Alignment.BottomCenter))
                            // How far the search has got, under the search bar: over the pages, so they don't move.
                            if (finding && found.searching) {
                                LinearProgressIndicator(
                                    progress = { found.pagesDone.toFloat() / maxOf(1, shown.document.pageCount) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(2.dp)
                                        .align(Alignment.TopCenter),
                                )
                            }
                        }
                    }
                    opening -> Opening(onCancel = actions.back, modifier = Modifier.align(Alignment.Center))
                }
                // The file is being copied for another app (Share, Open with another app): a thin moving bar under the top bar.
                if (!preview && handingOver) {
                    LinearProgressIndicator(
                        Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .align(Alignment.TopCenter),
                    )
                }
            }
        }
        // While the PDF is being made, nothing on the screen can be touched, as behind the dialog.
        if (working) {
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
    // Not over the next screen while this one slides away.
    if (!preview && active) {
        locked.firstOrNull()?.let { pending ->
            PasswordDialog(
                input = pending,
                wrongPassword = wrongPassword,
                unlocking = unlocking,
                onUnlock = { vm.unlock(pending, it) },
                onEdit = vm::clearWrongPassword,
                // Cancel puts the copy away and leaves the reader as Back does: for a file another app
                // opened, back to that app rather than by way of Home.
                onDismiss = {
                    vm.dismissPassword(pending)
                    actions.back()
                },
            )
        }
        // From the moment the document is in until its PDF shows: Cancel leaves the reader, which puts it away.
        documents.firstOrNull()?.let { opened ->
            OfficeProgressDialog(convertProgress, opened.kind, onCancel = actions.back, title = stringResource(R.string.reader_converting))
        }
    }
    if (working) WorkingDialog(markProgress, onCancel = vm::cancel)
    // Asked while the pages show; after Android closed Usugami, it comes back once they show again.
    val pageCount = shown?.document?.pageCount
    if (goingToPage && pageCount != null) {
        GoToPageDialog(
            pageCount = pageCount,
            onGo = { page ->
                goingToPage = false
                scope.launch {
                    list.scrollToItem(page - 1)
                    wentTo = intArrayOf(page - 1, list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
                }
            },
            onDismiss = { goingToPage = false },
        )
    }
}

/**
 * The reader's More button and its menu: Save as PDF ([saveAsPdf], for a PDF made of a document),
 * Share, Print, Tools, Go to page and Open with another app, those of [actions] that are given (not
 * Share, Print and Open with another app while Annotate's additions can be saved). Each
 * closes the menu first and acts once: an item tapped again while the menu fades out does nothing.
 */
@Composable
private fun MoreMenu(open: MutableState<Boolean>, saveAsPdf: (() -> Unit)?, actions: ReaderActions, goToPage: () -> Unit) {
    @Composable
    fun Item(icon: ImageVector, text: Int, act: (() -> Unit)?) {
        if (act == null) return
        DropdownMenuItem(
            text = { Text(stringResource(text)) },
            leadingIcon = { Icon(icon, contentDescription = null) },
            onClick = {
                if (open.value) {
                    open.value = false
                    act()
                }
            },
        )
    }
    Box {
        IconButton(onClick = { open.value = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.reader_more)) }
        DropdownMenu(expanded = open.value, onDismissRequest = { open.value = false }) {
            Item(Icons.Rounded.PictureAsPdf, R.string.reader_save_as_pdf, saveAsPdf)
            Item(Icons.Rounded.Share, R.string.share, actions.share)
            Item(Icons.Rounded.Print, R.string.print, actions.print)
            Item(Icons.Rounded.Apps, R.string.reader_tools, actions.tools)
            Item(Icons.Rounded.Pageview, R.string.reader_go_to_page, goToPage)
            Item(Icons.AutoMirrored.Rounded.OpenInNew, R.string.reader_open_elsewhere, actions.openElsewhere)
        }
    }
}

/**
 * Under the bar, for a PDF made of the document [name] opened: it is Usugami's layout, which may look
 * different in the app that made it, and Save as PDF ([onSave]; not there when null).
 */
@Composable
private fun ConvertedNote(name: String, onSave: (() -> Unit)?) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.reader_converted_note, name), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            if (onSave != null) TextButton(onClick = onSave) { Text(stringResource(R.string.reader_save_as_pdf)) }
        }
    }
}

/**
 * While Save as PDF or Save PDF makes its PDF: after a moment, as in every tool, the working dialog
 * ("Working on your PDF…"), or, once the Usugami mark goes on it ([marking]), the dialog that says so;
 * Cancel stops it.
 */
@Composable
private fun WorkingDialog(marking: BatchProgress?, onCancel: () -> Unit) {
    // Most PDFs are done before a dialog could be read: it only shows for one that takes a while.
    var showing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(WORKING_DIALOG_DELAY_MS)
        showing = true
    }
    BackHandler {}
    if (!showing) return
    AlertDialog(
        onDismissRequest = {},
        // The screen stays on while it shows, as for every job (see ProgressDialog).
        modifier = Modifier.keepScreenOn(),
        title = { Text(stringResource(if (marking != null) R.string.mark_adding else R.string.pdf_working)) },
        text = { LinearProgressIndicator(Modifier.fillMaxWidth()) },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}

/** How long Save as PDF or Save PDF works before its dialog shows, as in every tool (PdfToolScreen). */
private const val WORKING_DIALOG_DELAY_MS = 400L

/** "Opening…" with a moving bar, while a file opened is copied in and read, and Cancel. */
@Composable
private fun Opening(onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .widthIn(max = 360.dp)
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.reader_opening), style = MaterialTheme.typography.titleMedium)
        LinearProgressIndicator(Modifier.fillMaxWidth())
        TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
    }
}

/**
 * The page showing (0 for the first), as the page indicator says it: the one that fills most of the
 * screen, or the page Go to page went to ([wentTo]: that page, and the list's first item and offset
 * it left) while the list stays there. Before the pages are laid out, the list's first item.
 */
private fun pageShowing(list: LazyListState, wentTo: IntArray?): Int {
    val info = list.layoutInfo
    val items = info.visibleItemsInfo.map { ReaderGeometry.Item(it.index, it.offset - info.viewportStartOffset, it.size) }
    return wentTo?.takeIf { it[1] == list.firstVisibleItemIndex && it[2] == list.firstVisibleItemScrollOffset }?.get(0)
        ?: ReaderGeometry.pageInView(items, info.viewportEndOffset - info.viewportStartOffset)
        ?: list.firstVisibleItemIndex
}

/**
 * Find text's bar, in place of the reader's: Close search, the field ([query], searched for with the
 * keyboard's search key, [onSearch]) and, once something is found, "3 of 17" (heard as "Match 3 of 17",
 * and said again as it changes) with Previous match and Next match ([onStep] -1 and 1). With [takeFocus],
 * the field takes the focus, and the keyboard comes up, as the bar opens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit, onSearch: () -> Unit, found: Found, onStep: (Int) -> Unit, onClose: () -> Unit, takeFocus: Boolean) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (takeFocus) focus.requestFocus() }
    TopAppBar(
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.reader_find_close)) } },
        title = {
            TextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text(stringResource(R.string.reader_find_hint), maxLines = 1) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
            )
        },
        actions = {
            val count = found.matches.size
            if (count > 0 && found.current >= 0) {
                val spoken = stringResource(R.string.reader_find_count_spoken, found.current + 1, count)
                Text(
                    stringResource(R.string.reader_find_count, found.current + 1, count),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .semantics {
                            contentDescription = spoken
                            liveRegion = LiveRegionMode.Polite
                        },
                )
                IconButton(onClick = { onStep(-1) }) { Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = stringResource(R.string.reader_find_previous)) }
                IconButton(onClick = { onStep(1) }) { Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = stringResource(R.string.reader_find_next)) }
            }
        },
    )
}

/**
 * The page showing, of [pageCount]: "3 of 12", heard as "Page 3 of 12". Tapped, it opens Go to page
 * ([onClick]). After Go to page, the page it went to while the list stays where it left it ([wentTo]:
 * the page, the list's first item and its offset).
 */
@Composable
private fun PageIndicator(list: LazyListState, pageCount: Int, wentTo: IntArray?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val asked by rememberUpdatedState(wentTo)
    val page by remember(list) { derivedStateOf { pageShowing(list, asked) } }
    val number = (page + 1).coerceIn(1, maxOf(1, pageCount))
    val spoken = stringResource(R.string.reader_page_of_spoken, number, pageCount)
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        modifier = modifier
            .navigationBarsPadding()
            .padding(bottom = 12.dp),
    ) {
        Text(
            stringResource(R.string.reader_page_of, number, pageCount),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                // A button: a screen reader says "Page 3 of 12, button, double-tap to go to page". Touches a
                // little around it count too (Compose's minimum touch target); the pill's shape clips the ripple.
                .clickable(onClickLabel = stringResource(R.string.reader_go_to_page), role = Role.Button, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .semantics { contentDescription = spoken },
        )
    }
}

/** Go to page: a page number from 1 to [pageCount], and Go, on only while the number is one of them. */
@Composable
private fun GoToPageDialog(pageCount: Int, onGo: (page: Int) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val page = text.toIntOrNull()?.takeIf { it in 1..pageCount }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reader_go_to_page)) },
        text = {
            OutlinedTextField(
                value = text,
                // Digits only, few enough for a number.
                onValueChange = { typed -> text = typed.filter(Char::isDigit).take(9) },
                label = { Text(stringResource(R.string.reader_page_field, pageCount)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { page?.let(onGo) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { page?.let(onGo) }, enabled = page != null) { Text(stringResource(R.string.reader_go)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
