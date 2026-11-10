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
import kotlinx.coroutines.CoroutineDispatcher
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Share or save as ZIP" on the result screen of a run that made several PDFs: one ZIP file of them,
 * with progress and cancelling. MainViewModel and PdfToolsViewModel each keep one for their results.
 * [dir] is where the ZIP for sharing is made, beside the PDFs; [resultCleared] is called when the
 * PDFs have gone from the cache. The ZIP file is written on [io], which a view model can share with
 * its other file work, so that freeing the PDFs waits for a ZIP file of them being saved. A view
 * model whose other work on the PDFs runs elsewhere gives the [turn] that work holds: the ZIP file
 * is then made of the PDFs before or after the Usugami mark changes them, never while it does.
 */
internal class ResultZipper(
    context: Context,
    dir: File,
    private val scope: CoroutineScope,
    private val strings: () -> Resources,
    private val message: (String) -> Unit,
    private val resultCleared: (count: Int) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val turn: Mutex? = null,
) {
    private val zip = ResultZip(context, dir)
    private var job: Job? = null
    /** Whether the ZIP file being made goes into a document the user picked, rather than being shared. */
    private var saving = false

    /** How far the ZIP file has got, while it is being made. */
    private val _progress = MutableStateFlow<ZipProgress?>(null)
    val progress: StateFlow<ZipProgress?> = _progress.asStateFlow()

    /** Whether a ZIP file is being made: another can't start until it is done. */
    val busy: Boolean get() = _progress.value != null

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

    /**
     * Writes a ZIP file of [files] into [target], a document made by the "Save as" screen (unless
     * [busy]). [finished] is called once that is over, whether it was saved or not.
     */
    fun saveTo(files: List<PdfFile>, target: Uri, onSaved: () -> Unit, finished: () -> Unit) {
        run(files, saving = true, work = { check -> zip.saveTo(files.map { it.file }, target, check) { _progress.value = it } }, finished = finished) {
            onSaved()
            message(strings().getString(R.string.msg_saved))
        }
    }

    /** Stops making the ZIP file; what was written of it is deleted. */
    fun cancel() {
        job?.cancel()
    }

    /** Stops making a ZIP file to share, which is no longer wanted. One being saved where the user chose goes on. */
    fun stopSharing() {
        if (!saving) job?.cancel()
    }

    /** Runs [work] in the background, then [done] with what it returns, or [cancelled] when cancelled; then [finished]. */
    private fun <T> run(
        files: List<PdfFile>,
        saving: Boolean = false,
        work: (check: () -> Unit) -> T,
        cancelled: () -> Unit = {},
        finished: () -> Unit = {},
        done: (T) -> Unit,
    ) {
        if (_progress.value != null || files.size < 2) return finished()
        _progress.value = ZipProgress(0, files.size, 0, 0)
        this.saving = saving
        job = scope.launch {
            val job = coroutineContext[Job]!!
            try {
                val made = suspend { withContext(io) { work { job.ensureActive() } } }
                done(if (turn != null) turn.withLock { made() } else made())
            } catch (e: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) { cancelled() }
                throw e
            } catch (_: Exception) {
                if (files.any { !it.file.isFile }) resultCleared(files.size)
                else message(strings().getString(R.string.zip_failed))
            } finally {
                _progress.value = null
                if (this@ResultZipper.job === job) {
                    this@ResultZipper.job = null
                    this@ResultZipper.saving = false
                }
                finished()
            }
        }
    }
}
