package io.github.nath_goa.usugami.data

import android.app.DownloadManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.MlKitException
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ExecutionException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Translating with Google ML Kit, on the phone. Each language but English has a pack (a download of
 * 35 to 50 MB, about 45 MB on the phone) that ML Kit downloads once, through Android's download
 * manager (which shows its own notification, "en_fr.zip", while it runs), and keeps until it is
 * deleted. English is built into the library: it is always among [downloaded], never downloaded,
 * and [delete] of it does nothing (ML Kit answers that it worked and keeps it). A pair without
 * English needs both its packs: ML Kit translates through English.
 *
 * Making the engine is cheap and touches nothing; ML Kit is only used by the methods that need it.
 * Apart from [TextTranslator.translate] (see [MlKitTextTranslator]), every method throws only
 * [TranslationUnavailableException] (and CancellationException): [TranslationProblem.FAILED_TO_START]
 * for anything that isn't a download problem.
 */
internal class MlKitTranslationEngine(private val context: Context) : TranslationEngine {
    override val languages: List<String> get() = TranslateLanguage.getAllLanguages()

    override suspend fun downloaded(): Set<String> = unavailableUnless {
        RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java).await()
            .mapTo(HashSet()) { it.language }
    }

    /**
     * Downloads the packs of [source] and [target] that aren't on the phone yet, any kind of
     * network (the screen asks the user first). When they are all there, ML Kit's download, which
     * would also look online for newer packs, isn't called. [onDownload] gets the fraction of the
     * bytes downloaded, which Android's download manager tells (ML Kit itself gives no progress),
     * and null until every pack's size is known (the first few seconds) and while the phone is
     * offline. A download that is getting somewhere has no time limit (a slow connection may take
     * many minutes) but for a ceiling of [DOWNLOAD_LIMIT]; it fails with
     * [TranslationProblem.NO_INTERNET] at once when the phone is offline, and when it stays offline
     * for [OFFLINE_LIMIT] during the download, and with [TranslationProblem.DOWNLOAD_FAILED] when,
     * online, it gets nowhere for [STALL_LIMIT] (Google's servers can't be reached; the download
     * manager would go on retrying for about a quarter of an hour). When it fails, or the coroutine
     * is cancelled, the packs it was downloading that haven't arrived are deleted, which also
     * stops their downloads in the download manager: nothing goes on downloading once the user has
     * been told it stopped, and the next call starts those packs again from the beginning. A pack
     * that arrived stays.
     */
    override suspend fun ensureReady(source: String, target: String, onDownload: (Float?) -> Unit) {
        try {
            val manager = RemoteModelManager.getInstance()
            val missing = packs(source, target).filterNot { manager.isModelDownloaded(it).await() }
            if (missing.isEmpty()) return
            // Offline, the download manager would wait for a network without a word.
            if (!isOnline()) throw TranslationUnavailableException(TranslationProblem.NO_INTERNET)
            onDownload(null)
            try {
                download(manager, missing, onDownload)
            } catch (t: Throwable) {
                removeUnfinished(manager, missing)
                throw t
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TranslationUnavailableException) {
            throw e
        } catch (e: MlKitException) {
            throw TranslationUnavailableException(problemFor(e.errorCode), e)
        } catch (e: OcrUnavailableException) {
            // Task.await's word for a task that ended as cancelled: ML Kit cancels a pack's download
            // when the pack is deleted meanwhile.
            throw TranslationUnavailableException(TranslationProblem.DOWNLOAD_FAILED, e)
        } catch (e: Exception) {
            // Not ML Kit's word on a download: e.g. ML Kit failing to start, as it would if R8
            // removed its component registrars.
            throw TranslationUnavailableException(TranslationProblem.FAILED_TO_START, e)
        }
    }

    private suspend fun download(manager: RemoteModelManager, missing: List<TranslateRemoteModel>, onDownload: (Float?) -> Unit) =
        // A supervisor scope, so that a failed download comes out of waiting.await() as it is.
        supervisorScope {
            val conditions = DownloadConditions.Builder().build()
            val download = Tasks.whenAll(missing.map { manager.download(it, conditions) })
            val progress = DownloadProgress(context, missing.size)
            // One wait for the whole download; the loop below looks at it every POLL. Tasks.whenAll
            // reports a pack's failed download as an ExecutionException around ML Kit's own.
            val waiting = async {
                try {
                    download.await()
                } catch (e: ExecutionException) {
                    throw e.cause as? Exception ?: e
                }
            }
            try {
                val started = TimeSource.Monotonic.markNow()
                var offlineSince: TimeSource.Monotonic.ValueTimeMark? = null
                var lastBytes: Long? = null
                var movedAt = TimeSource.Monotonic.markNow()
                while (withTimeoutOrNull(POLL) { waiting.await(); true } == null) {
                    if (started.elapsedNow() >= DOWNLOAD_LIMIT) throw TranslationUnavailableException(TranslationProblem.DOWNLOAD_FAILED)
                    if (isOnline()) {
                        offlineSince = null
                        progress.update()
                        // A download the download manager can't tell about isn't taken for stuck.
                        val bytes = progress.bytes
                        if (bytes == null || bytes != lastBytes) {
                            lastBytes = bytes
                            movedAt = TimeSource.Monotonic.markNow()
                        } else if (movedAt.elapsedNow() >= STALL_LIMIT) {
                            throw TranslationUnavailableException(TranslationProblem.DOWNLOAD_FAILED)
                        }
                        onDownload(progress.fraction)
                    } else {
                        movedAt = TimeSource.Monotonic.markNow() // time offline counts for OFFLINE_LIMIT instead
                        val since = offlineSince ?: TimeSource.Monotonic.markNow().also { offlineSince = it }
                        if (since.elapsedNow() >= OFFLINE_LIMIT) throw TranslationUnavailableException(TranslationProblem.NO_INTERNET)
                        onDownload(null)
                    }
                }
            } finally {
                waiting.cancel()
            }
        }

    /**
     * Deletes those of [packs] that haven't arrived. ML Kit can't cancel a download, but deleting its
     * pack removes it from the download manager, which would otherwise go on with it, on any network,
     * also with the app closed.
     */
    private suspend fun removeUnfinished(manager: RemoteModelManager, packs: List<TranslateRemoteModel>) {
        // Above all when cancelled. A delete takes about a tenth of a second; the limit is for ML Kit hanging.
        withContext(NonCancellable) {
            withTimeoutOrNull(CLEANUP_LIMIT) {
                for (pack in packs) {
                    try {
                        if (!manager.isModelDownloaded(pack).await()) manager.deleteDownloadedModel(pack).await()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Nothing more to do for this pack; the other is still deleted.
                    }
                }
            }
        }
    }

    /** Whether the phone has a network that reaches the internet (connected; not necessarily validated). */
    private fun isOnline(): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun open(source: String, target: String): TextTranslator {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(tagOf(source))
            .setTargetLanguage(tagOf(target))
            .build()
        return MlKitTextTranslator(Translation.getClient(options))
    }

    /** Deletes [tag]'s pack; for English, which is built in, ML Kit does nothing and says it worked. */
    override suspend fun delete(tag: String) = unavailableUnless {
        RemoteModelManager.getInstance().deleteDownloadedModel(pack(tag)).await()
        Unit
    }

    /**
     * ML Kit's language identification (built in: no download, no internet), when it is at least
     * 50 % sure, as the tag of a language ML Kit translates (its "fil" is Tagalog, "tl"); null when
     * it isn't sure or the language isn't one of [languages] (as Hindi written in Latin letters,
     * "hi-Latn").
     */
    override suspend fun detect(text: String): String? = unavailableUnless {
        if (text.isBlank()) return@unavailableUnless null
        val identifier = LanguageIdentification.getClient()
        try {
            val tag = identifier.identifyLanguage(text).await()
            // Language identification calls Tagalog "fil", after Filipino; translation calls it "tl".
            if (tag == LanguageIdentifier.UNDETERMINED_LANGUAGE_TAG) null
            else TranslateLanguage.fromLanguageTag(if (tag == "fil") TranslateLanguage.TAGALOG else tag)
        } finally {
            identifier.close()
        }
    }

    /** [block], with anything it throws but cancelling turned into [TranslationProblem.FAILED_TO_START]. */
    private suspend fun <T> unavailableUnless(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: TranslationUnavailableException) {
        throw e
    } catch (e: Exception) {
        throw TranslationUnavailableException(TranslationProblem.FAILED_TO_START, e)
    }
}

/**
 * One translation direction. [translate] throws ML Kit's own exception when it fails on this one
 * text, so that only that paragraph is left as it was, and [TranslationUnavailableException] when
 * the packs it has to load are gone from the phone. (ML Kit keeps the packs it has loaded in memory
 * until the app ends, so a pack deleted after its first use still translates.) ML Kit can't stop a
 * translation that has begun: cancelling stops the wait, and closing the translator drops what is
 * still to come.
 */
private class MlKitTextTranslator(private val translator: Translator) : TextTranslator {
    override suspend fun translate(text: String): String = try {
        translator.translate(text).await()
    } catch (e: MlKitException) {
        // "Translation model files not found": the packs were deleted since ensureReady.
        if (e.errorCode == MlKitException.NOT_FOUND) throw TranslationUnavailableException(TranslationProblem.DOWNLOAD_FAILED, e)
        throw e
    } catch (e: OcrUnavailableException) {
        // Task.await's word for a task that ended as cancelled, which ML Kit does to the
        // translations still waiting when the translator is closed.
        throw TranslationUnavailableException(TranslationProblem.FAILED_TO_START, e)
    }

    override fun close() = translator.close()
}

/**
 * How far ML Kit's downloads of language packs are. ML Kit tells nothing about it, but they are
 * downloads of this app in Android's download manager, which lists an app's own downloads to it
 * (Usugami has no others: text recognition's models are downloaded by Play services).
 */
private class DownloadProgress(context: Context, private val packs: Int) {
    private val downloads = context.getSystemService(DownloadManager::class.java)

    /** The size of each download seen pending or running since this was made, or -1 while it isn't known. */
    private val sizes = HashMap<Long, Long>()

    /** The bytes downloaded, as of the last [update]; null when the download manager couldn't be asked. */
    var bytes: Long? = null
        private set

    /**
     * The fraction of the bytes downloaded, as of the last [update]; null while there are fewer
     * downloads than [packs] or a size is unknown.
     */
    var fraction: Float? = null
        private set

    /** Asks the download manager how far the downloads are. */
    fun update() {
        bytes = null
        fraction = null
        val manager = downloads ?: return
        var done = 0L
        val listed = HashSet<Long>()
        try {
            val rows = manager.query(DownloadManager.Query()) ?: return
            rows.use {
                val idColumn = rows.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
                val statusColumn = rows.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                val doneColumn = rows.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalColumn = rows.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                while (rows.moveToNext()) {
                    val id = rows.getLong(idColumn)
                    val status = rows.getInt(statusColumn)
                    val active = status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_RUNNING ||
                        status == DownloadManager.STATUS_PAUSED
                    // Downloads that ended before this run are not part of it.
                    if (!active && id !in sizes) continue
                    val total = rows.getLong(totalColumn)
                    sizes[id] = total
                    listed += id
                    done += if (status == DownloadManager.STATUS_SUCCESSFUL && total > 0) total else rows.getLong(doneColumn)
                }
            }
        } catch (_: RuntimeException) {
            return // the download manager's provider failing is no reason to stop the download
        }
        // ML Kit removes a finished download from the list once it has the pack.
        done += sizes.filterKeys { it !in listed }.values.sumOf { it.coerceAtLeast(0) }
        bytes = done
        if (sizes.size >= packs && sizes.values.all { it > 0 }) {
            fraction = (done.toDouble() / sizes.values.sum()).toFloat().coerceIn(0f, 1f)
        }
    }
}

/** The packs a translation from [source] to [target] needs: those of the two languages, English's excepted. */
private fun packs(source: String, target: String): List<TranslateRemoteModel> =
    listOf(tagOf(source), tagOf(target)).distinct().filter { it != TranslateLanguage.ENGLISH }.map(::pack)

private fun pack(tag: String): TranslateRemoteModel = TranslateRemoteModel.Builder(tagOf(tag)).build()

/** [tag] as ML Kit names it ("iw" is "he"); IllegalArgumentException for a language ML Kit doesn't translate. */
private fun tagOf(tag: String): String =
    requireNotNull(TranslateLanguage.fromLanguageTag(tag)) { "ML Kit doesn't translate \"$tag\"" }

/**
 * [code] is an [MlKitException] code. Codes for a mistake in how ML Kit is called are kept apart
 * from download problems.
 */
private fun problemFor(code: Int): TranslationProblem = when (code) {
    MlKitException.NOT_ENOUGH_SPACE -> TranslationProblem.NO_STORAGE
    MlKitException.NETWORK_ISSUE -> TranslationProblem.NO_INTERNET
    MlKitException.INVALID_ARGUMENT, MlKitException.FAILED_PRECONDITION, MlKitException.UNIMPLEMENTED, MlKitException.UNSUPPORTED ->
        TranslationProblem.FAILED_TO_START
    else -> TranslationProblem.DOWNLOAD_FAILED
}

/** How often a download's progress and the network are looked at. */
private val POLL = 500.milliseconds

/** How long the phone may be offline during a download before it counts as having no internet. */
private val OFFLINE_LIMIT = 60.seconds

/** How long a download may get nowhere, the phone being online, before it counts as failed. */
private val STALL_LIMIT = 2.minutes

/** A download not finished after this long has failed, however it was going. */
private val DOWNLOAD_LIMIT = 30.minutes

/** How long deleting the packs of a download that failed or was cancelled may take at most. */
private val CLEANUP_LIMIT = 10.seconds
