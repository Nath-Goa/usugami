package com.nathan.pdfer.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nathan.pdfer.R
import com.nathan.pdfer.data.PdfSaver

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

    if (route == "home") {
        HomeScreen(
            onImages = { route = "images" },
            onTool = { tool ->
                pdfVm.reset()
                route = tool.name.lowercase()
            },
        )
    } else if (route == "images" && result == null) {
        BackHandler { if (items.isEmpty()) route = "home" else confirmDiscard = true }
        EditorScreen(
            items = items,
            layout = layout,
            pendingImports = pendingImports,
            thumbnails = vm.thumbnails,
            snackbar = snackbar,
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
        if (showConvertSheet && items.isNotEmpty()) {
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
    } else if (route == "images") {
        val files = result ?: emptyList()
        BackHandler { vm.backToEditing() }
        ResultScreen(
            files = files,
            saved = saved,
            busy = busySaving,
            snackbar = snackbar,
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
    } else {
        val tool = PdfTool.entries.first { it.name.equals(route, ignoreCase = true) }
        if (pdfResult == null) {
            BackHandler { route = "home" }
            PdfToolScreen(
                tool = tool,
                vm = pdfVm,
                snackbar = snackbar,
                onBack = { route = "home" },
                pickSingle = { pdfPicker.launch(arrayOf(PdfSaver.PDF_MIME)) },
                pickMultiple = { pdfMultiPicker.launch(arrayOf(PdfSaver.PDF_MIME)) },
            )
        } else {
            val files = pdfResult ?: emptyList()
            BackHandler { pdfVm.backToEditing() }
            ResultScreen(
                files = files,
                saved = pdfSaved,
                busy = pdfSaving,
                snackbar = snackbar,
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
