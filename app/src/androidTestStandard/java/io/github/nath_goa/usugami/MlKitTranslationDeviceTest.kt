package io.github.nath_goa.usugami

import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nath_goa.usugami.data.TranslationProblem
import io.github.nath_goa.usugami.data.TranslationUnavailableException
import io.github.nath_goa.usugami.data.translationEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Google ML Kit's translation itself (standard edition only), with the real language packs: the
 * first run downloads Spanish's (about 30 MB), so it needs internet, and is skipped without it.
 * Translate PDF's pipeline and screen are tested with a fake engine elsewhere.
 */
class MlKitTranslationDeviceTest {
    private val engine = translationEngine(InstrumentationRegistry.getInstrumentation().targetContext)!!

    /** [TranslationEngine.ensureReady], skipping the test when the pack can't be downloaded here. */
    private suspend fun ensureReadyOrSkip(source: String, target: String, onDownload: (Float?) -> Unit = {}) {
        try {
            engine.ensureReady(source, target, onDownload)
        } catch (e: TranslationUnavailableException) {
            assumeTrue("No language pack without internet (${e.problem})", e.problem != TranslationProblem.NO_INTERNET && e.problem != TranslationProblem.DOWNLOAD_FAILED)
            throw e
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

    /** ML Kit can't stop a download, but cancelling stops the wait for it at once. */
    @Test fun cancellingStopsWaitingForADownload() = runBlocking {
        if ("de" in engine.downloaded()) engine.delete("de")
        val downloading = CompletableDeferred<TranslationUnavailableException?>()
        val job = launch(Dispatchers.Default) {
            try {
                engine.ensureReady("en", "de") { downloading.complete(null) }
            } catch (e: TranslationUnavailableException) {
                downloading.complete(e)
            }
        }
        try {
            val problem = withTimeout(60_000) { downloading.await() }
            assumeTrue("No language pack without internet (${problem?.problem})", problem == null)
            val cancelled = SystemClock.elapsedRealtime()
            job.cancelAndJoin()
            val took = SystemClock.elapsedRealtime() - cancelled
            assertTrue("The wait should have been cancelled, not finished", job.isCancelled)
            assertTrue("Cancelling took $took ms", took < 2000)
        } finally {
            job.cancelAndJoin()
            // Also stops ML Kit's download. Not to hide why the test ended if it fails.
            runCatching { engine.delete("de") }.onFailure { Log.w(TAG, "Deleting German's pack failed", it) }
        }
    }

    private companion object {
        const val TAG = "UsugamiTest"
    }
}
