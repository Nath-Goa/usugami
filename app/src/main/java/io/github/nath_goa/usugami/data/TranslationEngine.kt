package io.github.nath_goa.usugami.data

import java.io.Closeable

/**
 * Why translating can't run on this phone right now. Only the standard edition, which translates
 * with Google ML Kit, has an engine; its language packs are downloaded once, by ML Kit itself.
 */
enum class TranslationProblem {
    /** A language pack has to be downloaded and the phone is offline. */
    NO_INTERNET,

    /** The download failed, or took far too long. */
    DOWNLOAD_FAILED,

    /** The phone has no room for the language pack. */
    NO_STORAGE,

    /** An unexpected error (most likely a bug), kept apart so it isn't mistaken for a network problem. */
    FAILED_TO_START,
}

class TranslationUnavailableException(val problem: TranslationProblem, cause: Throwable? = null) :
    Exception("Translation unavailable: $problem", cause)

/**
 * A translation engine. Each edition provides `translationEngine(context)` in its own source set:
 * the standard edition Google ML Kit (languages are downloaded once, then translating needs no
 * internet); Usugami OCR and Usugami Lite none (null), so they have no translating at all.
 *
 * A language is its BCP-47 tag as ML Kit names it ("en", "fr", "hi", "zh"...); [languages] lists them.
 */
interface TranslationEngine {
    /** Every language the engine translates between, as tags. */
    val languages: List<String>

    /** The tags whose language packs are on the phone now. */
    suspend fun downloaded(): Set<String>

    /**
     * Returns once text can be translated from [source] to [target], downloading the language packs
     * that are missing first (ML Kit translates through English: a pair without English needs the
     * packs of both its languages; English has no pack, it is built in). [onDownload] is called
     * only when a download is needed, with the fraction done (0..1), or null while the size is
     * unknown or the download is waiting. Cancellable.
     * @throws TranslationUnavailableException when it can't be made ready.
     */
    suspend fun ensureReady(source: String, target: String, onDownload: (Float?) -> Unit = {})

    /** A translator from [source] to [target], to use once [ensureReady] has returned. Close it when done. */
    fun open(source: String, target: String): TextTranslator

    /** Deletes the language pack of [tag] from the phone (English has none: it is built in and stays). */
    suspend fun delete(tag: String)

    /** The language [text] is written in, or null when the engine can't tell. Works without internet. */
    suspend fun detect(text: String): String?
}

/** Translates text between two languages, one text at a time. */
interface TextTranslator : Closeable {
    /**
     * [text] in the target language, one paragraph or less, line breaks kept as the engine keeps
     * them. Cancellable.
     * @throws TranslationUnavailableException when the language packs have gone since [TranslationEngine.ensureReady].
     */
    suspend fun translate(text: String): String
}
