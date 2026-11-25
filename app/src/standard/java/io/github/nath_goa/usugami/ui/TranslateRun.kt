package io.github.nath_goa.usugami.ui

import android.app.Application
import android.content.res.Resources
import android.util.Log
import androidx.lifecycle.viewModelScope
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.data.NothingToTranslate
import io.github.nath_goa.usugami.data.OcrProblem
import io.github.nath_goa.usugami.data.OcrUnavailableException
import io.github.nath_goa.usugami.data.PdfInput
import io.github.nath_goa.usugami.data.TranslateOutcome
import io.github.nath_goa.usugami.data.TranslateProgress
import io.github.nath_goa.usugami.data.TranslationEngine
import io.github.nath_goa.usugami.data.TranslationProblem
import io.github.nath_goa.usugami.data.TranslationTestHook
import io.github.nath_goa.usugami.data.TranslationUnavailableException
import io.github.nath_goa.usugami.data.currentTranslationEngine
import io.github.nath_goa.usugami.data.ocrEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Translates [input]'s pages typed in [pagesText] (all of them when it is blank) from [source] to
 * [target] into a new PDF named after [fileName], in place of the result before. When language packs
 * it needs aren't on the phone, the user is asked first ([TranslateState.download]), and it runs once
 * they agree ([confirmTranslateDownload]). What stops it is said by a dialog
 * ([TranslateState.problem], [TranslateState.ocrProblem]) or a message.
 */
internal fun PdfToolsViewModel.runTranslate(input: PdfInput, pagesText: String, source: String?, target: String, fileName: String) {
    // As the tools in the view model do (see runExportImages): not for a PDF put away between the
    // screen's last drawing and the tap.
    if (inputs.value.singleOrNull()?.id != input.id) return
    // A second tap while the packs are looked at, or while the run goes, does nothing.
    if (processing.value || translate.checking?.isActive == true) return
    // The standard edition, the only one with the tool, always has an engine.
    val translation = currentTranslationEngine(getApplication<Application>()) ?: return message(strings.getString(R.string.translate_unavailable))
    // The button is off until both languages are known and differ.
    if (source == null || source == target) return
    val pages = exportPages(pagesText, input) ?: return
    translate.checking = viewModelScope.launch {
        val missing = missingPacks(translation, source, target)
        // Put away meanwhile: there is nothing to ask about.
        if (inputs.value.singleOrNull()?.id != input.id) return@launch
        val request = TranslateRequest(input.id, pages, source, target, fileName, missing)
        if (missing.isEmpty()) startTranslate(request) else translate.download.value = request
    }
}

/** The user agreed to download the language packs the waiting run needs: it runs, downloading them first. */
internal fun PdfToolsViewModel.confirmTranslateDownload() {
    val request = translate.download.value ?: return
    translate.download.value = null
    startTranslate(request)
}

/** The user doesn't want the language packs downloaded: the waiting run doesn't happen. */
internal fun PdfToolsViewModel.dismissTranslateDownload() {
    translate.download.value = null
}

/** Of the language packs translating [source] to [target] needs, those not on the phone; all of them when that can't be told. */
private suspend fun missingPacks(translation: TranslationEngine, source: String, target: String): List<String> {
    val needed = packsNeeded(source, target)
    // Asked about all of them rather than downloaded without asking.
    val onPhone = translation.downloadedOrNull() ?: emptySet()
    return needed.filterNot { it in onPhone }
}

/** The run [request] asked for, of the PDF it was asked for while that PDF is still the tool's. */
private fun PdfToolsViewModel.startTranslate(request: TranslateRequest) {
    val input = inputs.value.singleOrNull()?.takeIf { it.id == request.inputId } ?: return
    val app = getApplication<Application>()
    val translation = currentTranslationEngine(app) ?: return message(strings.getString(R.string.translate_unavailable))
    // Cheap to make: text recognition is only asked for once a scanned page needs reading.
    val ocr = ocrEngine(app)
    val res = strings
    var problem: TranslationProblem? = null
    var ocrProblem: OcrProblem? = null
    process(request.fileName, listOf(input), afterResult = { files ->
        // On the main thread. No file: the run couldn't start, and a dialog says why.
        if (files.isEmpty()) {
            problem?.let { translate.problem.value = it }
            ocrProblem?.let { translate.ocrProblem.value = it }
        }
    }) { check ->
        try {
            val outcome: TranslateOutcome = TranslationTestHook.runner(
                engine, input, request.pages, translation, ocr, request.source, request.target, request.fileName, check,
            ) { tell(res, it, request.missing) }
            listOf(outcome.file)
        } catch (e: TranslationUnavailableException) {
            // No file, so no result shows; afterResult shows the dialog.
            if (e.problem == TranslationProblem.FAILED_TO_START) Log.e("Usugami", "Translating couldn't start", e)
            problem = e.problem
            emptyList()
        } catch (e: OcrUnavailableException) {
            if (e.problem == OcrProblem.FAILED_TO_START) Log.e("Usugami", "Text recognition couldn't start", e)
            ocrProblem = e.problem
            emptyList()
        } catch (e: NothingToTranslate) {
            throw ToolMessage(res.getString(if (e.unreadable) R.string.translate_none_unreadable else R.string.translate_none))
        } finally {
            // Packs may have been downloaded, whatever came of the run: what lists them reads them again.
            translate.packs.update { it + 1 }
        }
    }
}

/**
 * Says how far a run is in the lines under the working dialog's bar, in [res] (Usugami's language):
 * which language pack downloads ([missing], those the run was known to need) and how much of it,
 * then which page is translated. While a download's size isn't known, the bar moves on its own.
 */
private fun PdfToolsViewModel.tell(res: Resources, progress: TranslateProgress, missing: List<String>) {
    val (stage, done, total) = progress
    val known = total > 0
    val percent = if (known) res.getString(R.string.ocr_download_percent, done) else null
    when (stage) {
        TranslateProgress.Stage.DOWNLOAD -> {
            val what = missing.singleOrNull()?.let { res.getString(R.string.translate_downloading_one, languageWord(it, res.configuration.locales[0])) }
                ?: res.getString(R.string.translate_downloading)
            reportProgress(if (known) done else 0, if (known) total else 0, listOfNotNull(what, percent, res.getString(R.string.translate_download_note)).joinToString("\n"))
        }
        TranslateProgress.Stage.DOWNLOAD_OCR -> reportProgress(
            if (known) done else 0,
            if (known) total else 0,
            listOfNotNull(res.getString(R.string.ocr_downloading), percent, res.getString(R.string.ocr_download_note)).joinToString("\n"),
        )
        TranslateProgress.Stage.PAGE -> reportProgress(done, total, res.getString(R.string.translate_progress_page, minOf(done + 1, total), total))
    }
}

/**
 * The language of [input]'s text as [translation] detects it, among the languages it translates;
 * null when it can't tell (no text, a scan, too little to go by) or there is no engine. The PDF is
 * read with the other file work, one thing at a time.
 */
internal suspend fun PdfToolsViewModel.detectLanguage(input: PdfInput, translation: TranslationEngine?): String? {
    if (translation == null) return null
    val found = try {
        withContext(fileOps) {
            val context = currentCoroutineContext()
            TranslationTestHook.detector(engine, input, translation) { context.ensureActive() }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // A PDF that can't be read here can't be translated either, which the run says: the user chooses meanwhile.
        null
    }
    return found?.let(::engineTag)?.takeIf { it in translation.languages }
}

/** The tags of the language packs on the phone, or null when the engine can't say. */
internal suspend fun TranslationEngine.downloadedOrNull(): Set<String>? = try {
    downloaded()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

/** Deletes [tag]'s language pack from the phone with [translation], and says when that fails. */
internal fun PdfToolsViewModel.deleteLanguagePack(translation: TranslationEngine, tag: String) {
    viewModelScope.launch {
        try {
            translation.delete(tag)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            message(strings.getString(R.string.translate_pack_delete_failed))
        } finally {
            translate.packs.update { it + 1 }
        }
    }
}
