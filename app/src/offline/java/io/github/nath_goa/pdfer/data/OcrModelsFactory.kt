package io.github.nath_goa.pdfer.data

import android.content.Context

/** Offline edition: the recognition models are inside the APK, so there is never anything to download. */
fun ocrModels(@Suppress("unused") context: Context): OcrModels = BundledOcrModels

private object BundledOcrModels : OcrModels {
    override suspend fun ensureReady(language: OcrLanguage, onDownload: (Float?) -> Unit) = Unit
}
