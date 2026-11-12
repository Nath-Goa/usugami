package io.github.nath_goa.usugami.ui

import android.content.Context
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.SavedStateHandle
import io.github.nath_goa.usugami.core.MarkStyle
import io.github.nath_goa.usugami.data.MarkForbidden
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
import java.util.concurrent.ConcurrentHashMap

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
 * PDF couldn't be changed, and [refused] is told once how many of the result's PDFs are certified
 * against any change: they are left as they are, without the mark. A result's files that are not PDFs
 * (images and text files) are left alone: never marked, and never drawn.
 */
internal class ResultMarker(
    context: Context,
    private val handle: SavedStateHandle,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val result: StateFlow<List<PdfFile>?>,
    private val changed: (List<PdfFile>) -> Unit,
    private val failed: () -> Unit,
    private val refused: (count: Int) -> Unit,
    private val turn: Mutex? = null,
) {
    private val marks = PageMark(context)
    private val settings = SettingsStore(context)

    /** Where a copy of a result's first page without the mark is drawn from, for a while. */
    private val previewDir = File(context.cacheDir, "mark-preview")

    /** Whether the mark is on, and its size and opacity: one setting for the whole app. */
    val setting: StateFlow<MarkSetting> = settings.mark

    private val _working = MutableStateFlow(false)
    /** The result's PDFs are being changed to the setting, or about to be. */
    val working: StateFlow<Boolean> = _working.asStateFlow()

    private val _preview = MutableStateFlow<MarkPreview?>(null)
    /** The result's first page, for the result screen to show the mark on. */
    val preview: StateFlow<MarkPreview?> = _preview.asStateFlow()

    private var job: Job? = null

    /**
     * The result's PDFs certified against any change: they keep no mark, which the user has been told.
     * Kept in the saved state too, so that a result shown again after Android closed Usugami isn't
     * tried, and told of, once more.
     */
    private val forbidden: MutableSet<File> = ConcurrentHashMap.newKeySet<File>().also { files ->
        handle.get<ArrayList<String>>(KEY_FORBIDDEN)?.mapTo(files) { File(it) }
    }

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
        forget()
        _working.value = false
        handle.remove<Boolean>(KEY_UNDER_WAY)
    }

    /**
     * Puts the mark on [files], PDFs just made, if it is on, before they show, keeping their first page
     * as it was made for the preview first. Blocking: call it in the run's background work, which
     * [check] stops; [onFile] is told which of them is being marked, of how many. Returns them, each
     * with the mark it carries; one that can't be marked is left as it is, and [failed] is called. They
     * are made without the mark (the tools take it off the PDFs they read, in memory), so with it off
     * there is nothing to do.
     */
    fun markMade(files: List<PdfFile>, check: () -> Unit, onFile: (index: Int, count: Int) -> Unit = { _, _ -> }): List<PdfFile> {
        uncertain = false
        forget()
        _preview.value = files.firstOrNull()?.let { render(it) }
        val wanted = setting.value.wanted ?: return files
        return bringTo(files, wanted, verify = true, check, onFile).also { tell(it) }.files
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
        if (!verify && files.all { settled(it, wanted) }) {
            _working.value = false
            handle.remove<Boolean>(KEY_UNDER_WAY)
            // A result shown again: its first page as it was made.
            if (_preview.value == null) {
                scope.launch(io) {
                    val first = files.first()
                    if (result.value === files && _preview.value == null) _preview.value = render(first, asMade = true)
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
                val outcome = if (turn != null) turn.withLock { change(files, wanted, verify, job) } else change(files, wanted, verify, job)
                val marked = outcome.files
                uncertain = false
                if (result.value === files && marked.indices.any { marked[it] !== files[it] }) changed(marked)
                tell(outcome)
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
        bringTo(files, wanted, verify, { job.ensureActive() }).also { outcome ->
            // A result shown again whose first page couldn't be drawn yet: as it was made.
            val first = outcome.files.firstOrNull()
            if (first != null && _preview.value?.page == null) _preview.value = render(first, asMade = true)
        }
    }

    /** Notes [file] as certified against any change. False when it was noted already. */
    private fun forbid(file: File): Boolean = forbidden.add(file).also { if (it) keepForbidden() }

    /** No PDF is noted as certified against any change any more: the result is new, or put away. */
    private fun forget() {
        if (forbidden.isEmpty()) return
        forbidden.clear()
        keepForbidden()
    }

    /** Puts [forbidden] in the saved state, on the main thread, where the saved state is written. */
    private fun keepForbidden() {
        scope.launch {
            val paths = forbidden.mapTo(ArrayList()) { it.path }
            if (paths.isEmpty()) handle.remove<ArrayList<String>>(KEY_FORBIDDEN) else handle[KEY_FORBIDDEN] = paths
        }
    }

    /**
     * Whether [file] is as it should be with [wanted]: it isn't a PDF, which never carries the mark, it
     * says it carries it, or it is certified against the mark.
     */
    private fun settled(file: PdfFile, wanted: MarkStyle?) = !file.isPdf || file.mark == wanted || (wanted != null && file.file in forbidden)

    /**
     * What [bringTo] did: [files] as they are now, those that couldn't be changed, and how many were
     * found to be certified against any change.
     */
    private class Outcome(val files: List<PdfFile>, val failures: Set<File>, val forbidden: Int)

    /** Tells the user what went wrong in [outcome]. */
    private fun tell(outcome: Outcome) {
        if (outcome.failures.isNotEmpty()) failed()
        if (outcome.forbidden > 0) refused(outcome.forbidden)
    }

    /**
     * Brings each of [files] to carry [wanted]: those that say they carry another, or each one when
     * [verify], telling [onFile] of each. Returns them, those that changed in new objects that say so,
     * the files that couldn't be changed (each left as it was), and how many were found to be
     * certified against any change (left without the mark, and skipped from then on).
     */
    private fun bringTo(
        files: List<PdfFile>,
        wanted: MarkStyle?,
        verify: Boolean,
        check: () -> Unit,
        onFile: (index: Int, count: Int) -> Unit = { _, _ -> },
    ): Outcome {
        val failures = HashSet<File>()
        var refusing = 0
        val marked = files.mapIndexed { index, file ->
            check()
            // A file that isn't a PDF is left as it is. A PDF gone from the cache is said to be when
            // the result is next used.
            if (!file.isPdf || !file.file.isFile || (!verify && file.mark == wanted) || (wanted != null && file.file in forbidden)) return@mapIndexed file
            onFile(index, files.size)
            try {
                when {
                    // Changed, a PDF Compress kept as it was no longer says so, even once the mark is
                    // taken off again.
                    marks.apply(file.file, wanted, check) -> file.copy(mark = wanted, keptOriginal = false)
                    file.mark != wanted -> file.copy(mark = wanted)
                    else -> file
                }
            } catch (_: MarkForbidden) {
                // Its certification allows no change: it has no mark, and keeps none.
                if (forbid(file.file)) refusing++
                if (file.mark != null) file.copy(mark = null) else file
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
        return Outcome(marked, failures, refusing)
    }

    /**
     * [file]'s first page as shown, as it was made, or null if it can't be drawn. With [asMade], for a
     * result shown again, [file] may carry the mark and is read first: the page is drawn from a copy
     * of it without the mark, and not at all if the file can't be read. On Android 8, once Android
     * has refused a PDF it draws none until Usugami is closed: a file that isn't a PDF is never drawn.
     */
    private fun render(file: PdfFile, asMade: Boolean = false): MarkPreview? {
        if (!file.isPdf) return null
        var copy: File? = null
        try {
            val clean = if (!asMade) file.file else try {
                copy = File.createTempFile("page", ".pdf", previewDir.apply { mkdirs() })
                if (marks.firstPageUnmarked(file.file, copy)) copy else file.file
            } catch (e: Exception) {
                Log.w("Usugami", "Couldn't read the first page", e)
                null
            } catch (_: OutOfMemoryError) {
                null
            } catch (_: StackOverflowError) {
                null
            } ?: return null
            val page = renderShownPage(PdfInput(0, clean.path, file.name, file.pageCount), 0, PREVIEW_SIDE) ?: return null
            return MarkPreview(page.image, page.width, page.height)
        } finally {
            copy?.delete()
        }
    }

    private companion object {
        /** In the saved state while the result's PDFs are being changed. */
        const val KEY_UNDER_WAY = "marking"

        /** In the saved state: the paths of the result's PDFs certified against any change. */
        const val KEY_FORBIDDEN = "mark_forbidden"

        const val SETTLE_MS = 250L

        /** The preview's longer side, in pixels. */
        const val PREVIEW_SIDE = 640
    }
}
