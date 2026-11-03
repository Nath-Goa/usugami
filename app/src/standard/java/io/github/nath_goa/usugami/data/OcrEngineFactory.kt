package io.github.nath_goa.usugami.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleAvailabilityResponse.AvailabilityStatus
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusCodes
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Standard edition: text recognition with Google ML Kit, which runs in Google Play services. Play
 * services downloads each language's model the first time it is used and keeps it for every app.
 * Usugami itself needs no permission for it.
 */
fun ocrEngine(context: Context): OcrEngine? = PlayServicesOcrEngine(context.applicationContext)

private class PlayServicesOcrEngine(private val context: Context) : OcrEngine {
    private val installer = ModuleInstall.getClient(context)

    override fun open(language: OcrLanguage): OcrRecognizer = MlKitRecognizer(newRecognizer(language))

    override suspend fun ensureReady(language: OcrLanguage, onDownload: (Float?) -> Unit) {
        checkPlayServices()
        try {
            newRecognizer(language).use { recognizer ->
                val availability = installer.areModulesAvailable(recognizer).await()
                when {
                    availability.areModulesAvailable() -> Unit
                    availability.availabilityStatus == AvailabilityStatus.STATUS_UNKNOWN_MODULE ->
                        throw OcrUnavailableException(OcrProblem.PLAY_SERVICES_OUTDATED)
                    else -> download(recognizer, onDownload)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: OcrUnavailableException) {
            throw e
        } catch (e: ApiException) {
            throw OcrUnavailableException(problemFor(e.statusCode), e)
        } catch (e: Exception) {
            // Not a status from Play services, so not a download problem: e.g. ML Kit itself failing
            // to start, as it did when R8 removed its component registrars.
            throw OcrUnavailableException(OcrProblem.FAILED_TO_START, e)
        }
    }

    private suspend fun download(recognizer: TextRecognizer, onDownload: (Float?) -> Unit) {
        onDownload(null)
        // Updates arrive on another thread; passing them through a channel means onDownload is
        // only ever called from this coroutine, and never after it returns.
        val updates = Channel<ModuleInstallStatusUpdate>(Channel.UNLIMITED)
        val listener = InstallStatusListener { updates.trySend(it) }
        val request = ModuleInstallRequest.newBuilder().addApi(recognizer).setListener(listener).build()
        try {
            if (installer.installModules(request).await().areModulesAlreadyInstalled()) return
            // Without internet, Play services keeps the request waiting and sends nothing, so a
            // download that makes no progress for a while is reported as failed. Then, as when the
            // user cancels, only this wait stops: Play services carries on in the background, so
            // the next attempt is quick.
            var lastProgress = TimeSource.Monotonic.markNow()
            var last: Pair<Int, Long?>? = null
            while (true) {
                val update = withTimeoutOrNull(STALL_TIMEOUT - lastProgress.elapsedNow()) { updates.receive() }
                    ?: throw OcrUnavailableException(OcrProblem.DOWNLOAD_FAILED)
                when (update.installState) {
                    InstallState.STATE_COMPLETED -> return
                    InstallState.STATE_FAILED, InstallState.STATE_CANCELED ->
                        throw OcrUnavailableException(problemFor(update.errorCode))
                    else -> {
                        val progress = update.progressInfo
                        // A new state or more bytes count as progress; repeats of the same update don't.
                        val now = update.installState to progress?.bytesDownloaded
                        if (now != last) {
                            last = now
                            lastProgress = TimeSource.Monotonic.markNow()
                        }
                        val total = progress?.totalBytesToDownload ?: 0L
                        onDownload(if (progress != null && total > 0) progress.bytesDownloaded.toFloat() / total else null)
                    }
                }
            }
        } finally {
            updates.close()
            installer.unregisterListener(listener)
        }
    }

    private fun checkPlayServices() {
        when (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)) {
            ConnectionResult.SUCCESS -> Unit
            ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED, ConnectionResult.SERVICE_UPDATING ->
                throw OcrUnavailableException(OcrProblem.PLAY_SERVICES_OUTDATED)
            else -> throw OcrUnavailableException(OcrProblem.NO_PLAY_SERVICES)
        }
    }

    /**
     * [code] is a [ModuleInstallStatusCodes] (or [CommonStatusCodes]) value. A missing, disabled or
     * old Play services app is caught earlier, by [checkPlayServices].
     */
    private fun problemFor(code: Int): OcrProblem = when (code) {
        ModuleInstallStatusCodes.INSUFFICIENT_STORAGE -> OcrProblem.NO_STORAGE
        CommonStatusCodes.API_NOT_CONNECTED, ModuleInstallStatusCodes.UNKNOWN_MODULE, ModuleInstallStatusCodes.MODULE_NOT_FOUND ->
            OcrProblem.PLAY_SERVICES_OUTDATED
        else -> OcrProblem.DOWNLOAD_FAILED
    }
}

/** How long a download may go without any progress before it counts as failed (usually: no internet). */
private val STALL_TIMEOUT = 60.seconds

/** ML Kit's recognizer for [language]; the download in [PlayServicesOcrEngine.ensureReady] is for the same one. */
private fun newRecognizer(language: OcrLanguage): TextRecognizer = when (language) {
    OcrLanguage.ENGLISH, OcrLanguage.FRENCH, OcrLanguage.SPANISH -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    OcrLanguage.HINDI -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
}

private class MlKitRecognizer(private val recognizer: TextRecognizer) : OcrRecognizer {
    override suspend fun recognize(page: Bitmap): List<OcrLine> {
        var recognition: Task<Text>? = null
        try {
            recognition = recognizer.process(InputImage.fromBitmap(page, 0))
            val text = try {
                recognition.await()
            } catch (e: MlKitException) {
                // Play services no longer has the model (removed since ensureReady checked).
                if (e.errorCode == MlKitException.UNAVAILABLE) throw OcrUnavailableException(OcrProblem.DOWNLOAD_FAILED, e)
                throw e
            }
            return text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                val corners = line.cornerPoints?.takeIf { it.size == 4 }?.toList()
                    ?: listOf(Point(box.left, box.top), Point(box.right, box.top), Point(box.right, box.bottom), Point(box.left, box.bottom))
                OcrLine(line.text, corners)
            }
        } finally {
            // After a cancel, ML Kit may still be reading the bitmap; it is freed with it then.
            if (recognition == null || recognition.isComplete) page.recycle()
        }
    }

    override fun close() = recognizer.close()
}
