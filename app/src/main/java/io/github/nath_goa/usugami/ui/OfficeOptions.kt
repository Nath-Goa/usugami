package io.github.nath_goa.usugami.ui

import android.text.format.Formatter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.core.PageSize
import io.github.nath_goa.usugami.data.office.OfficeInput
import io.github.nath_goa.usugami.data.office.OfficeKind
import io.github.nath_goa.usugami.data.office.OfficeProgress

/** The picked Word, PowerPoint or EPUB file: its name, kind and size, and a button to remove it. */
@Composable
internal fun DocumentInputCard(document: OfficeInput, onRemove: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            DocumentIcon(document.kind, Modifier.size(width = 58.dp, height = 78.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(document.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(documentDetails(document), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.remove_file, document.name)) }
        }
    }
}

/** Where the PDF tools show a PDF's first page: a file to convert has no pages until it is converted. */
@Composable
internal fun DocumentIcon(kind: OfficeKind, modifier: Modifier) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small, modifier = modifier) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                when (kind) {
                    OfficeKind.WORD -> Icons.Rounded.Description
                    OfficeKind.POWERPOINT -> Icons.Rounded.Slideshow
                    OfficeKind.EPUB -> Icons.Rounded.AutoStories
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

/** [document]'s kind and size, such as "Word document · 25 kB". */
@Composable
private fun documentDetails(document: OfficeInput): String {
    val context = LocalContext.current
    // Read once: the copy doesn't change while it is listed.
    val size = remember(document.path) { document.file.length() }
    return stringResource(
        R.string.result_details,
        stringResource(
            when (document.kind) {
                OfficeKind.WORD -> R.string.document_word
                OfficeKind.POWERPOINT -> R.string.document_powerpoint
                OfficeKind.EPUB -> R.string.document_epub
            },
        ),
        Formatter.formatShortFileSize(context, size),
    )
}

/** [document] as a [BatchList] shows it: its name, kind and size. */
@Composable
internal fun documentBatchEntry(document: OfficeInput): BatchEntry = BatchEntry(document.id, document.name, documentDetails(document))

/** What converts, and what changes, in a few words under the file. */
@Composable
internal fun OfficeNote(kind: OfficeKind) {
    Text(
        stringResource(
            when (kind) {
                OfficeKind.WORD -> R.string.office_note_word
                OfficeKind.POWERPOINT -> R.string.office_note_powerpoint
                OfficeKind.EPUB -> R.string.office_note_epub
            },
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The paper size for a book: A5, about a paperback's, or A4 for printing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookPageOptions(page: PageSize, onPage: (PageSize) -> Unit) {
    val sizes = listOf(PageSize.A5 to R.string.size_a5, PageSize.A4 to R.string.size_a4)
    Text(stringResource(R.string.option_page_size), style = MaterialTheme.typography.titleMedium)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        sizes.forEachIndexed { index, (size, label) ->
            SegmentedButton(
                selected = page == size,
                onClick = { onPage(size) },
                shape = SegmentedButtonDefaults.itemShape(index, sizes.size),
                icon = {},
                contentPadding = SegmentPadding,
                label = { SegmentLabel(stringResource(label)) },
            )
        }
    }
    Text(stringResource(R.string.book_page_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * Converting a Word, PowerPoint or EPUB file: the page being drawn (a Word document's page count
 * isn't known until the end), or the slide or chapter out of how many; then the PDF is compressed.
 * Converting [many] files, it also says which of them is being converted ([batch]), and the bar
 * shows how far they all have got.
 */
@Composable
internal fun OfficeProgressDialog(progress: OfficeProgress?, kind: OfficeKind, onCancel: () -> Unit, many: Boolean = false, batch: BatchProgress? = null) {
    val done = progress?.done ?: 0
    val total = progress?.takeIf { !it.compressing }?.total?.takeIf { it > 0 }
    // How far the file being converted has got; with several, it counts for its share of them all.
    val part = when {
        total != null -> done.toFloat() / total
        progress?.compressing == true -> 1f
        else -> 0f
    }
    val fraction by animateFloatAsState(
        targetValue = if (many) batch?.let { (it.number - 1 + part) / it.total } ?: 0f else part,
        animationSpec = Motion.medium(),
        label = "conversion progress",
    )
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(if (many) R.string.creating_pdfs else R.string.creating_pdf)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (if (many) batch == null else total == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                }
                if (many && batch != null) {
                    Column {
                        // A screen reader says it as it moves on to the next file.
                        Text(
                            stringResource(R.string.batch_progress, batch.number, batch.total),
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                        Text(
                            batch.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (progress != null && !progress.compressing && done > 0) {
                    Text(
                        when {
                            total == null -> stringResource(R.string.pdf_page_number, done)
                            kind == OfficeKind.EPUB -> stringResource(R.string.progress_chapter, done, total)
                            else -> pluralStringResource(R.plurals.progress_slides, total, done, total)
                        },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}
