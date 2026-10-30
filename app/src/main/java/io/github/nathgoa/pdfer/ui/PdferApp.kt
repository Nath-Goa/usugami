package io.github.nathgoa.pdfer.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.nathgoa.pdfer.R
import io.github.nathgoa.pdfer.data.PdfSaver

@Composable
fun PdferApp(vm: MainViewModel) {
    val context = LocalContext.current
    val pdfVm: PdfToolsViewModel = viewModel()
    val items by vm.items.collectAsStateWithLifecycle()
    val layout by vm.layout.collectAsStateWithLifecycle()
    val merge by vm.merge.collectAsStateWithLifecycle()
    val compression by vm.compression.collectAsStateWithLifecycle()
    val pendingImports by vm.pendingImports.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val result by vm.result.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    val busySaving by vm.busySaving.collectAsStateWithLifecycle()
    val pdfResult by pdfVm.result.collectAsStateWithLifecycle()
    val pdfSaved by pdfVm.saved.collectAsStateWithLifecycle()
    val pdfSaving by pdfVm.saving.collectAsStateWithLifecycle()

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
    LaunchedEffect(items.size) {
        if (items.isNotEmpty() && route == "home") route = "images"
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { vm.addImages(it) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.addImages(it) }
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pdfVm.addPdfs(listOf(uri), replace = true)
    }
    val pdfMultiPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        pdfVm.addPdfs(uris, replace = false)
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

    fun launch(block: () -> Unit) {
        try {
            block()
        } catch (e: ActivityNotFoundException) {
            vm.showMessage(R.string.msg_no_pdf_app)
        }
    }

    var showConvertSheet by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }

    // Which screen is showing. The result screens sit one level deeper than their editors, which
    // sit one level deeper than home; that depth decides the slide direction.
    val screen = when (route) {
        "home" -> "home"
        "images" -> if (result == null) "images" else "images/result"
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
            when {
                current == "home" -> HomeScreen(
                    onImages = { route = "images" },
                    onTool = { tool ->
                        pdfVm.reset()
                        route = tool.name.lowercase()
                    },
                )
                current == "images" -> {
                    BackHandler(enabled = active) { if (items.isEmpty()) route = "home" else confirmDiscard = true }
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
                        onBack = { if (items.isEmpty()) route = "home" else confirmDiscard = true },
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
                            open = { file -> launch { context.startActivity(vm.viewIntent(file)) } },
                            share = { launch { context.startActivity(vm.shareIntent(files)) } },
                            saveToDownloads = if (vm.canSaveToDownloads) vm::saveToDownloads else null,
                            saveAs = {
                                launch {
                                    if (files.size == 1) createDocument.launch(files[0].name) else pickFolder.launch(null)
                                }
                            },
                            startOver = vm::startOver,
                        ),
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
                            open = { file -> launch { context.startActivity(pdfVm.viewIntent(file)) } },
                            share = { launch { context.startActivity(pdfVm.shareIntent(files)) } },
                            saveToDownloads = if (pdfVm.canSaveToDownloads) pdfVm::saveToDownloads else null,
                            saveAs = {
                                launch {
                                    if (files.size == 1) createDocument.launch(files[0].name) else pickFolder.launch(null)
                                }
                            },
                            startOver = pdfVm::startOver,
                        ),
                    )
                }
                else -> {
                    val tool = PdfTool.entries.first { it.name.equals(current, ignoreCase = true) }
                    BackHandler(enabled = active) { route = "home" }
                    PdfToolScreen(
                        tool = tool,
                        vm = pdfVm,
                        snackbar = screenSnackbar,
                        onBack = { route = "home" },
                        pickSingle = { pdfPicker.launch(arrayOf(PdfSaver.PDF_MIME)) },
                        pickMultiple = { pdfMultiPicker.launch(arrayOf(PdfSaver.PDF_MIME)) },
                    )
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
            defaultName = remember { vm.defaultFileName() },
            onDismiss = { showConvertSheet = false },
            onCreate = { mergeAll, name, level, ocr ->
                showConvertSheet = false
                vm.convert(mergeAll, name, level, ocr)
            },
        )
    }

    progress?.let { ProgressDialog(it, onCancel = vm::cancelConversion) }

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
}

/** How deep a screen is: home 0, a tool's editor 1, a result 2. */
private fun screenDepth(screen: String): Int = when {
    screen == "home" -> 0
    screen.endsWith("/result") -> 2
    else -> 1
}
