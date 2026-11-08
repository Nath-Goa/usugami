package io.github.nath_goa.usugami.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.data.PdfInput

/**
 * A file in the list of a tool that works on several files one after another, each into a PDF of
 * its own (see [BatchList]): its [id], its [name], and [details] such as its pages and size.
 */
class BatchEntry(val id: Long, val name: String, val details: String)

/** [input] as a [BatchList] shows it: its name, pages and size. */
@Composable
fun pdfBatchEntry(input: PdfInput): BatchEntry {
    val context = LocalContext.current
    // Read once: the copy doesn't change while it is listed.
    val size = remember(input.path) { input.file.length() }
    val pages = pluralStringResource(R.plurals.page_count, input.pageCount, input.pageCount)
    return BatchEntry(input.id, input.name, stringResource(R.string.result_details, pages, Formatter.formatShortFileSize(context, size)))
}

/**
 * The files a tool works on one after another, each into a PDF of its own: for each, a card with
 * its name and [BatchEntry.details], the name its PDF gets ([outputName], changed with
 * [onOutputName]), what [options] puts above that (a level of its own, say), and a button that
 * removes it. [thumbnail] draws a file's first page beside its name, for files that have pages.
 */
@Composable
fun BatchList(
    entries: List<BatchEntry>,
    outputName: (Long) -> String,
    onOutputName: (Long, String) -> Unit,
    onRemove: (Long) -> Unit,
    thumbnail: (@Composable (Long) -> Unit)? = null,
    options: @Composable (Long) -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (entry in entries) {
            // Each card keeps its own state (the name being typed) when one before it is removed.
            key(entry.id) {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (thumbnail != null) {
                                thumbnail(entry.id)
                                Spacer(Modifier.width(12.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                Text(entry.details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { onRemove(entry.id) }) {
                                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.remove_file, entry.name))
                            }
                        }
                        options(entry.id)
                        OutputNameField(entry.id, outputName(entry.id), onChange = { onOutputName(entry.id, it) })
                    }
                }
            }
        }
    }
}

/**
 * The name the PDF made of file [id] gets, starting as [initial]. What is typed shows at once and
 * goes to [onChange], which keeps it: the field holds it itself, as a text field must to keep up with
 * fast typing, and keeps it through rotation. What it holds is saved under [id], so a name typed for
 * one file never comes back in the field of another put in its place, as after Start over.
 */
@Composable
fun OutputNameField(id: Long, initial: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    key(id) {
        var text by rememberSaveable { mutableStateOf(initial) }
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                onChange(it)
            },
            label = { Text(stringResource(R.string.file_name)) },
            suffix = { Text(".pdf") },
            singleLine = true,
            modifier = modifier.fillMaxWidth(),
        )
    }
}
