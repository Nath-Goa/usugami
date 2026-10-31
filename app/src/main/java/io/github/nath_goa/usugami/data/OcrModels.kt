package io.github.nath_goa.usugami.data

/** Why text recognition can't run on this phone right now. */
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
 * Makes sure text recognition can run. The offline edition has the models in the APK, so it is
 * always ready; the standard edition asks Google Play services to download them the first time.
 * Each edition provides `ocrModels(context)` in its own source set.
 */
interface OcrModels {
    /**
     * Returns once [language] can be recognized, downloading its model first if needed.
     * [onDownload] is called only when a download is needed, with the fraction done (0..1), or
     * null while the size is unknown or the download is waiting. Cancellable.
     * @throws OcrUnavailableException when text recognition can't be made available.
     */
    suspend fun ensureReady(language: OcrLanguage, onDownload: (Float?) -> Unit)
}
