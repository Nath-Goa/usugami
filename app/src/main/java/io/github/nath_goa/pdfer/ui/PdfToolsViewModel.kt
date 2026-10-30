package io.github.nath_goa.pdfer.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.nath_goa.pdfer.R
import io.github.nath_goa.pdfer.core.FileNames
import io.github.nath_goa.pdfer.core.PdfPageSelection
import io.github.nath_goa.pdfer.data.PdfCompression
import io.github.nath_goa.pdfer.data.PdfFile
import io.github.nath_goa.pdfer.data.PdfInput
import io.github.nath_goa.pdfer.data.PdfPageRef
import io.github.nath_goa.pdfer.data.PdfPasswordRequired
import io.github.nath_goa.pdfer.data.PdfSaver
import io.github.nath_goa.pdfer.data.PdfToolsEngine
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
import java.io.IOException

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
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private var nextId = maxOf(System.currentTimeMillis(), (inputs.value.maxOfOrNull { it.id } ?: 0) + 1)
    private var task: Job? = null

    init {
        viewModelScope.launch(fileOps) {
            engine.deleteAllExcept(if (restored) inputs.value else emptyList())
            engine.clearOutput()
        }
    }

    fun addPdfs(uris: List<Uri>, replace: Boolean) {
        if (uris.isEmpty() || _processing.value || _importing.value) return
        _importing.value = true
        viewModelScope.launch {
            if (replace) clearInputs()
            try {
                for (uri in uris) {
                    val id = nextId++
                    try {
                        val input = withContext(fileOps) { engine.import(uri, id) }
                        append(input)
                    } catch (e: PdfPasswordRequired) {
                        _locked.value = e.input
                    } catch (e: Exception) {
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
            } catch (_: Exception) {
                message(app.getString(R.string.pdf_wrong_password))
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
        process { check -> engine.split(input, groups, fileName, check) }
    }

    fun runMerge(pageMode: Boolean, fileName: String) {
        val source = inputs.value
        if (source.size < 2 || (pageMode && pages.value.isEmpty())) return
        val selected = if (pageMode) pages.value else null
        process { check -> listOf(engine.merge(source, selected, fileName, check)) }
    }

    fun runCompress(level: PdfCompression, fileName: String) {
        val input = inputs.value.singleOrNull() ?: return
        process(afterResult = { files ->
            // The engine keeps the original file when re-encoding would not make an unprotected PDF
            // smaller. Say so, otherwise the result looks like nothing happened.
            val file = files.single()
            if (input.password.isEmpty() && file.sizeBytes >= file.originalBytes) {
                message(app.getString(R.string.pdf_no_reduction))
            }
        }) { check -> listOf(engine.compress(input, level, fileName, check)) }
    }

    private fun process(afterResult: (List<PdfFile>) -> Unit = {}, block: (() -> Unit) -> List<PdfFile>) {
        if (_processing.value || _importing.value) return
        _processing.value = true
        task = viewModelScope.launch {
            try {
                val job = coroutineContext[Job]!!
                val files = withContext(fileOps) { block { job.ensureActive() } }
                _saved.value = false
                _result.value = files
                afterResult(files)
            } catch (_: CancellationException) {
                // The engine removes partial output on cancellation.
            } catch (_: OutOfMemoryError) {
                // A very large image can exhaust the heap; the engine has already removed partial output.
                message(app.getString(R.string.pdf_process_failed))
            } catch (e: Exception) {
                message(if (e is IOException) app.getString(R.string.pdf_process_failed) else app.getString(R.string.pdf_process_failed))
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

    fun reset() = startOver()

    private fun clearInputs() {
        val old = inputs.value
        setInputs(emptyList())
        setPages(emptyList())
        old.forEach { input -> viewModelScope.launch(fileOps) { engine.delete(input) } }
    }

    val canSaveToDownloads get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun saveToDownloads() {
        val files = _result.value ?: return
        if (!canSaveToDownloads) return
        save {
            files.forEach { saver.saveToDownloads(it.file) }
            app.getString(R.string.pdf_saved_downloads)
        }
    }

    fun saveTo(uri: Uri) {
        val file = _result.value?.singleOrNull() ?: return
        save { saver.saveTo(file.file, uri); app.getString(R.string.msg_saved) }
    }

    fun saveToFolder(tree: Uri) {
        val files = _result.value ?: return
        save { saver.saveToFolder(files.map { it.file }, tree); app.getString(R.string.msg_saved) }
    }

    private fun save(block: () -> String) {
        if (_saving.value) return
        _saving.value = true
        viewModelScope.launch {
            try {
                message(withContext(Dispatchers.IO) { block() })
                _saved.value = true
            } catch (_: Exception) {
                message(app.getString(R.string.msg_save_failed))
            } finally {
                _saving.value = false
            }
        }
    }

    fun shareIntent(files: List<PdfFile>): Intent = saver.shareIntent(files.map { it.file })
    fun viewIntent(file: PdfFile): Intent = saver.viewIntent(file.file)

    fun defaultName(tool: PdfTool): String {
        val base = inputs.value.firstOrNull()?.name?.let(FileNames::baseName) ?: "PDFer"
        return when (tool) {
            PdfTool.SPLIT -> "${base}_split"
            PdfTool.COMPRESS -> "${base}_compressed"
            PdfTool.MERGE -> "Merged_PDF"
        }
    }

    private fun setInputs(list: List<PdfInput>) { handle[KEY_INPUTS] = ArrayList(list) }
    private fun setPages(list: List<PdfPageRef>) { handle[KEY_PAGES] = ArrayList(list) }
    private fun message(text: String) { _messages.trySend(text) }

    override fun onCleared() {
        val old = inputs.value
        Thread { old.forEach(engine::delete) }.start()
    }

    private companion object {
        const val KEY_INPUTS = "pdf_inputs"
        const val KEY_PAGES = "pdf_pages"
    }
}
