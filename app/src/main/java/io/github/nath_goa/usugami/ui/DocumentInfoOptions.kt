package io.github.nath_goa.usugami.ui

import android.content.Context
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.core.InfoField
import io.github.nath_goa.usugami.core.InfoText
import io.github.nath_goa.usugami.core.isOutOfSpace
import io.github.nath_goa.usugami.data.DocumentDetails
import io.github.nath_goa.usugami.data.InfoDate
import io.github.nath_goa.usugami.data.PageMark
import io.github.nath_goa.usugami.data.PdfInput
import io.github.nath_goa.usugami.data.documentDetails
import io.github.nath_goa.usugami.data.setDocumentInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Document info's form, for [input] ([shown] isn't used: the tool draws no pages): a note when the
 * PDF is digitally signed; its title, author, subject and keywords to change; what else it says of
 * itself (dates, the app that made it, pages, version, size); and Remove all info, with its Undo.
 * The button saves the PDF with the info as the form has it, once something differs from the PDF's.
 *
 * What is typed survives rotation, the result screen and Android stopping Usugami (see
 * [DocumentInfoState]); the PDF's own info is read again.
 */
@Composable
internal fun DocumentInfoOptions(input: PdfInput, shown: PdfInput, vm: PdfToolsViewModel, action: ToolAction) {
    val state = vm.documentInfo
    val changed by state.changed.collectAsStateWithLifecycle()
    val details by state.details.collectAsStateWithLifecycle()
    val removeAll by state.removeAll.collectAsStateWithLifecycle()
    val generation by state.generation.collectAsStateWithLifecycle()
    // Again whenever the form starts over, so a form cleared while it shows doesn't wait for a read never started.
    LaunchedEffect(input.id, generation) { vm.loadDocumentInfo(input) }
    // "Save PDF" (the tool's own words), the usual ".pdf" name field; on once something differs from the PDF.
    action.Bind(ready = changed) { fileName -> vm.runDocumentInfo(input, fileName) }

    val info = details?.takeIf { it.inputId == input.id }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (info == null) {
            Column {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    stringResource(R.string.docinfo_reading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        } else {
            if (info.signed) TonalNote { Text(stringResource(R.string.docinfo_signed_note), style = MaterialTheme.typography.bodyMedium) }
            InfoFields(state, input.id, generation, info, removeAll)
            AboutPdf(input, info, removeAll)
            if (removeAll) {
                // The button below the text brings its own padding.
                TonalNote(PaddingValues(start = 16.dp, top = 16.dp, end = 8.dp, bottom = 4.dp)) {
                    // Said by a screen reader as it takes the place of the button that had the focus.
                    Text(
                        stringResource(R.string.docinfo_removing),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    TextButton(onClick = state::undoRemoveAll, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.undo)) }
                }
            } else {
                Column {
                    OutlinedButton(onClick = state::removeAll) { Text(stringResource(R.string.docinfo_remove_all)) }
                    Text(
                        stringResource(R.string.docinfo_remove_all_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/** The four fields that can be changed, filled with what the PDF has (or what was typed). */
@Composable
private fun InfoFields(state: DocumentInfoState, inputId: Long, generation: Int, info: DocumentDetails, removeAll: Boolean) {
    // A value longer than a field takes shows cut, and changing it keeps only what shows: said, but not once all is to go.
    fun note(field: InfoField, @StringRes otherwise: Int? = null): Int? = if (field in info.cut && !removeAll) R.string.docinfo_cut else otherwise
    InfoTextField(state, inputId, generation, InfoField.TITLE, R.string.docinfo_title, lines = 3, note = note(InfoField.TITLE))
    InfoTextField(state, inputId, generation, InfoField.AUTHOR, R.string.docinfo_author, lines = 1, note = note(InfoField.AUTHOR), words = true)
    InfoTextField(state, inputId, generation, InfoField.SUBJECT, R.string.docinfo_subject, lines = 3, note = note(InfoField.SUBJECT))
    InfoTextField(
        state, inputId, generation, InfoField.KEYWORDS, R.string.docinfo_keywords, lines = 4,
        note = note(InfoField.KEYWORDS, otherwise = R.string.docinfo_keywords_hint), last = true,
    )
}

/**
 * The field for [field], up to [lines] lines high, with [note] under it. It holds its own text, as
 * OutputNameField does, since a field fed from a flow drops characters under fast typing; it starts
 * anew from what the state shows at each [generation]. Enter never puts a line break in: the
 * keyboard's action moves on to the next field, or closes the keyboard after the last.
 */
@Composable
private fun InfoTextField(
    state: DocumentInfoState,
    inputId: Long,
    generation: Int,
    field: InfoField,
    @StringRes label: Int,
    lines: Int,
    @StringRes note: Int?,
    words: Boolean = false,
    last: Boolean = false,
) {
    key(inputId, generation, field) {
        var text by rememberSaveable { mutableStateOf(state.shown(field)) }
        OutlinedTextField(
            value = text,
            onValueChange = { typed ->
                text = infoTyped(typed)
                state.setText(field, text)
            },
            label = { Text(stringResource(label)) },
            supportingText = note?.let { { Text(stringResource(it)) } },
            singleLine = lines == 1,
            maxLines = lines,
            keyboardOptions = KeyboardOptions(
                capitalization = if (words) KeyboardCapitalization.Words else KeyboardCapitalization.Sentences,
                imeAction = if (last) ImeAction.Done else ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * What a field keeps of [typed]: each line break, or run of them (a Windows one, an empty line
 * pasted), one space, as the PDF's own values are read; then cut to the length a field takes.
 */
internal fun infoTyped(typed: String): String = InfoText.cut(typed.replace(LINE_BREAKS, " "))

private val LINE_BREAKS = Regex("[\r\n]+")

/** "About this PDF": what else it says of itself, which can't be changed here but goes with Remove all info. */
@Composable
private fun AboutPdf(input: PdfInput, info: DocumentDetails, removeAll: Boolean) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val notSet = stringResource(R.string.docinfo_not_set)
    val goes = stringResource(R.string.docinfo_will_be_removed)
    val apps = listOfNotNull(info.creator, info.producer).let {
        // One name when both say the same.
        if (it.size == 2 && it[0].equals(it[1], ignoreCase = true)) it.take(1) else it
    }
    val madeWith = when (apps.size) {
        0 -> null
        1 -> apps[0]
        else -> stringResource(R.string.docinfo_made_with_both, apps[0], apps[1])
    }
    // What Remove all info takes says so once it is chosen.
    fun removable(value: String?) = if (value != null && removeAll) goes else value ?: notSet
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.docinfo_about),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        AboutRow(R.string.docinfo_created, removable(info.created?.let { shownDate(context, locale, it) }))
        AboutRow(R.string.docinfo_changed, removable(info.modified?.let { shownDate(context, locale, it) }))
        AboutRow(R.string.docinfo_made_with, removable(madeWith))
        AboutRow(R.string.docinfo_pages, String.format(locale, "%d", input.pageCount))
        AboutRow(R.string.docinfo_version, info.version)
        AboutRow(R.string.docinfo_size, Formatter.formatShortFileSize(context, info.sizeBytes))
    }
}

/** [label] at the start, [value] at the end (mirrored in right-to-left languages): one item for a screen reader. */
@Composable
private fun AboutRow(@StringRes label: Int, value: String) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

/** A note on a tonal card. */
@Composable
private fun TonalNote(padding: PaddingValues = PaddingValues(16.dp), content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(padding), content = content)
    }
}

/**
 * [date] as the phone shows a date and time, in [locale] (Usugami's language), in its time zone and
 * with its 12- or 24-hour setting; as the PDF writes it when it can't be read as a date.
 */
private fun shownDate(context: Context, locale: Locale, date: InfoDate): String? {
    val millis = date.millis ?: return date.raw.ifEmpty { null }
    val skeleton = if (DateFormat.is24HourFormat(context)) "yMMMdHm" else "yMMMdhma"
    return SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(Date(millis))
}

/** Reads [input]'s info for the form, unless it is there or being read. */
internal fun PdfToolsViewModel.loadDocumentInfo(input: PdfInput) {
    val state = documentInfo
    if (!state.begin(input)) return
    state.reading = viewModelScope.launch {
        try {
            // On the view model's file-work thread, one job at a time: after the start-up cleanup that
            // deletes PDFBox's scratch folder, never beside it.
            val details = withContext(fileOps) {
                if (input.file.isFile) engine.documentDetails(input, PageMark(getApplication())) else null
            }
            if (details != null) return@launch state.loaded(details)
            // Its copy was cleared from the cache, as the system may do while Usugami runs: the PDF is
            // put away and that said, not "Couldn't open that PDF", nor "Reading…" for ever (Save PDF
            // stays off, so no run would find it gone). After Android closed Usugami, the view model's
            // start-up check found it gone too: its file work, queued before this, is waited for, and
            // then it has said so and put the PDF away, the form with it, which cancels this read.
            withContext(fileOps) {}
            if (inputs.value.firstOrNull()?.id != input.id) return@launch
            pluralMessage(R.plurals.msg_files_cleared, 1)
            // The form goes with it (see DocumentInfoState.clear).
            removeInput(input.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            unreadable(input, e.isOutOfSpace())
        } catch (_: LinkageError) {
            unreadable(input, false)
        } catch (_: StackOverflowError) {
            unreadable(input, false)
        } catch (_: OutOfMemoryError) {
            unreadable(input, false)
        }
    }
}

/** [input]'s info can't be read: say so and put the PDF away, as a tool whose preview can't be made does. */
private fun PdfToolsViewModel.unreadable(input: PdfInput, noSpace: Boolean) {
    if (inputs.value.firstOrNull()?.id != input.id) return
    message(strings.getString(if (noSpace) R.string.open_no_space else R.string.pdf_import_failed))
    // The form goes with it (see DocumentInfoState.clear).
    removeInput(input.id)
}

/** Saves [input] with the info as the form has it, named after [fileName]. */
internal fun PdfToolsViewModel.runDocumentInfo(input: PdfInput, fileName: String) {
    // Not for a PDF put away between the screen's last drawing and the tap.
    if (inputs.value.singleOrNull()?.id != input.id) return
    val change = documentInfo.change() ?: return
    // Set by the job, on the file-work thread, before the result shows.
    var dropped = false
    process(fileName, listOf(input), afterResult = { if (dropped) message(strings.getString(R.string.docinfo_xmp_dropped)) }) { check ->
        val result = engine.setDocumentInfo(input, change, fileName, check)
        dropped = result.droppedXmp
        listOf(result.file)
    }
}
