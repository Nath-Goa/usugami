package io.github.nath_goa.usugami.data

// Translate PDF, in the engine (standard edition only). The signatures here are the contract between
// the engine and the tool's screen; the bodies are filled in by the engine's work.

/**
 * How far a translation run is. [Stage.DOWNLOAD]: language packs download, [done] per cent of
 * [total] 100, or [total] 0 while the size isn't known. [Stage.DOWNLOAD_OCR]: the same for text
 * recognition's model. [Stage.PAGE]: [done] pages of the pages chosen are finished and the next is
 * being worked on, of [total].
 */
data class TranslateProgress(val stage: Stage, val done: Int, val total: Int) {
    enum class Stage { DOWNLOAD, DOWNLOAD_OCR, PAGE }
}

/**
 * The PDF made, and what the result's notes say. Every page list is of zero-based pages of the PDF
 * that was translated, in page order, among the pages chosen:
 * - [noText]: pages with no text to translate (a picture, a blank page, only numbers): in the new PDF as they are.
 * - [unread]: pages that look scanned but couldn't be read (no text recognition for the language, or it failed on the page): as they are.
 * - [partly]: pages where a paragraph was left as it was, because translating it failed or this phone has no font that draws its translation.
 */
class TranslateOutcome(val file: PdfFile, val noText: List<Int>, val unread: List<Int>, val partly: List<Int>)

/** None of the pages chosen has text to translate; [unreadable] when some pages looked scanned but couldn't be read. */
class NothingToTranslate(val unreadable: Boolean) : Exception("No page has text to translate")

/**
 * The zero-based [pages] of [input] (sorted, each once, at least one) translated from [source] to
 * [target] (tags, see [TranslationEngine]), as a new PDF in place of the files made before, named
 * after [typedName]. Pages with real text are read from it; pages without are read with [ocr]
 * when it is given and reads [source]'s script (see [ocrLanguageFor]). Language packs are
 * downloaded first when missing. [check] is called often and throws to stop; [onProgress] is told as
 * the run goes. Throws [NothingToTranslate], [TranslationUnavailableException], [OcrUnavailableException]
 * and an IOException when the file can't be written (see isOutOfSpace); if it fails or is stopped,
 * nothing it made is left behind.
 */
internal suspend fun PdfToolsEngine.translatePdf(
    input: PdfInput,
    pages: List<Int>,
    translation: TranslationEngine,
    ocr: OcrEngine?,
    source: String,
    target: String,
    typedName: String,
    check: () -> Unit,
    onProgress: (TranslateProgress) -> Unit,
): TranslateOutcome = TODO("Translate PDF's engine")

/**
 * The language the text of [input]'s first pages with text is written in, as [translation] detects
 * it from at most a few thousand letters; null when the PDF has no text or the language isn't clear.
 * Cheap enough to run when a PDF is picked. [check] throws to stop.
 */
internal suspend fun PdfToolsEngine.detectSourceLanguage(input: PdfInput, translation: TranslationEngine, check: () -> Unit): String? =
    TODO("Translate PDF's language detection")

/**
 * The language text recognition reads [tag]'s script in: Latin for the Latin-script languages ML Kit
 * translates (any of them reads as [OcrLanguage.ENGLISH], whose recognizer takes no language hint),
 * Devanagari for Hindi and Marathi ([OcrLanguage.HINDI]); null for the others (Arabic, Cyrillic,
 * Chinese...), whose scanned pages can't be read.
 */
internal fun ocrLanguageFor(tag: String): OcrLanguage? = when (tag) {
    "hi", "mr" -> OcrLanguage.HINDI
    "af", "ca", "cs", "cy", "da", "de", "en", "eo", "es", "et", "fi", "fr", "ga", "gl", "hr", "ht", "hu", "id",
    "is", "it", "lt", "lv", "ms", "mt", "nl", "no", "pl", "pt", "ro", "sk", "sl", "sq", "sv", "sw", "tl", "tr", "vi",
    -> OcrLanguage.ENGLISH
    else -> null
}
