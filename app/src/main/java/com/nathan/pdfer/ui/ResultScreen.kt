package com.nathan.pdfer.ui

import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.SaveAs
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nathan.pdfer.R
import com.nathan.pdfer.data.PdfFile

class ResultActions(
    val back: () -> Unit,
    val open: (PdfFile) -> Unit,
    val share: () -> Unit,
    val saveToDownloads: (() -> Unit)?,
    val saveAs: () -> Unit,
    val startOver: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(
    files: List<PdfFile>,
    saved: Boolean,
    busy: Boolean,
    snackbar: SnackbarHostState,
    actions: ResultActions,
) {
    var confirmStartOver by rememberSaveable { mutableStateOf(false) }
    val single = files.size == 1
    // The success check mark grows in once when the result arrives. Saved, so it does not
    // replay after a rotation.
    var checkShown by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { checkShown = true }
    val checkScale by animateFloatAsState(
        targetValue = if (checkShown) 1f else 0.7f,
        animationSpec = tween(Motion.MEDIUM, delayMillis = Motion.SHORT, easing = Motion.EnterEasing),
        label = "checkScale",
    )
    Scaffold(
        topBar = {
            TopAppBar(
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
                    WideButton(Icons.Rounded.Share, stringResource(R.string.share_all), Modifier.fillMaxWidth(), primary = true, onClick = actions.share)
                }
                actions.saveToDownloads?.let { save ->
                    WideButton(Icons.Rounded.Download, stringResource(R.string.save_to_downloads), Modifier.fillMaxWidth(), enabled = !busy, onClick = save)
                }
                WideButton(
                    Icons.Rounded.SaveAs,
                    stringResource(if (single) R.string.save_as else R.string.save_all_to_folder),
                    Modifier.fillMaxWidth(),
                    enabled = !busy,
                    onClick = actions.saveAs,
                )
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
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = stringResource(R.string.open))
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
