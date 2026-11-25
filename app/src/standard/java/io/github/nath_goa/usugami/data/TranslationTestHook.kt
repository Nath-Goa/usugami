package io.github.nath_goa.usugami.data

import android.content.Context
import androidx.annotation.VisibleForTesting

/** [translatePdf] as a value, with the PDF tools' engine as its first parameter. */
private val realRunner: suspend (
    PdfToolsEngine, PdfInput, List<Int>, TranslationEngine, OcrEngine?, String, String, String, () -> Unit, (TranslateProgress) -> Unit,
) -> TranslateOutcome = { engine, input, pages, translation, ocr, source, target, typedName, check, onProgress ->
    engine.translatePdf(input, pages, translation, ocr, source, target, typedName, check, onProgress)
}

/** [detectSourceLanguage] as a value, with the PDF tools' engine as its first parameter. */
private val realDetector: suspend (PdfToolsEngine, PdfInput, TranslationEngine, () -> Unit) -> String? = { engine, input, translation, check ->
    engine.detectSourceLanguage(input, translation, check)
}

/**
 * Where Translate PDF's screen gets its engine, its runs and its language detection: the real ones
 * ([translationEngine], [translatePdf], [detectSourceLanguage]) unless a device test puts its own in
 * their place, so the screen can be tested without a network or ML Kit. Nothing in the app sets them.
 */
@VisibleForTesting
internal object TranslationTestHook {
    /** Makes the engine in place of [translationEngine], when set: what it returns is the engine, null included. */
    @Volatile
    var engine: ((Context) -> TranslationEngine?)? = null

    /** Runs a translation: [translatePdf], with the same parameters, the PDF tools' engine first. */
    @Volatile
    var runner: suspend (
        engine: PdfToolsEngine,
        input: PdfInput,
        pages: List<Int>,
        translation: TranslationEngine,
        ocr: OcrEngine?,
        source: String,
        target: String,
        typedName: String,
        check: () -> Unit,
        onProgress: (TranslateProgress) -> Unit,
    ) -> TranslateOutcome = realRunner

    /** Detects a PDF's language: [detectSourceLanguage], with the same parameters, the PDF tools' engine first. */
    @Volatile
    var detector: suspend (engine: PdfToolsEngine, input: PdfInput, translation: TranslationEngine, check: () -> Unit) -> String? = realDetector

    /** Puts the real ones back. */
    fun reset() {
        engine = null
        runner = realRunner
        detector = realDetector
    }
}

/** The translation engine Translate PDF uses: a device test's (see [TranslationTestHook.engine]), else [translationEngine]'s. */
internal fun currentTranslationEngine(context: Context): TranslationEngine? {
    val override = TranslationTestHook.engine ?: return translationEngine(context)
    return override(context)
}
