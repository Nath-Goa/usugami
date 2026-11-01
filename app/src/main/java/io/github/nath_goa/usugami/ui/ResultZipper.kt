package io.github.nath_goa.usugami.ui

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.core.ZipProgress
import io.github.nath_goa.usugami.data.PdfFile
import io.github.nath_goa.usugami.data.ResultZip
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Share or save as ZIP" on the result screen of a run that made several PDFs: one ZIP file of them,
 * with progress and cancelling. MainViewModel and PdfToolsViewModel each keep one for their results.
 * [dir] is where the ZIP for sharing is made, beside the PDFs; [resultCleared] is called when the
 * PDFs have gone from the cache.
 */
internal class ResultZipper(
    context: Context,
    dir: File,
    private val scope: CoroutineScope,
    private val strings: () -> Resources,
    private val message: (String) -> Unit,
    private val resultCleared: (count: Int) -> Unit,
) {
    private val zip = ResultZip(context, dir)
    private var job: Job? = null

    /** How far the ZIP file has got, while it is being made. */
    private val _progress = MutableStateFlow<ZipProgress?>(null)
    val progress: StateFlow<ZipProgress?> = _progress.asStateFlow()

    /** Share screens for finished ZIP files, for the activity to start while it is showing. */
    private val _shares = Channel<Intent>(Channel.BUFFERED)
    val shares: Flow<Intent> = _shares.receiveAsFlow()

    /** Makes a ZIP file of [files] named after the run ([name]), then asks to share it. */
    fun share(files: List<PdfFile>, name: String) {
        run(
            files,
            work = { check -> zip.make(files.map { it.file }, name, check) { _progress.value = it } },
            // Also when it was finished just as Cancel was pressed: it won't be shared.
            cancelled = zip::clear,
        ) { file ->
            _shares.trySend(zip.shareIntent(file))
        }
    }

    /** Writes a ZIP file of [files] into [target], a document made by the "Save as" screen. */
    fun saveTo(files: List<PdfFile>, target: Uri, onSaved: () -> Unit) {
        run(files, work = { check -> zip.saveTo(files.map { it.file }, target, check) { _progress.value = it } }) {
            onSaved()
            message(strings().getString(R.string.msg_saved))
        }
    }

    /** Stops making the ZIP file; what was written of it is deleted. */
    fun cancel() {
        job?.cancel()
    }

    /** Runs [work] in the background, then [done] with what it returns, or [cancelled] when cancelled. */
    private fun <T> run(files: List<PdfFile>, work: (check: () -> Unit) -> T, cancelled: () -> Unit = {}, done: (T) -> Unit) {
        if (_progress.value != null || files.size < 2) return
        _progress.value = ZipProgress(0, files.size, 0, 0)
        job = scope.launch {
            val job = coroutineContext[Job]!!
            try {
                done(withContext(Dispatchers.IO) { work { job.ensureActive() } })
            } catch (e: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) { cancelled() }
                throw e
            } catch (_: Exception) {
                if (files.any { !it.file.isFile }) resultCleared(files.size)
                else message(strings().getString(R.string.zip_failed))
            } finally {
                _progress.value = null
                if (this@ResultZipper.job === job) this@ResultZipper.job = null
            }
        }
    }
}
