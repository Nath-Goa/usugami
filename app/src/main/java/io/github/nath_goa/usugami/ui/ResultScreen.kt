package io.github.nath_goa.usugami.ui

import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.SaveAs
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.core.ZipProgress
import io.github.nath_goa.usugami.data.PdfFile
import kotlinx.coroutines.delay

class ResultActions(
    val back: () -> Unit,
    val open: (PdfFile) -> Unit,
    val share: () -> Unit,
    val saveToDownloads: (() -> Unit)?,
    val saveAs: () -> Unit,
    val startOver: () -> Unit,
    /** For several PDFs: one ZIP file of them, shared, or saved with the "Save as" screen. */
    val shareZip: () -> Unit,
    val saveZip: () -> Unit,
    val cancelZip: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(
    files: List<PdfFile>,
    saved: Boolean,
    busy: Boolean,
    snackbar: SnackbarHostState,
    actions: ResultActions,
    zipProgress: ZipProgress? = null,
    note: String? = null,
) {
    var confirmStartOver by rememberSaveable { mutableStateOf(false) }
    val single = files.size == 1
    // Nothing else starts while a save or a ZIP file is under way.
    val idle = !busy && zipProgress == null
    // The success check mark grows in once when the result arrives. Saved, so it does not
    // replay after a rotation.
    var checkShown by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { checkShown = true }
    val checkScale by animateFloatAsState(
        targetValue = if (checkShown) 1f else 0.7f,
        animationSpec = tween(Motion.MEDIUM, delayMillis = Motion.SHORT, easing = Motion.EnterEasing),
        label = "checkScale",
    )
    // The bar takes a tint of its own once the page scrolls under it.
    val barScroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(barScroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                scrollBehavior = barScroll,
                title = { Text(stringResource(R.string.result_title)) },
                navigationIcon = {
                    IconButton(onClick = actions.back) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back_to_editing))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(64.dp)
                    .graphicsLayer {
                        scaleX = checkScale
                        scaleY = checkScale
                    },
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(if (single) R.string.result_heading_one else R.string.result_heading_many),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))

            Column(Modifier.widthIn(max = 480.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                files.forEach { file -> FileRow(file, showOpen = !single, onOpen = { actions.open(file) }) }
            }
            if (note != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(24.dp))
            Column(Modifier.widthIn(max = 480.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (single) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        WideButton(Icons.AutoMirrored.Rounded.OpenInNew, stringResource(R.string.open), Modifier.weight(1f), primary = true) {
                            actions.open(files[0])
                        }
                        WideButton(Icons.Rounded.Share, stringResource(R.string.share), Modifier.weight(1f), tonal = true, onClick = actions.share)
                    }
                } else {
                    WideButton(Icons.Rounded.Share, stringResource(R.string.share_all), Modifier.fillMaxWidth(), primary = true, enabled = zipProgress == null, onClick = actions.share)
                }
                actions.saveToDownloads?.let { save ->
                    WideButton(Icons.Rounded.Download, stringResource(R.string.save_to_downloads), Modifier.fillMaxWidth(), enabled = idle, onClick = save)
                }
                WideButton(
                    Icons.Rounded.SaveAs,
                    stringResource(if (single) R.string.save_as else R.string.save_all_to_folder),
                    Modifier.fillMaxWidth(),
                    enabled = idle,
                    onClick = actions.saveAs,
                )
                if (!single) ZipButton(enabled = idle, onShare = actions.shareZip, onSave = actions.saveZip)
            }

            AnimatedVisibility(
                visible = !saved,
                enter = fadeIn(Motion.medium()) + expandVertically(Motion.medium()),
                exit = fadeOut(Motion.medium()) + shrinkVertically(Motion.medium()),
                label = "notSavedNote",
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.not_saved_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = { if (saved) actions.startOver() else confirmStartOver = true }) {
                Icon(Icons.Rounded.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.start_over))
            }
        }
    }

    // A small ZIP file is made in a moment: the dialog only shows for one that takes a while.
    if (zipProgress != null) {
        var show by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            delay(ZIP_DIALOG_DELAY_MS)
            show = true
        }
        if (show) ZipProgressDialog(zipProgress, onCancel = actions.cancelZip)
    }

    if (confirmStartOver) {
        AlertDialog(
            onDismissRequest = { confirmStartOver = false },
            title = { Text(stringResource(R.string.start_over_title)) },
            text = { Text(stringResource(R.string.not_saved_note)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmStartOver = false
                    actions.startOver()
                }) { Text(stringResource(R.string.start_over)) }
            },
            dismissButton = { TextButton(onClick = { confirmStartOver = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** "Share or save as ZIP": a menu with the two ways, under the button. */
@Composable
private fun ZipButton(enabled: Boolean, onShare: () -> Unit, onSave: () -> Unit) {
    var menu by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        WideButton(Icons.Rounded.FolderZip, stringResource(R.string.zip_button), Modifier.fillMaxWidth(), enabled = enabled) { menu = true }
        DropdownMenu(expanded = menu && enabled, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.zip_share)) },
                leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
                onClick = {
                    menu = false
                    onShare()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.zip_save)) },
                leadingIcon = { Icon(Icons.Rounded.SaveAs, contentDescription = null) },
                onClick = {
                    menu = false
                    onSave()
                },
            )
        }
    }
}

/** Making the ZIP file: how many of the PDFs are in, and Cancel. */
@Composable
private fun ZipProgressDialog(progress: ZipProgress, onCancel: () -> Unit) {
    val fraction by animateFloatAsState(targetValue = progress.fraction, animationSpec = Motion.medium(), label = "zip progress")
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.zip_making)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Text(pluralStringResource(R.plurals.progress_files, progress.totalFiles, progress.files, progress.totalFiles))
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}

private const val ZIP_DIALOG_DELAY_MS = 400L

@Composable
private fun FileRow(file: PdfFile, showOpen: Boolean, onOpen: () -> Unit) {
    val context = LocalContext.current
    // Read the size once. Start over deletes the files while this screen is still animating away,
    // and reading the size again then would show "0 B".
    val sizeBytes = remember(file) { file.sizeBytes }
    val size = Formatter.formatShortFileSize(context, sizeBytes)
    val pages = pluralStringResource(R.plurals.page_count, file.pageCount, file.pageCount)
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.PictureAsPdf, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(file.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(R.string.result_details, pages, size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (file.originalBytes > sizeBytes) {
                    val percent = ((file.originalBytes - sizeBytes) * 100 / file.originalBytes).toInt()
                    Text(
                        stringResource(R.string.compressed_from, Formatter.formatShortFileSize(context, file.originalBytes), percent),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (showOpen) {
                IconButton(onClick = onOpen) {
                    // One per file, so the label names the file.
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = stringResource(R.string.open_file, file.name))
                }
            } else {
                Spacer(Modifier.width(12.dp))
            }
        }
    }
}

@Composable
private fun WideButton(
    icon: ImageVector,
    text: String,
    modifier: Modifier,
    primary: Boolean = false,
    tonal: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val content: @Composable () -> Unit = {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    when {
        primary -> Button(onClick = onClick, enabled = enabled, modifier = modifier) { content() }
        tonal -> FilledTonalButton(onClick = onClick, enabled = enabled, modifier = modifier) { content() }
        else -> OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) { content() }
    }
}
