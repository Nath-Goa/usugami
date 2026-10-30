package io.github.nath_goa.pdfer.ui

import android.app.Application
import android.util.Log
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.nath_goa.pdfer.R
import io.github.nath_goa.pdfer.core.Compression
import io.github.nath_goa.pdfer.core.FileNames
import io.github.nath_goa.pdfer.core.LayoutSettings
import io.github.nath_goa.pdfer.core.NaturalOrder
import io.github.nath_goa.pdfer.data.ImageItem
import io.github.nath_goa.pdfer.data.ImageStore
import io.github.nath_goa.pdfer.data.ImageUnreadableException
import io.github.nath_goa.pdfer.data.PdfExporter
import io.github.nath_goa.pdfer.data.PdfOcr
import io.github.nath_goa.pdfer.data.OcrLanguage
import io.github.nath_goa.pdfer.data.OcrProblem
import io.github.nath_goa.pdfer.data.OcrUnavailableException
import io.github.nath_goa.pdfer.data.ocrModels
import io.github.nath_goa.pdfer.data.PdfFile
import io.github.nath_goa.pdfer.data.PdfSaver
import io.github.nath_goa.pdfer.data.SettingsStore
import io.github.nath_goa.pdfer.data.Thumbnails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class SortMode { NAME_ASC, NAME_DESC, REVERSE }

/** [done] of [total] steps of [stage]; a [total] of 0 means the amount isn't known yet. */
data class Progress(val done: Int, val total: Int, val stage: Stage = Stage.CREATE) {
    enum class Stage { DOWNLOAD_OCR, CREATE, READ_TEXT }
}

class MainViewModel(private val app: Application, private val handle: SavedStateHandle) : AndroidViewModel(app) {
    private val store = ImageStore(app)
    private val exporter = PdfExporter(app)
    private val ocr = PdfOcr(app)
    private val ocrModels = ocrModels(app)
    private val saver = PdfSaver(app)
    private val settingsStore = SettingsStore(app)
    val thumbnails = Thumbnails()

    /** Serialises file work (cleanup, imports, deletes) so they never race each other. */
    private val fileOps = Dispatchers.IO.limitedParallelism(1)

    // Must be read before getStateFlow, which stores the initial value.
    private val restored = handle.contains(KEY_ITEMS)

    /** The picked images in page order. Survives rotation and the system killing the app. */
    val items: StateFlow<List<ImageItem>> = handle.getStateFlow(KEY_ITEMS, ArrayList<ImageItem>())

    private val _layout = MutableStateFlow(settingsStore.layout)
    val layout: StateFlow<LayoutSettings> = _layout.asStateFlow()

    private val _merge = MutableStateFlow(settingsStore.merge)
    val merge: StateFlow<Boolean> = _merge.asStateFlow()

    private val _compression = MutableStateFlow(settingsStore.compression)
    val compression: StateFlow<Compression> = _compression.asStateFlow()

    private val _pendingImports = MutableStateFlow(0)
    val pendingImports: StateFlow<Int> = _pendingImports.asStateFlow()

    /** Non-null while a PDF is being created. */
    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    /** Non-null once PDFs have been created: the result screen is showing. */
    private val _result = MutableStateFlow<List<PdfFile>?>(null)
    val result: StateFlow<List<PdfFile>?> = _result.asStateFlow()

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    private val _busySaving = MutableStateFlow(false)
    val busySaving: StateFlow<Boolean> = _busySaving.asStateFlow()

    /** Non-null while the "text recognition isn't available" dialog is showing. */
    private val _ocrProblem = MutableStateFlow<OcrProblem?>(null)
    val ocrProblem: StateFlow<OcrProblem?> = _ocrProblem.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    private var nextId = maxOf(System.currentTimeMillis(), (items.value.maxOfOrNull { it.id } ?: 0L) + 1)
    private var conversion: Job? = null

    init {
        val keep = if (restored) items.value else emptyList()
        viewModelScope.launch {
            val missing = withContext(fileOps) {
                // Leftovers from an earlier session (or half-copied files) are removed.
                store.deleteAllExcept(keep)
                exporter.clearOutput()
                // The copies are in the cache, which the system may have cleared while PDFer was away.
                keep.filterNot(store::exists)
            }
            if (missing.isNotEmpty()) forgetMissing(missing)
        }
    }

    // ---- Picking images ----

    /** [refused] counts shared files that were turned away before import; they're reported as unreadable. */
    fun addImages(uris: List<Uri>, refused: Int = 0) {
        if (uris.isEmpty()) {
            if (refused > 0) pluralMessage(R.plurals.msg_failed_import, refused)
            return
        }
        _result.value = null
        _pendingImports.update { it + uris.size }
        viewModelScope.launch {
            var unsupported = 0
            var failed = refused
            try {
                for (uri in uris) {
                    val id = nextId++
                    when (val r = withContext(fileOps) { store.import(uri, id) }) {
                        is ImageStore.ImportResult.Added -> setItems(items.value + r.item)
                        ImageStore.ImportResult.Unsupported -> unsupported++
                        ImageStore.ImportResult.Failed -> failed++
                    }
                    _pendingImports.update { it - 1 }
                }
            } finally {
                if (unsupported > 0) pluralMessage(R.plurals.msg_skipped_unsupported, unsupported)
                if (failed > 0) pluralMessage(R.plurals.msg_failed_import, failed)
            }
        }
    }

    // ---- Editing ----

    fun rotate(id: Long, degrees: Int) = setItems(items.value.map { if (it.id == id) it.rotatedBy(degrees) else it })

    fun remove(id: Long) {
        val item = items.value.firstOrNull { it.id == id } ?: return
        setItems(items.value - item)
        thumbnails.remove(id)
        viewModelScope.launch(fileOps) { store.delete(item) }
    }

    fun clearAll() {
        val all = items.value
        setItems(emptyList())
        all.forEach { thumbnails.remove(it.id) }
        viewModelScope.launch(fileOps) { all.forEach(store::delete) }
    }

    /** Moves the image [fromId] to where [toId] is (drag and drop). */
    fun move(fromId: Long, toId: Long) {
        val list = items.value.toMutableList()
        val from = list.indexOfFirst { it.id == fromId }
        val to = list.indexOfFirst { it.id == toId }
        if (from < 0 || to < 0 || from == to) return
        list.add(to, list.removeAt(from))
        setItems(list)
    }

    /** Moves an image one place earlier (-1) or later (+1). */
    fun moveBy(id: Long, offset: Int) {
        val list = items.value.toMutableList()
        val from = list.indexOfFirst { it.id == id }
        val to = from + offset
        if (from < 0 || to !in list.indices) return
        list.add(to, list.removeAt(from))
        setItems(list)
    }

    fun sort(mode: SortMode) {
        val list = items.value
        setItems(
            when (mode) {
                SortMode.NAME_ASC -> list.sortedWith(compareBy(NaturalOrder) { it.name })
                SortMode.NAME_DESC -> list.sortedWith(compareBy(NaturalOrder) { it.name }).reversed()
                SortMode.REVERSE -> list.reversed()
            },
        )
    }

    fun setLayout(layout: LayoutSettings) {
        _layout.value = layout
        settingsStore.layout = layout
    }

    private fun setItems(list: List<ImageItem>) {
        handle[KEY_ITEMS] = ArrayList(list)
    }

    /** Drops images whose copies the system has cleared from the cache, and says so. */
    private fun forgetMissing(missing: List<ImageItem>) {
        val ids = missing.mapTo(HashSet()) { it.id }
        setItems(items.value.filterNot { it.id in ids })
        ids.forEach(thumbnails::remove)
        pluralMessage(R.plurals.msg_files_cleared, ids.size)
    }

    // ---- Converting ----

    /** Suggested name for a merged PDF: the image's own name for one image, otherwise a timestamp. */
    fun defaultFileName(): String {
        val list = items.value
        if (list.size == 1) return FileNames.sanitize(FileNames.baseName(list[0].name), PdfExporter.DEFAULT_NAME)
        return "PDFer_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT))
    }

    fun convert(merge: Boolean, fileName: String, compression: Compression, ocrLanguage: OcrLanguage? = null) {
        val snapshot = items.value
        if (snapshot.isEmpty() || _progress.value != null || _pendingImports.value > 0) return
        if (snapshot.size > 1) {
            _merge.value = merge
            settingsStore.merge = merge
        }
        _compression.value = compression
        settingsStore.compression = compression
        val layout = _layout.value
        _progress.value = Progress(0, snapshot.size)
        conversion = viewModelScope.launch {
            try {
                val missing = withContext(Dispatchers.IO) { snapshot.filterNot(store::exists) }
                if (missing.isNotEmpty()) {
                    forgetMissing(missing)
                    return@launch
                }
                val files = withContext(Dispatchers.IO) {
                    if (ocrLanguage != null) {
                        // Before any PDF work, so a missing recognizer is reported straight away.
                        ocrModels.ensureReady(ocrLanguage) { fraction ->
                            _progress.value = if (fraction == null) Progress(0, 0, Progress.Stage.DOWNLOAD_OCR)
                            else Progress((fraction * 100).toInt(), 100, Progress.Stage.DOWNLOAD_OCR)
                        }
                        _progress.value = Progress(0, snapshot.size)
                    }
                    val files = exporter.export(snapshot, layout, compression, merge || snapshot.size == 1, fileName, app.getString(R.string.app_name)) { done ->
                        _progress.value = Progress(done, snapshot.size)
                    }
                    if (ocrLanguage == null) files else addTextLayer(files, ocrLanguage)
                }
                _saved.value = false
                _result.value = files
            } catch (e: CancellationException) {
                throw e
            } catch (e: OcrUnavailableException) {
                Log.w("PDFer", "Text recognition unavailable", e)
                _ocrProblem.value = e.problem
            } catch (e: ImageUnreadableException) {
                // Its copy may have been cleared from the cache while the PDF was being made.
                val missing = withContext(Dispatchers.IO) { snapshot.filterNot(store::exists) }
                if (missing.isNotEmpty()) forgetMissing(missing)
                else message(R.string.msg_convert_failed, app.getString(R.string.msg_image_unreadable, e.imageName))
            } catch (e: IOException) {
                Log.e("PDFer", "PDF generation failed", e)
                message(R.string.msg_convert_failed, app.getString(if (e.isOutOfSpace()) R.string.msg_out_of_space else R.string.msg_unknown_error))
            } catch (e: OutOfMemoryError) {
                message(R.string.msg_convert_failed, app.getString(R.string.msg_out_of_memory))
            } catch (e: Exception) {
                Log.e("PDFer", "PDF generation failed", e)
                message(R.string.msg_convert_failed, app.getString(R.string.msg_unknown_error))
            } finally {
                _progress.value = null
                conversion = null
            }
        }
    }

    /**
     * Returns [files] with a searchable text layer. If recognition fails, the PDFs are kept as they
     * are, without the text layer, and a message says so.
     */
    private suspend fun addTextLayer(files: List<PdfFile>, language: OcrLanguage): List<PdfFile> {
        val pages = files.sumOf { it.pageCount }
        _progress.value = Progress(0, pages, Progress.Stage.READ_TEXT)
        return try {
            val output = ocr.apply(files, language) { done -> _progress.value = Progress(done, pages, Progress.Stage.READ_TEXT) }
            if (output.lineCount == 0) message(R.string.ocr_no_text)
            output.files
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("PDFer", "Text recognition failed", e)
            message(R.string.ocr_failed)
            files
        } catch (e: OutOfMemoryError) {
            Log.w("PDFer", "Text recognition ran out of memory", e)
            message(R.string.ocr_failed)
            files
        }
    }

    fun cancelConversion() {
        conversion?.cancel()
    }

    fun dismissOcrProblem() {
        _ocrProblem.value = null
    }

    // ---- Result ----

    fun backToEditing() {
        _result.value = null
    }

    fun startOver() {
        _result.value = null
        clearAll()
        viewModelScope.launch(fileOps) { exporter.clearOutput() }
    }

    val canSaveToDownloads: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /**
     * The finished PDFs are in the cache, which the system may clear while PDFer is in the background.
     * Returns false, after saying so and going back to the editor, when that has happened.
     */
    fun checkResult(): Boolean {
        val files = _result.value ?: return false
        if (files.all { it.file.isFile }) return true
        resultCleared(files.size)
        return false
    }

    private fun resultCleared(count: Int) {
        _result.value = null
        _messages.trySend(app.resources.getQuantityString(R.plurals.msg_result_cleared, count))
    }

    fun saveToDownloads() {
        val files = _result.value ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        save {
            val names = files.map { saver.saveToDownloads(it.file) }
            if (names.size == 1) app.getString(R.string.msg_saved_downloads, names[0])
            else app.resources.getQuantityString(R.plurals.msg_saved_downloads_many, names.size, names.size)
        }
    }

    /** Saves the single result into a document created with the system "Save as" screen. */
    fun saveTo(uri: Uri) {
        val file = _result.value?.singleOrNull() ?: return
        save {
            saver.saveTo(file.file, uri)
            app.getString(R.string.msg_saved)
        }
    }

    /** Saves every result into a folder picked with the system folder picker. */
    fun saveToFolder(tree: Uri) {
        val files = _result.value ?: return
        save {
            saver.saveToFolder(files.map { it.file }, tree)
            app.resources.getQuantityString(R.plurals.msg_saved_folder, files.size, files.size)
        }
    }

    fun shareIntent(files: List<PdfFile>): Intent = saver.shareIntent(files.map { it.file })

    fun viewIntent(file: PdfFile): Intent = saver.viewIntent(file.file)

    fun showMessage(@StringRes res: Int) = message(res)

    private fun save(block: suspend () -> String) {
        if (_busySaving.value) return
        _busySaving.value = true
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) { block() }
                _saved.value = true
                _messages.send(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val files = _result.value
                if (files != null && files.any { !it.file.isFile }) resultCleared(files.size)
                else message(R.string.msg_save_failed)
            } finally {
                _busySaving.value = false
            }
        }
    }

    private fun message(@StringRes res: Int, vararg args: Any) {
        _messages.trySend(app.getString(res, *args))
    }

    private fun pluralMessage(@PluralsRes res: Int, count: Int) {
        _messages.trySend(app.resources.getQuantityString(res, count, count))
    }

    override fun onCleared() {
        // The activity is finishing for good: picked copies are no longer needed. Finished PDFs
        // are kept until the next launch in case another app is still reading a shared one.
        val all = items.value
        Thread { all.forEach(store::delete) }.start()
    }

    private companion object {
        const val KEY_ITEMS = "items"
    }
}

/** Whether this failure (or one of its causes) is the storage running full. */
internal fun Throwable.isOutOfSpace(): Boolean {
    val text = generateSequence(this) { it.cause }.joinToString(" ") { it.message.orEmpty() }
    return "ENOSPC" in text || "No space left" in text
}
