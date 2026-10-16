package com.nathan.pdfer.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
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
import com.nathan.pdfer.R
import com.nathan.pdfer.data.PdfSaver

@Composable
fun PdferApp(vm: MainViewModel) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val items by vm.items.collectAsStateWithLifecycle()
    val layout by vm.layout.collectAsStateWithLifecycle()
    val merge by vm.merge.collectAsStateWithLifecycle()
    val compression by vm.compression.collectAsStateWithLifecycle()
    val pendingImports by vm.pendingImports.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val result by vm.result.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    val busySaving by vm.busySaving.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { vm.addImages(it) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.addImages(it) }
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(PdfSaver.PDF_MIME)) { uri ->
        if (uri != null) vm.saveTo(uri)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.saveToFolder(uri)
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

    val files = result
    if (files == null) {
        BackHandler(enabled = items.isNotEmpty()) { confirmDiscard = true }
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
        )
        if (showConvertSheet && items.isNotEmpty()) {
            ConvertSheet(
                imageCount = items.size,
                layout = layout,
                initialMerge = merge,
                initialCompression = compression,
                defaultName = remember { vm.defaultFileName() },
                onDismiss = { showConvertSheet = false },
                onCreate = { mergeAll, name, level ->
                    showConvertSheet = false
                    vm.convert(mergeAll, name, level)
                },
            )
        }
    } else {
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
                    activity?.finish()
                }) { Text(stringResource(R.string.discard)) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.keep)) } },
        )
    }
}
