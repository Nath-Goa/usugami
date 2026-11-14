package io.github.nath_goa.usugami.ui

import android.text.format.Formatter
import androidx.annotation.StringRes
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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.TextSnippet
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Print
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
import androidx.compose.runtime.MutableState
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
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.core.Compressor
import io.github.nath_goa.usugami.core.MarkStyle
import io.github.nath_goa.usugami.core.OutputType
import io.github.nath_goa.usugami.core.PdfPageSelection
import io.github.nath_goa.usugami.core.ZipProgress
import io.github.nath_goa.usugami.data.MarkSetting
import io.github.nath_goa.usugami.data.PdfFile
import kotlinx.coroutines.delay

class ResultActions(
    val back: () -> Unit,
    val open: (PdfFile) -> Unit,
    val share: () -> Unit,
    val saveToDownloads: (() -> Unit)?,
    val saveAs: () -> Unit,
    val startOver: () -> Unit,
    /** For several files: one ZIP file of them, shared, or saved with the "Save as" screen. */
    val shareZip: () -> Unit,
    val saveZip: () -> Unit,
    /**
     * For several files, each file's own Share and Save buttons, beside Open: saved into Downloads
     * ([saveOneToDownloads], null where Android has no Downloads to save to) or with the "Save as"
     * screen. Without [saveOneAs], each file has only Open.
     */
    val shareOne: ((PdfFile) -> Unit)? = null,
    val saveOneToDownloads: ((PdfFile) -> Unit)? = null,
    val saveOneAs: ((PdfFile) -> Unit)? = null,
    /** Puts the Usugami mark on the PDFs, or takes it off, and gives it a size and opacity (see [MarkCard]). */
    val setMark: (Boolean) -> Unit = {},
    val setMarkStyle: (MarkStyle) -> Unit = {},
    /** Prints a PDF with Android's print screen; null where the phone can't print. */
    val print: ((PdfFile) -> Unit)? = null,
    /** Puts the text of a text file made (PDF to images or text) on the clipboard: the text card's Copy. */
    val copyText: (() -> Unit)? = null,
)

/**
 * What a tool made: PDFs, or images or text files (PDF to images or text), each with Open, and Share
 * and the ways to save them. The Usugami watermark is only offered when there is a PDF to put it on.
 */
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
    /** The names of the files saved so far, each marked as saved (with [ResultActions.saveOneAs]). */
    savedFiles: Set<String> = emptySet(),
    /** Whether the PDFs carry the Usugami mark, and its size and opacity. */
    mark: MarkSetting = MarkSetting(),
    /** The PDFs are being changed to [mark]: until they are, they can't be opened, shared or saved. */
    marking: Boolean = false,
    /** The first page, to show the mark on while it is changed. */
    markPreview: MarkPreview? = null,
    /** The beginning of the text file made, shown under it with Copy (see [ResultTextCard]). */
    text: TextPreview? = null,
) {
    var confirmStartOver by rememberSaveable { mutableStateOf(false) }
    val single = files.size == 1
    // What the result is, as its first file is: a tool makes one kind of file.
    val kind = files.firstOrNull()?.type ?: OutputType.PDF
    // Nothing else starts while a save, a ZIP file or a change of the mark is under way.
    val idle = !busy && zipProgress == null && !marking
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
                title = { BarTitle(stringResource(R.string.result_title)) },
                navigationIcon = {
                    // Not while saving: leaving and converting again would replace the files being saved.
                    IconButton(onClick = actions.back, enabled = !busy) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back_to_editing))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        // The files (a result can have thousands, one per page of a long PDF split) are in a lazy list,
        // so only those on screen are laid out, and a save that turns their buttons off and on again
        // redraws only those.
        val bars = WindowInsets.navigationBars.asPaddingValues()
        val direction = LocalLayoutDirection.current
        // The name of the file whose Save menu is open: one for the whole screen, so the saved state
        // doesn't grow with the files.
        val saveMenu = rememberSaveable { mutableStateOf<String?>(null) }
        val wide = Modifier.widthIn(max = 480.dp).fillMaxWidth()
        LazyColumn(
            Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(
                start = bars.calculateStartPadding(direction) + 24.dp,
                top = bars.calculateTopPadding() + 16.dp,
                end = bars.calculateEndPadding(direction) + 24.dp,
                bottom = bars.calculateBottomPadding() + 16.dp,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item(key = "heading") {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
                        stringResource(resultHeading(kind, single)),
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(20.dp))
                }
            }
            // Near the top, above the PDFs and what can be done with them: it changes them. Only with
            // a PDF to put it on (an empty list is a screen sliding away before it had files: it keeps
            // the card).
            if (files.isEmpty() || files.any { it.isPdf }) {
                item(key = "mark") {
                    MarkCard(
                        setting = mark,
                        working = marking,
                        // Not while the PDFs, or a ZIP file of them, are being saved.
                        enabled = !busy && zipProgress == null,
                        preview = markPreview,
                        onOn = actions.setMark,
                        onStyle = actions.setMarkStyle,
                        modifier = wide.padding(bottom = 12.dp),
                    )
                }
            }
            itemsIndexed(files, key = { _, file -> file.file.path }) { index, file ->
                val saveAs = actions.saveOneAs
                Box(if (index > 0) wide.padding(top = 8.dp) else wide) {
                    if (!single && saveAs != null) {
                        OwnFileRow(
                            file,
                            saved = file.name in savedFiles,
                            onOpen = { actions.open(file) },
                            onShare = actions.shareOne?.let { share -> { share(file) } },
                            onSaveToDownloads = actions.saveOneToDownloads?.let { save -> { save(file) } },
                            onSaveAs = { saveAs(file) },
                            onPrint = actions.print?.takeIf { file.isPdf }?.let { print -> { print(file) } },
                            saveMenu = saveMenu,
                            canOpen = !marking,
                            canShare = zipProgress == null && !marking,
                            canSave = idle,
                            // Reading the PDF doesn't disturb a save; it is never read while the mark rewrites it.
                            canPrint = !marking,
                        )
                    } else {
                        FileRow(
                            file,
                            showOpen = !single,
                            canOpen = !marking,
                            onOpen = { actions.open(file) },
                            onPrint = actions.print?.takeIf { file.isPdf }?.let { print -> { print(file) } },
                        )
                    }
                }
            }
            if (text != null) {
                item(key = "text") {
                    ResultTextCard(text, enabled = idle, onCopy = { actions.copyText?.invoke() }, modifier = wide.padding(top = 12.dp))
                }
            }
            item(key = "actions") {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
                    Column(wide, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (single) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                WideButton(Icons.AutoMirrored.Rounded.OpenInNew, stringResource(R.string.open), Modifier.weight(1f), primary = true, enabled = !marking, halfWidth = true) {
                                    actions.open(files[0])
                                }
                                WideButton(Icons.Rounded.Share, stringResource(R.string.share), Modifier.weight(1f), tonal = true, enabled = !marking, halfWidth = true, onClick = actions.share)
                            }
                        } else {
                            WideButton(Icons.Rounded.Share, stringResource(R.string.share_all), Modifier.fillMaxWidth(), primary = true, enabled = zipProgress == null && !marking, onClick = actions.share)
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
                        // Under "Save as…", across the screen: a third button beside Open and Share wouldn't fit longer languages.
                        if (single) {
                            actions.print?.takeIf { files[0].isPdf }?.let { print ->
                                WideButton(Icons.Rounded.Print, stringResource(R.string.print), Modifier.fillMaxWidth(), enabled = !marking) { print(files[0]) }
                            }
                        }
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
                    // Not while saving: starting over deletes the files being saved.
                    TextButton(onClick = { if (saved) actions.startOver() else confirmStartOver = true }, enabled = !busy) {
                        Icon(Icons.Rounded.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.start_over))
                    }
                }
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

/** "Share or save as ZIP": a menu with the two ways, under the button. */
@Composable
private fun ZipButton(enabled: Boolean, onShare: () -> Unit, onSave: () -> Unit) {
    var menu by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        WideButton(Icons.Rounded.FolderZip, stringResource(R.string.zip_button), Modifier.fillMaxWidth(), enabled = enabled) { menu = true }
        // The items can still be tapped while the menu fades out: only the first tap counts.
        DropdownMenu(expanded = menu && enabled, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.zip_share)) },
                leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
                onClick = {
                    if (menu) {
                        menu = false
                        onShare()
                    }
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.zip_save)) },
                leadingIcon = { Icon(Icons.Rounded.SaveAs, contentDescription = null) },
                onClick = {
                    if (menu) {
                        menu = false
                        onSave()
                    }
                },
            )
        }
    }
}

/**
 * Making a ZIP file of a result's PDFs: how many of the PDFs are in, and Cancel, while [progress] is
 * set. It shows over whichever screen shows, as a ZIP file being saved goes on when images shared
 * meanwhile open the editor. A small ZIP file is made in a moment: the dialog only shows for one that
 * takes a while.
 */
@Composable
fun ZipProgressDialog(progress: ZipProgress?, onCancel: () -> Unit) {
    if (progress != null) {
        var show by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            delay(ZIP_DIALOG_DELAY_MS)
            show = true
        }
        if (show) ZipDialog(progress, onCancel)
    }
}

/** Making the ZIP file: how many of the PDFs are in, and Cancel. */
@Composable
private fun ZipDialog(progress: ZipProgress, onCancel: () -> Unit) {
    val fraction by animateFloatAsState(targetValue = progress.fraction, animationSpec = Motion.medium(), label = "zip progress")
    AlertDialog(
        onDismissRequest = {},
        // The screen stays on while it shows, as for every job (see ProgressDialog).
        modifier = Modifier.keepScreenOn(),
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

/** The heading over a result of [kind]: one file ([single]) or several. */
@StringRes
private fun resultHeading(kind: OutputType, single: Boolean): Int = when (kind) {
    OutputType.PDF -> if (single) R.string.result_heading_one else R.string.result_heading_many
    OutputType.JPEG, OutputType.PNG -> if (single) R.string.result_heading_image_one else R.string.result_heading_image_many
    OutputType.TEXT -> if (single) R.string.result_heading_text_one else R.string.result_heading_text_many
}

/** The icon a file of this type shows beside its name: a PDF, an image or a text file. */
internal val OutputType.icon: ImageVector
    get() = when (this) {
        OutputType.PDF -> Icons.Rounded.PictureAsPdf
        OutputType.JPEG, OutputType.PNG -> Icons.Rounded.Image
        OutputType.TEXT -> Icons.AutoMirrored.Rounded.TextSnippet
    }

/**
 * A file of the result, with its own Open (and Print, for a PDF on a phone that prints) when there are
 * several ([showOpen]); a single file has the wide buttons under it instead.
 */
@Composable
private fun FileRow(file: PdfFile, showOpen: Boolean, canOpen: Boolean, onOpen: () -> Unit, onPrint: (() -> Unit)?) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(file.type.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(12.dp))
            FileDetails(file, Modifier.weight(1f))
            if (showOpen) {
                IconButton(onClick = onOpen, enabled = canOpen) {
                    // One per file, so the label names the file.
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = stringResource(R.string.open_file, file.name))
                }
                if (onPrint != null) {
                    IconButton(onClick = onPrint, enabled = canOpen) {
                        Icon(Icons.Rounded.Print, contentDescription = stringResource(R.string.print_file, file.name))
                    }
                }
            } else {
                Spacer(Modifier.width(12.dp))
            }
        }
    }
}

/**
 * [file]'s name, its pages (for a PDF) and size, and how much smaller it was made, or that its
 * original was kept.
 */
@Composable
private fun FileDetails(file: PdfFile, modifier: Modifier = Modifier, saved: Boolean = false) {
    val context = LocalContext.current
    // Read the size once. Start over deletes the files while this screen is still animating away,
    // and reading the size again then would show "0 B".
    val sizeBytes = remember(file) { file.sizeBytes }
    val size = Formatter.formatShortFileSize(context, sizeBytes)
    // An image or a text file shows its size alone: it has no pages to count.
    val details = if (file.isPdf) {
        stringResource(R.string.result_details, pluralStringResource(R.plurals.page_count, file.pageCount, file.pageCount), size)
    } else {
        size
    }
    Column(modifier) {
        Text(file.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            details,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val percent = Compressor.savedPercent(file.originalBytes, sizeBytes)
        if (percent != null) {
            Text(
                stringResource(R.string.compressed_from, Formatter.formatShortFileSize(context, file.originalBytes), percent),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        } else if (file.keptOriginal) {
            Text(stringResource(R.string.original_kept), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Compress PDF's Black and white left these pages partly in colour: said for as long as the
        // result shows, the pages as "2,5-7", cut after eight of those with "…".
        val colorPages = file.gray?.colorPages.orEmpty()
        if (colorPages.isNotEmpty()) {
            val pages = remember(colorPages) {
                val parts = PdfPageSelection.format(colorPages).split(',')
                if (parts.size > 8) parts.take(8).joinToString(",") + "…" else parts.joinToString(",")
            }
            Text(
                pluralStringResource(R.plurals.gray_color_left, colorPages.size, colorPages.size, pages),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        AnimatedVisibility(visible = saved, enter = fadeIn(Motion.medium()) + expandVertically(Motion.medium()), label = "saved mark") {
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.msg_saved), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/**
 * One of several files, with buttons of its own under it: Open, Share, Save, which offers Downloads
 * and "Save as" (or goes straight to "Save as" where Android has no Downloads to save to), and Print
 * for a PDF on a phone that prints ([onPrint]). [saved] marks it once it has been saved, on its own or
 * with the others. [saveMenu] holds the name of the file whose menu is open.
 */
@Composable
private fun OwnFileRow(
    file: PdfFile,
    saved: Boolean,
    onOpen: () -> Unit,
    onShare: (() -> Unit)?,
    onSaveToDownloads: (() -> Unit)?,
    onSaveAs: () -> Unit,
    onPrint: (() -> Unit)?,
    saveMenu: MutableState<String?>,
    canOpen: Boolean,
    canShare: Boolean,
    canSave: Boolean,
    canPrint: Boolean,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(top = 12.dp, bottom = 4.dp, end = 8.dp)) {
            Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(file.type.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(12.dp))
                FileDetails(file, Modifier.weight(1f), saved = saved)
            }
            // Under the details, so a long name, a long translation or a large font size wraps the
            // buttons onto another line instead of squeezing the name. Each names its file for a screen reader.
            val openLabel = stringResource(R.string.open_file, file.name)
            val shareLabel = stringResource(R.string.share_file, file.name)
            val saveLabel = stringResource(R.string.save_file, file.name)
            val printLabel = stringResource(R.string.print_file, file.name)
            // Their icons line up with the file's.
            FlowRow(Modifier.padding(start = 8.dp)) {
                TextButton(onClick = onOpen, enabled = canOpen, contentPadding = RowButtonPadding, modifier = Modifier.semantics { contentDescription = openLabel }) {
                    RowButtonContent(Icons.AutoMirrored.Rounded.OpenInNew, stringResource(R.string.open))
                }
                if (onShare != null) {
                    TextButton(onClick = onShare, enabled = canShare, contentPadding = RowButtonPadding, modifier = Modifier.semantics { contentDescription = shareLabel }) {
                        RowButtonContent(Icons.Rounded.Share, stringResource(R.string.share))
                    }
                }
                // Read when tapped, not when drawn: a second tap before the screen is drawn again finds it closed.
                fun menuOpen() = saveMenu.value == file.name
                Box {
                    TextButton(
                        // With Downloads to save to, a menu offers it and "Save as"; otherwise "Save as" opens.
                        onClick = { if (onSaveToDownloads != null) saveMenu.value = file.name else onSaveAs() },
                        enabled = canSave,
                        contentPadding = RowButtonPadding,
                        modifier = Modifier.semantics { contentDescription = saveLabel },
                    ) {
                        RowButtonContent(Icons.Rounded.Download, stringResource(R.string.save))
                    }
                    // The items can still be tapped while the menu fades out: only the first tap counts.
                    DropdownMenu(expanded = menuOpen() && canSave, onDismissRequest = { if (menuOpen()) saveMenu.value = null }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.save_to_downloads)) },
                            leadingIcon = { Icon(Icons.Rounded.Download, contentDescription = null) },
                            onClick = {
                                if (menuOpen()) {
                                    saveMenu.value = null
                                    onSaveToDownloads?.invoke()
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.save_as)) },
                            leadingIcon = { Icon(Icons.Rounded.SaveAs, contentDescription = null) },
                            onClick = {
                                if (menuOpen()) {
                                    saveMenu.value = null
                                    onSaveAs()
                                }
                            },
                        )
                    }
                }
                if (onPrint != null) {
                    TextButton(onClick = onPrint, enabled = canPrint, contentPadding = RowButtonPadding, modifier = Modifier.semantics { contentDescription = printLabel }) {
                        RowButtonContent(Icons.Rounded.Print, stringResource(R.string.print))
                    }
                }
            }
        }
    }
}

/** Narrower than a text button's own, so the four fit on one line on most phones. */
private val RowButtonPadding = PaddingValues(horizontal = 6.dp)

@Composable
private fun RowButtonContent(icon: ImageVector, text: String) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(4.dp))
    Text(text)
}

@Composable
private fun WideButton(
    icon: ImageVector,
    text: String,
    modifier: Modifier,
    primary: Boolean = false,
    tonal: Boolean = false,
    enabled: Boolean = true,
    halfWidth: Boolean = false,
    onClick: () -> Unit,
) {
    val content: @Composable () -> Unit = {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        // With a large font size or a long translation: beside another button, the label (one word)
        // shrinks to fit, smaller than other labels if it must (Portuguese "Compartilhar" with font size
        // 130% on a narrow phone); across the screen, it takes a second line. Either way it isn't cut off.
        if (halfWidth) {
            Text(
                text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                autoSize = WholeWordsAutoSize(MaterialTheme.typography.labelLarge.fontSize),
            )
        } else {
            Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
    when {
        primary -> Button(onClick = onClick, enabled = enabled, modifier = modifier) { content() }
        tonal -> FilledTonalButton(onClick = onClick, enabled = enabled, modifier = modifier) { content() }
        else -> OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) { content() }
    }
}
