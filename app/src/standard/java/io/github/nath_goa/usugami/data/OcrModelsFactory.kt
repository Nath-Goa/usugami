package io.github.nath_goa.usugami.data

import android.content.Context
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
import com.google.mlkit.vision.text.TextRecognizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Standard edition: text recognition runs in Google Play services, which downloads each language's
 * model the first time it is used and keeps it for every app. Usugami itself needs no permission for it.
 */
fun ocrModels(context: Context): OcrModels = PlayServicesOcrModels(context.applicationContext)

private class PlayServicesOcrModels(private val context: Context) : OcrModels {
    private val installer = ModuleInstall.getClient(context)

    override suspend fun ensureReady(language: OcrLanguage, onDownload: (Float?) -> Unit) {
        checkPlayServices()
        try {
            PdfOcr.newRecognizer(language).use { recognizer ->
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
            throw OcrUnavailableException(OcrProblem.DOWNLOAD_FAILED, e)
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
            // If the user cancels, only this wait stops: Play services finishes the download in
            // the background, so the next attempt is quick.
            for (update in updates) {
                when (update.installState) {
                    InstallState.STATE_COMPLETED -> return
                    InstallState.STATE_FAILED, InstallState.STATE_CANCELED ->
                        throw OcrUnavailableException(problemFor(update.errorCode))
                    else -> {
                        val progress = update.progressInfo
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

/** Waits for a Play services task (what kotlinx-coroutines-play-services does, without the dependency). */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener(Runnable::run) { task ->
        val error = task.exception
        when {
            error != null -> continuation.resumeWithException(error)
            task.isCanceled -> continuation.cancel()
            else -> continuation.resume(task.result)
        }
    }
}
