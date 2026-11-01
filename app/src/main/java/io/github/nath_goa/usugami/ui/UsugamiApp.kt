package io.github.nath_goa.usugami.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.core.ZipArchive
import io.github.nath_goa.usugami.data.AppLanguage
import io.github.nath_goa.usugami.data.AppLanguages
import io.github.nath_goa.usugami.data.Appearance
import io.github.nath_goa.usugami.data.LegalDocument
import io.github.nath_goa.usugami.data.PdfSaver
import kotlinx.coroutines.flow.merge

@Composable
fun UsugamiApp(vm: MainViewModel, onChooseLanguage: (AppLanguage) -> Unit, onChooseAppearance: (Appearance) -> Unit) {
    val context = LocalContext.current
    // The first time Usugami opens, it asks for a language before anything else. Choosing one
    // usually recreates the activity in that language; this state carries over, so Home follows.
    var languageChosen by rememberSaveable { mutableStateOf(AppLanguages.isChosen(context)) }
    if (!languageChosen) {
        LanguageScreen { language ->
            languageChosen = true
            onChooseLanguage(language)
        }
        return
    }
    val pdfVm: PdfToolsViewModel = viewModel()
    val items by vm.items.collectAsStateWithLifecycle()
    val layout by vm.layout.collectAsStateWithLifecycle()
    val merge by vm.merge.collectAsStateWithLifecycle()
    val compression by vm.compression.collectAsStateWithLifecycle()
    val pendingImports by vm.pendingImports.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val cancelling by vm.cancelling.collectAsStateWithLifecycle()
    val ocrProblem by vm.ocrProblem.collectAsStateWithLifecycle()
    val result by vm.result.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    val busySaving by vm.busySaving.collectAsStateWithLifecycle()
    val pdfResult by pdfVm.result.collectAsStateWithLifecycle()
    val pdfSaved by pdfVm.saved.collectAsStateWithLifecycle()
    val pdfSaving by pdfVm.saving.collectAsStateWithLifecycle()
    val pdfPasswordRemoved by pdfVm.passwordRemoved.collectAsStateWithLifecycle()
    val zipProgress by vm.zipProgress.collectAsStateWithLifecycle()
    val pdfZipProgress by pdfVm.zipProgress.collectAsStateWithLifecycle()

    var route by rememberSaveable { mutableStateOf(if (vm.items.value.isNotEmpty()) "images" else "home") }

    val snackbar = remember { SnackbarHostState() }
    // Given to a screen while it animates away, so a message never shows on two screens at once.
    val idleSnackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }
    LaunchedEffect(pdfVm) {
        pdfVm.messages.collect { snackbar.showSnackbar(it) }
    }
    // A ZIP file of the result's PDFs is shared once it is ready, while Usugami is on screen: an app
    // in the background can't open the share screen.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(vm, pdfVm, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            merge(vm.zipShares, pdfVm.zipShares).collect { intent ->
                try {
                    context.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    vm.showMessage(R.string.msg_no_share_app)
                }
            }
        }
    }
    // Set when images were shared while a PDF tool had a PDF that would be lost by leaving it.
    var confirmLeavePdf by rememberSaveable { mutableStateOf(false) }
    // The user chose to stay with that PDF: more shared images don't ask again until they leave the tool.
    var stayOnPdf by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(route) {
        if (!isPdfToolRoute(route)) {
            confirmLeavePdf = false
            stayOnPdf = false
        }
    }
    LaunchedEffect(items.size) {
        // Images shared from another app can arrive on any screen, a PDF tool's included: show them.
        // Leaving a tool discards its PDF, so ask first while one is being made or isn't saved yet.
        if (items.isEmpty() || route == "images" || stayOnPdf) return@LaunchedEffect
        val unsavedPdf = pdfVm.processing.value || (pdfVm.result.value != null && !pdfVm.saved.value)
        if (isPdfToolRoute(route) && unsavedPdf) confirmLeavePdf = true else route = "images"
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { vm.addImages(it) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.addImages(it) }
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pdfVm.addPdfs(listOf(uri), replace = true)
    }
    val pdfMultiPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        pdfVm.addPdfs(uris, replace = false)
    }
    // Reorganize PDF's "Add pages": one more PDF, whose pages go where the tool said.
    val pdfAddPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pdfVm.addPdfs(listOf(uri), replace = false)
    }
    // Edit PDF's pictures, from the system photo picker (or the file picker where there is none).
    val picturePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) pdfVm.addPicture(uri)
    }
    // For Word, PowerPoint and EPUB to PDF: the tool showing says which kind of file it takes.
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val kind = PdfTool.entries.firstOrNull { it.name.equals(route, ignoreCase = true) }?.officeKind
        if (uri != null && kind != null) pdfVm.addDocument(uri, kind)
    }
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(PdfSaver.PDF_MIME)) { uri ->
        if (uri != null) {
            if (route == "images") vm.saveTo(uri) else pdfVm.saveTo(uri)
        }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            if (route == "images") vm.saveToFolder(uri) else pdfVm.saveToFolder(uri)
        }
    }
    val createZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ZipArchive.MIME)) { uri ->
        if (uri != null) {
            if (route == "images") vm.saveZipTo(uri) else pdfVm.saveZipTo(uri)
        }
    }

    /** Starts another app's screen; [noApp] is shown when no app can handle it. */
    fun launch(@StringRes noApp: Int, block: () -> Unit) {
        try {
            block()
        } catch (_: ActivityNotFoundException) {
            vm.showMessage(noApp)
        }
    }

    fun openSourceCode() = launch(R.string.msg_no_browser) { context.startActivity(Intent(Intent.ACTION_VIEW, SOURCE_CODE_URL.toUri())) }

    // A tool's choices (its options, a signature's place) are kept while its result shows, so
    // "Back to editing" returns to them. Opening the tool from Home starts afresh.
    val toolStates = rememberSaveableStateHolder()

    // What is typed into Home's search field. It is kept while a tool opened from the results shows,
    // so going back returns to them.
    var homeQuery by rememberSaveable { mutableStateOf("") }

    var showConvertSheet by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }

    // Which screen is showing. The result screens sit one level deeper than their editors, which
    // sit one level deeper than home, and so do Settings ("settings"), About ("settings/about") and
    // the texts About opens ("settings/about/privacy"); that depth decides the slide direction.
    val screen = when {
        route == "home" || route.startsWith("settings") -> route
        route == "images" -> if (result == null) "images" else "images/result"
        else -> if (pdfResult == null) route else "$route/result"
    }

    AnimatedContent(
        targetState = screen,
        transitionSpec = { Motion.screenTransition(forward = screenDepth(targetState) >= screenDepth(initialState)) },
        label = "screen",
    ) { current ->
        // While a screen animates away it must not handle Back or touches, and it keeps showing
        // its last data.
        val active = transition.targetState == EnterExitState.Visible
        val screenSnackbar = if (active) snackbar else idleSnackbar
        Box {
            if (current == "home" || current.startsWith("images")) {
                // Once a PDF tool's screens have gone (the user went home, or shared images opened the
                // editor), nothing of that tool can be shown again: opening a tool starts afresh. Its
                // copies and results are freed as soon as the new screen has settled.
                val settled = transition.currentState == EnterExitState.Visible && transition.targetState == EnterExitState.Visible
                LaunchedEffect(settled) { if (settled) pdfVm.reset() }
            }
            when {
                current == "home" -> {
                    // Back clears a search first, then leaves Usugami.
                    BackHandler(enabled = active && homeQuery.isNotEmpty()) { homeQuery = "" }
                    HomeScreen(
                        snackbar = screenSnackbar,
                        query = homeQuery,
                        onQueryChange = { homeQuery = it },
                        onImages = { route = "images" },
                        onTool = { tool ->
                            pdfVm.reset()
                            toolStates.removeState(tool.name.lowercase())
                            route = tool.name.lowercase()
                        },
                        onSettings = { route = "settings" },
                    )
                }
                current == "settings" -> {
                    BackHandler(enabled = active) { route = "home" }
                    SettingsScreen(
                        snackbar = screenSnackbar,
                        onBack = { route = "home" },
                        onChooseLanguage = onChooseLanguage,
                        onChooseAppearance = onChooseAppearance,
                        onSourceCode = { openSourceCode() },
                        onAbout = { route = "settings/about" },
                    )
                }
                current == "settings/about" -> {
                    BackHandler(enabled = active) { route = "settings" }
                    AboutScreen(
                        snackbar = screenSnackbar,
                        onBack = { route = "settings" },
                        onDocument = { document -> route = "settings/about/${document.name.lowercase()}" },
                        onSourceCode = { openSourceCode() },
                    )
                }
                current.startsWith("settings/about/") -> {
                    val document = LegalDocument.valueOf(current.substringAfterLast('/').uppercase())
                    BackHandler(enabled = active) { route = "settings/about" }
                    LegalDocumentScreen(document, snackbar = screenSnackbar, onBack = { route = "settings/about" })
                }
                current == "images" -> {
                    // Images still being imported count too: they would arrive at home and reopen the editor.
                    val leave: () -> Unit = { if (items.isEmpty() && pendingImports == 0) route = "home" else confirmDiscard = true }
                    BackHandler(enabled = active, onBack = leave)
                    EditorScreen(
                        items = freezeWhen(!active, items),
                        layout = layout,
                        pendingImports = pendingImports,
                        thumbnails = vm.thumbnails,
                        snackbar = screenSnackbar,
                        actions = EditorActions(
                            addFromPhotos = {
                                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                            addFromFiles = { filePicker.launch(arrayOf("image/jpeg", "image/png")) },
                            changeLayout = vm::setLayout,
                            rotate = vm::rotate,
                            remove = vm::remove,
                            move = vm::move,
                            moveBy = vm::moveBy,
                            sort = vm::sort,
                            clearAll = vm::clearAll,
                            convert = { showConvertSheet = true },
                        ),
                        onBack = leave,
                    )
                }
                current == "images/result" -> {
                    val files = freezeWhen(!active, result) ?: emptyList()
                    BackHandler(enabled = active) { vm.backToEditing() }
                    ResultScreen(
                        files = files,
                        saved = saved,
                        busy = busySaving,
                        snackbar = screenSnackbar,
                        actions = ResultActions(
                            back = vm::backToEditing,
                            open = { file -> launch(R.string.msg_no_pdf_app) { if (vm.checkResult()) context.startActivity(vm.viewIntent(file)) } },
                            share = { launch(R.string.msg_no_share_app) { if (vm.checkResult()) context.startActivity(vm.shareIntent(files)) } },
                            saveToDownloads = if (vm.canSaveToDownloads) vm::saveToDownloads else null,
                            saveAs = {
                                launch(R.string.msg_no_file_picker) {
                                    if (vm.checkResult()) {
                                        if (files.size == 1) createDocument.launch(files[0].name) else pickFolder.launch(null)
                                    }
                                }
                            },
                            startOver = vm::startOver,
                            shareZip = { if (vm.checkResult()) vm.shareZip() },
                            saveZip = { launch(R.string.msg_no_file_picker) { if (vm.checkResult()) createZip.launch(vm.zipFileName()) } },
                            cancelZip = vm::cancelZip,
                        ),
                        zipProgress = zipProgress,
                    )
                }
                current.endsWith("/result") -> {
                    val files = freezeWhen(!active, pdfResult) ?: emptyList()
                    BackHandler(enabled = active) { pdfVm.backToEditing() }
                    ResultScreen(
                        files = files,
                        saved = pdfSaved,
                        busy = pdfSaving,
                        snackbar = screenSnackbar,
                        actions = ResultActions(
                            back = pdfVm::backToEditing,
                            open = { file -> launch(R.string.msg_no_pdf_app) { if (pdfVm.checkResult()) context.startActivity(pdfVm.viewIntent(file)) } },
                            share = { launch(R.string.msg_no_share_app) { if (pdfVm.checkResult()) context.startActivity(pdfVm.shareIntent(files)) } },
                            saveToDownloads = if (pdfVm.canSaveToDownloads) pdfVm::saveToDownloads else null,
                            saveAs = {
                                launch(R.string.msg_no_file_picker) {
                                    if (pdfVm.checkResult()) {
                                        if (files.size == 1) createDocument.launch(files[0].name) else pickFolder.launch(null)
                                    }
                                }
                            },
                            startOver = pdfVm::startOver,
                            shareZip = { if (pdfVm.checkResult()) pdfVm.shareZip() },
                            saveZip = { launch(R.string.msg_no_file_picker) { if (pdfVm.checkResult()) createZip.launch(pdfVm.zipFileName()) } },
                            cancelZip = pdfVm::cancelZip,
                        ),
                        zipProgress = pdfZipProgress,
                        note = if (pdfPasswordRemoved) pluralStringResource(R.plurals.pdf_password_removed, files.size) else null,
                    )
                }
                else -> {
                    val tool = PdfTool.entries.first { it.name.equals(current, ignoreCase = true) }
                    BackHandler(enabled = active) { route = "home" }
                    toolStates.SaveableStateProvider(current) {
                        PdfToolScreen(
                            tool = tool,
                            vm = pdfVm,
                            snackbar = screenSnackbar,
                            onBack = { route = "home" },
                            pickSingle = {
                                val kind = tool.officeKind
                                if (kind != null) documentPicker.launch(kind.pickerTypes) else pdfPicker.launch(arrayOf(PdfSaver.PDF_MIME))
                            },
                            pickMultiple = { pdfMultiPicker.launch(arrayOf(PdfSaver.PDF_MIME)) },
                            pickAdd = { pdfAddPicker.launch(arrayOf(PdfSaver.PDF_MIME)) },
                            pickPicture = {
                                launch(R.string.msg_no_file_picker) {
                                    picturePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                }
                            },
                        )
                    }
                }
            }
            if (!active) {
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

    if (showConvertSheet && route == "images" && result == null && items.isNotEmpty()) {
        ConvertSheet(
            imageCount = items.size,
            layout = layout,
            initialMerge = merge,
            initialCompression = compression,
            initialChoices = remember { vm.initialChoices() },
            onDismiss = { showConvertSheet = false },
            onCreate = { mergeAll, name, level, ocr ->
                showConvertSheet = false
                vm.convert(mergeAll, name, level, ocr)
            },
        )
    }

    progress?.let { ProgressDialog(it, cancelling, onCancel = vm::cancelConversion) }
    ocrProblem?.let { OcrProblemDialog(it, onDismiss = vm::dismissOcrProblem) }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.discard_title)) },
            text = { Text(stringResource(R.string.discard_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    vm.clearAll()
                    route = "home"
                }) { Text(stringResource(R.string.discard)) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.keep)) } },
        )
    }

    if (confirmLeavePdf) {
        val keepPdf = {
            confirmLeavePdf = false
            stayOnPdf = true
        }
        AlertDialog(
            onDismissRequest = keepPdf,
            title = { Text(stringResource(R.string.pdf_discard_title)) },
            text = { Text(stringResource(R.string.pdf_discard_shared_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeavePdf = false
                    // The editor frees the tool's files once it has opened.
                    route = "images"
                }) { Text(stringResource(R.string.discard)) }
            },
            dismissButton = { TextButton(onClick = keepPdf) { Text(stringResource(R.string.keep)) } },
        )
    }
}

/** Whether [route] is a PDF tool's screen (its result included: that shares the tool's route). */
private fun isPdfToolRoute(route: String): Boolean = PdfTool.entries.any { it.name.equals(route, ignoreCase = true) }

/** How deep a screen is: home 0, a tool's editor or Settings 1, a result or About 2, a text About opens 3. */
private fun screenDepth(screen: String): Int = if (screen == "home") 0 else 1 + screen.count { it == '/' }
