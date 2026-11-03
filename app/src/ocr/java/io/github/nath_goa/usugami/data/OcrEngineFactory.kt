package io.github.nath_goa.usugami.data

import android.content.Context
import io.github.nath_goa.usugami.ocr.NcnnNet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Usugami OCR: text recognition built into the app. PaddleOCR's PP-OCRv5 models, run on the phone's
 * CPU by Tencent's ncnn (built from source in :ocr-native), are part of the APK, so there is never
 * anything to download and nothing leaves the phone.
 */
fun ocrEngine(context: Context): OcrEngine? = BuiltInOcrEngine(context.applicationContext)

private class BuiltInOcrEngine(private val context: Context) : OcrEngine {
    /** Always ready; this only checks that the engine and the language's models are in this build. */
    override suspend fun ensureReady(language: OcrLanguage, onDownload: (Float?) -> Unit) {
        withContext(Dispatchers.IO) {
            try {
                NcnnNet.loadLibrary()
                for (file in PaddleModels.files(language)) context.assets.open(file).close()
            } catch (e: IOException) {
                throw OcrUnavailableException(OcrProblem.FAILED_TO_START, e)
            } catch (e: LinkageError) {
                // libusugami_ocr.so missing or not loadable on this phone.
                throw OcrUnavailableException(OcrProblem.FAILED_TO_START, e)
            }
        }
    }

    override fun open(language: OcrLanguage): OcrRecognizer = PaddleOcrRecognizer(context.assets, language)
}
