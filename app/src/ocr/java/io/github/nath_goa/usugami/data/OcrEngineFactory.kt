package io.github.nath_goa.usugami.data

import android.content.Context

/**
 * Usugami OCR: open-source text recognition built into the app, so there is never anything to
 * download. This build doesn't have the engine yet, so text recognition reports that it can't start.
 */
fun ocrEngine(@Suppress("unused") context: Context): OcrEngine? = NotYetAvailableOcrEngine

private object NotYetAvailableOcrEngine : OcrEngine {
    override suspend fun ensureReady(language: OcrLanguage, onDownload: (Float?) -> Unit): Unit = throw notYetAvailable()

    override fun open(language: OcrLanguage): OcrRecognizer = throw notYetAvailable()

    private fun notYetAvailable() = OcrUnavailableException(
        OcrProblem.FAILED_TO_START,
        UnsupportedOperationException("The built-in text recognition engine is not in this build yet"),
    )
}
