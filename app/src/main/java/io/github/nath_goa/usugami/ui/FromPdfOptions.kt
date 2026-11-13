package io.github.nath_goa.usugami.ui

import android.app.Application
import android.content.res.Resources
import android.os.Parcelable
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import io.github.nath_goa.usugami.BuildConfig
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.core.ExportImageFormat
import io.github.nath_goa.usugami.core.ExportImageSize
import io.github.nath_goa.usugami.core.ExportImages
import io.github.nath_goa.usugami.core.ExportText
import io.github.nath_goa.usugami.core.OutputType
import io.github.nath_goa.usugami.core.PageSelectionException
import io.github.nath_goa.usugami.core.PdfPageSelection
import io.github.nath_goa.usugami.core.isOutOfSpace
import io.github.nath_goa.usugami.data.AppLanguage
import io.github.nath_goa.usugami.data.ExportProgress
import io.github.nath_goa.usugami.data.NoTextFound
import io.github.nath_goa.usugami.data.OcrLanguage
import io.github.nath_goa.usugami.data.OcrProblem
import io.github.nath_goa.usugami.data.OcrUnavailableException
import io.github.nath_goa.usugami.data.PageNotDrawn
import io.github.nath_goa.usugami.data.PdfInput
import io.github.nath_goa.usugami.data.TextOutcome
import io.github.nath_goa.usugami.data.ocrEngine
import io.github.nath_goa.usugami.data.pagesToImages
import io.github.nath_goa.usugami.data.pagesToText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.parcelize.Parcelize

/** What PDF to images or text makes of the PDF's pages: a picture of each, or one text file of their text. */
enum class ExportKind { IMAGES, TEXT }

/**
 * Pages a note on the result names: how many there are ([count], never 0), and their numbers as the
 * note lists them ([listed], "3,5-7", see ExportText.pagesListed), a few bytes whatever the PDF's length.
 */
@Parcelize
data class NotedPages(val count: Int, val listed: String) : Parcelable

/**
 * What a run found that the result's notes say: the pages without text ([noText]), and why they
 * have none ([noTextWhy]: [NO_TEXT_LITE], [NO_TEXT_OFF] or [NO_TEXT_FOUND]); those text recognition
 * failed on ([failed]) and those it read ([read]); whether form fields or comments were left out of
 * the text ([forms]); and the images that got fewer pixels than the size chosen gives ([reduced]).
 */
@Parcelize
data class ExportNotes(
    val noText: NotedPages? = null,
    val noTextWhy: Int = NO_TEXT_LITE,
    val failed: NotedPages? = null,
    val read: NotedPages? = null,
    val forms: Boolean = false,
    val reduced: NotedPages? = null,
) : Parcelable {
    companion object {
        /** Usugami Lite has no text recognition to read them with. */
        const val NO_TEXT_LITE = 0
        /** "Read pages without text" was off. */
        const val NO_TEXT_OFF = 1
        /** Text recognition read them and found nothing. */
        const val NO_TEXT_FOUND = 2
    }
}

/** PDF to images or text's own state in the view model (see [ToolState]). */
internal class FromPdfState(private val handle: SavedStateHandle) : ToolState {
    /** What the result's notes say, for the result showing; kept while Android stops Usugami in the background. */
    val notes: StateFlow<ExportNotes?> = handle.getStateFlow<ExportNotes?>(KEY_NOTES, null)

    /** Written on the main thread only, as the saved state must be. */
    fun setNotes(notes: ExportNotes?) {
        handle[KEY_NOTES] = notes
    }

    /** Why text recognition couldn't start, while the dialog saying so shows. */
    val ocrProblem = MutableStateFlow<OcrProblem?>(null)

    /**
     * Whether the last run started in this tool was this tool's, as it was when the view model was
     * made: after Android closed Usugami mid-run, what is said then is said of images or text.
     */
    val ranHere: Boolean = handle.get<Boolean>(KEY_RAN) == true

    /** A run of this tool starts. On the main thread, before it. */
    fun ran() {
        handle[KEY_RAN] = true
    }

    override fun clear() {
        setNotes(null)
        ocrProblem.value = null
        handle.remove<Boolean>(KEY_RAN)
    }

    private companion object {
        // The saved state's keys of tools in files of their own begin with the tool's name.
        const val KEY_NOTES = "from_pdf_notes"
        const val KEY_RAN = "from_pdf_ran"
    }
}

/**
 * PDF to images or text's choices, for [input]; its pages are drawn from [shown] (see
 * PdfToolsEngine.previewView). They tell [action] what the button says and does: the images or the
 * text, of the pages typed or tapped (all of them while none are), named as typed here. The
 * screen's own name field isn't shown: the images add their page numbers to the name, and the
 * files don't end in .pdf.
 *
 * The choices survive rotation, the result screen and Android stopping Usugami; the pages and the
 * name start again with another PDF, and everything with the tool opened afresh.
 */
@Composable
internal fun FromPdfOptions(input: PdfInput, shown: PdfInput, vm: PdfToolsViewModel, action: ToolAction) {
    var kind by rememberSaveable { mutableStateOf(ExportKind.IMAGES) }
    var format by rememberSaveable { mutableStateOf(ExportImageFormat.JPG) }
    var size by rememberSaveable { mutableStateOf(ExportImageSize.MEDIUM) }
    var readScans by rememberSaveable { mutableStateOf(true) }
    // Text recognition reads the app's language unless another is picked, as in the Convert sheet.
    val appLanguage = AppLanguage.shownIn(LocalContext.current).ocr
    var language by rememberSaveable { mutableStateOf(appLanguage) }
    var pages by rememberSaveable(input.id) { mutableStateOf("") }
    var name by rememberSaveable(input.id) { mutableStateOf(vm.defaultName(PdfTool.PDF_TO_IMAGES_OR_TEXT, input)) }

    // The sections that come and go carry the gap above them, so each gap opens and closes with its section.
    Column {
        Text(stringResource(R.string.export_kind_title), style = MaterialTheme.typography.titleMedium)
        Segments(ExportKind.entries, kind, onSelect = { kind = it }, Modifier.padding(top = 8.dp)) {
            stringResource(if (it == ExportKind.TEXT) R.string.export_kind_text else R.string.export_kind_images)
        }
        // A PDF of one page has no pages to choose from.
        if (input.pageCount > 1) {
            PagesChoice(input, shown, pages, onText = { pages = it }, onTap = { change -> pages = change(pages) })
        }
        AnimatedVisibility(visible = kind == ExportKind.IMAGES, enter = pdfSectionEnter(), exit = pdfSectionExit(), label = "image options") {
            Column(Modifier.padding(top = 16.dp)) {
                Text(stringResource(R.string.export_format), style = MaterialTheme.typography.titleSmall)
                Segments(ExportImageFormat.entries, format, onSelect = { format = it }, Modifier.padding(top = 8.dp)) {
                    stringResource(if (it == ExportImageFormat.PNG) R.string.export_format_png else R.string.export_format_jpg)
                }
                Summary(if (format == ExportImageFormat.PNG) R.string.export_format_png_summary else R.string.export_format_jpg_summary)
                Text(stringResource(R.string.export_size), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
                Segments(ExportImageSize.entries, size, onSelect = { size = it }, Modifier.padding(top = 8.dp)) {
                    stringResource(
                        when (it) {
                            ExportImageSize.SMALL -> R.string.export_size_small
                            ExportImageSize.MEDIUM -> R.string.export_size_medium
                            ExportImageSize.LARGE -> R.string.export_size_large
                        },
                    )
                }
                Summary(
                    when (size) {
                        ExportImageSize.SMALL -> R.string.export_size_small_summary
                        ExportImageSize.MEDIUM -> R.string.export_size_medium_summary
                        ExportImageSize.LARGE -> R.string.export_size_large_summary
                    },
                )
            }
        }
        AnimatedVisibility(visible = kind == ExportKind.TEXT, enter = pdfSectionEnter(), exit = pdfSectionExit(), label = "text options") {
            Column(Modifier.padding(top = 16.dp)) {
                Text(stringResource(R.string.export_text_note), style = MaterialTheme.typography.bodyMedium)
                // Usugami Lite has no text recognition.
                if (BuildConfig.HAS_OCR) {
                    Column(Modifier.padding(top = 8.dp)) {
                        OptionSwitch(R.string.export_text_read_scans, stringResource(R.string.export_text_read_scans_detail), readScans, onChange = { readScans = it })
                    }
                    AnimatedVisibility(visible = readScans, enter = pdfSectionEnter(), exit = pdfSectionExit(), label = "text language") {
                        Column(Modifier.padding(top = 8.dp)) {
                            OcrLanguageChip(selected = language, onSelect = { language = it })
                        }
                    }
                } else {
                    Summary(R.string.export_text_lite_note)
                }
            }
        }
        // One name for both: each image adds its page number to it.
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.file_name)) },
            suffix = { Text(".${if (kind == ExportKind.TEXT) OutputType.TEXT.extension else format.type.extension}") },
            singleLine = true,
            modifier = Modifier.padding(top = 16.dp).fillMaxWidth(),
        )
        AnimatedVisibility(visible = kind == ExportKind.IMAGES, enter = pdfSectionEnter(), exit = pdfSectionExit(), label = "image name") {
            val example = remember(name, pages, input.pageCount) { exampleImageName(name, pages, input.pageCount) }
            Summary(R.string.export_images_name_hint, example + "." + format.type.extension)
        }
    }
    // Once, here: the button stays on for as long as these options show.
    action.Bind(
        ready = true,
        label = stringResource(if (kind == ExportKind.TEXT) R.string.export_text_action else R.string.export_images_action),
        suffix = null,
    ) { _ ->
        if (kind == ExportKind.TEXT) vm.runExportText(input, pages, readScans, language, name)
        else vm.runExportImages(input, pages, format, size, name)
    }
}

/**
 * The name, without its ending, of the image of the third page (the last, in a PDF of fewer pages)
 * as the run names it from [typed]: a long name is cut as the images of the pages in [pagesText]
 * are cut (all of them while it is blank or can't be read), to leave room for the longest page
 * number among them, so the example is cut as the files are.
 */
internal fun exampleImageName(typed: String, pagesText: String, pageCount: Int): String {
    val example = minOf(3, pageCount) - 1
    // The last page has the longest number; typed pages are made in page order.
    val last = typedPages(pagesText, pageCount).maxOrNull() ?: (pageCount - 1)
    return ExportImages.names(typed, listOf(example, last).distinct()).first()
}

/**
 * The pages to convert: typed into a field (all of them while it is empty), or tapped in the row of
 * pages, drawn from [shown]; the two stay in step. The pages are made in page order, so a tap writes
 * them sorted. [onTap] changes the text as it is then: two pages tapped before the screen redraws
 * are both chosen.
 */
@Composable
private fun PagesChoice(input: PdfInput, shown: PdfInput, text: String, onText: (String) -> Unit, onTap: (change: (String) -> String) -> Unit) {
    val chosen = remember(text, input.pageCount) { typedPages(text, input.pageCount).toSet() }
    OutlinedTextField(
        value = text,
        onValueChange = onText,
        label = { Text(stringResource(R.string.export_pages)) },
        supportingText = { Text(stringResource(R.string.export_pages_hint)) },
        singleLine = true,
        modifier = Modifier.padding(top = 16.dp).fillMaxWidth(),
    )
    // A screen reader says the new count as pages are typed or tapped. While what is typed can't be
    // read (a range half typed), none count, as in Reorganize PDF's Selected pages; the button says
    // what is wrong.
    Text(
        if (text.isBlank()) stringResource(R.string.export_pages_all) else stringResource(R.string.export_pages_count, chosen.size, input.pageCount),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
    )
    PageRowPicker(shown, chosen, Modifier.padding(top = 12.dp).fillMaxWidth()) { i ->
        onTap { current ->
            // Left as it is while what is typed can't be read, rather than replaced by this page alone.
            tappedOnto(current, input.pageCount)?.let { now -> PdfPageSelection.format(if (i in now) now - i else now + i) } ?: current
        }
    }
}

/** A row of segments, one for each of [items], [selected] chosen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Segments(items: List<T>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier, label: @Composable (T) -> String) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        items.forEachIndexed { index, item ->
            SegmentedButton(
                selected = item == selected,
                onClick = { onSelect(item) },
                shape = SegmentedButtonDefaults.itemShape(index, items.size),
                icon = {},
                contentPadding = SegmentPadding,
                label = { SegmentLabel(label(item)) },
            )
        }
    }
}

/** A line saying more of the choice above it. */
@Composable
private fun Summary(@StringRes text: Int, vararg args: Any) {
    Text(
        stringResource(text, *args),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
}

/**
 * Makes [input]'s pages typed in [pagesText] (all of them when it is blank) into pictures in
 * [format] at [size], named after [fileName] ("Report_p3.jpg"), in place of the result before. The
 * pages that came out with fewer pixels than [size] gives are noted for the result.
 */
internal fun PdfToolsViewModel.runExportImages(input: PdfInput, pagesText: String, format: ExportImageFormat, size: ExportImageSize, fileName: String) {
    // As the tools in the view model do (they read inputs again): not for a PDF put away between
    // the screen's last drawing and the tap, which would be said to have been cleared.
    if (inputs.value.singleOrNull()?.id != input.id) return
    val pages = exportPages(pagesText, input) ?: return
    val res = strings
    var reduced: NotedPages? = null
    fromPdf.ran()
    process(ExportImages.resultName(fileName), listOf(input), afterResult = { files ->
        // Each result says what it has to say, and nothing an earlier one said.
        if (files.isNotEmpty()) fromPdf.setNotes(reduced?.let { ExportNotes(reduced = it) })
    }) { check ->
        val outcome = said { engine.pagesToImages(input, pages, format, size, fileName, check) { tell(res, it) } }
        reduced = noted(outcome.reduced)
        outcome.files
    }
}

/**
 * Makes the text of [input]'s pages typed in [pagesText] (all of them when it is blank) into one
 * text file named after [fileName] ("Report.txt"), in place of the result before; with [readScans]
 * (and text recognition in this edition) pages without text are read with it, in [language]. What
 * the result's notes say is kept with it. When text recognition can't start, nothing is made and
 * [FromPdfState.ocrProblem] says why.
 */
internal fun PdfToolsViewModel.runExportText(input: PdfInput, pagesText: String, readScans: Boolean, language: OcrLanguage, fileName: String) {
    if (inputs.value.singleOrNull()?.id != input.id) return
    val pages = exportPages(pagesText, input) ?: return
    // Null in Usugami Lite. Cheap to make: its model is loaded only once a page needs reading.
    val ocr = if (readScans) ocrEngine(getApplication<Application>()) else null
    val res = strings
    var outcome: TextOutcome? = null
    var problem: OcrProblem? = null
    fromPdf.ran()
    process(ExportText.baseName(fileName), listOf(input), afterResult = { files ->
        // On the main thread, where the saved state is written. No file: text recognition couldn't start.
        val made = outcome
        if (files.isEmpty()) problem?.let { fromPdf.ocrProblem.value = it }
        else if (made != null) fromPdf.setNotes(notesOf(made, recognition = ocr != null))
    }) { check ->
        try {
            val made = said(noText = { e ->
                when {
                    e.recognitionFailed -> R.string.export_none_failed
                    ocr != null -> R.string.export_none_found
                    BuildConfig.HAS_OCR -> R.string.export_none_off
                    else -> R.string.export_none_lite
                }
            }) {
                engine.pagesToText(input, pages, ocr, language, fileName, check) { tell(res, it) }
            }
            outcome = made
            listOf(made.file)
        } catch (e: OcrUnavailableException) {
            // No file, so no result shows; afterResult shows the dialog.
            if (e.problem == OcrProblem.FAILED_TO_START) Log.e("Usugami", "Text recognition couldn't start", e)
            problem = e.problem
            emptyList()
        }
    }
}

/** The pages typed, zero-based and in page order: all of them when nothing is typed. Null, after saying why, when they can't be read. */
private fun PdfToolsViewModel.exportPages(text: String, input: PdfInput): List<Int>? =
    if (text.isBlank()) {
        (0 until input.pageCount).toList()
    } else {
        try {
            PdfPageSelection.selected(text, input.pageCount).sorted()
        } catch (e: PageSelectionException) {
            message(pageSelectionMessage(e))
            null
        }
    }

/**
 * What only this tool can go wrong with, as a message of its own (the view model says a
 * ToolMessage's text): the storage full, whatever ran into it, a page that can't be drawn, and no
 * text on any page, said as [noText] gives. A cancel, and text recognition that can't start (its
 * dialog says why, also when the storage is full), go up as they are.
 */
private inline fun <T> PdfToolsViewModel.said(noText: (NoTextFound) -> Int = { R.string.export_none_found }, block: () -> T): T =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: OcrUnavailableException) {
        throw e
    } catch (e: Exception) {
        throw when {
            e.isOutOfSpace() -> ToolMessage(strings.getString(R.string.export_no_space))
            e is PageNotDrawn -> ToolMessage(strings.getString(R.string.export_page_failed, e.page + 1))
            e is NoTextFound -> ToolMessage(strings.getString(noText(e)))
            else -> e
        }
    }

/**
 * Says how far a run is in the line under the working dialog's bar, in [res] (Usugami's language).
 * Its bar fills as the pages are done; while text recognition downloads with its size not yet
 * known, the bar is empty.
 */
private fun PdfToolsViewModel.tell(res: Resources, progress: ExportProgress) {
    val (stage, done, total) = progress
    when (stage) {
        ExportProgress.Stage.IMAGES -> reportProgress(done, total, res.getString(R.string.export_progress_image, done + 1, total))
        ExportProgress.Stage.TEXT -> reportProgress(done, total, res.getString(R.string.export_progress_text, done + 1, total))
        ExportProgress.Stage.READ_SCANS -> reportProgress(done, total, res.getString(R.string.export_progress_scans, done + 1, total))
        ExportProgress.Stage.DOWNLOAD_OCR -> reportProgress(
            if (total > 0) done else 0,
            100,
            listOfNotNull(
                res.getString(R.string.ocr_downloading),
                if (total > 0) res.getString(R.string.ocr_download_percent, done) else null,
                res.getString(R.string.ocr_download_note),
            ).joinToString("\n"),
        )
    }
}

/** What the notes say of a text run's [outcome], when they have anything to say. [recognition]: text recognition was there to read pages. */
private fun notesOf(outcome: TextOutcome, recognition: Boolean): ExportNotes? {
    val notes = ExportNotes(
        noText = noted(outcome.withoutText),
        noTextWhy = when {
            recognition -> ExportNotes.NO_TEXT_FOUND
            BuildConfig.HAS_OCR -> ExportNotes.NO_TEXT_OFF
            else -> ExportNotes.NO_TEXT_LITE
        },
        failed = noted(outcome.failed),
        read = noted(outcome.read),
        forms = outcome.formsOrComments,
    )
    return notes.takeIf { it.noText != null || it.failed != null || it.read != null || it.forms }
}

/** [pages] (zero-based) as a note names them, or null for none. */
private fun noted(pages: List<Int>): NotedPages? = if (pages.isEmpty()) null else NotedPages(pages.size, ExportText.pagesListed(pages))
