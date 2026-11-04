package io.github.nath_goa.usugami.ui

import android.app.Application
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.core.os.bundleOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.core.Compression
import io.github.nath_goa.usugami.core.FileNames
import io.github.nath_goa.usugami.core.LayoutSettings
import io.github.nath_goa.usugami.core.NaturalOrder
import io.github.nath_goa.usugami.core.SharedFiles
import io.github.nath_goa.usugami.core.SharedKind
import io.github.nath_goa.usugami.core.ZipProgress
import io.github.nath_goa.usugami.core.isOutOfSpace
import io.github.nath_goa.usugami.data.AppLanguages
import io.github.nath_goa.usugami.data.DownloadsCopy
import io.github.nath_goa.usugami.data.ImageFormat
import io.github.nath_goa.usugami.data.ImageItem
import io.github.nath_goa.usugami.data.ImageStore
import io.github.nath_goa.usugami.data.ImageUnreadableException
import io.github.nath_goa.usugami.data.OcrEngine
import io.github.nath_goa.usugami.data.OcrLanguage
import io.github.nath_goa.usugami.data.OcrProblem
import io.github.nath_goa.usugami.data.OcrUnavailableException
import io.github.nath_goa.usugami.data.PdfExporter
import io.github.nath_goa.usugami.data.PdfFile
import io.github.nath_goa.usugami.data.PdfOcr
import io.github.nath_goa.usugami.data.PdfSaver
import io.github.nath_goa.usugami.data.ResultZip
import io.github.nath_goa.usugami.data.SettingsStore
import io.github.nath_goa.usugami.data.ShareInbox
import io.github.nath_goa.usugami.data.SharedFile
import io.github.nath_goa.usugami.data.Thumbnails
import io.github.nath_goa.usugami.data.ocrEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class SortMode { NAME_ASC, NAME_DESC, REVERSE }

/** [done] of [total] steps of [stage]; a [total] of 0 means the amount isn't known yet. */
data class Progress(val done: Int, val total: Int, val stage: Stage = Stage.CREATE) {
    enum class Stage { DOWNLOAD_OCR, CREATE, READ_TEXT, ADD_TEXT }
}

/** What the user chose in the Convert sheet. [ocrLanguage] is null when OCR is off. */
data class ConvertChoices(val fileName: String, val ocrLanguage: OcrLanguage?)

class MainViewModel(private val app: Application, private val handle: SavedStateHandle) : AndroidViewModel(app) {
    // Messages are written here, in the language the user picked (see AppLanguages.localized).
    private val strings: Resources get() = AppLanguages.localized(app).resources

    private val store = ImageStore(app)
    private val exporter = PdfExporter(app)
    /** Text recognition: null in Usugami Lite, which has none. */
    private val ocrEngine = ocrEngine(app)
    private val ocr by lazy { PdfOcr(app) }
    private val saver = PdfSaver(app)
    private val settingsStore = SettingsStore(app)
    val thumbnails = Thumbnails()

    /** Serialises file work (cleanup, imports, deletes) so they never race each other. */
    private val fileOps = Dispatchers.IO.limitedParallelism(1)

    private val restored = handle.contains(KEY_ITEMS)

    private val _items = MutableStateFlow(restoredItems())
    /** The picked images in page order. Survives rotation and the system killing the app (see [packItems]). */
    val items: StateFlow<List<ImageItem>> = _items.asStateFlow()

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

    /** True once Cancel has been pressed, until the work has stopped. */
    private val _cancelling = MutableStateFlow(false)
    val cancelling: StateFlow<Boolean> = _cancelling.asStateFlow()

    /** The result, whether it was saved and its name are kept in the saved state too. */
    private val keptResult = KeptResult(handle)

    /** Non-null once PDFs have been created: the result screen is showing. */
    private val _result = MutableStateFlow(keptResult.restore())
    val result: StateFlow<List<PdfFile>?> = _result.asStateFlow()

    private val _saved = MutableStateFlow(handle[KEY_SAVED] ?: false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    /** PDFs or a ZIP file of them are being saved, or waiting their turn. */
    private val _busySaving = MutableStateFlow(false)
    val busySaving: StateFlow<Boolean> = _busySaving.asStateFlow()
    /** How many saves are under way or waiting (see [startSave]). */
    private var saves = 0
    /** Saves of the PDFs go one at a time, in the order they were asked for. */
    private val saveTurn = Mutex()
    /** The result's copies in Downloads, once it has been saved there: saving it again copies only what has gone. */
    private var inDownloads: Pair<List<PdfFile>, List<DownloadsCopy>>? = null
    /**
     * The user put the last result away (Back, Start over, images shared), rather than the system
     * clearing it: a document picked for it afterwards was picked after leaving it (see [notSaved]).
     */
    private var resultPutAway = false

    /** Non-null while the "text recognition isn't available" dialog is showing. */
    private val _ocrProblem = MutableStateFlow<OcrProblem?>(null)
    val ocrProblem: StateFlow<OcrProblem?> = _ocrProblem.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    /** The name the last PDFs were made with, which a ZIP file of them is named after. */
    private var resultName: String
        get() = handle[KEY_RESULT_NAME] ?: ""
        set(value) { handle[KEY_RESULT_NAME] = value }
    private val zipper = ResultZipper(app, exporter.zipDir, viewModelScope, { strings }, { _messages.trySend(it) }, ::resultCleared)
    /** How far the ZIP file of the result's PDFs has got, while it is being made. */
    val zipProgress: StateFlow<ZipProgress?> = zipper.progress
    /** Share screens for ZIP files of the result's PDFs, once each is ready. */
    val zipShares: Flow<Intent> = zipper.shares

    private var nextId = maxOf(System.currentTimeMillis(), (items.value.maxOfOrNull { it.id } ?: 0L) + 1)
    private var conversion: Job? = null

    /** Bumped by [clearAll], so imports still running drop their images instead of bringing the list back. */
    private var importGeneration = 0

    /** Held by the batch of images being imported; the next batch waits for it (see [addImages]). */
    private val importLock = Mutex()

    /**
     * The choices of the last attempt that made no PDF (text recognition wasn't available, it failed
     * or it was cancelled), so the Convert sheet opens with them again instead of the defaults.
     */
    private var lastChoices: ConvertChoices? = null

    /** Copies of the PDFs and documents shared to Usugami, until the tool they open in takes them. */
    private val inbox = ShareInbox(app)

    /** The work on [inbox]: shares are sorted and copied in one at a time, in the order they came. */
    private val shareOps = Dispatchers.IO.limitedParallelism(1)

    /** Held by the share being sorted and copied in; the next one waits for it (see [share]). */
    private val shareLock = Mutex()

    /** Bumped by [dropShares], so shares still being copied in stop and leave nothing behind. */
    @Volatile private var shareGeneration = 0

    private val _sharedFiles = MutableStateFlow(handle.get<ArrayList<SharedFile>>(KEY_SHARED).orEmpty())
    /**
     * PDFs, Word, PowerPoint and EPUB files shared to Usugami, copied in and waiting for the tool
     * they open in to take them (see [takeSharedFiles]). Kept in the saved state.
     */
    val sharedFiles: StateFlow<List<SharedFile>> = _sharedFiles.asStateFlow()

    private val _sharing = MutableStateFlow<SharedKind?>(null)
    /** The kind of the shared files being copied in, while they are. */
    val sharing: StateFlow<SharedKind?> = _sharing.asStateFlow()

    init {
        handle.setSavedStateProvider(KEY_ITEMS) { packItems(_items.value) }
        val keep = if (restored) items.value else emptyList()
        val keepShared = _sharedFiles.value
        viewModelScope.launch {
            // Shared files left from an earlier session, or half copied, go; those waiting stay, unless
            // the system cleared them from the cache while Usugami was away.
            val missing = withContext(shareOps) {
                inbox.deleteAllExcept(keepShared)
                keepShared.filterNot { it.file.isFile }
            }
            if (missing.isNotEmpty()) {
                setShared(_sharedFiles.value - missing.toSet())
                pluralMessage(R.plurals.msg_files_cleared, missing.size)
            }
        }
        // The result that was showing when Android stopped Usugami in the background, if any.
        val shown = _result.value
        viewModelScope.launch {
            val (missing, resultGone) = withContext(fileOps) {
                // Leftovers from an earlier session (or half-copied files) are removed.
                store.deleteAllExcept(keep)
                val stillThere = shown?.takeIf { files -> files.all { it.file.isFile } }
                exporter.clearOutput(keep = stillThere?.map { it.file }.orEmpty())
                // The copies are in the cache, which the system may have cleared while Usugami was away.
                keep.filterNot(store::exists) to (stillThere == null)
            }
            if (missing.isNotEmpty()) forgetMissing(missing)
            if (shown != null && resultGone && _result.value === shown) resultCleared(shown.size)
        }
        viewModelScope.launch { _result.collect(keptResult::keep) }
        viewModelScope.launch { _saved.collect { handle[KEY_SAVED] = it } }
    }

    // ---- Picking images ----

    /** [refused] counts shared files that were turned away before import; they're reported as unreadable. */
    fun addImages(uris: List<Uri>, refused: Int = 0) {
        if (uris.isEmpty()) {
            if (refused > 0) pluralMessage(R.plurals.msg_failed_import, refused)
            return
        }
        putAwayResult()
        // Shared images open the editor: a ZIP file to share the PDFs that showed is no longer wanted.
        // One being saved where the user chose goes on, as a save of the PDFs does.
        zipper.stopSharing()
        _pendingImports.update { it + uris.size }
        val generation = importGeneration
        viewModelScope.launch {
            var unsupported = 0
            var failed = refused
            var noSpace = 0
            try {
                // One batch at a time, in the order they came: images picked or shared while others
                // are still being copied go after them, not in between.
                importLock.withLock {
                    for (uri in uris) {
                        // Discarded while waiting its turn.
                        if (generation != importGeneration) return@launch
                        val id = nextId++
                        val r = withContext(fileOps) { store.import(uri, id) }
                        if (generation != importGeneration) {
                            // Discarded while it was being copied.
                            if (r is ImageStore.ImportResult.Added) viewModelScope.launch(fileOps) { store.delete(r.item) }
                            return@launch
                        }
                        when (r) {
                            is ImageStore.ImportResult.Added -> setItems(items.value + r.item)
                            ImageStore.ImportResult.Unsupported -> unsupported++
                            ImageStore.ImportResult.Failed -> failed++
                            ImageStore.ImportResult.NoSpace -> noSpace++
                        }
                        _pendingImports.update { it - 1 }
                    }
                }
            } finally {
                // Nothing to report about images the user has discarded.
                if (generation == importGeneration) {
                    if (unsupported > 0) pluralMessage(R.plurals.msg_skipped_unsupported, unsupported)
                    if (failed > 0) pluralMessage(R.plurals.msg_failed_import, failed)
                    if (noSpace > 0) pluralMessage(R.plurals.msg_import_no_space, noSpace)
                }
            }
        }
    }

    // ---- Files shared from other apps ----

    /**
     * Takes in the files at [uris], shared to Usugami from another app as [sharedAs] (the share's
     * type), all of one kind (see [SharedFiles.chosen]): images go to the editor ([addImages]), PDFs,
     * Word, PowerPoint and EPUB files are copied in at once (the share lets Usugami read them for a
     * while only) and wait in [sharedFiles] for the tool they open in. The files of other kinds, and
     * those Usugami can't use, are left out, naming them. [refused] counts files turned away before.
     */
    fun share(uris: List<Uri>, sharedAs: String?, refused: Int = 0) {
        if (uris.isEmpty()) return addImages(emptyList(), refused)
        val generation = shareGeneration
        viewModelScope.launch {
            // One share at a time, in the order they came: the files of one shared while another is
            // being copied in go after them.
            shareLock.withLock {
                val described = withContext(shareOps) { uris.map(inbox::describe) }
                // The share's own type tells what a file is only when its type and name don't.
                val kinds = described.map { (type, name) -> SharedFiles.kindOf(type, name, sharedAs) }
                val kind = SharedFiles.chosen(kinds)
                val names = uris.indices.map { i -> described[i].second ?: fallbackName(uris[i], kinds[i]) }
                leftOut(R.plurals.share_unsupported, names.filterIndexed { i, _ -> kinds[i] == SharedKind.OTHER })
                leftOut(R.plurals.share_other_kind, names.filterIndexed { i, _ -> kinds[i] != SharedKind.OTHER && kinds[i] != kind })
                val taken = uris.indices.filter { kinds[it] == kind }
                when (kind) {
                    null -> if (refused > 0) pluralMessage(R.plurals.msg_share_unreadable, refused)
                    SharedKind.IMAGE -> addImages(taken.map { uris[it] }, refused)
                    else -> {
                        if (refused > 0) pluralMessage(R.plurals.msg_share_unreadable, refused)
                        copyShared(generation, kind, taken.map { uris[it] to names[it] })
                    }
                }
            }
        }
    }

    /** Copies [files] (each one's address and name), shared files of [kind], into [sharedFiles]. */
    private suspend fun copyShared(generation: Int, kind: SharedKind, files: List<Pair<Uri, String>>) {
        fun dropped() = generation != shareGeneration
        if (dropped()) return
        _sharing.value = kind
        val copies = ArrayList<SharedFile>()
        var failed = 0
        var noSpace = false
        try {
            for ((uri, name) in files) {
                val id = nextId++
                try {
                    copies += withContext(shareOps) {
                        inbox.copy(uri, id, name, kind) { if (dropped()) throw ShareDropped() }
                    }
                } catch (_: ShareDropped) {
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e.isOutOfSpace()) noSpace = true else failed++
                }
            }
        } finally {
            if (dropped()) {
                withContext(NonCancellable + shareOps) { copies.forEach(inbox::delete) }
            } else {
                // Listed before the copying is said to be over, so they are never taken for gone.
                setShared(_sharedFiles.value + copies)
                if (failed > 0) pluralMessage(R.plurals.msg_share_unreadable, failed)
                if (noSpace) message(R.string.open_no_space)
                _sharing.value = null
            }
        }
    }

    /**
     * The shared files waiting, for the tool they open in to take them. They are of one kind; files
     * of another kind shared while they waited are left out (see [SharedFiles.chosen]), naming them.
     */
    fun takeSharedFiles(): List<SharedFile> {
        val all = _sharedFiles.value
        val kind = SharedFiles.chosen(all.map { it.kind }) ?: return emptyList()
        val (taken, others) = all.partition { it.kind == kind }
        setShared(emptyList())
        if (others.isNotEmpty()) {
            leftOut(R.plurals.share_other_kind, others.map { it.name })
            viewModelScope.launch(shareOps) { others.forEach(inbox::delete) }
        }
        return taken
    }

    /** Forgets the shared files waiting or being copied in: the user left the screen they were opening in. */
    fun dropShares() {
        shareGeneration++
        val dropped = _sharedFiles.value
        setShared(emptyList())
        _sharing.value = null
        if (dropped.isNotEmpty()) viewModelScope.launch(shareOps) { dropped.forEach(inbox::delete) }
    }

    /**
     * For tests: returns once the shares started so far are sorted and copied in, and the cleanup of
     * leftovers a new view model starts is done.
     */
    @VisibleForTesting
    internal suspend fun awaitShares() = shareLock.withLock { withContext(shareOps) {} }

    private fun setShared(list: List<SharedFile>) {
        _sharedFiles.value = list
        handle[KEY_SHARED] = ArrayList(list)
    }

    /** Says the files [names] were left out of a share, [res] saying why. */
    private fun leftOut(@PluralsRes res: Int, names: List<String>) {
        if (names.isNotEmpty()) _messages.trySend(strings.getQuantityString(res, names.size, names.size, names.joinToString(", ")))
    }

    /** A name for a shared file whose app gives it none, to say which it is. */
    private fun fallbackName(uri: Uri, kind: SharedKind): String = when (kind) {
        SharedKind.PDF -> "Document.pdf"
        SharedKind.WORD -> "Document.docx"
        SharedKind.POWERPOINT -> "Presentation.pptx"
        SharedKind.EPUB -> "Book.epub"
        else -> uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "File"
    }

    // ---- Editing ----

    fun rotate(id: Long, degrees: Int) = setItems(items.value.map { if (it.id == id) it.rotatedBy(degrees) else it })

    fun remove(id: Long) {
        val item = items.value.firstOrNull { it.id == id } ?: return
        setItems(items.value - item)
        thumbnails.remove(id)
        viewModelScope.launch(fileOps) { store.delete(item) }
    }

    /** Removes every image, including those still being imported. */
    fun clearAll() {
        importGeneration++
        _pendingImports.value = 0
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
        _items.value = list
        if (list.isEmpty()) lastChoices = null
    }

    /** The images [packItems] kept in the saved state, if Android restarted Usugami. */
    private fun restoredItems(): List<ImageItem> {
        val saved = handle.get<Bundle>(KEY_ITEMS) ?: return emptyList()
        val ids = saved.getLongArray("ids") ?: return emptyList()
        val sizes = saved.getIntArray("sizes") ?: return emptyList()
        val details = saved.getIntArray("details") ?: return emptyList()
        if (ids.isEmpty() || sizes.size != 2 * ids.size || details.size != ids.size) return emptyList()
        val names = saved.getByteArray("names")?.decodeToString()?.split('/').orEmpty()
        return ids.indices.map { i ->
            val format = ImageFormat.entries[details[i] shr 16]
            ImageItem(
                id = ids[i],
                path = store.fileFor(ids[i], format).path,
                name = names.getOrNull(i) ?: "Image",
                format = format,
                width = sizes[2 * i],
                height = sizes[2 * i + 1],
                exifOrientation = details[i] shr 8 and 0xFF,
                rotation = (details[i] and 0xFF) * 90,
            )
        }
    }

    /** Drops images whose copies the system has cleared from the cache, and says so. */
    private fun forgetMissing(missing: List<ImageItem>) {
        val ids = missing.mapTo(HashSet()) { it.id }
        setItems(items.value.filterNot { it.id in ids })
        ids.forEach(thumbnails::remove)
        pluralMessage(R.plurals.msg_files_cleared, ids.size)
    }

    // ---- Converting ----

    /** What the Convert sheet starts with: the last attempt's choices if it made no PDF, otherwise the defaults. */
    fun initialChoices(): ConvertChoices = lastChoices ?: ConvertChoices(defaultFileName(), ocrLanguage = null)

    /** Suggested name for a merged PDF: the image's own name for one image, otherwise a timestamp. */
    private fun defaultFileName(): String {
        val list = items.value
        if (list.size == 1) return FileNames.sanitize(FileNames.baseName(list[0].name), PdfExporter.DEFAULT_NAME)
        return "Usugami_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT))
    }

    fun convert(merge: Boolean, fileName: String, compression: Compression, ocrLanguage: OcrLanguage? = null) {
        val engine = ocrEngine
        // Usugami Lite has no text recognition, so it never adds a text layer.
        val language = ocrLanguage?.takeIf { engine != null }
        val snapshot = items.value
        if (snapshot.isEmpty() || _progress.value != null || _pendingImports.value > 0) return
        if (snapshot.size > 1) {
            _merge.value = merge
            settingsStore.merge = merge
        }
        _compression.value = compression
        settingsStore.compression = compression
        lastChoices = ConvertChoices(fileName, language)
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
                    if (engine != null && language != null) {
                        // Before any PDF work, so a missing recognizer is reported straight away.
                        engine.ensureReady(language) { fraction ->
                            _progress.value = if (fraction == null) Progress(0, 0, Progress.Stage.DOWNLOAD_OCR)
                            else Progress((fraction * 100).toInt(), 100, Progress.Stage.DOWNLOAD_OCR)
                        }
                        _progress.value = Progress(0, snapshot.size)
                    }
                    val files = exporter.export(snapshot, layout, compression, merge || snapshot.size == 1, fileName, strings.getString(R.string.app_name)) { done ->
                        _progress.value = Progress(done, snapshot.size)
                    }
                    if (engine == null || language == null) files else addTextLayer(files, engine, language)
                }
                _saved.value = false
                resultPutAway = false
                resultName = fileName
                _result.value = files
                lastChoices = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: OcrUnavailableException) {
                if (e.problem == OcrProblem.FAILED_TO_START) Log.e("Usugami", "Text recognition couldn't start", e)
                else Log.w("Usugami", "Text recognition unavailable", e)
                _ocrProblem.value = e.problem
            } catch (e: ImageUnreadableException) {
                // Its copy may have been cleared from the cache while the PDF was being made.
                val missing = withContext(Dispatchers.IO) { snapshot.filterNot(store::exists) }
                if (missing.isNotEmpty()) forgetMissing(missing)
                else message(R.string.msg_convert_failed, strings.getString(R.string.msg_image_unreadable, e.imageName))
            } catch (e: IOException) {
                Log.e("Usugami", "PDF generation failed", e)
                message(R.string.msg_convert_failed, strings.getString(if (e.isOutOfSpace()) R.string.msg_out_of_space else R.string.msg_unknown_error))
            } catch (e: OutOfMemoryError) {
                message(R.string.msg_convert_failed, strings.getString(R.string.msg_out_of_memory))
            } catch (e: Exception) {
                Log.e("Usugami", "PDF generation failed", e)
                message(R.string.msg_convert_failed, strings.getString(R.string.msg_unknown_error))
            } finally {
                _progress.value = null
                _cancelling.value = false
                conversion = null
            }
        }
    }

    /**
     * Returns [files] with a searchable text layer. If recognition fails, the PDFs are kept as they
     * are, without the text layer, and a message says so.
     */
    private suspend fun addTextLayer(files: List<PdfFile>, engine: OcrEngine, language: OcrLanguage): List<PdfFile> {
        val pages = files.sumOf { it.pageCount }
        _progress.value = Progress(0, pages, Progress.Stage.READ_TEXT)
        return try {
            val output = ocr.apply(files, engine, language) { done ->
                // Once every page is read, only writing the text layer is left, which takes a while
                // for a long PDF.
                _progress.value = if (done < pages) Progress(done, pages, Progress.Stage.READ_TEXT)
                else Progress(0, 0, Progress.Stage.ADD_TEXT)
            }
            if (output.lineCount == 0) message(R.string.ocr_no_text)
            output.files
        } catch (e: CancellationException) {
            throw e
        } catch (e: OcrUnavailableException) {
            // The model went missing after ensureReady found it (standard edition). Not the OCR
            // problem dialog: the PDF has been made, just without text.
            Log.w("Usugami", "Text recognition unavailable", e)
            message(R.string.ocr_not_ready)
            files
        } catch (e: Exception) {
            Log.e("Usugami", "Text recognition failed", e)
            message(if (e.isOutOfSpace()) R.string.ocr_failed_no_space else R.string.ocr_failed)
            files
        } catch (e: OutOfMemoryError) {
            Log.w("Usugami", "Text recognition ran out of memory", e)
            message(R.string.ocr_failed)
            files
        }
    }

    fun cancelConversion() {
        val job = conversion ?: return
        _cancelling.value = true
        job.cancel()
    }

    fun dismissOcrProblem() {
        _ocrProblem.value = null
    }

    // ---- Result ----

    fun backToEditing() {
        // Not while the PDFs (or a ZIP file of them) are being saved: converting again would replace
        // them mid-save, and leaving would cancel a ZIP file, deleting the document the user picked.
        if (_busySaving.value) return
        // A ZIP file of the result is no longer wanted once the result screen has gone.
        zipper.cancel()
        putAwayResult()
    }

    /** The result showing goes, because the user left it (see [resultPutAway]). */
    private fun putAwayResult() {
        if (_result.value != null) resultPutAway = true
        _result.value = null
    }

    fun startOver() {
        // Not while the PDFs are being saved: their files would be deleted mid-save.
        if (_busySaving.value) return
        zipper.cancel()
        putAwayResult()
        clearAll()
        viewModelScope.launch(fileOps) { exporter.clearOutput() }
    }

    /**
     * For tests: returns once the file work started so far is done, such as the cleanup
     * [startOver] starts, or the one a new view model starts, which would otherwise delete files
     * the next test has just made.
     */
    @VisibleForTesting
    internal suspend fun awaitFileWork() = withContext(fileOps) {}

    val canSaveToDownloads: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /**
     * The finished PDFs are in the cache, which the system may clear while Usugami is in the background.
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
        _messages.trySend(strings.getQuantityString(R.plurals.msg_result_cleared, count))
    }

    fun saveToDownloads() {
        val files = _result.value ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        // A second tap that lands before the button is turned off does nothing.
        if (_busySaving.value) return
        save {
            // Saved there already (a second tap, say), it only says so again.
            val copies = saver.saveToDownloads(files.map { it.file }, earlier = inDownloads?.takeIf { it.first === files }?.second)
            inDownloads = files to copies
            val names = copies.map { it.name }
            if (names.size == 1) strings.getString(R.string.msg_saved_downloads, names[0])
            else strings.getQuantityString(R.plurals.msg_saved_downloads_many, names.size, names.size)
        }
    }

    /** Saves the single result into a document created with the system "Save as" screen. */
    fun saveTo(uri: Uri) {
        val file = _result.value?.singleOrNull()
        if (file == null) {
            // The system restarted Usugami while the screen was open, which cleared the PDF, or the
            // user left the result before the screen covered Usugami. Remove the empty document the
            // screen made, and say so.
            viewModelScope.launch(Dispatchers.IO) { saver.discardIfEmpty(uri) }
            if (resultPutAway) notSaved() else resultCleared(1)
            return
        }
        save {
            saver.saveTo(file.file, uri)
            strings.getString(R.string.msg_saved)
        }
    }

    /** Saves every result into a folder picked with the system folder picker. */
    fun saveToFolder(tree: Uri) {
        // As in saveTo; the folder picker offered for several PDFs creates nothing itself.
        val files = _result.value ?: return if (resultPutAway) notSaved() else resultCleared(2)
        save {
            saver.saveToFolder(files.map { it.file }, tree)
            strings.getQuantityString(R.plurals.msg_saved_folder, files.size, files.size)
        }
    }

    fun shareIntent(files: List<PdfFile>): Intent = saver.shareIntent(files.map { it.file })

    fun viewIntent(file: PdfFile): Intent = saver.viewIntent(file.file)

    /** The name the ZIP file of the result's PDFs gets: the name the Convert sheet had. */
    fun zipFileName(): String = ResultZip.fileName(resultName)

    /** Makes one ZIP file of the PDFs, one per image, and shares it (see [zipShares]). */
    fun shareZip() {
        val files = _result.value ?: return
        zipper.share(files, resultName)
    }

    /** Saves one ZIP file of the PDFs into a document created with the system "Save as" screen. */
    fun saveZipTo(uri: Uri) {
        val files = _result.value
        if (files == null) {
            // As in saveTo: the system restarted Usugami while the screen was open, or the user left.
            viewModelScope.launch(Dispatchers.IO) { saver.discardIfEmpty(uri) }
            if (resultPutAway) notSaved() else resultCleared(2)
            return
        }
        if (zipper.busy) return zipBusy(uri)
        // Counted as saving, so that Back and Start over wait for it as they do for a save of the PDFs.
        startSave()
        zipper.saveTo(files, uri, onSaved = { _saved.value = true }, finished = ::endSave)
    }

    /** The ZIP file to go into [uri] can't be made while another is: the empty document isn't left behind. */
    private fun zipBusy(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) { saver.discardIfEmpty(uri) }
        message(R.string.zip_busy)
    }

    /** A save screen gave a place to save a result the user had already put away: nothing is saved there. */
    private fun notSaved() = message(R.string.msg_not_saved)

    fun cancelZip() = zipper.cancel()

    fun showMessage(@StringRes res: Int) = message(res)

    private fun startSave() {
        saves++
        _busySaving.value = true
    }

    private fun endSave() {
        if (--saves == 0) _busySaving.value = false
    }

    /**
     * Runs [block], which saves the result, once the saves asked for before it are done. A document
     * picked while another save is under way gets its PDF too, rather than being left empty.
     */
    private fun save(block: suspend () -> String) {
        startSave()
        val saving = _result.value
        viewModelScope.launch {
            try {
                val text = saveTurn.withLock { withContext(Dispatchers.IO) { block() } }
                _saved.value = true
                _messages.send(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val files = _result.value
                when {
                    // Its PDFs are still there, so the save itself failed (the storage may be full), also
                    // when images shared meanwhile put the result away.
                    saving != null && saving.all { it.file.isFile } -> message(R.string.msg_save_failed)
                    files != null && files === saving -> resultCleared(files.size)
                    // Otherwise the result was put away and its PDFs have gone: cleared, which was said then.
                }
            } finally {
                endSave()
            }
        }
    }

    private fun message(@StringRes res: Int, vararg args: Any) {
        _messages.trySend(strings.getString(res, *args))
    }

    private fun pluralMessage(@PluralsRes res: Int, count: Int) {
        _messages.trySend(strings.getQuantityString(res, count, count))
    }

    private var finishing = false

    /** Called when the activity is finishing for good, so [onCleared] can free the copies. */
    fun activityFinishing() {
        finishing = true
    }

    override fun onCleared() {
        // A view model is also cleared when the system destroys the activity but keeps the process.
        // The saved state still lists the copies then, so they're only freed when the user has left
        // for good. Anything left behind goes at the next fresh launch. Finished PDFs are kept until
        // then in case another app is still reading a shared one.
        if (!finishing) return
        val all = items.value
        val shared = _sharedFiles.value
        Thread {
            all.forEach(store::delete)
            shared.forEach(inbox::delete)
        }.start()
    }

    companion object {
        private const val KEY_ITEMS = "items"
        private const val KEY_SAVED = "saved"
        private const val KEY_RESULT_NAME = "result_name"
        private const val KEY_SHARED = "shared_files"

        /**
         * [list] for the saved state, in a few bytes per image: a list of [ImageItem]s took hundreds,
         * and a few thousand images were more than the saved state can hold, which crashed Usugami as
         * it went to the background. The copies' paths follow from their ids (see [ImageStore.fileFor]),
         * and names never contain "/" (they are what follows the last one).
         */
        @VisibleForTesting
        internal fun packItems(list: List<ImageItem>): Bundle = bundleOf(
            "ids" to LongArray(list.size) { list[it].id },
            "names" to list.joinToString("/") { it.name }.encodeToByteArray(),
            "sizes" to IntArray(2 * list.size) { if (it % 2 == 0) list[it / 2].width else list[it / 2].height },
            "details" to IntArray(list.size) { list[it].run { format.ordinal shl 16 or (exifOrientation shl 8) or rotation / 90 } },
        )
    }
}

/** The shared files being copied in were dropped (see [MainViewModel.dropShares]). */
private class ShareDropped : Exception()
