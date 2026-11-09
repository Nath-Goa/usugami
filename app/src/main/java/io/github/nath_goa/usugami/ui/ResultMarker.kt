package io.github.nath_goa.usugami.ui

import android.content.Context
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.SavedStateHandle
import io.github.nath_goa.usugami.core.MarkStyle
import io.github.nath_goa.usugami.data.MarkSetting
import io.github.nath_goa.usugami.data.PageMark
import io.github.nath_goa.usugami.data.PdfFile
import io.github.nath_goa.usugami.data.PdfInput
import io.github.nath_goa.usugami.data.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The first page of a result as it was made, without the mark, and its size as shown, in points: the
 * page the result screen draws the mark on while its size and opacity are moved. [page] is null when it
 * can't be drawn without a mark on it: a plain page of that size stands in.
 */
class MarkPreview(val page: ImageBitmap?, val width: Float, val height: Float)

/**
 * The Usugami mark on a result's PDFs (see [PageMark]): put on them as they are made, if it is on, and
 * put on, changed or taken off on the PDFs showing whenever the setting changes, on this result screen
 * or another's. MainViewModel and PdfToolsViewModel each keep one for their results.
 *
 * [result] is the view model's result, each PDF knowing the mark it carries ([PdfFile.mark]), also
 * when shown again after Android closed Usugami (see [KeptResult]). The PDFs are changed with the view
 * model's file work, [io], one thing at a time, after a save of them under way (holding [turn] too,
 * when given) and before a cleanup that deletes them. [changed] is given the result once a change has
 * rewritten its PDFs: the copies saved before are of the PDFs as they were. [failed] is called when a
 * PDF couldn't be changed.
 */
internal class ResultMarker(
    context: Context,
    private val handle: SavedStateHandle,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val result: StateFlow<List<PdfFile>?>,
    private val changed: (List<PdfFile>) -> Unit,
    private val failed: () -> Unit,
    private val turn: Mutex? = null,
) {
    private val marks = PageMark(context)
    private val settings = SettingsStore(context)

    /** Whether the mark is on, and its size and opacity: one setting for the whole app. */
    val setting: StateFlow<MarkSetting> = settings.mark

    private val _working = MutableStateFlow(false)
    /** The result's PDFs are being changed to the setting, or about to be. */
    val working: StateFlow<Boolean> = _working.asStateFlow()

    private val _preview = MutableStateFlow<MarkPreview?>(null)
    /** The result's first page, for the result screen to show the mark on. */
    val preview: StateFlow<MarkPreview?> = _preview.asStateFlow()

    private var job: Job? = null

    /** Set once [job] has started changing the PDFs. */
    @Volatile private var started = false

    /**
     * Whether the PDFs may carry another mark than they say: a change was stopped part way, or Android
     * closed Usugami in the middle of one (kept in the saved state while one is under way). Each is
     * then looked at.
     */
    @Volatile private var uncertain = handle.remove<Boolean>(KEY_UNDER_WAY) == true

    init {
        // Also at once, for a result shown again after Android closed Usugami.
        scope.launch { setting.collect { follow(it.wanted) } }
    }

    /** Puts the mark on, or takes it off, for every PDF made from now on and the result showing. */
    fun setOn(on: Boolean) = settings.setMark(setting.value.copy(on = on))

    /** Gives the mark [style], kept while it is off. */
    fun setStyle(style: MarkStyle) = settings.setMark(setting.value.copy(style = style))

    /** The result has been put away, and its PDFs are deleted next: they aren't changed any more. */
    fun stop() {
        job?.cancel()
        job = null
        uncertain = false
        _working.value = false
        handle.remove<Boolean>(KEY_UNDER_WAY)
    }

    /**
     * Puts the mark on [files], PDFs just made, if it is on, before they show, keeping their first page
     * as it was made for the preview first. Blocking: call it in the run's background work, which
     * [check] stops. Returns them, each with the mark it carries; one that can't be marked is left as it
     * is, and [failed] is called. They are made without the mark (the tools take it off the PDFs they
     * read, in memory), so with it off there is nothing to do.
     */
    fun markMade(files: List<PdfFile>, check: () -> Unit): List<PdfFile> {
        uncertain = false
        _preview.value = files.firstOrNull()?.let { render(it, withPage = true) }
        val wanted = setting.value.wanted ?: return files
        val (marked, failures) = bringTo(files, wanted, verify = true, check)
        if (failures.isNotEmpty()) failed()
        return marked
    }

    /** Brings the result showing, if any, to carry [wanted] (no mark when it is null), in the background. */
    private fun follow(wanted: MarkStyle?) {
        val files = result.value ?: return
        job?.let {
            it.cancel()
            if (started) uncertain = true
        }
        job = null
        started = false
        val verify = uncertain
        if (!verify && files.all { it.mark == wanted }) {
            _working.value = false
            handle.remove<Boolean>(KEY_UNDER_WAY)
            // A result shown again: its first page, drawn as it is (the page itself only without a mark).
            if (_preview.value == null) {
                scope.launch(io) {
                    val first = files.first()
                    if (result.value === files && _preview.value == null) _preview.value = render(first, withPage = first.mark == null)
                }
            }
            return
        }
        _working.value = true
        handle[KEY_UNDER_WAY] = true
        job = scope.launch {
            val job = coroutineContext.job
            try {
                // A moment first: a quick run of changes, as on and off again, rewrites the PDFs once.
                delay(SETTLE_MS)
                val (marked, failures) = if (turn != null) turn.withLock { change(files, wanted, verify, job) } else change(files, wanted, verify, job)
                uncertain = false
                if (result.value === files && marked.indices.any { marked[it] !== files[it] }) changed(marked)
                if (failures.isNotEmpty()) failed()
            } finally {
                if (this@ResultMarker.job === job) {
                    this@ResultMarker.job = null
                    _working.value = false
                    handle.remove<Boolean>(KEY_UNDER_WAY)
                }
            }
        }
    }

    /** [bringTo] with the file work, and the preview drawn again for a result shown again. */
    private suspend fun change(files: List<PdfFile>, wanted: MarkStyle?, verify: Boolean, job: Job) = withContext(io) {
        job.ensureActive()
        started = true
        bringTo(files, wanted, verify) { job.ensureActive() }.also { (marked, failures) ->
            val first = marked.firstOrNull()
            if (first != null && _preview.value?.page == null) {
                // Its first page as it is: the page itself only once it has no mark of its own to show.
                _preview.value = render(first, withPage = wanted == null && first.file !in failures)
            }
        }
    }

    /**
     * Brings each of [files] to carry [wanted]: those that say they carry another, or each one when
     * [verify]. Returns them, those that changed in new objects that say so, and the files that couldn't
     * be changed (each left as it was).
     */
    private fun bringTo(files: List<PdfFile>, wanted: MarkStyle?, verify: Boolean, check: () -> Unit): Pair<List<PdfFile>, Set<File>> {
        val failures = HashSet<File>()
        val marked = files.map { file ->
            check()
            // A PDF gone from the cache is said to be when the result is next used.
            if (!file.file.isFile || (!verify && file.mark == wanted)) return@map file
            try {
                if (marks.apply(file.file, wanted, check) || file.mark != wanted) file.copy(mark = wanted) else file
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Usugami", "Changing the mark failed", e)
                failures += file.file
                file
            } catch (_: OutOfMemoryError) {
                failures += file.file
                file
            } catch (_: StackOverflowError) {
                failures += file.file
                file
            }
        }
        return marked to failures
    }

    /** [file]'s first page as shown, with the page drawn when [withPage]; null if Android can't draw it. */
    private fun render(file: PdfFile, withPage: Boolean): MarkPreview? =
        renderShownPage(PdfInput(0, file.file.path, file.name, file.pageCount), 0, PREVIEW_SIDE)?.let {
            MarkPreview(if (withPage) it.image else null, it.width, it.height)
        }

    private companion object {
        /** In the saved state while the result's PDFs are being changed. */
        const val KEY_UNDER_WAY = "marking"

        const val SETTLE_MS = 250L

        /** The preview's longer side, in pixels. */
        const val PREVIEW_SIDE = 640
    }
}
