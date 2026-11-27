package io.github.nath_goa.usugami

import android.app.DownloadManager
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mlkit.common.MlKitException
import io.github.nath_goa.usugami.data.TranslationProblem
import io.github.nath_goa.usugami.data.TranslationUnavailableException
import io.github.nath_goa.usugami.data.problemOf
import io.github.nath_goa.usugami.data.translationEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.IOException

/**
 * Google ML Kit's translation itself (standard edition only), with the real language packs: the
 * first run downloads Spanish's (about 30 MB), so it needs internet, and is skipped without it.
 * Translate PDF's pipeline and screen are tested with a fake engine elsewhere.
 */
class MlKitTranslationDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val engine = translationEngine(context)!!
    private val downloads = context.getSystemService(DownloadManager::class.java)

    /** [TranslationEngine.ensureReady], skipping the test when the pack can't be downloaded here. */
    private suspend fun ensureReadyOrSkip(source: String, target: String, onDownload: (Float?) -> Unit = {}) {
        try {
            engine.ensureReady(source, target, onDownload)
        } catch (e: TranslationUnavailableException) {
            assumeTrue("No language pack without internet (${e.problem})", e.problem != TranslationProblem.NO_INTERNET && e.problem != TranslationProblem.DOWNLOAD_FAILED)
            throw e
        }
    }

    /** The addresses of the app's downloads that Android's download manager is still at. */
    private fun unfinishedDownloads(): List<String> {
        val unfinished = DownloadManager.Query()
            .setFilterByStatus(DownloadManager.STATUS_PENDING or DownloadManager.STATUS_RUNNING or DownloadManager.STATUS_PAUSED)
        return downloads.query(unfinished).use { rows ->
            val uri = rows.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)
            buildList { while (rows.moveToNext()) add(rows.getString(uri)) }
        }
    }

    /** A pack's life: downloaded once, used to translate, listed, deleted. English is built in. */
    @Test fun spanishPackDownloadsTranslatesAndIsDeleted() = runBlocking {
        if ("es" in engine.downloaded()) engine.delete("es")
        val started = SystemClock.elapsedRealtime()
        val progress = ArrayList<Float?>()
        ensureReadyOrSkip("en", "es") { fraction ->
            if (progress.lastOrNull() != fraction) Log.i(TAG, "Downloading Spanish: $fraction after ${SystemClock.elapsedRealtime() - started} ms")
            progress += fraction
        }
        Log.i(TAG, "Spanish's pack downloaded in ${SystemClock.elapsedRealtime() - started} ms; progress ${progress.distinct()}")
        assertTrue("The download was reported", progress.isNotEmpty())
        assertEquals("It starts as unknown", null, progress.first())

        val spanish = engine.open("en", "es").use { it.translate("Good morning") }
        Log.i(TAG, "Good morning: $spanish")
        assertTrue("\"$spanish\" isn't Spanish", spanish.contains("buenos", ignoreCase = true))
        assertTrue(engine.downloaded().containsAll(setOf("en", "es")))

        // Once there, a pack needs no download.
        var downloading = false
        engine.ensureReady("en", "es") { downloading = true }
        assertFalse(downloading)

        engine.delete("en")
        assertTrue("English is built in", "en" in engine.downloaded())
        engine.delete("es")
        assertFalse("es" in engine.downloaded())
    }

    @Test fun detectsFrench() = runBlocking {
        assertEquals("fr", engine.detect("Bonjour tout le monde, comment allez-vous ?"))
    }

    /** Language identification calls Tagalog "fil", and ML Kit's translation "tl". */
    @Test fun detectsTagalog() = runBlocking {
        assertEquals("tl", engine.detect("Magandang umaga sa inyong lahat. Kumusta kayo ngayong araw? Salamat po sa inyong pagdalo sa aming pulong."))
    }

    /** ML Kit's translation reports a full storage as an internal error, which is still told apart. */
    @Test fun aFullStorageIsToldApartFromOtherDownloadFailures() {
        val downloadManagerFull = MlKitException("Downloading error ${DownloadManager.ERROR_INSUFFICIENT_SPACE}", MlKitException.INTERNAL)
        assertEquals(TranslationProblem.NO_STORAGE, problemOf(downloadManagerFull))
        val unzipFull = MlKitException("Could not unzip translate model file", MlKitException.INTERNAL, IOException("write failed: ENOSPC (No space left on device)"))
        assertEquals(TranslationProblem.NO_STORAGE, problemOf(unzipFull))
        val other = MlKitException("Downloading error ${DownloadManager.ERROR_HTTP_DATA_ERROR}", MlKitException.INTERNAL)
        assertEquals(TranslationProblem.DOWNLOAD_FAILED, problemOf(other))
    }

    /**
     * Cancelling stops the wait for a download at once, and the download itself: Android's download
     * manager has nothing of it left and the pack isn't there. The next try downloads it again.
     */
    @Test fun cancellingStopsADownload() = runBlocking {
        if ("de" in engine.downloaded()) engine.delete("de")
        try {
            repeat(2) { attempt ->
                val going = CompletableDeferred<Float>()
                val job = launch(Dispatchers.Default) {
                    try {
                        engine.ensureReady("en", "de") { fraction -> if (fraction != null) going.complete(fraction) }
                    } catch (e: TranslationUnavailableException) {
                        going.completeExceptionally(e)
                    }
                }
                val first = try {
                    withTimeout(120_000) { going.await() }
                } catch (e: TranslationUnavailableException) {
                    assumeTrue("No language pack without internet (${e.problem})", false)
                    throw e
                }
                Log.i(TAG, "German's download ${attempt + 1} going: $first; downloads ${unfinishedDownloads()}")
                val cancelled = SystemClock.elapsedRealtime()
                job.cancelAndJoin()
                val took = SystemClock.elapsedRealtime() - cancelled
                assertTrue("The wait should have been cancelled, not finished", job.isCancelled)
                assertTrue("Cancelling took $took ms", took < 2000)
                assertFalse("de" in engine.downloaded())
                // Watched for a while, as ML Kit does its work on a download a moment after it is asked.
                repeat(10) {
                    assertEquals(emptyList<String>(), unfinishedDownloads())
                    delay(500)
                }
            }
        } finally {
            // Not to hide why the test ended if it fails.
            runCatching { engine.delete("de") }.onFailure { Log.w(TAG, "Deleting German's pack failed", it) }
        }
    }

    /**
     * A download's progress is its own packs': another download of the app (here one that gets
     * nowhere, as a pack's left from an earlier run might) doesn't count, and the fraction never
     * goes back.
     */
    @Test fun downloadProgressCountsOnlyItsOwnPacks() = runBlocking {
        if ("de" in engine.downloaded()) engine.delete("de")
        // Nothing answers on port 9, so the download manager keeps it waiting to try again.
        val other = downloads.enqueue(DownloadManager.Request(Uri.parse("https://127.0.0.1:9/en_fr.zip")))
        try {
            val progress = ArrayList<Float>()
            ensureReadyOrSkip("en", "de") { fraction -> if (fraction != null && progress.lastOrNull() != fraction) progress += fraction }
            Log.i(TAG, "German's progress beside another download: $progress")
            assertTrue("A fraction was told", progress.isNotEmpty())
            assertTrue("It starts near 0, not at ${progress.first()}", progress.first() < 0.5f)
            assertEquals("It never goes back", progress.sorted(), progress)
        } finally {
            downloads.remove(other)
            runCatching { engine.delete("de") }.onFailure { Log.w(TAG, "Deleting German's pack failed", it) }
        }
    }

    private companion object {
        const val TAG = "UsugamiTest"
    }
}
