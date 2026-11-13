package io.github.nath_goa.usugami.ui

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.core.PageGlyphs
import io.github.nath_goa.usugami.core.RedactBox
import io.github.nath_goa.usugami.core.isOutOfSpace
import io.github.nath_goa.usugami.data.PdfFile
import io.github.nath_goa.usugami.data.PdfInput
import io.github.nath_goa.usugami.data.ReaderDocument
import io.github.nath_goa.usugami.data.ReaderFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What Find text has found so far in the PDF the reader shows. */
internal data class Found(
    /** What was searched for; empty when nothing was. */
    val query: String = "",
    /** The matches so far, in page order: each the boxes over its lines, all on one page (see Redactor.find). */
    val matches: List<List<RedactBox>> = emptyList(),
    /** The match Next and Previous stand on: an index into [matches], or -1 while there is none. */
    val current: Int = -1,
    val searching: Boolean = false,
    /** How many pages have been searched, for the bar under the search field. */
    val pagesDone: Int = 0,
    /** How many pages had text that couldn't be read: the pages of the chunks' TextSearch.unread. */
    val unread: Int = 0,
    /**
     * Counts the times a match was marked (the first one found, Next, Previous): the pages follow the
     * match marked each time, also when it is the same match again (the only one, or the same word
     * searched again). Not counted when matches of earlier pages come in before the match marked.
     */
    val moves: Int = 0,
)

/**
 * A PDF showing in the reader: [shown] (the view it is drawn and read from) open as [document], and
 * what Find text found in it. [id] is the reader's PDF's number. Used on the main thread.
 */
internal class ReaderSession(val id: Long, val shown: PdfInput, val document: ReaderDocument, private val vm: PdfToolsViewModel) {
    private val _found = MutableStateFlow(Found())

    /** What Find text found: the matches show as they are found. */
    val found: StateFlow<Found> = _found.asStateFlow()

    /** The search running, if any. */
    private var search: Job? = null

    /**
     * Searches for [query] from page [from] (zero-based) on, then from the first page up to it, CHUNK
     * pages at a time, on the document's own thread (never on the file work's: imports, saves and the
     * next file opened don't wait for it). The match marked is the first one from page [from] on, or
     * the PDF's first when there is none there. A blank query clears. Another search, or stopping,
     * stops this one.
     */
    fun find(query: String, from: Int) {
        search?.cancel()
        search = null
        if (query.isBlank()) {
            _found.value = Found()
            return
        }
        val pageCount = document.pageCount
        val start = from.coerceIn(0, maxOf(0, pageCount - 1))
        // The count of marks goes on, so a search again for the same word follows its first match too.
        _found.value = Found(query, searching = true, moves = _found.value.moves)
        // Known as the search running before it starts: what it finds is shown only while it is.
        val job = vm.viewModelScope.launch(start = CoroutineStart.LAZY) {
            val running = coroutineContext[Job]!!
            try {
                // From the page showing to the end, then from the first page up to it.
                for (chunk in chunks(start, pageCount)) {
                    val result = document.find(query, chunk) { running.ensureActive() }
                    // Stopped, or replaced, while the chunk was read: nothing of it is shown.
                    if (search !== running) return@launch
                    if (!merge(chunk, result.matches, result.unread.size)) {
                        vm.message(vm.strings.getString(R.string.reader_find_capped, MAX_MATCHES))
                        return@launch
                    }
                }
                val done = _found.value.copy(searching = false)
                _found.value = done
                if (done.matches.isEmpty()) vm.message(vm.strings.getString(R.string.reader_find_none))
                if (done.unread > 0) vm.pluralMessage(R.plurals.reader_find_unread, done.unread)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed(running, query)
            } catch (_: LinkageError) {
                failed(running, query)
            } catch (_: StackOverflowError) {
                failed(running, query)
            } catch (_: OutOfMemoryError) {
                failed(running, query)
            }
        }
        search = job
        job.start()
    }

    /**
     * Adds what the search of [chunk] found: its [matches] go in by page, before those of later
     * pages, and the match marked stays the same one. The first chunk that finds anything marks its
     * first match: from the page the search started at, the nearest; in the pages before it, the
     * PDF's first. Returns false, the search over, when that makes more than MAX_MATCHES: those found
     * first are kept, so the match marked is among them.
     */
    private fun merge(chunk: IntRange, matches: List<List<RedactBox>>, unread: Int): Boolean {
        val now = _found.value
        val room = MAX_MATCHES - now.matches.size
        val capped = matches.size > room
        val added = if (capped) matches.take(room) else matches
        // The chunks are pages apart: the chunk's matches go in as one, after those of earlier pages.
        val at = now.matches.indexOfFirst { it.first().page > chunk.first }.let { if (it < 0) now.matches.size else it }
        val merged = ArrayList<List<RedactBox>>(now.matches.size + added.size).apply {
            addAll(now.matches.subList(0, at))
            addAll(added)
            addAll(now.matches.subList(at, now.matches.size))
        }
        val current = when {
            now.current >= 0 -> if (at <= now.current) now.current + added.size else now.current
            added.isNotEmpty() -> at
            else -> -1
        }
        _found.value = now.copy(
            matches = merged,
            current = current,
            // Followed only when the first match is marked, not each time the match marked shifts.
            moves = if (now.current < 0 && current >= 0) now.moves + 1 else now.moves,
            searching = !capped,
            pagesDone = now.pagesDone + (chunk.last - chunk.first + 1),
            unread = now.unread + unread,
        )
        return !capped
    }

    /** The search by [job] for [query] failed: what it found goes, and the user is told. */
    private fun failed(job: Job, query: String) {
        if (search !== job) return
        _found.value = Found(query)
        vm.message(vm.strings.getString(R.string.redact_find_failed))
    }

    /** Moves to the next ([by] 1) or previous (-1) match, round and round. */
    fun step(by: Int) {
        val now = _found.value
        val count = now.matches.size
        if (count == 0) return
        val next = if (now.current < 0) (if (by > 0) 0 else count - 1) else Math.floorMod(now.current + by, count)
        _found.value = now.copy(current = next, moves = now.moves + 1)
    }

    /**
     * Stops a search and forgets what was found; the PDFBox document the text was read from is closed,
     * unless text is selected (see [selecting]).
     */
    fun stopFinding() {
        search?.cancel()
        search = null
        _found.value = Found()
        if (!selecting) document.releaseText()
    }

    /** The text of the pages asked for last (see [glyphs]), the one asked for last at the end. */
    private val pageGlyphs = LinkedHashMap<Int, PageGlyphs>(GLYPH_PAGES, 0.75f, true)

    /**
     * Page [page]'s text for selecting, read on the document's own thread (after the lot of pages a
     * search reads now, if one runs); kept for the GLYPH_PAGES pages asked for last. Null when it
     * can't be read (a page PDFBox can't read, a font naming an encoding it doesn't have).
     */
    suspend fun glyphs(page: Int): PageGlyphs? {
        pageGlyphs[page]?.let { return it }
        val read = try {
            document.glyphs(page)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        } catch (_: LinkageError) {
            return null
        } catch (_: StackOverflowError) {
            return null
        } catch (_: OutOfMemoryError) {
            return null
        }
        pageGlyphs[page] = read
        while (pageGlyphs.size > GLYPH_PAGES) pageGlyphs.remove(pageGlyphs.keys.first())
        return read
    }

    /**
     * Whether text is selected on the pages: while it is, closing the search keeps the PDFBox document
     * the text is read from open. Set by the reader's selection (ReaderSelection).
     */
    var selecting = false
        private set

    /** Text is selected now. */
    fun startSelecting() {
        selecting = true
    }

    /**
     * Text is no longer selected, or a page's text was read and nothing was selected (a long-press on
     * blank paper or a page without text, the screen reader's Copy the text of this page): the text
     * read for selecting is forgotten (dense pages hold megabytes of it), and the PDFBox document it
     * was read from is closed, unless Find text still has a search (it is closed when the search is,
     * see [stopFinding]). Closing a document that isn't open does nothing.
     */
    fun stopSelecting() {
        selecting = false
        pageGlyphs.clear()
        if (search == null && _found.value.query.isEmpty()) document.releaseText()
    }

    fun close() {
        search?.cancel()
        search = null
        document.close()
    }

    companion object {
        /** How many pages are searched at a time. */
        const val CHUNK = 16

        /** The most matches kept. */
        const val MAX_MATCHES = 2000

        /** How many pages' text for selecting is kept. */
        const val GLYPH_PAGES = 24

        /** A PDF of [pageCount] pages from page [start] to its end, then from its first page up to [start], CHUNK pages at a time. */
        fun chunks(start: Int, pageCount: Int): List<IntRange> {
            val chunks = ArrayList<IntRange>()
            for (first in start until pageCount step CHUNK) chunks += first until minOf(first + CHUNK, pageCount)
            for (first in 0 until start step CHUNK) chunks += first until minOf(first + CHUNK, start)
            return chunks
        }
    }
}

/**
 * The reader's own state in the view model (see ToolState): the PDF showing, a finished PDF being
 * looked at (a preview), and where the PDF came from. The PDF showing is the tool slot's PDF
 * (PdfToolsViewModel.inputs), drawn from its view; it goes when that PDF is put away.
 */
internal class ReaderState(
    private val vm: PdfToolsViewModel,
    // What the reader keeps in the saved state goes here, under keys that start with "reader_".
    private val handle: SavedStateHandle,
) : ToolState {
    // Nothing of vm is touched here: the view model makes its states before its file work is ready.

    private val _session = MutableStateFlow<ReaderSession?>(null)

    /** The reader's PDF, once it can be drawn; null until then and once it is put away. */
    val session: StateFlow<ReaderSession?> = _session.asStateFlow()

    /** The job getting a PDF ready to draw, and that PDF's number. */
    private var showJob: Job? = null
    private var showing: Long? = null

    private val _preview = MutableStateFlow<ReaderSession?>(null)

    /**
     * A finished PDF looked at from a result screen (a preview); null when none is. It is not the
     * tool's PDF: clear() leaves it, and it touches neither the tool's PDF nor its result.
     */
    val preview: StateFlow<ReaderSession?> = _preview.asStateFlow()

    /** The job opening a preview, and the path of the file it is (or is being) opened for. */
    private var previewJob: Job? = null
    private var previewing: String? = null

    /**
     * The PDF (by its number) that was made of a document opened in the reader, and that document's
     * name ("Report.docx"); null when none was. It says so of that PDF only: a PDF with another number
     * was opened as it is. Not forgotten by clear(): handed to Choose a tool and chosen there again
     * for the reader, it is still the document's PDF. Kept in the saved state.
     */
    val source: StateFlow<Pair<Long, String>?> = handle.getStateFlow(KEY_SOURCE, null)

    /** The PDF [id] was made of the document [name] opened in the reader. */
    fun converted(id: Long, name: String) {
        handle[KEY_SOURCE] = id to name
    }

    private val _handingOver = MutableStateFlow(false)

    /**
     * A copy of the reader's PDF is being made for another app (see [shareCopy]). It is a real copy,
     * which takes a moment for a very large PDF: the reader shows a thin moving bar meanwhile, and a
     * second Share or Open with another app does nothing until it is made.
     */
    val handingOver: StateFlow<Boolean> = _handingOver.asStateFlow()

    /** The copy being made for another app; putting the PDF away (clear()) stops it. */
    private var copyJob: Job? = null

    /**
     * Gets [input] ready to draw: its view with the watermark kept (previewView(unmark = false)), its
     * pages' sizes, the document. Once per PDF: called again for the same PDF, it does nothing.
     * When it can't be drawn, the user is told and the PDF is put away, which empties the reader.
     */
    fun show(input: PdfInput) {
        if (_session.value?.id == input.id || (showJob?.isActive == true && showing == input.id)) return
        showJob?.cancel()
        _session.value?.close()
        _session.value = null
        showing = input.id
        showJob = vm.viewModelScope.launch {
            val job = coroutineContext[Job]!!
            // The document once open, until it is shown: closed in the end if it isn't.
            var opened: ReaderDocument? = null
            try {
                val (view, sizes) = withContext(vm.fileOps) {
                    val check = { job.ensureActive() }
                    // Without a password, the PDF itself can be drawn, without its comments and form
                    // fields; with one, never: on Android 8 the renderer would refuse every PDF after it.
                    fun itself(e: Throwable): PdfInput = if (input.password.isEmpty()) input else throw e
                    val view = try {
                        vm.engine.previewView(input, check, unmark = false)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        itself(e)
                    } catch (e: LinkageError) {
                        itself(e)
                    } catch (e: StackOverflowError) {
                        itself(e)
                    } catch (e: OutOfMemoryError) {
                        itself(e)
                    }
                    check()
                    view to vm.engine.shownPageSizes(view)
                }
                // Opened whatever happens meanwhile, and kept here rather than returned: a result is
                // dropped when this job is cancelled meanwhile, and the document would never be closed.
                withContext(NonCancellable + vm.fileOps) {
                    opened = ReaderDocument.open(view.file, sizes) { vm.engine.load(view.file, "") }
                }
                if (job.isActive && showing == input.id && vm.inputs.value.firstOrNull()?.id == input.id) {
                    _session.value = ReaderSession(input.id, view, opened!!, vm)
                    opened = null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed(input, if (e.isOutOfSpace()) R.string.open_no_space else R.string.pdf_import_failed)
            } catch (_: LinkageError) {
                failed(input, R.string.pdf_import_failed)
            } catch (_: StackOverflowError) {
                failed(input, R.string.pdf_import_failed)
            } catch (_: OutOfMemoryError) {
                failed(input, R.string.pdf_import_failed)
            } finally {
                // Not shown: the PDF went, or another took its place, while it was opened.
                opened?.close()
            }
        }
    }

    /** [input] can't be shown: said, and the PDF put away. Nothing may suspend after it: putting it away cancels this job. */
    private fun failed(input: PdfInput, message: Int) {
        if (showing != input.id || vm.inputs.value.firstOrNull()?.id != input.id) return
        vm.message(vm.strings.getString(message))
        vm.removeInput(input.id)
    }

    /**
     * Opens [file], a result's PDF, for a preview: its view with the watermark kept, as the reader's
     * PDF is shown (when a view can't be made, the file itself). Called again for the same file while
     * it is open or being opened, it does nothing, so it can be called again after a recreation. When
     * it can't be shown, the user is told and [failed] runs.
     */
    fun openPreview(file: PdfFile, failed: () -> Unit) {
        val path = file.file.path
        if (previewing == path && (_preview.value != null || previewJob?.isActive == true)) return
        stopPreview()
        previewing = path
        val input = PdfInput(PREVIEW_ID, path, file.name, file.pageCount)
        previewJob = vm.viewModelScope.launch {
            val job = coroutineContext[Job]!!
            // The document once open, until it is shown: closed in the end if it isn't.
            var opened: ReaderDocument? = null
            try {
                val (view, sizes) = withContext(vm.fileOps) {
                    val check = { job.ensureActive() }
                    // A view left from the last preview would show the wrong PDF.
                    vm.engine.deleteViews(input)
                    // A result has no password: when its view can't be made, it is drawn as it is.
                    val view = try {
                        vm.engine.previewView(input, check, unmark = false)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        input
                    } catch (_: LinkageError) {
                        input
                    } catch (_: StackOverflowError) {
                        input
                    } catch (_: OutOfMemoryError) {
                        input
                    }
                    check()
                    view to vm.engine.shownPageSizes(view)
                }
                withContext(NonCancellable + vm.fileOps) {
                    opened = ReaderDocument.open(view.file, sizes) { vm.engine.load(view.file, "") }
                }
                if (job.isActive && previewing == path) {
                    _preview.value = ReaderSession(PREVIEW_ID, view, opened!!, vm)
                    opened = null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                previewFailed(path, failed)
            } catch (_: LinkageError) {
                previewFailed(path, failed)
            } catch (_: StackOverflowError) {
                previewFailed(path, failed)
            } catch (_: OutOfMemoryError) {
                previewFailed(path, failed)
            } finally {
                opened?.close()
            }
        }
    }

    /** The preview of [path] can't be shown: said, and [failed] runs. */
    private fun previewFailed(path: String, failed: () -> Unit) {
        if (previewing != path) return
        previewing = null
        previewJob = null
        vm.message(vm.strings.getString(R.string.pdf_import_failed))
        failed()
    }

    /** Closes the preview, or stops it being opened, and deletes its views with the other file work. */
    fun closePreview() {
        stopPreview()
        vm.viewModelScope.launch(vm.fileOps) { vm.engine.deleteViews(PdfInput(PREVIEW_ID, "", "", 0)) }
    }

    private fun stopPreview() {
        previewJob?.cancel()
        previewJob = null
        previewing = null
        _preview.value?.close()
        _preview.value = null
    }

    /**
     * The reader's file [input] under its own name, to hand to another app (ReaderFiles.shareCopy),
     * made with the other file work; null when it can't be made, or the PDF was put away meanwhile
     * (clear() stops the copy).
     */
    suspend fun shareCopy(input: PdfInput): File? {
        copyJob?.cancel()
        _handingOver.value = true
        val copy = vm.viewModelScope.async(vm.fileOps) { ReaderFiles.shareCopy(vm.getApplication<Application>(), input) { ensureActive() } }
        copyJob = copy
        return try {
            copy.await()
        } catch (e: CancellationException) {
            // The one asking went (the screen did): the copy is no longer wanted. Else the PDF was put away.
            copy.cancel()
            currentCoroutineContext().ensureActive()
            null
        } catch (_: Exception) {
            null
        } finally {
            if (copyJob === copy) {
                copyJob = null
                _handingOver.value = false
            }
        }
    }

    /**
     * The PDF was put away, or handed to Choose a tool (ToolState.clear): the session is closed, and a
     * copy being made for another app stopped. A preview stays: it is not the tool's PDF.
     */
    override fun clear() {
        showJob?.cancel()
        showJob = null
        showing = null
        _session.value?.close()
        _session.value = null
        copyJob?.cancel()
        copyJob = null
        _handingOver.value = false
    }

    private companion object {
        const val KEY_SOURCE = "reader_source"

        /** The number a preview's PDF goes by: no PDF taken in has a negative number. */
        const val PREVIEW_ID = -2L
    }
}
