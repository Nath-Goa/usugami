package io.github.nath_goa.usugami.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.core.ExportText
import io.github.nath_goa.usugami.core.OutputType
import io.github.nath_goa.usugami.data.PdfFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** What the result screen shows of a text file: [text], its beginning, and whether that is the [whole] text. */
class TextPreview(val text: String, val whole: Boolean)

/** How many lines of the text the card shows until it is opened up. */
private const val PREVIEW_LINES = 12

/** The text file a preview was read from, as it was then: one made again under the same name is another. */
private data class PreviewKey(val path: String, val length: Long, val modified: Long)

/**
 * The last preview read, while Usugami runs: after a rotation the card is there in the first frame,
 * so the list goes back to the place in the text the user had scrolled to. Main thread only.
 */
private var lastPreview: Pair<PreviewKey, TextPreview>? = null

/**
 * The beginning of the text file [files] are, when they are a single text file (PDF to images or
 * text's), read from the file: null until it is read, and for other results or a file that can't be
 * read. Not kept in the saved state, as it is up to ExportText.PREVIEW_CHARS characters long: the
 * last one read is kept in memory for a rotation, and it is read again after Android stopped Usugami.
 */
@Composable
internal fun textPreviewOf(files: List<PdfFile>): TextPreview? {
    val file = files.singleOrNull()?.takeIf { it.type == OutputType.TEXT } ?: return null
    // Its length and time are read once: Start over deletes the file while the screen slides away, and
    // a length read again then would read the text again, and fail.
    val key = remember(file) { PreviewKey(file.file.path, file.sizeBytes, file.file.lastModified()) }
    val known = lastPreview?.takeIf { it.first == key }?.second
    val preview by produceState(known, key) {
        value = known ?: withContext(Dispatchers.IO) { previewOf(file.file) }?.also { lastPreview = key to it }
    }
    return preview
}

/** [file]'s text as the card shows it, or null when it can't be read. */
private fun previewOf(file: File): TextPreview? = try {
    val start = readAtMost(file, ExportText.PREVIEW_CHARS + 1)
    // The line break that may end the file isn't shown as an empty line of its own.
    TextPreview(ExportText.preview(start).trimEnd('\n'), whole = start.length <= ExportText.PREVIEW_CHARS)
} catch (_: IOException) {
    null
}

/**
 * The first [max] characters of [file]'s text (UTF-8), or all of it when it has fewer. A reader
 * gives some at a time, so it is asked until it has given them all.
 */
private fun readAtMost(file: File, max: Int): String = file.reader(Charsets.UTF_8).use { reader ->
    val chars = CharArray(max)
    var count = 0
    while (count < max) {
        val read = reader.read(chars, count, max - count)
        if (read < 0) break
        count += read
    }
    String(chars, 0, count)
}

/**
 * The card under a text file made by PDF to images or text: "Text" with a Copy button ([onCopy],
 * on while [enabled]), and the text's first lines, with Show more and Show less when there are
 * more. Each paragraph is set as its writing goes (Hebrew and Arabic from the right). When the file
 * has more than [preview] holds, the text shown ends by saying so. Not selectable: Copy copies it all.
 */
@Composable
internal fun ResultTextCard(preview: TextPreview, enabled: Boolean, onCopy: () -> Unit, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    // Whether the first lines leave some of the text out, as the text's layout tells while they show alone.
    var cut by remember { mutableStateOf(false) }
    OutlinedCard(modifier) {
        Column(Modifier.padding(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.result_text_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 12.dp).weight(1f),
                )
                val copyText = stringResource(R.string.copy_text)
                TextButton(onClick = onCopy, enabled = enabled, modifier = Modifier.semantics { contentDescription = copyText }) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.copy))
                }
            }
            Text(
                preview.text,
                style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                maxLines = if (expanded) Int.MAX_VALUE else PREVIEW_LINES,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!expanded) cut = it.hasVisualOverflow },
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(top = 4.dp)
                    .fillMaxWidth()
                    .animateContentSize(Motion.medium()),
            )
            // Said where the text shown ends: once opened up, or when the first lines are all there is to show.
            if (!preview.whole && (expanded || !cut)) {
                Text(
                    stringResource(R.string.result_text_more),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp).padding(top = 8.dp),
                )
            }
            if (expanded || cut) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(stringResource(if (expanded) R.string.show_less else R.string.show_more))
                }
            } else {
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

/**
 * Puts the whole text of the text file showing as the result on the clipboard, and says so below
 * Android 13 (which says so itself). A text longer than ExportText.COPY_MAX_CHARS isn't copied, as
 * the clipboard can't take it in one go: the file is the way to it then.
 */
internal fun PdfToolsViewModel.copyResultText() {
    if (!checkResult()) return
    val file = result.value?.singleOrNull()?.takeIf { it.type == OutputType.TEXT } ?: return
    val res = strings
    viewModelScope.launch {
        val text = withContext(Dispatchers.IO) {
            try {
                readAtMost(file.file, ExportText.COPY_MAX_CHARS + 1)
            } catch (_: IOException) {
                null
            }
        }
        // Another result showing by now isn't this text's.
        if (result.value?.singleOrNull() != file) return@launch
        when {
            text == null -> message(res.getString(R.string.msg_copy_failed))
            text.length > ExportText.COPY_MAX_CHARS -> message(res.getString(R.string.msg_text_too_long))
            else -> try {
                val clipboard = getApplication<Application>().getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText(file.name, text))
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) message(res.getString(R.string.msg_text_copied))
            } catch (_: RuntimeException) {
                // Android's clipboard turned it down.
                message(res.getString(R.string.msg_copy_failed))
            }
        }
    }
}

/**
 * The notes on a result of PDF to images or text, each a paragraph of its own: the pages without
 * text and why, those text recognition failed on or read, form fields and comments left out, and
 * the pages whose images have fewer pixels. Null when there is nothing to say.
 */
@Composable
internal fun exportNotesText(notes: ExportNotes?): String? {
    if (notes == null) return null
    val paragraphs = ArrayList<String>()
    notes.noText?.let { pages ->
        val said = when (notes.noTextWhy) {
            ExportNotes.NO_TEXT_LITE -> R.plurals.export_no_text_lite
            ExportNotes.NO_TEXT_OFF -> R.plurals.export_no_text_off
            else -> R.plurals.export_no_text_found
        }
        paragraphs += pluralStringResource(said, pages.count, pages.count, pages.listed)
    }
    notes.failed?.let { paragraphs += pluralStringResource(R.plurals.export_ocr_failed_pages, it.count, it.count, it.listed) }
    notes.read?.let { paragraphs += pluralStringResource(R.plurals.export_scans_read, it.count, it.count, it.listed) }
    if (notes.forms) paragraphs += stringResource(R.string.export_forms_left_out)
    notes.reduced?.let { paragraphs += pluralStringResource(R.plurals.export_images_reduced, it.count, it.count, it.listed) }
    return paragraphs.joinToString("\n\n").ifEmpty { null }
}
