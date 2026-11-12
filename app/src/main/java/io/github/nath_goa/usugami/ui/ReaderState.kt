package io.github.nath_goa.usugami.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.core.isOutOfSpace
import io.github.nath_goa.usugami.data.PdfInput
import io.github.nath_goa.usugami.data.ReaderDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A PDF showing in the reader: [shown] (the view it is drawn and read from) open as [document]. [id] is the reader's PDF's number. */
internal class ReaderSession(val id: Long, val shown: PdfInput, val document: ReaderDocument) {
    fun close() = document.close()
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
                    _session.value = ReaderSession(input.id, view, opened!!)
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
