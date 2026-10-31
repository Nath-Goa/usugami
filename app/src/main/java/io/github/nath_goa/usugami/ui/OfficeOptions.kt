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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.data.office.OfficeInput
import io.github.nath_goa.usugami.data.office.OfficeKind
import io.github.nath_goa.usugami.data.office.OfficeProgress

/** The picked Word or PowerPoint file: its name, kind and size, and a button to remove it. */
@Composable
internal fun DocumentInputCard(document: OfficeInput, onRemove: () -> Unit) {
    val context = LocalContext.current
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            // Where the PDF tools show the first page: the file has no pages until it is converted.
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.size(width = 58.dp, height = 78.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (document.kind == OfficeKind.WORD) Icons.Rounded.Description else Icons.Rounded.Slideshow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(document.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(
                        R.string.result_details,
                        stringResource(if (document.kind == OfficeKind.WORD) R.string.document_word else R.string.document_powerpoint),
                        Formatter.formatShortFileSize(context, document.file.length()),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.remove_file, document.name)) }
        }
    }
}

/** What converts, and what changes, in a few words under the file. */
@Composable
internal fun OfficeNote(kind: OfficeKind) {
    Text(
        stringResource(if (kind == OfficeKind.WORD) R.string.office_note_word else R.string.office_note_powerpoint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Converting a Word or PowerPoint file: the page being drawn (a Word document's page count isn't
 * known until the end), or the slide out of how many; then the PDF is compressed.
 */
@Composable
internal fun OfficeProgressDialog(progress: OfficeProgress?, onCancel: () -> Unit) {
    val done = progress?.done ?: 0
    val total = progress?.takeIf { !it.compressing }?.total?.takeIf { it > 0 }
    val fraction by animateFloatAsState(
        targetValue = if (total == null) 0f else done.toFloat() / total,
        animationSpec = Motion.medium(),
        label = "conversion progress",
    )
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.creating_pdf)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (total == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                }
                if (progress != null && !progress.compressing && done > 0) {
                    Text(
                        if (total != null) pluralStringResource(R.plurals.progress_slides, total, done, total)
                        else stringResource(R.string.pdf_page_number, done),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}
