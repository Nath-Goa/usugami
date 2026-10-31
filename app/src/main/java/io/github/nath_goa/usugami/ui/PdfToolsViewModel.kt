package io.github.nath_goa.usugami.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.annotation.PluralsRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.core.FileNames
import io.github.nath_goa.usugami.core.PdfPageSelection
import io.github.nath_goa.usugami.data.PdfCertificateEncrypted
import io.github.nath_goa.usugami.data.PdfCompression
import io.github.nath_goa.usugami.data.PdfFile
import io.github.nath_goa.usugami.data.PdfInput
import io.github.nath_goa.usugami.data.PdfPageRef
import io.github.nath_goa.usugami.data.PdfPasswordRequired
import io.github.nath_goa.usugami.data.PdfSaver
import io.github.nath_goa.usugami.data.PdfToolsEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class PdfTool { SPLIT, MERGE, COMPRESS }
enum class SplitMode { SELECTED, CUSTOM, FIXED, EVERY_PAGE }

class PdfToolsViewModel(private val app: Application, private val handle: SavedStateHandle) : AndroidViewModel(app) {
    private val engine = PdfToolsEngine(app)
    private val saver = PdfSaver(app)
    private val fileOps = Dispatchers.IO.limitedParallelism(1)
    private val restored = handle.contains(KEY_INPUTS)

    val inputs: StateFlow<List<PdfInput>> = handle.getStateFlow(KEY_INPUTS, ArrayList<PdfInput>())
    val pages: StateFlow<List<PdfPageRef>> = handle.getStateFlow(KEY_PAGES, ArrayList<PdfPageRef>())

    private val _locked = MutableStateFlow<PdfInput?>(null)
    val locked = _locked.asStateFlow()
    private val _result = MutableStateFlow<List<PdfFile>?>(null)
    val result = _result.asStateFlow()
    private val _processing = MutableStateFlow(false)
    val processing = _processing.asStateFlow()
    private val _importing = MutableStateFlow(false)
    val importing = _importing.asStateFlow()
    private val _saving = MutableStateFlow(false)
    val saving = _saving.asStateFlow()
    private val _saved = MutableStateFlow(false)
    val saved = _saved.asStateFlow()
    /** True when the result was made from a PDF opened with a password: the new PDF has none. */
    private val _passwordRemoved = MutableStateFlow(false)
    val passwordRemoved = _passwordRemoved.asStateFlow()
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private var nextId = maxOf(System.currentTimeMillis(), (inputs.value.maxOfOrNull { it.id } ?: 0) + 1)
    private var task: Job? = null
    private var importJob: Job? = null

    init {
        val keep = if (restored) inputs.value else emptyList()
        viewModelScope.launch {
            val missing = withContext(fileOps) {
                // Leftovers from an earlier session, including PDFBox scratch files from a killed run.
                engine.deleteAllExcept(keep)
                engine.clearOutput()
                engine.clearScratch()
                // The copies are in the cache, which the system may have cleared while Usugami was away.
                keep.filterNot { it.file.isFile }
            }
            if (missing.isNotEmpty()) forgetInputs(missing)
        }
    }

    fun addPdfs(uris: List<Uri>, replace: Boolean) {
        if (uris.isEmpty() || _processing.value || _importing.value) return
        _importing.value = true
        importJob = viewModelScope.launch {
            if (replace) clearInputs()
            try {
                for (uri in uris) {
                    val id = nextId++
                    try {
                        val input = withContext(fileOps) { engine.import(uri, id) }
                        append(input)
                    } catch (e: PdfPasswordRequired) {
                        _locked.value = e.input
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: PdfCertificateEncrypted) {
                        message(app.getString(R.string.pdf_certificate_unsupported))
                    } catch (_: Exception) {
                        message(app.getString(R.string.pdf_import_failed))
                    } catch (_: LinkageError) {
                        // Never crash on a PDF that PDFBox can't handle.
                        message(app.getString(R.string.pdf_import_failed))
                    } catch (_: OutOfMemoryError) {
                        // A PDF with a huge number of objects can exhaust the heap while it is read.
                        message(app.getString(R.string.pdf_import_failed))
                    }
                }
            } finally {
                _importing.value = false
            }
        }
    }

    private var unlockJob: Job? = null

    fun unlock(password: String) {
        val pending = _locked.value ?: return
        // A second tap while the first unlock is still loading would add the same PDF twice.
        if (unlockJob?.isActive == true) return
        unlockJob = viewModelScope.launch {
            try {
                val input = withContext(fileOps) { engine.inspect(pending, password) }
                // The dialog may have been cancelled while loading; its file is already deleted.
                if (_locked.value != pending) return@launch
                _locked.value = null
                append(input)
            } catch (e: CancellationException) {
                throw e
            } catch (_: PdfCertificateEncrypted) {
                message(app.getString(R.string.pdf_certificate_unsupported))
            } catch (_: Exception) {
                message(app.getString(R.string.pdf_wrong_password))
            } catch (_: LinkageError) {
                message(app.getString(R.string.pdf_import_failed))
            } catch (_: OutOfMemoryError) {
                message(app.getString(R.string.pdf_import_failed))
            }
        }
    }

    fun dismissPassword() {
        val pending = _locked.value ?: return
        _locked.value = null
        viewModelScope.launch(fileOps) { engine.delete(pending) }
    }

    private fun append(input: PdfInput) {
        if (inputs.value.any { it.id == input.id }) return
        setInputs(inputs.value + input)
        setPages(pages.value + (0 until input.pageCount).map { PdfPageRef(input.id, it) })
    }

    /** Drops PDFs whose copies the system has cleared from the cache, and says so. */
    private fun forgetInputs(missing: List<PdfInput>) {
        val ids = missing.mapTo(HashSet()) { it.id }
        setInputs(inputs.value.filterNot { it.id in ids })
        setPages(pages.value.filterNot { it.inputId in ids })
        pluralMessage(R.plurals.msg_files_cleared, ids.size)
    }

    fun removeInput(id: Long) {
        val input = inputs.value.firstOrNull { it.id == id } ?: return
        setInputs(inputs.value - input)
        setPages(pages.value.filterNot { it.inputId == id })
        viewModelScope.launch(fileOps) { engine.delete(input) }
    }

    fun moveInput(id: Long, offset: Int) {
        val list = inputs.value.toMutableList()
        val from = list.indexOfFirst { it.id == id }
        val to = from + offset
        if (from !in list.indices || to !in list.indices) return
        list.add(to, list.removeAt(from))
        setInputs(list)
    }

    fun sortInputs(reverse: Boolean = false) {
        val sorted = inputs.value.sortedBy { it.name.lowercase() }
        setInputs(if (reverse) sorted.reversed() else sorted)
    }

    fun movePage(from: PdfPageRef, to: PdfPageRef) {
        val list = pages.value.toMutableList()
        val a = list.indexOf(from)
        val b = list.indexOf(to)
        if (a < 0 || b < 0 || a == b) return
        list.add(b, list.removeAt(a))
        setPages(list)
    }

    fun movePageBy(ref: PdfPageRef, offset: Int) {
        val list = pages.value.toMutableList()
        val a = list.indexOf(ref)
        val b = a + offset
        if (a !in list.indices || b !in list.indices) return
        list.add(b, list.removeAt(a))
        setPages(list)
    }

    fun rotatePage(ref: PdfPageRef, degrees: Int) {
        setPages(pages.value.map { if (it == ref) it.copy(rotation = Math.floorMod(it.rotation + degrees, 360)) else it })
    }

    fun removePage(ref: PdfPageRef) = setPages(pages.value - ref)

    fun resetPages() = setPages(inputs.value.flatMap { input -> (0 until input.pageCount).map { PdfPageRef(input.id, it) } })

    fun runSplit(mode: SplitMode, text: String, combine: Boolean, fileName: String) {
        val input = inputs.value.singleOrNull() ?: return
        val groups = try {
            when (mode) {
                SplitMode.SELECTED -> {
                    val selected = PdfPageSelection.selected(text, input.pageCount)
                    if (combine) listOf(selected) else selected.map(::listOf)
                }
                SplitMode.CUSTOM -> {
                    val ranges = PdfPageSelection.customRanges(text, input.pageCount)
                    if (combine) listOf(ranges.flatten()) else ranges
                }
                SplitMode.FIXED -> PdfPageSelection.fixed(input.pageCount, text.trim().toIntOrNull() ?: 0)
                SplitMode.EVERY_PAGE -> PdfPageSelection.fixed(input.pageCount, 1)
            }
        } catch (e: IllegalArgumentException) {
            message(e.message ?: app.getString(R.string.pdf_invalid_pages))
            return
        }
        process(listOf(input)) { check -> engine.split(input, groups, fileName, check) }
    }

    fun runMerge(pageMode: Boolean, fileName: String) {
        val source = inputs.value
        if (source.size < 2 || (pageMode && pages.value.isEmpty())) return
        val selected = if (pageMode) pages.value else null
        process(source) { check -> listOf(engine.merge(source, selected, fileName, check)) }
    }

    fun runCompress(level: PdfCompression, fileName: String) {
        val input = inputs.value.singleOrNull() ?: return
        process(listOf(input), afterResult = { files ->
            // The engine keeps the original file when re-encoding would not make an unprotected PDF
            // smaller. Say so, otherwise the result looks like nothing happened.
            val file = files.single()
            if (input.password.isEmpty() && file.sizeBytes >= file.originalBytes) {
                message(app.getString(R.string.pdf_no_reduction))
            }
        }) { check -> listOf(engine.compress(input, level, fileName, check)) }
    }

    /** Runs [block], which reads [sources]. Split, merge and compress all write PDFs without a password. */
    private fun process(sources: List<PdfInput>, afterResult: (List<PdfFile>) -> Unit = {}, block: (() -> Unit) -> List<PdfFile>) {
        if (_processing.value || _importing.value) return
        _processing.value = true
        task = viewModelScope.launch {
            try {
                val job = coroutineContext[Job]!!
                val missing = withContext(fileOps) { sources.filterNot { it.file.isFile } }
                if (missing.isNotEmpty()) {
                    forgetInputs(missing)
                    return@launch
                }
                val files = withContext(fileOps) { block { job.ensureActive() } }
                _saved.value = false
                _passwordRemoved.value = sources.any { it.password.isNotEmpty() }
                _result.value = files
                afterResult(files)
            } catch (_: CancellationException) {
                // The engine removes partial output on cancellation.
            } catch (_: OutOfMemoryError) {
                // A very large image can exhaust the heap; the engine has already removed partial output.
                message(app.getString(R.string.pdf_process_failed))
            } catch (_: PdfCertificateEncrypted) {
                message(app.getString(R.string.pdf_certificate_unsupported))
            } catch (e: Exception) {
                message(
                    if (e.isOutOfSpace()) app.getString(R.string.msg_convert_failed, app.getString(R.string.msg_out_of_space))
                    else app.getString(R.string.pdf_process_failed),
                )
            } catch (_: LinkageError) {
                message(app.getString(R.string.pdf_process_failed))
            } finally {
                _processing.value = false
                task = null
            }
        }
    }

    fun cancel() { task?.cancel() }
    fun backToEditing() { _result.value = null }

    fun startOver() {
        _result.value = null
        clearInputs()
        viewModelScope.launch(fileOps) { engine.clearOutput() }
    }

    /**
     * Forgets the current tool's PDFs and results and frees their files: when a tool is opened, and
     * once the tool's screens have gone (nothing of them can come back: opening a tool starts afresh).
     */
    fun reset() {
        importJob?.cancel()
        unlockJob?.cancel()
        task?.cancel()
        _locked.value = null
        _result.value = null
        setInputs(emptyList())
        setPages(emptyList())
        // Every copy goes, including one still being imported or waiting for its password. File work
        // runs one task at a time, so this waits for a cancelled import or run to stop first.
        viewModelScope.launch(fileOps) {
            engine.deleteAllExcept(emptyList())
            engine.clearOutput()
        }
    }

    private fun clearInputs() {
        val old = inputs.value
        setInputs(emptyList())
        setPages(emptyList())
        old.forEach { input -> viewModelScope.launch(fileOps) { engine.delete(input) } }
    }

    val canSaveToDownloads get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /**
     * The finished PDFs are in the cache, which the system may clear while Usugami is in the background.
     * Returns false, after saying so and going back to the tool, when that has happened.
     */
    fun checkResult(): Boolean {
        val files = _result.value ?: return false
        if (files.all { it.file.isFile }) return true
        resultCleared(files.size)
        return false
    }

    private fun resultCleared(count: Int) {
        _result.value = null
        message(app.resources.getQuantityString(R.plurals.msg_result_cleared, count))
    }

    fun saveToDownloads() {
        val files = _result.value ?: return
        if (!canSaveToDownloads) return
        save {
            files.forEach { saver.saveToDownloads(it.file) }
            app.getString(R.string.pdf_saved_downloads)
        }
    }

    fun saveTo(uri: Uri) {
        val file = _result.value?.singleOrNull()
        if (file == null) {
            // The system restarted Usugami while the "Save as" screen was open, which cleared the PDF.
            // Remove the empty document the screen made, and say so.
            viewModelScope.launch(Dispatchers.IO) { saver.discardIfEmpty(uri) }
            resultCleared(1)
            return
        }
        save { saver.saveTo(file.file, uri); app.getString(R.string.msg_saved) }
    }

    fun saveToFolder(tree: Uri) {
        // As in saveTo; the folder picker offered for several PDFs creates nothing itself.
        val files = _result.value ?: return resultCleared(2)
        save { saver.saveToFolder(files.map { it.file }, tree); app.getString(R.string.msg_saved) }
    }

    private fun save(block: () -> String) {
        if (_saving.value) return
        _saving.value = true
        viewModelScope.launch {
            try {
                message(withContext(Dispatchers.IO) { block() })
                _saved.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                val files = _result.value
                if (files != null && files.any { !it.file.isFile }) resultCleared(files.size)
                else message(app.getString(R.string.msg_save_failed))
            } finally {
                _saving.value = false
            }
        }
    }

    fun shareIntent(files: List<PdfFile>): Intent = saver.shareIntent(files.map { it.file })
    fun viewIntent(file: PdfFile): Intent = saver.viewIntent(file.file)

    fun defaultName(tool: PdfTool): String {
        val base = inputs.value.firstOrNull()?.name?.let(FileNames::baseName) ?: "Usugami"
        return when (tool) {
            PdfTool.SPLIT -> "${base}_split"
            PdfTool.COMPRESS -> "${base}_compressed"
            PdfTool.MERGE -> "Merged_PDF"
        }
    }

    private fun setInputs(list: List<PdfInput>) { handle[KEY_INPUTS] = ArrayList(list) }
    private fun setPages(list: List<PdfPageRef>) { handle[KEY_PAGES] = ArrayList(list) }
    private fun message(text: String) { _messages.trySend(text) }
    private fun pluralMessage(@PluralsRes res: Int, count: Int) = message(app.resources.getQuantityString(res, count, count))

    private var finishing = false

    /** Called when the activity is finishing for good, so [onCleared] can free the copies. */
    fun activityFinishing() {
        finishing = true
    }

    override fun onCleared() {
        // Not when the system merely destroyed the activity: the saved state still lists the copies.
        if (!finishing) return
        val old = inputs.value
        Thread { old.forEach(engine::delete) }.start()
    }

    private companion object {
        const val KEY_INPUTS = "pdf_inputs"
        const val KEY_PAGES = "pdf_pages"
    }
}
