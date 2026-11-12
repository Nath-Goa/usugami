package io.github.nath_goa.usugami.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.core.RedactBox
import io.github.nath_goa.usugami.core.isOutOfSpace
import io.github.nath_goa.usugami.data.PdfInput
import io.github.nath_goa.usugami.data.ReaderDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    /** Stops a search and forgets what was found; the PDFBox document the text was read from is closed. */
    fun stopFinding() {
        search?.cancel()
        search = null
        _found.value = Found()
        document.releaseText()
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
 * The reader's own state in the view model (see ToolState): the PDF showing. It is the tool slot's
 * PDF (PdfToolsViewModel.inputs), drawn from its view; it goes when that PDF is put away.
 */
internal class ReaderState(
    private val vm: PdfToolsViewModel,
    // What the reader keeps in the saved state goes here, under keys that start with "reader_".
    @Suppress("unused") private val handle: SavedStateHandle,
) : ToolState {
    // Nothing of vm is touched here: the view model makes its states before its file work is ready.

    private val _session = MutableStateFlow<ReaderSession?>(null)

    /** The reader's PDF, once it can be drawn; null until then and once it is put away. */
    val session: StateFlow<ReaderSession?> = _session.asStateFlow()

    /** The job getting a PDF ready to draw, and that PDF's number. */
    private var showJob: Job? = null
    private var showing: Long? = null

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

    /** The PDF was put away, or handed to Choose a tool (ToolState.clear): the session is closed. */
    override fun clear() {
        showJob?.cancel()
        showJob = null
        showing = null
        _session.value?.close()
        _session.value = null
    }
}
