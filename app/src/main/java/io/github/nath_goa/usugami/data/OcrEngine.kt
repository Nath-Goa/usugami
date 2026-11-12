package io.github.nath_goa.usugami.data

import android.graphics.Bitmap
import android.graphics.Point
import java.io.Closeable

/**
 * The languages text recognition offers, in the order the text-language menu lists them. Hindi mode
 * reads Devanagari and also keeps English words. English, French, Spanish, German, Portuguese and
 * Indonesian share one Latin-script recognizer (in the standard edition ML Kit's, so one download
 * covers all six; in Usugami OCR the built-in latin model).
 */
enum class OcrLanguage { ENGLISH, FRENCH, SPANISH, HINDI, GERMAN, PORTUGUESE, INDONESIAN }

/**
 * Why text recognition can't run on this phone right now. Only the standard edition, which gets
 * it from Google Play services, has the first four.
 */
enum class OcrProblem {
    NO_PLAY_SERVICES,
    PLAY_SERVICES_OUTDATED,
    DOWNLOAD_FAILED,
    NO_STORAGE,

    /** An unexpected error (most likely a bug), kept apart so it isn't mistaken for a network problem. */
    FAILED_TO_START,
}

class OcrUnavailableException(val problem: OcrProblem, cause: Throwable? = null) :
    Exception("Text recognition unavailable: $problem", cause)

/**
 * A text-recognition engine. Each edition provides `ocrEngine(context)` in its own source set: the
 * standard edition Google ML Kit through Google Play services, which downloads each language's
 * model the first time; Usugami OCR its built-in engine, which is always ready; Usugami Lite none
 * (null). [PdfOcr] turns what an engine reads into a PDF's text layer.
 */
interface OcrEngine {
    /**
     * Returns once [language] can be recognized, downloading its model first if needed.
     * [onDownload] is called only when a download is needed, with the fraction done (0..1), or
     * null while the size is unknown or the download is waiting. Cancellable.
     * @throws OcrUnavailableException when text recognition can't be made available.
     */
    suspend fun ensureReady(language: OcrLanguage, onDownload: (Float?) -> Unit = {})

    /** A recognizer for [language], to use once [ensureReady] has returned. Close it when done. */
    fun open(language: OcrLanguage): OcrRecognizer
}

/** Reads the text on page images in one language. One page at a time. */
interface OcrRecognizer : Closeable {
    /**
     * The lines of text on [page], a page image on a white background. The recognizer takes
     * [page] over and recycles it once it is done with it, also when it fails or is cancelled (an
     * engine may still be reading it then). Cancellable.
     * @throws OcrUnavailableException when the model has gone since [OcrEngine.ensureReady].
     */
    suspend fun recognize(page: Bitmap): List<OcrLine>
}

/**
 * A line of text on a page image. [corners] are in the image's pixels (y down): the text's top-left,
 * top-right, bottom-right and bottom-left corners as it reads, which also give its angle.
 */
data class OcrLine(val text: String, val corners: List<Point>) {
    init {
        require(corners.size == 4) { "A line has 4 corners, not ${corners.size}" }
    }
}
