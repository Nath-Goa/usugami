package io.github.nath_goa.usugami.ui

import android.app.Application
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.core.os.bundleOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.core.BatchChoices
import io.github.nath_goa.usugami.core.Compressor
import io.github.nath_goa.usugami.core.EditLayout
import io.github.nath_goa.usugami.core.FileNames
import io.github.nath_goa.usugami.core.InkItem
import io.github.nath_goa.usugami.core.PageItem
import io.github.nath_goa.usugami.core.PageNumberPosition
import io.github.nath_goa.usugami.core.PageNumberSize
import io.github.nath_goa.usugami.core.PageNumbers
import io.github.nath_goa.usugami.core.PageSize
import io.github.nath_goa.usugami.core.PageSelectionException
import io.github.nath_goa.usugami.core.PdfPageSelection
import io.github.nath_goa.usugami.core.PictureItem
import io.github.nath_goa.usugami.core.RedactBox
import io.github.nath_goa.usugami.core.Redaction
import io.github.nath_goa.usugami.core.SharedKind
import io.github.nath_goa.usugami.core.SignaturePlacement
import io.github.nath_goa.usugami.core.isOutOfSpace
import io.github.nath_goa.usugami.data.AppLanguages
import io.github.nath_goa.usugami.data.CompressJob
import io.github.nath_goa.usugami.data.DownloadsCopy
import io.github.nath_goa.usugami.data.NothingToFlatten
import io.github.nath_goa.usugami.data.NothingToNumber
import io.github.nath_goa.usugami.data.NumberingJob
import io.github.nath_goa.usugami.data.PagePictures
import io.github.nath_goa.usugami.data.PdfCertificateEncrypted
import io.github.nath_goa.usugami.data.PdfCompression
import io.github.nath_goa.usugami.data.PdfFile
import io.github.nath_goa.usugami.data.PdfInput
import io.github.nath_goa.usugami.data.PdfPageRef
import io.github.nath_goa.usugami.data.PdfPasswordRequired
import io.github.nath_goa.usugami.data.PdfSaver
import io.github.nath_goa.usugami.data.PdfToolsEngine
import io.github.nath_goa.usugami.data.PdfWrongPassword
import io.github.nath_goa.usugami.data.ResultZip
import io.github.nath_goa.usugami.data.SharedFile
import io.github.nath_goa.usugami.data.Signature
import io.github.nath_goa.usugami.data.ToolJob
import io.github.nath_goa.usugami.data.WatermarkColor
import io.github.nath_goa.usugami.data.WatermarkFontMissing
import io.github.nath_goa.usugami.data.WatermarkOpacity
import io.github.nath_goa.usugami.data.WatermarkText
import io.github.nath_goa.usugami.data.office.ConvertJob
import io.github.nath_goa.usugami.data.office.EpubProtected
import io.github.nath_goa.usugami.data.office.OfficeConverter
import io.github.nath_goa.usugami.data.office.OfficeEmbeddedContent
import io.github.nath_goa.usugami.data.office.OfficeEncrypted
import io.github.nath_goa.usugami.data.office.OfficeFileProblem
import io.github.nath_goa.usugami.data.office.OfficeInput
import io.github.nath_goa.usugami.data.office.OfficeKind
import io.github.nath_goa.usugami.data.office.OfficeLegacyFormat
import io.github.nath_goa.usugami.data.office.OfficeNotRecognized
import io.github.nath_goa.usugami.data.office.OfficeNothingToConvert
import io.github.nath_goa.usugami.data.office.OfficeProgress
import io.github.nath_goa.usugami.data.office.OfficeTooLarge
import io.github.nath_goa.usugami.data.office.SheetOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The PDF tools. Each has a screen at its lowercase name. All but MERGE work on one PDF, except WORD,
 * POWERPOINT, EXCEL and EPUB, which turn one Word, PowerPoint, Excel or EPUB file into a PDF.
 * REORGANIZE works on one PDF and can take pages from others. EDIT puts text, drawings, shapes and
 * pictures on a PDF's pages. REDACT removes what is under its boxes for good. COMPRESS, PAGE_NUMBERS,
 * WATERMARK and FLATTEN also work on several PDFs, and WORD, POWERPOINT, EXCEL and EPUB on several
 * files, one after another, each into a PDF of its own (see [isBatch]).
 */
enum class PdfTool {
    SPLIT, MERGE, COMPRESS, ROTATE, PAGE_NUMBERS, WATERMARK, SIGN, WORD, POWERPOINT, EPUB,
    DELETE_PAGES, REORGANIZE, FLATTEN, EDIT, REDACT, EXCEL,
}

/**
 * Whether the tool takes several PDFs (or, for Word, PowerPoint, Excel and EPUB to PDF, several files) at
 * once and makes a PDF of each, with a name and choices of its own (see [PdfToolsViewModel.batch]).
 * With one it works as the other tools do.
 */
val PdfTool.isBatch: Boolean
    get() = when (this) {
        PdfTool.COMPRESS, PdfTool.PAGE_NUMBERS, PdfTool.WATERMARK, PdfTool.FLATTEN, PdfTool.WORD, PdfTool.POWERPOINT, PdfTool.EXCEL, PdfTool.EPUB -> true
        else -> false
    }

/** The kind of file a tool converts to PDF; null for the tools that work on PDFs. */
val PdfTool.officeKind: OfficeKind?
    get() = when (this) {
        PdfTool.WORD -> OfficeKind.WORD
        PdfTool.POWERPOINT -> OfficeKind.POWERPOINT
        PdfTool.EXCEL -> OfficeKind.EXCEL
        PdfTool.EPUB -> OfficeKind.EPUB
        else -> null
    }
enum class SplitMode { SELECTED, CUSTOM, FIXED, EVERY_PAGE }

/** Compress PDF's level for PDF [id] (see [PdfToolsViewModel.batch]). */
fun BatchChoices.compressionOf(id: Long): PdfCompression = PdfCompression.entries.getOrElse(choiceOf(id)) { PdfCompression.RECOMMENDED }

/** A tool working on several PDFs one after another is on PDF [number] (from 1) of [total], [name]. */
data class BatchProgress(val number: Int, val total: Int, val name: String)

class PdfToolsViewModel(private val app: Application, private val handle: SavedStateHandle) : AndroidViewModel(app) {
    // Messages are written here, in the language the user picked (see AppLanguages.localized).
    private val strings: Resources get() = AppLanguages.localized(app).resources

    private val engine = PdfToolsEngine(app)
    private val office = OfficeConverter(app, engine)
    private val saver = PdfSaver(app)
    private val pictures = PagePictures(app)
    private val history = EditHistory(handle)
    private val fileOps = Dispatchers.IO.limitedParallelism(1)
    private val restored = handle.contains(KEY_INPUTS)

    val inputs: StateFlow<List<PdfInput>> = handle.getStateFlow(KEY_INPUTS, ArrayList<PdfInput>())

    /**
     * The pages to save, in order. In the saved state they take a few bytes each (see [savePages]),
     * and nothing while they are every page of the PDFs in order, as most tools leave them: a long
     * PDF's pages as a list of objects would be more than the saved state can hold.
     */
    private val _pages = MutableStateFlow(restoredPages())
    val pages: StateFlow<List<PdfPageRef>> = _pages.asStateFlow()
    /** Picked PDFs that need a password, in the order they were picked. The dialog asks for the first. */
    val locked: StateFlow<List<PdfInput>> = handle.getStateFlow(KEY_LOCKED, ArrayList<PdfInput>())

    /**
     * The signature drawn for Sign PDF. It stays for the whole session, so it can go on another PDF
     * without drawing it again: [reset] keeps it. It lives only here, in the saved state.
     */
    val signature: StateFlow<Signature?> = handle.getStateFlow(KEY_SIGNATURE, null)

    /** The Word, PowerPoint, Excel or EPUB files picked for Word, PowerPoint, Excel or EPUB to PDF, in order. */
    val documents: StateFlow<List<OfficeInput>> = handle.getStateFlow(KEY_DOCUMENTS, ArrayList<OfficeInput>())

    /**
     * For a tool that works on several PDFs or files at once ([isBatch]): the name each one's PDF
     * gets, and its own choice (Compress PDF's level, by [PdfCompression.ordinal]). They follow
     * [inputs] and [documents], and are kept in the saved state in a few bytes per file.
     */
    private val _batch = MutableStateFlow(restoredBatch())
    val batch: StateFlow<BatchChoices> = _batch.asStateFlow()

    /** Which PDF is being made, while a tool works on several one after another. */
    private val _batchProgress = MutableStateFlow<BatchProgress?>(null)
    val batchProgress = _batchProgress.asStateFlow()

    /**
     * Redact PDF's boxes, on the pages as shown (see [RedactBox]), the page showing and the box
     * chosen (an index into the boxes, or -1). They survive process death in the saved state.
     */
    private val _redactBoxes = MutableStateFlow(Redaction.unpack(handle[KEY_REDACT_BOXES] ?: FloatArray(0)))
    val redactBoxes = _redactBoxes.asStateFlow()
    val redactPage: StateFlow<Int> = handle.getStateFlow(KEY_REDACT_PAGE, 0)
    val redactSelected: StateFlow<Int> = handle.getStateFlow(KEY_REDACT_SELECTED, -1)

    /**
     * What Find text found in the PDF being redacted, as typed: Redact also takes out the form
     * fields that hold it where no page shows them (see [PdfToolsEngine.redact]). Kept until the
     * boxes are all cleared, or the PDF is put away, in the saved state too.
     */
    private var redactSearched: List<String>
        get() = handle.get<ArrayList<String>>(KEY_REDACT_SEARCHED).orEmpty()
        set(value) { handle[KEY_REDACT_SEARCHED] = ArrayList(value) }

    /** The PDF the tool draws its preview from (see [PdfToolsEngine.previewView]); null until it is ready. */
    private val _previewView = MutableStateFlow<PdfInput?>(null)
    val previewView = _previewView.asStateFlow()

    /**
     * The views (see [PdfToolsEngine.previewView]) of the PDFs opened with a password, by id, once
     * ready: Android's renderer can't open those, so their pages' thumbnails are drawn from these.
     */
    private val _views = MutableStateFlow<Map<Long, PdfInput>>(emptyMap())
    val views = _views.asStateFlow()
    /** The views being made, by id; one stops when its PDF is put away. */
    private val viewJobs = HashMap<Long, Job>()

    /** Find text is looking through the PDF. */
    private val _finding = MutableStateFlow(false)
    val finding = _finding.asStateFlow()

    /** How far converting the file in [documents] being converted has got. */
    private val _convertProgress = MutableStateFlow<OfficeProgress?>(null)
    val convertProgress = _convertProgress.asStateFlow()

    /**
     * Page lists before the latest changes, newest last, for Undo. Kept in memory only: lists of a
     * long PDF's pages are too big for the saved state, and losing Undo when Android closes
     * Usugami in the background loses no work.
     */
    private val undoStack = ArrayDeque<List<PdfPageRef>>()
    private val _canUndo = MutableStateFlow(false)
    val canUndo = _canUndo.asStateFlow()

    /** Edit PDF's items on the PDF's pages, in the order they are drawn (the last on top). */
    val editItems: StateFlow<List<PageItem>> = history.items

    /** The id of the item chosen in Edit PDF, or null. */
    val editSelected: StateFlow<Long?> = history.selected
    val canUndoEdit = history.canUndo
    val canRedoEdit = history.canRedo

    /** The password is being tried on the first locked PDF. */
    private val _unlocking = MutableStateFlow(false)
    val unlocking = _unlocking.asStateFlow()
    /** The last password tried on the first locked PDF didn't open it. */
    private val _wrongPassword = MutableStateFlow(false)
    val wrongPassword = _wrongPassword.asStateFlow()
    /** The result, whether it was saved, and its name and note are kept in the saved state too. */
    private val keptResult = KeptResult(handle)
    private val _result = MutableStateFlow(keptResult.restore())
    val result = _result.asStateFlow()
    private val _processing = MutableStateFlow(false)
    val processing = _processing.asStateFlow()
    private val _importing = MutableStateFlow(false)
    val importing = _importing.asStateFlow()
    /** The result's PDFs or a ZIP file of them are being saved, or waiting their turn. */
    private val _saving = MutableStateFlow(false)
    val saving = _saving.asStateFlow()
    /** How many saves are under way or waiting (see [startSave]). */
    private var saves = 0
    /**
     * The result's copies in Downloads by path, once saved there: saving them again copies only what
     * has gone. Used in file work only.
     */
    private var inDownloads: Pair<List<PdfFile>, MutableMap<String, DownloadsCopy>>? = null
    /** The result's copies in the folder it was saved to last, by path, the same way. In file work only. */
    private var inFolder: Triple<List<PdfFile>, Uri, MutableMap<String, Uri>>? = null
    /**
     * The user put the last result away (Back, Start over, leaving the tool), rather than the system
     * clearing it: a document picked for it afterwards was picked after leaving it (see [notSaved]).
     */
    private var resultPutAway = false
    /** Whether every PDF of the result has been saved, all at once or each on its own. */
    private val _saved = MutableStateFlow(handle[KEY_SAVED] ?: false)
    val saved = _saved.asStateFlow()
    /** The names of the result's PDFs saved so far, all at once or each on its own. */
    private val _savedFiles = MutableStateFlow(restoredSavedFiles())
    val savedFiles = _savedFiles.asStateFlow()
    /** True when the result was made from a PDF opened with a password: the new PDF has none. */
    private val _passwordRemoved = MutableStateFlow(handle[KEY_PASSWORD_REMOVED] ?: false)
    val passwordRemoved = _passwordRemoved.asStateFlow()
    /**
     * Messages waiting to show or showing. The same one again meanwhile, as when a button is tapped
     * over and over, isn't put behind it: it would show again and again, long after the taps.
     */
    private val unshown: MutableSet<String> = ConcurrentHashMap.newKeySet()
    // A message lost on its way to the screen, as the screen goes (rotated, say) just as it is
    // handed over, is no longer waiting either: the next one like it shows.
    private val _messages = Channel<String>(Channel.BUFFERED, onUndeliveredElement = { unshown -= it })
    /** Messages to show, one at a time: each is done with when its collector asks for the next. */
    val messages: Flow<String> = flow {
        for (text in _messages) {
            try {
                emit(text)
            } finally {
                unshown -= text
            }
        }
    }

    /** What the last run was named (the file name typed), which a ZIP file of its PDFs is named after. */
    private var resultName: String
        get() = handle[KEY_RESULT_NAME] ?: ""
        set(value) { handle[KEY_RESULT_NAME] = value }
    // A ZIP file is written with the other file work, one at a time: freeing the result's PDFs (see
    // reset) waits for a ZIP file of them being saved, as it does for a save of the PDFs.
    private val zipper = ResultZipper(app, engine.zipDir, viewModelScope, { strings }, ::message, ::resultCleared, io = fileOps)
    /** How far the ZIP file of the result's PDFs has got, while it is being made. */
    val zipProgress = zipper.progress
    /** Share screens for ZIP files of the result's PDFs, once each is ready. */
    val zipShares = zipper.shares

    private var nextId = maxOf(System.currentTimeMillis(), (((inputs.value + locked.value).map { it.id } + documents.value.map { it.id }).maxOrNull() ?: 0) + 1)
    private var task: Job? = null
    private var importJob: Job? = null
    private var viewJob: Job? = null
    /** The id of the PDF [viewJob] makes the view of. */
    private var viewJobInput: Long? = null
    private var findJob: Job? = null
    /** Changes when an import starts or is abandoned, so an abandoned one knows to stay quiet. */
    private var importGeneration = 0

    init {
        val keep = if (restored) inputs.value + locked.value else emptyList()
        val keepDocuments = documents.value
        val keepPictures = history.items.value.filterIsInstance<PictureItem>().map { it.path }
        // The result that was showing when Android stopped Usugami in the background, if any.
        val shown = _result.value
        viewModelScope.launch {
            var resultGone = false
            val (missing, documentsMissing) = withContext(fileOps) {
                // Leftovers from an earlier session, including PDFBox scratch files from a killed run.
                engine.deleteAllExcept(keep)
                office.deleteAllExcept(keepDocuments)
                pictures.deleteAllExcept(keepPictures)
                val stillThere = shown?.takeIf { files -> files.all { it.file.isFile } }
                resultGone = stillThere == null
                engine.clearOutput(keep = stillThere?.map { it.file }.orEmpty())
                engine.clearScratch()
                office.clearWork()
                // The copies are in the cache, which the system may have cleared while Usugami was away.
                keep.filterNot { it.file.isFile } to keepDocuments.filterNot { it.file.isFile }
            }
            val picturesMissing = withContext(fileOps) { keepPictures.filterNot { File(it).isFile } }
            if (missing.isNotEmpty()) forgetInputs(missing)
            if (documentsMissing.isNotEmpty()) forgetDocuments(documentsMissing)
            if (picturesMissing.isNotEmpty()) forgetPictures(picturesMissing)
            if (shown != null && resultGone && _result.value === shown) resultCleared(shown.size)
            // The views were cleared with the leftovers.
            prepareViews(inputs.value)
        }
        viewModelScope.launch { _result.collect(keptResult::keep) }
        viewModelScope.launch { _saved.collect { handle[KEY_SAVED] = it } }
        viewModelScope.launch { _passwordRemoved.collect { handle[KEY_PASSWORD_REMOVED] = it } }
    }

    fun addPdfs(uris: List<Uri>, replace: Boolean) {
        if (uris.isEmpty() || _processing.value || _importing.value) return
        _importing.value = true
        val generation = ++importGeneration
        importJob = viewModelScope.launch {
            val job = coroutineContext[Job]!!
            // reset() abandons an import by cancelling it, but a PDF already being read still finishes,
            // and its outcome reaches here. Then it belongs to a tool that has gone: say nothing.
            fun abandoned() = generation != importGeneration
            // The PDFs left out, by name, and why: with several picked, the user is told which.
            val refused = ArrayList<Pair<String, Said>>()
            suspend fun failed(uri: Uri, why: Said) {
                if (abandoned()) return
                refused += (withContext(fileOps) { engine.displayName(uri) } ?: "Document.pdf") to why
            }
            if (replace) clearInputs()
            try {
                for (uri in uris) {
                    val id = nextId++
                    try {
                        val input = withContext(fileOps) { engine.import(uri, id) { job.ensureActive() } }
                        append(input)
                    } catch (e: PdfPasswordRequired) {
                        // Each locked PDF waits its turn in the password dialog. reset() deletes the copy
                        // of an abandoned one.
                        if (!abandoned()) setLocked(locked.value + e.input)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: PdfCertificateEncrypted) {
                        failed(uri, said(R.string.pdf_certificate_unsupported, R.string.pdf_certificate_unsupported_many))
                    } catch (e: Exception) {
                        failed(uri, if (e.isOutOfSpace()) said(R.string.open_no_space, R.string.open_no_space_many) else said(R.string.pdf_import_failed, R.string.pdf_import_failed_many))
                    } catch (_: LinkageError) {
                        // Never crash on a PDF that PDFBox can't handle.
                        failed(uri, said(R.string.pdf_import_failed, R.string.pdf_import_failed_many))
                    } catch (_: StackOverflowError) {
                        // Nor on one nested deeper than PDFBox goes (the engine fails it as damaged already).
                        failed(uri, said(R.string.pdf_import_failed, R.string.pdf_import_failed_many))
                    } catch (_: OutOfMemoryError) {
                        // A PDF with a huge number of objects can exhaust the heap while it is read.
                        failed(uri, said(R.string.pdf_import_failed, R.string.pdf_import_failed_many))
                    }
                }
            } finally {
                // After reset() a newer import may be running, and it owns the flag.
                if (!abandoned()) {
                    _importing.value = false
                    // One PDF picked is told about as before, without its name.
                    if (uris.size == 1) refused.forEach { message(it.second.one) } else sayOf(refused)
                }
            }
        }
    }

    /**
     * Copies the Word, PowerPoint, Excel or EPUB files at [uris] in, after those picked before. Files that
     * can't be converted are left out, saying why; with several picked at once, naming them.
     */
    fun addDocuments(uris: List<Uri>, kind: OfficeKind) {
        if (uris.isEmpty() || _processing.value || _importing.value) return
        _importing.value = true
        val generation = ++importGeneration
        importJob = viewModelScope.launch {
            val job = coroutineContext[Job]!!
            fun abandoned() = generation != importGeneration
            // The files left out, by name, and why.
            val refused = ArrayList<Pair<String, Said>>()
            suspend fun failed(uri: Uri, why: Said) {
                if (abandoned()) return
                val name = withContext(fileOps) { office.displayName(uri) } ?: "${kind.fallbackName}.${kind.extension}"
                refused += name to why
            }
            try {
                for (uri in uris) {
                    try {
                        val input = withContext(fileOps) { office.import(uri, nextId++, kind) { job.ensureActive() } }
                        if (abandoned()) {
                            withContext(fileOps) { office.delete(input) }
                            return@launch
                        }
                        setDocuments(documents.value + input)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: OfficeFileProblem) {
                        failed(uri, officeReason(e, kind))
                    } catch (e: Exception) {
                        failed(uri, if (e.isOutOfSpace()) said(R.string.open_no_space, R.string.open_no_space_many) else officeReason(OfficeNotRecognized(), kind))
                    } catch (_: OutOfMemoryError) {
                        failed(uri, officeReason(OfficeNotRecognized(), kind))
                    }
                }
            } finally {
                if (!abandoned()) {
                    _importing.value = false
                    // One file picked is told about as before, without its name.
                    if (uris.size == 1) refused.forEach { message(it.second.one) } else sayOf(refused)
                }
            }
        }
    }

    /**
     * Takes [files], shared to Usugami and copied in already (see [MainViewModel.share]), after the
     * files there: PDFs join [inputs] (those with a password wait for it, as picked ones do), Word,
     * PowerPoint, Excel and EPUB files [documents]. They are all of one kind. Files that can't be used are
     * left out, saying why; with several shared, naming them.
     */
    fun addShared(files: List<SharedFile>) {
        if (files.isEmpty()) return
        val officeKind = when (files[0].kind) {
            SharedKind.WORD -> OfficeKind.WORD
            SharedKind.POWERPOINT -> OfficeKind.POWERPOINT
            SharedKind.EPUB -> OfficeKind.EPUB
            SharedKind.EXCEL -> OfficeKind.EXCEL
            else -> null
        }
        _importing.value = true
        val generation = ++importGeneration
        // Kept in the saved state until they are all in: when Android closes Usugami meanwhile, those not
        // in yet are gone, and the user is told (see takeShareInterrupted).
        handle[KEY_TAKING_SHARED] = generation
        importJob = viewModelScope.launch {
            fun abandoned() = generation != importGeneration
            // The files left out, by name, and why.
            val refused = ArrayList<Pair<String, Said>>()
            try {
                for (shared in files) {
                    if (abandoned()) break
                    val id = nextId++
                    val reason = try {
                        if (officeKind == null) {
                            val input = withContext(fileOps) { engine.adopt(shared.file, id, shared.name) }
                            if (abandoned()) withContext(fileOps) { engine.delete(input) } else append(input)
                        } else {
                            val input = withContext(fileOps) { office.adopt(shared.file, id, officeKind, shared.name) }
                            if (abandoned()) withContext(fileOps) { office.delete(input) } else setDocuments(documents.value + input)
                        }
                        null
                    } catch (e: PdfPasswordRequired) {
                        // Each locked PDF waits its turn in the password dialog. reset() deletes the copy of an abandoned one.
                        if (!abandoned()) setLocked(locked.value + e.input)
                        null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: PdfCertificateEncrypted) {
                        said(R.string.pdf_certificate_unsupported, R.string.pdf_certificate_unsupported_many)
                    } catch (e: OfficeFileProblem) {
                        officeReason(e, officeKind ?: OfficeKind.WORD)
                    } catch (e: Exception) {
                        when {
                            e.isOutOfSpace() -> said(R.string.open_no_space, R.string.open_no_space_many)
                            officeKind == null -> said(R.string.pdf_import_failed, R.string.pdf_import_failed_many)
                            else -> officeReason(OfficeNotRecognized(), officeKind)
                        }
                    } catch (_: LinkageError) {
                        // Never crash on a PDF that PDFBox can't handle.
                        said(R.string.pdf_import_failed, R.string.pdf_import_failed_many)
                    } catch (_: StackOverflowError) {
                        // Nor on a file nested deeper than its reader goes.
                        if (officeKind == null) said(R.string.pdf_import_failed, R.string.pdf_import_failed_many) else officeReason(OfficeNotRecognized(), officeKind)
                    } catch (_: OutOfMemoryError) {
                        if (officeKind == null) said(R.string.pdf_import_failed, R.string.pdf_import_failed_many) else officeReason(OfficeNotRecognized(), officeKind)
                    }
                    if (reason != null) refused += shared.name to reason
                }
            } finally {
                if (handle.get<Int>(KEY_TAKING_SHARED) == generation) handle.remove<Int>(KEY_TAKING_SHARED)
                // Those not taken in (the tool was put away meanwhile) are deleted, so that none is left
                // in the cache. Those taken in were moved, and those that couldn't be used deleted.
                withContext(NonCancellable + fileOps) { files.forEach { it.file.delete() } }
                if (!abandoned()) {
                    _importing.value = false
                    // One file shared is told about as one picked is, without its name.
                    if (files.size == 1) refused.forEach { message(it.second.one) } else sayOf(refused)
                }
            }
        }
    }

    /** Android closed Usugami while it took in files shared to it (see [addShared]). */
    private var shareInterrupted = handle.remove<Int>(KEY_TAKING_SHARED) != null

    /**
     * Whether Android closed Usugami while it took in files shared to it, so that those not in yet are
     * gone: true once, for the user to be told.
     */
    fun takeShareInterrupted(): Boolean = shareInterrupted.also { shareInterrupted = false }

    /** Puts away the Word, PowerPoint, Excel or EPUB file [id], and frees its copy. */
    fun removeDocument(id: Long) {
        val input = documents.value.firstOrNull { it.id == id } ?: return
        setDocuments(documents.value - input)
        viewModelScope.launch(fileOps) { office.delete(input) }
    }

    /**
     * Converts every file in [documents] into a PDF of its own name ([outputName]), one after
     * another; a book is laid out on [bookPage] (A5 or A4), a workbook's sheets put on pages as
     * [sheets] says. One file is converted as it always was.
     */
    fun runConvertAll(bookPage: PageSize = PageSize.A5, sheets: SheetOptions = SheetOptions()) {
        val list = documents.value
        if (list.size <= 1) {
            val input = list.firstOrNull() ?: return
            val fileName = outputName(input)
            process(fileName, emptyList(), list) { check ->
                listOf(office.convert(input, fileName, bookPage, sheets, check) { _convertProgress.value = it })
            }
            return
        }
        val jobs = list.map { ConvertJob(it, outputName(it)) }
        processEach("Converted_PDFs", list.map { it.name }, emptyList(), list) { check, onNext ->
            office.convertEach(jobs, bookPage, sheets, check, onNext) { _convertProgress.value = it }
        }
    }

    /** Why a Word, PowerPoint, Excel or EPUB file can't be converted, in the user's language. */
    private fun officeMessage(e: OfficeFileProblem, kind: OfficeKind): String = officeReason(e, kind).one

    /** Why Word, PowerPoint, Excel or EPUB files can't be converted, said of one and of several (see [Said]). */
    private fun officeReason(e: OfficeFileProblem, kind: OfficeKind): Said = when (e) {
        is OfficeLegacyFormat -> when (kind) {
            OfficeKind.WORD -> said(R.string.office_legacy_word, R.string.office_legacy_word_many)
            OfficeKind.EXCEL -> said(R.string.office_legacy_excel, R.string.office_legacy_excel_many)
            else -> said(R.string.office_legacy_powerpoint, R.string.office_legacy_powerpoint_many)
        }
        is OfficeEncrypted ->
            if (kind == OfficeKind.EXCEL) said(R.string.office_encrypted_excel, R.string.office_encrypted_excel_many)
            else said(R.string.office_encrypted, R.string.office_encrypted_many)
        is EpubProtected -> said(R.string.epub_protected, R.string.epub_protected_many)
        is OfficeNothingToConvert -> when (kind) {
            OfficeKind.EPUB -> said(R.string.epub_empty, R.string.epub_empty_many)
            OfficeKind.EXCEL -> said(R.string.office_no_sheets, R.string.office_no_sheets_many)
            else -> said(R.string.office_no_slides, R.string.office_no_slides_many)
        }
        is OfficeEmbeddedContent -> said(R.string.office_embedded_content, R.string.office_embedded_content_many)
        is OfficeTooLarge -> said(R.string.office_too_large, R.string.office_too_large_many)
        is OfficeNotRecognized -> when (kind) {
            OfficeKind.WORD -> said(R.string.office_not_word, R.string.office_not_word_many)
            OfficeKind.POWERPOINT -> said(R.string.office_not_powerpoint, R.string.office_not_powerpoint_many)
            OfficeKind.EXCEL -> said(R.string.office_not_excel, R.string.office_not_excel_many)
            OfficeKind.EPUB -> said(R.string.epub_not_recognized, R.string.epub_not_recognized_many)
        }
    }

    /** What [one] says of one file, and [many] of several. */
    private fun said(@StringRes one: Int, @StringRes many: Int) = Said(strings.getString(one), strings.getString(many))

    /**
     * Says what [said] says of each of several files, naming it. Files of which the same is said are
     * named together, in one message worded for them all: messages show one at a time, and one for
     * each file would keep the others waiting long after.
     */
    private fun sayOf(said: List<Pair<String, Said>>) {
        for (group in said.groupBy { it.second.many }.values) {
            val text = if (group.size == 1) group[0].second.one else group[0].second.many
            message(strings.getString(R.string.file_problem, group.joinToString(", ") { it.first }, text))
        }
    }

    /** The copies of [missing], files picked to convert, were cleared from the cache: drop them, and say so. */
    private fun forgetDocuments(missing: List<OfficeInput>) {
        val ids = missing.mapTo(HashSet()) { it.id }
        setDocuments(documents.value.filterNot { it.id in ids })
        pluralMessage(R.plurals.msg_files_cleared, ids.size)
    }

    private fun setDocuments(list: List<OfficeInput>) {
        handle[KEY_DOCUMENTS] = ArrayList(list)
        followBatch()
    }

    private var unlockJob: Job? = null

    /** Tries [password] on [pending], the first locked PDF. A wrong one is reported in the dialog. */
    fun unlock(pending: PdfInput, password: String) {
        // A second tap while the first unlock is still loading would add the same PDF twice.
        if (locked.value.firstOrNull() != pending || _unlocking.value) return
        _unlocking.value = true
        _wrongPassword.value = false
        unlockJob = viewModelScope.launch {
            // The dialog may have been cancelled while loading, and its file deleted.
            fun stillAsked() = locked.value.firstOrNull() == pending
            try {
                val input = withContext(fileOps) { engine.unlock(pending, password) }
                if (!stillAsked()) return@launch
                setLocked(locked.value.drop(1))
                append(input)
            } catch (e: CancellationException) {
                throw e
            } catch (_: PdfWrongPassword) {
                if (stillAsked()) _wrongPassword.value = true
            } catch (e: Exception) {
                val reason = when {
                    e is PdfCertificateEncrypted -> R.string.pdf_certificate_unsupported
                    e.isOutOfSpace() -> R.string.open_no_space
                    else -> R.string.pdf_import_failed
                }
                if (stillAsked()) unlockFailed(pending, reason)
            } catch (_: LinkageError) {
                if (stillAsked()) unlockFailed(pending, R.string.pdf_import_failed)
            } catch (_: StackOverflowError) {
                if (stillAsked()) unlockFailed(pending, R.string.pdf_import_failed)
            } catch (_: OutOfMemoryError) {
                if (stillAsked()) unlockFailed(pending, R.string.pdf_import_failed)
            } finally {
                _unlocking.value = false
            }
        }
    }

    /** No password will open [pending] (it is damaged, or its copy has gone): drop it, and say why. */
    private fun unlockFailed(pending: PdfInput, @StringRes reason: Int) {
        if (!pending.file.isFile) return forgetInputs(listOf(pending))
        dismissPassword(pending)
        message(strings.getString(reason))
    }

    fun clearWrongPassword() { _wrongPassword.value = false }

    /** Skips [pending], the first locked PDF; the dialog moves on to the next one. */
    fun dismissPassword(pending: PdfInput) {
        if (locked.value.firstOrNull() != pending) return
        setLocked(locked.value.drop(1))
        _wrongPassword.value = false
        viewModelScope.launch(fileOps) { engine.delete(pending) }
    }

    /**
     * Adds [input] and its pages: at the end, or after the page [prepareInsert] named (Reorganize
     * PDF's "Add pages"). Adding to PDFs already there can be undone.
     */
    private fun append(input: PdfInput) {
        if (inputs.value.any { it.id == input.id }) return
        val list = pages.value
        if (inputs.value.isNotEmpty()) pushUndo(list)
        setInputs(inputs.value + input)
        val added = (0 until input.pageCount).map { PdfPageRef(input.id, it) }
        val anchor = handle.get<String>(KEY_INSERT_AFTER)?.let { key -> list.indexOfFirst { it.key == key } }?.takeIf { it >= 0 }
        handle[KEY_INSERT_AFTER] = null
        setPages(if (anchor == null) list + added else list.subList(0, anchor + 1) + added + list.subList(anchor + 1, list.size))
        prepareViews(listOf(input))
    }

    /** Makes the views (see [views]) of those of [list] that have a password and no view yet. */
    private fun prepareViews(list: List<PdfInput>) {
        for (input in list) {
            if (input.password.isEmpty() || input.id in _views.value || viewJobs[input.id]?.isActive == true) continue
            viewJobs[input.id] = viewModelScope.launch {
                val job = coroutineContext[Job]!!
                try {
                    // Stopped if the PDF is put away meanwhile (see setInputs): file work is done one
                    // task at a time, and the next PDF picked would wait for it.
                    val view = withContext(fileOps) { if (input.file.isFile) engine.previewView(input) { job.ensureActive() } else null }
                    if (view != null && inputs.value.any { it.id == input.id }) _views.value += input.id to view
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Its pages are shown without thumbnails.
                } catch (_: LinkageError) {
                    // Likewise.
                } catch (_: StackOverflowError) {
                    // Likewise.
                } catch (_: OutOfMemoryError) {
                    // Likewise.
                } finally {
                    if (viewJobs[input.id] === job) viewJobs.remove(input.id)
                }
            }
        }
    }

    /**
     * The next PDF added puts its pages after [after], or at the end when it is null. Kept in the
     * saved state, since Android may stop Usugami while the file picker is open.
     */
    fun prepareInsert(after: PdfPageRef?) {
        handle[KEY_INSERT_AFTER] = after?.key
    }

    /**
     * Adds a blank page after [after] (or at the end), the size of the page it follows as that page
     * shows: a blank page among sideways pages lies sideways too. Reading a PDF page's size waits for
     * other file work, so adding is busy meanwhile, as adding a PDF's pages is: asked for again, it
     * adds no second page, and the PDF can't be saved without it.
     */
    fun addBlankPage(after: PdfPageRef?) {
        if (_processing.value || _importing.value) return
        val list = pages.value
        val index = after?.let { list.indexOfFirst { ref -> ref.key == it.key } }?.takeIf { it >= 0 } ?: list.lastIndex
        val neighbour = list.getOrNull(index) ?: return
        if (neighbour.isBlank) return insertBlankPage(neighbour, neighbour.width, neighbour.height)
        val source = inputs.value.firstOrNull { it.id == neighbour.inputId } ?: return
        _importing.value = true
        val generation = ++importGeneration
        importJob = viewModelScope.launch {
            // Put away meanwhile (and another PDF picked since, say), the PDF takes its blank page with it.
            fun abandoned() = generation != importGeneration
            try {
                val (width, height) = withContext(fileOps) { engine.shownPageSize(source, neighbour.page) }
                if (!abandoned() && inputs.value.any { it.id == source.id }) insertBlankPage(neighbour, width, height)
            } finally {
                if (!abandoned()) _importing.value = false
            }
        }
    }

    /**
     * Puts a blank page of [width] by [height] points, turned as [neighbour] is, after it, or at the
     * end if it has gone since it was asked for: the pages may have changed while the size was read.
     */
    private fun insertBlankPage(neighbour: PdfPageRef, width: Float, height: Float) {
        val current = pages.value
        if (current.isEmpty()) return
        val found = current.indexOfFirst { it.key == neighbour.key }
        val at = if (found >= 0) found else current.lastIndex
        val sideways = (current.getOrNull(found) ?: neighbour).rotation % 180 != 0
        val number = (current.filter { it.isBlank }.maxOfOrNull { it.page } ?: -1) + 1
        val blank = if (sideways) PdfPageRef.blank(number, height, width) else PdfPageRef.blank(number, width, height)
        pushUndo(current)
        setPages(current.subList(0, at + 1) + blank + current.subList(at + 1, current.size))
    }

    /** Saves the page list so the change about to be made can be undone. */
    private fun pushUndo(list: List<PdfPageRef>) {
        undoStack.addLast(list)
        while (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        _canUndo.value = true
    }

    /** Takes back the latest change to the pages. */
    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        _canUndo.value = undoStack.isNotEmpty()
        // A PDF whose copy has gone since can't come back.
        val ids = inputs.value.mapTo(HashSet()) { it.id }
        setPages(previous.filter { it.isBlank || it.inputId in ids })
    }

    /**
     * Whether leaving [tool] would throw away work done on its PDF: Edit PDF's additions, Redact
     * PDF's boxes, or pages turned or moved. Merge's pages start as all its PDFs' pages, in order;
     * the other tools' as the first PDF's (Reorganize PDF's pages added from others count). Not
     * whether Undo has something: Undo is forgotten when Android closes Usugami in the background,
     * the pages aren't.
     */
    fun hasUnsavedWork(tool: PdfTool): Boolean = when {
        history.items.value.isNotEmpty() || _redactBoxes.value.isNotEmpty() -> true
        // A tool working on several PDFs lists every page of them all, and changes none.
        tool == PdfTool.MERGE || tool.isBatch -> pages.value != allPages()
        else -> pages.value != originalPages()
    }

    private fun clearUndo() {
        undoStack.clear()
        _canUndo.value = false
    }

    /** Drops PDFs whose copies the system has cleared from the cache, and says so. */
    private fun forgetInputs(missing: List<PdfInput>) {
        val ids = missing.mapTo(HashSet()) { it.id }
        // Only with the PDF previewed: Add watermark previews the first of its PDFs.
        if (inputs.value.firstOrNull()?.id in ids) clearRedaction()
        setInputs(inputs.value.filterNot { it.id in ids })
        setPages(pages.value.filterNot { it.inputId in ids })
        setLocked(locked.value.filterNot { it.id in ids })
        pluralMessage(R.plurals.msg_files_cleared, ids.size)
    }

    fun removeInput(id: Long) {
        val input = inputs.value.firstOrNull { it.id == id } ?: return
        // Only with the PDF previewed, the first: Add watermark keeps the preview of its first PDF, or
        // the one being made, when another of its PDFs is removed.
        if (inputs.value.first().id == id) clearRedaction()
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
        followFileOrder()
    }

    fun sortInputs(reverse: Boolean = false) {
        val sorted = inputs.value.sortedBy { it.name.lowercase() }
        setInputs(if (reverse) sorted.reversed() else sorted)
        followFileOrder()
    }

    /**
     * Merge's pages follow the PDFs' new order, each PDF's pages keeping their own order, turns and
     * removals. Not once pages of different PDFs have been mixed: that order was chosen page by page.
     */
    private fun followFileOrder() {
        val ids = pages.value.map { it.inputId }
        val grouped = ids.zipWithNext().count { (a, b) -> a != b } == ids.distinct().size - 1
        if (!grouped) return
        val rank = inputs.value.withIndex().associate { it.value.id to it.index }
        setPages(pages.value.sortedBy { rank[it.inputId] ?: Int.MAX_VALUE })
    }

    /** A page is about to be dragged: the whole drag is one change for Undo. */
    fun beginPageDrag() = pushUndo(pages.value)

    /** The drag is over; one that moved nothing leaves nothing to undo. */
    fun endPageDrag() {
        if (undoStack.lastOrNull() == pages.value) {
            undoStack.removeLast()
            _canUndo.value = undoStack.isNotEmpty()
        }
    }

    // The pages are found by their key, whatever their rotation: a button tapped again before the
    // screen shows the page turned still finds it.

    fun movePage(from: PdfPageRef, to: PdfPageRef) {
        val list = pages.value.toMutableList()
        val a = list.indexOfFirst { it.key == from.key }
        val b = list.indexOfFirst { it.key == to.key }
        if (a < 0 || b < 0 || a == b) return
        list.add(b, list.removeAt(a))
        setPages(list)
    }

    fun movePageBy(ref: PdfPageRef, offset: Int) {
        val list = pages.value.toMutableList()
        val a = list.indexOfFirst { it.key == ref.key }
        val b = a + offset
        if (a !in list.indices || b !in list.indices) return
        pushUndo(pages.value)
        list.add(b, list.removeAt(a))
        setPages(list)
    }

    fun rotatePage(ref: PdfPageRef, degrees: Int) {
        if (pages.value.none { it.key == ref.key }) return
        pushUndo(pages.value)
        setPages(pages.value.map { if (it.key == ref.key) it.copy(rotation = Math.floorMod(it.rotation + degrees, 360)) else it })
    }

    /** Turns every page by [degrees] more, as Rotate's "all pages" buttons do. */
    fun rotateAll(degrees: Int) {
        pushUndo(pages.value)
        setPages(pages.value.map { it.copy(rotation = Math.floorMod(it.rotation + degrees, 360)) })
    }

    fun removePage(ref: PdfPageRef) {
        if (pages.value.none { it.key == ref.key }) return
        pushUndo(pages.value)
        setPages(pages.value.filterNot { it.key == ref.key })
    }

    fun resetPages() {
        pushUndo(pages.value)
        setPages(allPages())
    }

    /** Every page of the PDFs, in order: what the page list starts as. */
    private fun allPages(): List<PdfPageRef> = inputs.value.flatMap { input -> (0 until input.pageCount).map { PdfPageRef(input.id, it) } }

    /** The first PDF's pages as they are: what Reorganize PDF starts from, and goes back to on Reset. */
    fun originalPages(): List<PdfPageRef> = inputs.value.firstOrNull()?.let { main -> (0 until main.pageCount).map { PdfPageRef(main.id, it) } }.orEmpty()

    /**
     * Reorganize PDF's Reset: the first PDF's pages, as they were. Pages added from other PDFs go.
     * Pressed again before the button turns off, it changes nothing, and Undo has nothing more to take back.
     */
    fun resetToOriginal() {
        val original = originalPages()
        if (pages.value == original) return
        pushUndo(pages.value)
        setPages(original)
    }

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
        } catch (e: PageSelectionException) {
            message(pageSelectionMessage(e))
            return
        } catch (_: IllegalArgumentException) {
            message(strings.getString(R.string.pdf_invalid_pages))
            return
        }
        process(fileName, listOf(input)) { check -> engine.split(input, groups, fileName, check) }
    }

    fun runMerge(pageMode: Boolean, fileName: String) {
        val source = inputs.value
        if (source.size < 2 || (pageMode && pages.value.isEmpty())) return
        val selected = if (pageMode) pages.value else null
        process(fileName, source) { check -> listOf(engine.merge(source, selected, fileName, check)) }
    }

    fun runCompress(level: PdfCompression, fileName: String) {
        val input = inputs.value.singleOrNull() ?: return
        process(fileName, listOf(input), afterResult = { files ->
            // The engine keeps the original file when re-encoding would not make an unprotected PDF
            // smaller. Say so, otherwise the result looks like nothing happened.
            val file = files.single()
            if (input.password.isEmpty() && Compressor.savedPercent(file.originalBytes, file.sizeBytes) == null) {
                message(strings.getString(R.string.pdf_no_reduction))
            }
        }) { check -> listOf(engine.compress(input, level, fileName, check)) }
    }

    /**
     * Compresses every PDF, each at its own level ([compressionOf]) into a PDF of its own name
     * ([outputName]), one after another. One PDF is compressed as [runCompress] does.
     */
    fun runCompressAll() {
        val list = inputs.value
        if (list.size <= 1) {
            list.firstOrNull()?.let { runCompress(compressionOf(it), outputName(PdfTool.COMPRESS, it)) }
            return
        }
        val jobs = list.map { CompressJob(it, compressionOf(it), outputName(PdfTool.COMPRESS, it)) }
        processEach("Compressed_PDFs", list.map { it.name }, list) { check, onNext -> engine.compressEach(jobs, check, onNext) }
    }

    /** The level [input] is to be compressed at: its own, or the one chosen for all. */
    fun compressionOf(input: PdfInput): PdfCompression = _batch.value.compressionOf(input.id)

    /** Every PDF, and those added later, get [choice] (see [batch]). */
    fun setBatchChoiceForAll(choice: Int) = setBatch(_batch.value.withAll(choice))

    /** PDF [id] alone gets [choice] (see [batch]). */
    fun setBatchChoice(id: Long, choice: Int) = setBatch(_batch.value.withChoice(id, choice))

    /** PDF [id]'s PDF is to be named [name], as typed (see [batch]). */
    fun setBatchName(id: Long, name: String) = setBatch(_batch.value.withName(id, name))

    /** The name [tool] gives the PDF it makes of [input]: the one typed, or its own. */
    fun outputName(tool: PdfTool, input: PdfInput): String = _batch.value.nameOf(input.id) ?: defaultName(tool, input)

    /** The name the PDF made of [document] gets: the one typed, or the file's own (Report.docx becomes Report). */
    fun outputName(document: OfficeInput): String = _batch.value.nameOf(document.id) ?: FileNames.baseName(document.name)

    private fun setBatch(choices: BatchChoices) {
        if (choices == _batch.value) return
        _batch.value = choices
        val packed = choices.pack()
        handle[KEY_BATCH] = bundleOf("ids" to packed.ids, "names" to packed.names, "choices" to packed.choices, "all" to packed.all)
    }

    /** The names and choices follow the PDFs and the files to convert, whichever the tool has. */
    private fun followBatch() = setBatch(_batch.value.following(inputs.value.map { it.id } + documents.value.map { it.id }))

    /** What [setBatch] kept, or none, with the level for all at Recommended. */
    private fun restoredBatch(): BatchChoices {
        val kept = handle.get<Bundle>(KEY_BATCH) ?: return BatchChoices(all = DEFAULT_BATCH_CHOICE)
        @Suppress("UNCHECKED_CAST")
        val names = kept.getStringArray("names") as Array<String?>?
        return BatchChoices.unpack(kept.getLongArray("ids"), names, kept.getIntArray("choices"), kept.getInt("all", DEFAULT_BATCH_CHOICE))
    }

    /** Saves the PDF with its pages turned as [pages] says; the button is off until one is turned. */
    fun runRotate(fileName: String) {
        val input = inputs.value.singleOrNull() ?: return
        val rotations = pages.value.filter { it.inputId == input.id && it.rotation != 0 }.associate { it.page to it.rotation }
        if (rotations.isEmpty()) return
        process(fileName, listOf(input)) { check -> listOf(engine.rotate(input, rotations, fileName, check)) }
    }

    /** [skip] lists pages to leave without a number, like "1" or "1-2,5"; it may be blank. */
    fun runPageNumbers(position: PageNumberPosition, size: PageNumberSize, skip: String, fileName: String) {
        val input = inputs.value.singleOrNull() ?: return
        val numbered = try {
            PageNumbers.numbered(skip, input.pageCount)
        } catch (e: PageSelectionException) {
            message(pageSelectionMessage(e))
            return
        }
        if (numbered.isEmpty()) {
            message(strings.getString(R.string.page_numbers_all_skipped))
            return
        }
        process(fileName, listOf(input)) { check -> listOf(engine.addPageNumbers(input, numbered, position, size, fileName, check)) }
    }

    /**
     * Numbers every PDF the same way, each into a PDF of its own name ([outputName]), one after
     * another. [skip] is read against the longest PDF, and a PDF with every page skipped is left out,
     * saying so (see [PageNumbers.numberedEach]). One PDF is numbered as [runPageNumbers] does.
     */
    fun runPageNumbersAll(position: PageNumberPosition, size: PageNumberSize, skip: String) {
        val list = inputs.value
        if (list.size <= 1) {
            list.firstOrNull()?.let { runPageNumbers(position, size, skip, outputName(PdfTool.PAGE_NUMBERS, it)) }
            return
        }
        val numbered = try {
            PageNumbers.numberedEach(skip, list.map { it.pageCount })
        } catch (e: PageSelectionException) {
            message(pageSelectionMessage(e))
            return
        }
        if (numbered.all { it.isEmpty() }) {
            message(strings.getString(R.string.page_numbers_all_skipped))
            return
        }
        val jobs = list.mapIndexed { i, input -> NumberingJob(input, numbered[i], outputName(PdfTool.PAGE_NUMBERS, input)) }
        processEach("Numbered_PDFs", list.map { it.name }, list) { check, onNext -> engine.addPageNumbersEach(jobs, position, size, check, onNext) }
    }

    fun runWatermark(text: String, color: WatermarkColor, opacity: WatermarkOpacity, fileName: String) {
        val input = inputs.value.singleOrNull() ?: return
        if (text.isBlank()) return
        // Checked here too, so the message comes at once rather than after the progress dialog.
        if (!WatermarkText.canDraw(text)) {
            message(strings.getString(R.string.watermark_no_font))
            return
        }
        process(fileName, listOf(input)) { check -> listOf(engine.addWatermark(input, text, color, opacity, fileName, check)) }
    }

    /**
     * Puts the same watermark on every PDF, each into a PDF of its own name ([outputName]), one
     * after another. One PDF gets it as [runWatermark] does.
     */
    fun runWatermarkAll(text: String, color: WatermarkColor, opacity: WatermarkOpacity) {
        val list = inputs.value
        if (list.size <= 1) {
            list.firstOrNull()?.let { runWatermark(text, color, opacity, outputName(PdfTool.WATERMARK, it)) }
            return
        }
        if (text.isBlank()) return
        if (!WatermarkText.canDraw(text)) {
            message(strings.getString(R.string.watermark_no_font))
            return
        }
        val jobs = list.map { ToolJob(it, outputName(PdfTool.WATERMARK, it)) }
        processEach("Watermarked_PDFs", list.map { it.name }, list) { check, onNext -> engine.addWatermarkEach(jobs, text, color, opacity, check, onNext) }
    }

    /** Saves the PDF without the pages in [text], typed like "2,5-7"; it must leave at least one page. */
    fun runDeletePages(text: String, fileName: String) {
        val input = inputs.value.singleOrNull() ?: return
        val doomed = try {
            PdfPageSelection.toDelete(text, input.pageCount)
        } catch (e: PageSelectionException) {
            message(pageSelectionMessage(e))
            return
        }
        process(fileName, listOf(input)) { check -> listOf(engine.deletePages(input, doomed, fileName, check)) }
    }

    /** Saves the first PDF with the pages as [pages] lists them, from it, from PDFs added and blank. */
    fun runReorganize(fileName: String) {
        val main = inputs.value.firstOrNull() ?: return
        val list = pages.value
        if (list.isEmpty()) return
        val extras = inputs.value.drop(1).filter { extra -> list.any { it.inputId == extra.id } }
        process(fileName, listOf(main) + extras) { check -> listOf(engine.reorganize(main, extras, list, fileName, check)) }
    }

    fun runFlatten(fileName: String) {
        val input = inputs.value.singleOrNull() ?: return
        var droppedXfa = false
        var keptFields = 0
        process(fileName, listOf(input), afterResult = {
            if (droppedXfa) message(strings.getString(R.string.flatten_xfa_dropped))
            if (keptFields > 0) pluralMessage(R.plurals.flatten_fields_kept, keptFields)
        }) { check ->
            val result = engine.flatten(input, fileName, check)
            droppedXfa = result.droppedDynamicXfa
            keptFields = result.keptFields
            listOf(result.file)
        }
    }

    /**
     * Flattens every PDF, each into a PDF of its own name ([outputName]), one after another. One with
     * nothing to flatten is left out, saying so, and what [runFlatten] says of one PDF is said of
     * each, naming it. One PDF is flattened as [runFlatten] does.
     */
    fun runFlattenAll() {
        val list = inputs.value
        if (list.size <= 1) {
            list.firstOrNull()?.let { runFlatten(outputName(PdfTool.FLATTEN, it)) }
            return
        }
        val jobs = list.map { ToolJob(it, outputName(PdfTool.FLATTEN, it)) }
        var notes = emptyList<Pair<String, Said>>()
        processEach("Flattened_PDFs", list.map { it.name }, list, afterResult = { sayOf(notes) }) { check, onNext ->
            val outcomes = engine.flattenEach(jobs, check, onNext)
            notes = outcomes.withIndex().flatMap { (i, outcome) ->
                val result = outcome.getOrNull() ?: return@flatMap emptyList()
                val kept = strings.getQuantityString(R.plurals.flatten_fields_kept, result.keptFields, result.keptFields)
                listOfNotNull(
                    said(R.string.flatten_xfa_dropped, R.string.flatten_xfa_dropped_many).takeIf { result.droppedDynamicXfa },
                    Said(kept, strings.getString(R.string.flatten_fields_kept_many)).takeIf { result.keptFields > 0 },
                ).map { list[i].name to it }
            }
            outcomes.map { outcome -> outcome.map { it.file } }
        }
    }

    fun selectEditItem(id: Long?) = history.select(id)

    /**
     * Edit PDF is saving: its items stay as they were when Save was tapped, which the PDF is made
     * from. The screen is covered meanwhile, but not a dialog or menu opened by a tap in the same
     * moment, or a finger still drawing or dragging: what they do would be left out of the PDF.
     */
    private val editSaving: Boolean get() = _processing.value

    fun newEditItemId(): Long = history.newId()

    /**
     * Puts [item] on top of the others; it is chosen, unless it is a stroke just drawn. Refused while
     * saving, and, with a message, once the PDF has as much as the saved state can keep.
     */
    fun addEditItem(item: PageItem): Boolean {
        if (editSaving) return false
        val items = history.items.value
        val points = (item as? InkItem)?.points?.size?.div(2) ?: 0
        if (items.size >= MAX_EDIT_ITEMS || inkPoints(items) + points > MAX_INK_POINTS) {
            message(strings.getString(R.string.edit_full))
            return false
        }
        history.change(items + item)
        if (item !is InkItem) history.select(item.id)
        return true
    }

    /** How many more points pen and highlighter strokes can have on this PDF. */
    fun inkRoom(): Int = maxOf(0, MAX_INK_POINTS - inkPoints(history.items.value))

    private fun inkPoints(items: List<PageItem>) = items.sumOf { (it as? InkItem)?.points?.size?.div(2) ?: 0 }

    /** Says the PDF can't take more strokes. */
    fun editFull() = message(strings.getString(R.string.edit_full))

    /** Puts [item] in place of the item with its id, as one change. */
    fun changeEditItem(item: PageItem) {
        if (!editSaving) history.change(replaced(item))
    }

    /**
     * Moves the item with [id] by ([dx], [dy]) points from where it is now, kept on the page, as one
     * change: a second press of a move button goes on from the first, even before the screen shows it.
     */
    fun moveEditItem(id: Long, dx: Float, dy: Float, pageWidth: Float, pageHeight: Float) {
        if (editSaving) return
        val item = history.items.value.firstOrNull { it.id == id } ?: return
        history.change(replaced(EditLayout.moved(item, dx, dy, pageWidth, pageHeight)))
    }

    /** A drag or a slider starts changing an item: its steps ([stepEditItem]) are one change for Undo. */
    fun beginEditChange() = history.begin()

    fun stepEditItem(item: PageItem) {
        if (!editSaving) history.step(replaced(item))
    }

    /**
     * A step of a drag of the chosen item: from [was], as the drag's last step left it, to [item].
     * Once it is no longer [was], or no longer chosen (another finger changed its colour, undid the
     * drag or chose another page), or Save has been tapped, nothing changes, and false says the drag
     * is over.
     */
    fun dragEditItem(was: PageItem, item: PageItem): Boolean {
        if (editSaving || history.selected.value != was.id || was !in history.items.value) return false
        history.step(replaced(item))
        return true
    }

    fun endEditChange() = history.end()

    private fun replaced(item: PageItem) = history.items.value.map { if (it.id == item.id) item else it }

    fun removeEditItem(id: Long) {
        if (editSaving) return
        history.change(history.items.value.filterNot { it.id == id })
        history.select(null)
    }

    /** Takes back the latest change to the items; returns the page it was on. */
    fun undoEdit(): Int? = if (editSaving) null else history.undo()

    /** Makes the change Undo took back again; returns the page it is on. */
    fun redoEdit(): Int? = if (editSaving) null else history.redo()

    /**
     * The next picture picked goes on page [page] (zero-based). Kept in the saved state, since
     * Android may stop Usugami while the photo picker is open.
     */
    fun prepareEditPicture(page: Int) {
        handle[KEY_PICTURE_PAGE] = page
    }

    /** Copies the picture at [uri] in and puts it in the middle of the page [prepareEditPicture] named. */
    fun addPicture(uri: Uri) {
        val input = inputs.value.singleOrNull() ?: return
        if (_processing.value || _importing.value) return
        val page = handle.get<Int>(KEY_PICTURE_PAGE)?.takeIf { it in 0 until input.pageCount } ?: 0
        if (history.items.value.size >= MAX_EDIT_ITEMS) return editFull()
        _importing.value = true
        val generation = ++importGeneration
        importJob = viewModelScope.launch {
            val job = coroutineContext[Job]!!
            fun abandoned() = generation != importGeneration
            fun failed(@StringRes reason: Int = R.string.edit_picture_failed) { if (!abandoned()) message(strings.getString(reason)) }
            try {
                val id = history.newId()
                val picked = withContext(fileOps) { pictures.import(uri, id) { job.ensureActive() } }
                // Once the tool has closed, its pictures' copies are deleted with the rest.
                if (abandoned()) return@launch
                if (picked == null) return@launch failed()
                val (width, height) = withContext(fileOps) { engine.shownPageSize(input, page) }
                val picture = EditLayout.newPicture(id, page, picked.path, picked.width.toFloat() / picked.height, picked.orientation, picked.jpeg, width, height)
                addEditItem(EditLayout.clearOf(picture, history.items.value, width, height))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed(if (e.isOutOfSpace()) R.string.open_no_space else R.string.edit_picture_failed)
            } catch (_: OutOfMemoryError) {
                failed()
            } finally {
                if (!abandoned()) _importing.value = false
            }
        }
    }

    /** The size of each page of [input] as shown, in points; null if the PDF can't be read. */
    suspend fun shownPageSizes(input: PdfInput): List<Pair<Float, Float>>? = withContext(Dispatchers.IO) {
        try {
            engine.shownPageSizes(input)
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: StackOverflowError) {
            null
        }
    }

    /**
     * Saves the PDF with Edit PDF's items drawn on its pages. It is busy from the tap, as every tool
     * is, even while the pictures are looked for: nothing can be added meanwhile that the PDF would
     * leave out, and leaving the tool stops it.
     */
    fun runEdit(fileName: String) {
        val input = inputs.value.singleOrNull() ?: return
        val items = history.items.value.filter { it.page in 0 until input.pageCount }
        if (items.isEmpty()) return
        val paths = items.filterIsInstance<PictureItem>().map { it.path }
        process(fileName, listOf(input)) { check ->
            val missing = paths.filterNot { File(it).isFile }
            if (missing.isNotEmpty()) throw PicturesCleared(missing)
            listOf(engine.editPages(input, items, fileName, check))
        }
    }

    /** Drops pictures whose copies the system has cleared from the cache, and says so. */
    private fun forgetPictures(paths: List<String>) {
        val gone = paths.toSet()
        history.change(history.items.value.filterNot { it is PictureItem && it.path in gone })
        pluralMessage(R.plurals.msg_files_cleared, gone.size)
    }

    fun setSignature(signature: Signature) {
        if (!signature.isEmpty) handle[KEY_SIGNATURE] = signature
    }

    /** Signs page [page] (zero-based) where [placement] says, or in its lower right when it is null. */
    fun runSign(page: Int, placement: SignaturePlacement?, fileName: String) {
        val input = inputs.value.singleOrNull() ?: return
        val signature = signature.value ?: return
        if (page !in 0 until input.pageCount) return
        process(fileName, listOf(input)) { check -> listOf(engine.sign(input, signature, page, placement, fileName, check)) }
    }

    /**
     * Gets [input] ready for the tool's preview: [previewView], once it is. Redact PDF needs it (it
     * searches and draws its pictures from it): when it can't be made, the PDF is put away. The other
     * tools then preview [input] itself.
     */
    fun preparePreview(input: PdfInput, required: Boolean = true) {
        if (_previewView.value?.id == input.id) return
        if (viewJob?.isActive == true) {
            if (viewJobInput == input.id) return
            // That of a PDF no longer previewed, as when Add watermark's PDFs are put in another
            // order: it gives way to the new first one, which wouldn't get its view otherwise.
            viewJob?.cancel()
        }
        viewJobInput = input.id
        viewJob = viewModelScope.launch {
            val job = coroutineContext[Job]!!
            try {
                val view = withContext(fileOps) { if (input.file.isFile) engine.previewView(input) { job.ensureActive() } else null }
                // Still the PDF previewed: the only one, or the first of those Add watermark has.
                if (inputs.value.firstOrNull()?.id == input.id) _previewView.value = view
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failedView(input, required, e.isOutOfSpace())
            } catch (_: LinkageError) {
                failedView(input, required)
            } catch (_: StackOverflowError) {
                failedView(input, required)
            } catch (_: OutOfMemoryError) {
                failedView(input, required)
            }
        }
    }

    /**
     * No preview can be drawn of [input]: say so, and put it away, if the tool [required] one.
     * [noSpace]: the storage was too full to write the view, rather than the PDF being unreadable.
     */
    private fun failedView(input: PdfInput, required: Boolean, noSpace: Boolean = false) {
        if (inputs.value.firstOrNull()?.id != input.id) return
        if (!required) return run { _previewView.value = input }
        message(strings.getString(if (noSpace) R.string.open_no_space else R.string.pdf_import_failed))
        removeInput(input.id)
    }

    fun showRedactPage(page: Int) {
        handle[KEY_REDACT_PAGE] = page
        handle[KEY_REDACT_SELECTED] = -1
    }

    fun selectRedactBox(index: Int) {
        handle[KEY_REDACT_SELECTED] = if (index in _redactBoxes.value.indices) index else -1
    }

    /** Adds [box] and chooses it; says so instead when there are as many boxes as Usugami keeps. */
    fun addRedactBox(box: RedactBox) {
        val boxes = _redactBoxes.value
        if (boxes.size >= Redaction.MAX_BOXES) {
            message(strings.getString(R.string.redact_too_many, Redaction.MAX_BOXES))
            return
        }
        setRedactBoxes(boxes + Redaction.clamp(box))
        handle[KEY_REDACT_SELECTED] = boxes.size
    }

    /**
     * Puts [box] in the place of box [was], at [index] when the change began (a drag, say), wherever
     * it is now: a box before it deleted meanwhile moves it up the list. Once [was] itself has gone,
     * deleted or changed by another finger (a move or size button pressed during a drag), nothing
     * changes: what the other finger did stays, as in Edit PDF, and the box now in its place isn't
     * replaced.
     */
    fun changeRedactBox(index: Int, was: RedactBox, box: RedactBox) {
        val boxes = _redactBoxes.value
        val at = Redaction.find(boxes, index, was)
        if (at < 0) return
        setRedactBoxes(boxes.toMutableList().also { it[at] = Redaction.clamp(box) })
    }

    /**
     * The chosen box's index, or -1, as it is now: a button pressed again before the screen has
     * caught up acts on the box chosen then, not on one chosen before the first press.
     */
    private val chosenRedactBox: Int get() = handle[KEY_REDACT_SELECTED] ?: -1

    /**
     * Changes the chosen box by [step] from where it is now, kept on the page: a second press of a
     * move or size button goes on from the first, even before the screen shows it.
     */
    fun stepChosenRedactBox(step: (RedactBox) -> RedactBox) {
        val index = chosenRedactBox
        val box = _redactBoxes.value.getOrNull(index) ?: return
        changeRedactBox(index, box, step(box))
    }

    /** Deletes the chosen box. Pressed again before the screen has caught up, it finds none chosen. */
    fun deleteChosenRedactBox() {
        val boxes = _redactBoxes.value
        val index = chosenRedactBox
        if (index !in boxes.indices) return
        setRedactBoxes(boxes.filterIndexed { i, _ -> i != index })
        handle[KEY_REDACT_SELECTED] = -1
    }

    fun clearRedactBoxes() {
        setRedactBoxes(emptyList())
        redactSearched = emptyList()
        handle[KEY_REDACT_SELECTED] = -1
    }

    /**
     * Boxes every place [query] is found, on every page, for the user to check before saving.
     * Boxes already there aren't added again.
     */
    fun findText(query: String) {
        val input = inputs.value.singleOrNull() ?: return
        if (query.isBlank() || _finding.value || _processing.value) return
        val view = _previewView.value?.takeIf { it.id == input.id }
        _finding.value = true
        findJob = viewModelScope.launch {
            val job = coroutineContext[Job]!!
            try {
                val matches = withContext(fileOps) {
                    if (!input.file.isFile) null else engine.findText(input, query, view) { job.ensureActive() }
                }
                if (matches == null) {
                    forgetInputs(listOf(input))
                    return@launch
                }
                if (inputs.value.singleOrNull()?.id != input.id) return@launch
                val found = matches.flatten()
                val boxes = _redactBoxes.value
                val new = ArrayList<RedactBox>()
                for (box in found) if (boxes.none { Redaction.same(it, box) } && new.none { Redaction.same(it, box) }) new += box
                when {
                    found.isEmpty() -> message(strings.getString(R.string.redact_find_none))
                    boxes.size + new.size > Redaction.MAX_BOXES -> message(strings.getString(R.string.redact_find_too_many, Redaction.MAX_BOXES))
                    else -> {
                        setRedactBoxes(boxes + new)
                        if (query !in redactSearched) redactSearched = redactSearched + query
                        // Shows a page with matches, unless the one showing has some.
                        val pages = found.mapTo(HashSet()) { it.page }
                        if (redactPage.value !in pages) showRedactPage(found.minOf { it.page }) else handle[KEY_REDACT_SELECTED] = -1
                        pluralMessage(R.plurals.redact_found, matches.size)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                message(strings.getString(R.string.redact_find_failed))
            } catch (_: LinkageError) {
                message(strings.getString(R.string.redact_find_failed))
            } catch (_: StackOverflowError) {
                message(strings.getString(R.string.redact_find_failed))
            } catch (_: OutOfMemoryError) {
                message(strings.getString(R.string.redact_find_failed))
            } finally {
                // A search for another PDF may have started since this one was abandoned.
                if (findJob === job) _finding.value = false
            }
        }
    }

    fun runRedact(fileName: String) {
        val input = inputs.value.singleOrNull() ?: return
        val boxes = _redactBoxes.value.filter { it.page in 0 until input.pageCount }
        if (boxes.isEmpty() || _finding.value) return
        val view = _previewView.value?.takeIf { it.id == input.id }
        val searched = redactSearched
        process(fileName, listOf(input)) { check -> listOf(engine.redact(input, boxes, fileName, view, searched, check)) }
    }

    private fun setRedactBoxes(boxes: List<RedactBox>) {
        _redactBoxes.value = boxes
        handle[KEY_REDACT_BOXES] = Redaction.pack(boxes)
    }

    /** Redact PDF starts again: its boxes belong to the PDF they were drawn on. */
    private fun clearRedaction() {
        viewJob?.cancel()
        findJob?.cancel()
        viewJob = null
        findJob = null
        _finding.value = false
        _previewView.value = null
        setRedactBoxes(emptyList())
        redactSearched = emptyList()
        handle[KEY_REDACT_PAGE] = 0
        handle[KEY_REDACT_SELECTED] = -1
    }

    /** Why the page numbers typed can't be used, in the user's language. */
    private fun pageSelectionMessage(e: PageSelectionException): String = when (e.problem) {
        PageSelectionException.Problem.FORMAT -> strings.getString(R.string.pdf_pages_format)
        PageSelectionException.Problem.RANGES_FORMAT -> strings.getString(R.string.pdf_ranges_format)
        PageSelectionException.Problem.OUT_OF_RANGE -> strings.getString(R.string.pdf_pages_range, e.pageCount)
        PageSelectionException.Problem.REVERSED -> strings.getString(R.string.pdf_pages_reversed)
        PageSelectionException.Problem.CHUNK_SIZE -> strings.getString(R.string.pdf_pages_chunk, e.pageCount)
        PageSelectionException.Problem.ALL_PAGES -> strings.getString(R.string.pdf_need_page)
    }

    /**
     * Runs a tool on each of the files named [names] in turn (the PDFs [sources], or the files to
     * convert [documents]), with [block] (see [PdfToolsEngine.each]), which tells which starts: the
     * progress shows it. A file that fails is named in a message once the others are made, saying why
     * (see [reportFailures]); with none made, there is no result. [name] is what the PDFs are called
     * together, as a ZIP file of them is. [afterResult] runs once they are shown.
     */
    private fun processEach(
        name: String,
        names: List<String>,
        sources: List<PdfInput>,
        documents: List<OfficeInput> = emptyList(),
        afterResult: () -> Unit = {},
        block: (check: () -> Unit, onNext: (Int) -> Unit) -> List<Result<PdfFile>>,
    ) {
        var failures = emptyList<Pair<String, Throwable>>()
        val afterAll: (List<PdfFile>) -> Unit = {
            reportFailures(failures)
            afterResult()
        }
        process(name, sources, documents, afterAll) { check ->
            val outcomes = block(check) { i ->
                _convertProgress.value = null
                _batchProgress.value = BatchProgress(i + 1, names.size, names[i])
            }
            failures = outcomes.withIndex().mapNotNull { (i, outcome) -> outcome.exceptionOrNull()?.let { names[i] to it } }
            outcomes.mapNotNull { it.getOrNull() }.ifEmpty { throw BatchFailed(failures) }
        }
    }

    /**
     * Says which files of several couldn't be made into PDFs, by name: those with nothing to do (no
     * page left to number, nothing to flatten) together, those with a reason of their own with it
     * (those with the same reason together), and the rest together.
     */
    private fun reportFailures(failures: List<Pair<String, Throwable>>) {
        val failed = ArrayList<String>()
        val nothingToNumber = ArrayList<String>()
        val nothingToFlatten = ArrayList<String>()
        val reasons = ArrayList<Pair<String, Said>>()
        for ((name, e) in failures) {
            when {
                e is NothingToNumber -> nothingToNumber += name
                e is NothingToFlatten && !e.dynamicXfa -> nothingToFlatten += name
                else -> failureReason(e)?.let { reasons += name to it } ?: failed.add(name)
            }
        }
        sayOf(reasons)
        fun named(@PluralsRes res: Int, names: List<String>) {
            if (names.isNotEmpty()) message(strings.getQuantityString(res, names.size, names.size, names.joinToString(", ")))
        }
        named(R.plurals.batch_nothing_to_number, nothingToNumber)
        named(R.plurals.batch_nothing_to_flatten, nothingToFlatten)
        named(R.plurals.batch_failed, failed)
    }

    /** Why one of several files couldn't be made into a PDF, when there is more to say than that it failed. */
    private fun failureReason(e: Throwable): Said? = when (e) {
        is OfficeFileProblem -> officeReason(e, documents.value.firstOrNull()?.kind ?: OfficeKind.WORD)
        is NothingToFlatten -> said(R.string.flatten_xfa_only, R.string.flatten_xfa_only_many)
        is PdfCertificateEncrypted -> said(R.string.pdf_certificate_unsupported, R.string.pdf_certificate_unsupported_many)
        // About the text typed, whichever PDFs it was for.
        is WatermarkFontMissing -> said(R.string.watermark_no_font, R.string.watermark_no_font)
        else -> null
    }

    /**
     * Runs [block], which reads [sources] (or the Word, PowerPoint, Excel or EPUB files [documents]) and
     * makes PDFs named after [name]. Every tool writes PDFs without a password.
     */
    private fun process(
        name: String,
        sources: List<PdfInput>,
        documents: List<OfficeInput> = emptyList(),
        afterResult: (List<PdfFile>) -> Unit = {},
        block: (() -> Unit) -> List<PdfFile>,
    ) {
        if (_processing.value || _importing.value) return
        _processing.value = true
        _convertProgress.value = null
        val document = documents.firstOrNull()
        task = viewModelScope.launch {
            try {
                val job = coroutineContext[Job]!!
                val missing = withContext(fileOps) { sources.filterNot { it.file.isFile } }
                if (missing.isNotEmpty()) {
                    forgetInputs(missing)
                    return@launch
                }
                val documentsMissing = withContext(fileOps) { documents.filterNot { it.file.isFile } }
                if (documentsMissing.isNotEmpty()) {
                    forgetDocuments(documentsMissing)
                    return@launch
                }
                val files = withContext(fileOps) { block { job.ensureActive() } }
                _saved.value = false
                resultPutAway = false
                setSavedFiles(emptySet())
                handle[KEY_SAVE_AS] = null
                _passwordRemoved.value = sources.any { it.password.isNotEmpty() }
                resultName = name
                _result.value = files
                afterResult(files)
            } catch (_: CancellationException) {
                // The engine removes partial output on cancellation.
            } catch (_: OutOfMemoryError) {
                // A very large image can exhaust the heap; the engine has already removed partial output.
                message(strings.getString(if (document != null) R.string.office_convert_failed else R.string.pdf_process_failed))
            } catch (e: OfficeFileProblem) {
                message(officeMessage(e, document?.kind ?: OfficeKind.WORD))
            } catch (_: PdfCertificateEncrypted) {
                message(strings.getString(R.string.pdf_certificate_unsupported))
            } catch (_: WatermarkFontMissing) {
                message(strings.getString(R.string.watermark_no_font))
            } catch (e: NothingToFlatten) {
                message(strings.getString(if (e.dynamicXfa) R.string.flatten_xfa_only else R.string.flatten_nothing))
            } catch (e: PicturesCleared) {
                forgetPictures(e.paths)
            } catch (e: BatchFailed) {
                reportFailures(e.failures)
            } catch (e: Exception) {
                message(
                    if (e.isOutOfSpace()) strings.getString(R.string.msg_convert_failed, strings.getString(R.string.msg_out_of_space))
                    else strings.getString(if (document != null) R.string.office_convert_failed else R.string.pdf_process_failed),
                )
            } catch (_: LinkageError) {
                message(strings.getString(if (document != null) R.string.office_convert_failed else R.string.pdf_process_failed))
            } catch (_: StackOverflowError) {
                // A PDF nested deeper than PDFBox goes when it copies or saves it.
                message(strings.getString(if (document != null) R.string.office_convert_failed else R.string.pdf_process_failed))
            } finally {
                _processing.value = false
                _convertProgress.value = null
                _batchProgress.value = null
                task = null
            }
        }
    }

    fun cancel() { task?.cancel() }

    fun backToEditing() {
        // Not while the PDFs (or a ZIP file of them) are being saved: running the tool again would
        // replace them mid-save, and leaving would cancel a ZIP file, deleting the document the user picked.
        if (_saving.value) return
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
        if (_saving.value) return
        // A PDF, document or picture still being added belongs to the one put away: the import is
        // abandoned, as reset() abandons it, rather than adding it once done.
        val importing = importJob?.isActive == true
        importJob?.cancel()
        importGeneration++
        importJob = null
        _importing.value = false
        zipper.cancel()
        putAwayResult()
        clearInputs()
        viewModelScope.launch(fileOps) {
            engine.clearOutput()
            // A copy the abandoned import finished anyway. Nothing else is in use: imports to come
            // wait for this.
            if (importing) {
                engine.deleteAllExcept(emptyList())
                office.deleteAllExcept(emptyList())
                pictures.deleteAllExcept(emptyList())
            }
        }
    }

    /**
     * Forgets the current tool's PDFs and results and frees their files: when a tool is opened, and
     * once the tool's screens have gone (nothing of them can come back: opening a tool starts afresh).
     * The drawn signature stays, for the next PDF to sign.
     */
    fun reset() {
        importJob?.cancel()
        unlockJob?.cancel()
        task?.cancel()
        // A ZIP file to share is no longer wanted. One being saved where the user chose goes on (images
        // shared meanwhile open the editor, which resets the tool), as a save of the PDFs does: freeing
        // the PDFs below waits for it.
        zipper.stopSharing()
        // The abandoned import stops at its next chunk; the next tool can import straight away.
        importGeneration++
        importJob = null
        _importing.value = false
        _wrongPassword.value = false
        putAwayResult()
        setInputs(emptyList())
        setPages(emptyList())
        setLocked(emptyList())
        setDocuments(emptyList())
        handle[KEY_INSERT_AFTER] = null
        handle[KEY_PICTURE_PAGE] = null
        // The next tool starts with its own names and the level for all at Recommended.
        setBatch(BatchChoices(all = DEFAULT_BATCH_CHOICE))
        clearUndo()
        history.clear()
        clearRedaction()
        // Every copy goes, including one still being imported or waiting for its password. File work
        // runs one task at a time, so this waits for a cancelled import or run to stop first, and for a
        // save of the result (its PDFs or a ZIP file of them) to finish.
        viewModelScope.launch(fileOps) {
            engine.deleteAllExcept(emptyList())
            office.deleteAllExcept(emptyList())
            pictures.deleteAllExcept(emptyList())
            engine.clearOutput()
            office.clearWork()
        }
    }

    /**
     * Forgets what was done with the PDFs in [tool], keeping the PDFs themselves, for another tool to
     * take them: when the tool goes back to Choose a tool. Its changes, its result and the names and
     * choices it gave each PDF go; a PDF or picture still being added is abandoned. A tool for one PDF
     * keeps only that one: the PDFs Reorganize PDF's "Add pages" added go with their pages, also one
     * still waiting for its password.
     */
    fun clearToolWork(tool: PdfTool) {
        val importing = importJob?.isActive == true
        importJob?.cancel()
        importGeneration++
        importJob = null
        _importing.value = false
        // As in reset: a ZIP file to share is no longer wanted, one being saved goes on.
        zipper.stopSharing()
        putAwayResult()
        val forOne = tool != PdfTool.MERGE && !tool.isBatch && inputs.value.isNotEmpty()
        val added = if (forOne) inputs.value.drop(1) + locked.value else emptyList()
        if (added.isNotEmpty()) {
            setInputs(inputs.value.take(1))
            setLocked(emptyList())
            _wrongPassword.value = false
            added.forEach { input -> viewModelScope.launch(fileOps) { engine.delete(input) } }
        }
        setPages(allPages())
        handle[KEY_INSERT_AFTER] = null
        handle[KEY_PICTURE_PAGE] = null
        setBatch(BatchChoices(all = DEFAULT_BATCH_CHOICE).following(inputs.value.map { it.id }))
        clearUndo()
        val hadItems = history.items.value.isNotEmpty()
        history.clear()
        clearRedaction()
        viewModelScope.launch(fileOps) {
            engine.clearOutput()
            // A PDF the abandoned import copied anyway goes with the others once the PDFs are put away.
            if (importing || hadItems) pictures.deleteAllExcept(emptyList())
        }
    }

    /**
     * For tests: returns once the file work started so far is done, such as the cleanup [reset]
     * starts, or the one a new view model starts, which would otherwise delete files the next test
     * has just made.
     */
    @VisibleForTesting
    internal suspend fun awaitFileWork() = withContext(fileOps) {}

    private fun clearInputs() {
        val old = inputs.value + locked.value
        val oldDocuments = documents.value
        setInputs(emptyList())
        setPages(emptyList())
        setLocked(emptyList())
        setDocuments(emptyList())
        handle[KEY_INSERT_AFTER] = null
        clearUndo()
        // What was added belongs to the PDF's pages.
        val hadItems = history.items.value.isNotEmpty()
        history.clear()
        clearRedaction()
        _wrongPassword.value = false
        old.forEach { input -> viewModelScope.launch(fileOps) { engine.delete(input) } }
        oldDocuments.forEach { viewModelScope.launch(fileOps) { office.delete(it) } }
        if (hadItems) viewModelScope.launch(fileOps) { pictures.deleteAllExcept(emptyList()) }
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
        message(strings.getQuantityString(R.plurals.msg_result_cleared, count))
    }

    fun saveToDownloads() {
        val files = _result.value ?: return
        if (!canSaveToDownloads) return
        // A second tap that lands before the button is turned off does nothing.
        if (_saving.value) return
        // The PDFs copied so far: when a later one fails, these are saved all the same.
        val done = ArrayList<PdfFile>()
        save(partial = { done }) {
            // Saved there already (a second tap, say, or each on its own), it only says so again. Each
            // copy is kept as it is made, so that saving again after a failure doesn't copy it twice.
            val copies = downloadsCopies(files)
            saver.saveToDownloads(files.map { it.file }, earlier = files.map { copies[it.file.path] }) { i, copy ->
                copies[files[i].file.path] = copy
                done += files[i]
            }
            strings.getString(R.string.pdf_saved_downloads)
        }
    }

    /** Saves [file], one of the result's PDFs, into Downloads on its own. */
    fun saveOneToDownloads(file: PdfFile) {
        val files = _result.value ?: return
        if (!canSaveToDownloads || _saving.value) return
        val one = files.firstOrNull { it.file == file.file } ?: return
        save(listOf(one)) {
            // Saved there already, with the others or on its own, it only says so again.
            val copies = downloadsCopies(files)
            val copy = saver.saveToDownloads(listOf(one.file), earlier = listOf(copies[one.file.path])).single()
            copies[one.file.path] = copy
            strings.getString(R.string.msg_saved_downloads, copy.name)
        }
    }

    /** The copies in Downloads of [files], the result, by path (see [inDownloads]); in file work only. */
    private fun downloadsCopies(files: List<PdfFile>): MutableMap<String, DownloadsCopy> =
        inDownloads?.takeIf { it.first === files }?.second ?: HashMap<String, DownloadsCopy>().also { inDownloads = files to it }

    /**
     * The next document the "Save as" screen makes is for [file], one of the result's PDFs, or for
     * the only one when it is null. Kept in the saved state, since Android may stop Usugami while the
     * screen is open.
     */
    fun prepareSaveAs(file: PdfFile?) {
        handle[KEY_SAVE_AS] = file?.name
    }

    /** Saves the PDF [prepareSaveAs] named into [uri], a document made by the "Save as" screen. */
    fun saveTo(uri: Uri) {
        val files = _result.value
        val name = handle.get<String>(KEY_SAVE_AS)
        handle[KEY_SAVE_AS] = null
        val file = if (name != null) files?.firstOrNull { it.name == name } else files?.singleOrNull()
        if (file == null) {
            // The system restarted Usugami while the "Save as" screen was open, which cleared the PDF,
            // or the user left the result before the screen covered Usugami. Remove the empty document
            // the screen made, and say so.
            viewModelScope.launch(Dispatchers.IO) { saver.discardIfEmpty(uri) }
            if (resultPutAway) notSaved() else resultCleared(files?.size ?: 1)
            return
        }
        save(listOf(file)) { saver.saveTo(file.file, uri); strings.getString(R.string.msg_saved) }
    }

    fun saveToFolder(tree: Uri) {
        // As in saveTo; the folder picker offered for several PDFs creates nothing itself.
        val files = _result.value ?: return if (resultPutAway) notSaved() else resultCleared(2)
        save {
            // As in saveToDownloads: saving again into that folder copies only what isn't there.
            val copies = inFolder?.takeIf { it.first === files && it.second == tree }?.third
                ?: HashMap<String, Uri>().also { inFolder = Triple(files, tree, it) }
            saver.saveToFolder(files.map { it.file }, tree, earlier = files.map { copies[it.file.path] }) { i, copy ->
                copies[files[i].file.path] = copy
            }
            strings.getString(R.string.msg_saved)
        }
    }

    /** A save screen gave a place to save a result the user had already put away: nothing is saved there. */
    private fun notSaved() = message(strings.getString(R.string.msg_not_saved))

    private fun startSave() {
        saves++
        _saving.value = true
    }

    private fun endSave() {
        if (--saves == 0) _saving.value = false
    }

    /**
     * Runs [block], which saves [files] (the result's PDFs, or all of them when it is null), once the
     * saves asked for before it are done. A document picked while another save is under way gets its
     * PDF too, rather than being left empty. When [block] fails partway, [partial] gives the PDFs it
     * saved before failing, which are marked saved all the same.
     */
    private fun save(files: List<PdfFile>? = null, partial: () -> List<PdfFile> = { emptyList() }, block: () -> String) {
        startSave()
        val saving = _result.value
        val saved = files ?: saving.orEmpty()
        viewModelScope.launch {
            // Whether the PDFs were still there when the save failed.
            var pdfsThere = false
            try {
                // With the other file work, one at a time: when images shared mid-save put the result
                // away, reset() frees its PDFs, and the next run replaces them, only once they are saved.
                val text = withContext(fileOps) {
                    try {
                        block()
                    } catch (e: Exception) {
                        // Checked before that file work goes ahead.
                        pdfsThere = saving?.all { it.file.isFile } == true
                        throw e
                    }
                }
                message(text)
                markSaved(saved)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                val files = _result.value
                when {
                    // The save itself failed (the storage may be full), also when the result was put away meanwhile.
                    pdfsThere -> {
                        partial().takeIf { it.isNotEmpty() }?.let(::markSaved)
                        message(strings.getString(R.string.msg_save_failed))
                    }
                    files != null && files === saving -> resultCleared(files.size)
                    // Otherwise the result was put away and its PDFs have gone: cleared, which was said then.
                }
            } finally {
                endSave()
            }
        }
    }

    fun shareIntent(files: List<PdfFile>): Intent = saver.shareIntent(files.map { it.file })
    fun viewIntent(file: PdfFile): Intent = saver.viewIntent(file.file)

    /** The name the ZIP file of the result's PDFs gets, such as "Report_split.zip". */
    fun zipFileName(): String = ResultZip.fileName(resultName)

    /** Makes one ZIP file of the result's PDFs and shares it (see [zipShares]). */
    fun shareZip() {
        val files = _result.value ?: return
        zipper.share(files, resultName)
    }

    /** Saves one ZIP file of the result's PDFs into a document created with the "Save as" screen. */
    fun saveZipTo(uri: Uri) {
        val files = _result.value
        if (files == null) {
            // As in saveTo: the system restarted Usugami while the screen was open, or the user left.
            viewModelScope.launch(Dispatchers.IO) { saver.discardIfEmpty(uri) }
            if (resultPutAway) notSaved() else resultCleared(2)
            return
        }
        if (zipper.busy) {
            // Another ZIP file is being made: the empty document made for this one isn't left behind.
            viewModelScope.launch(Dispatchers.IO) { saver.discardIfEmpty(uri) }
            message(strings.getString(R.string.zip_busy))
            return
        }
        // Counted as saving, so that Back and Start over wait for it as they do for a save of the PDFs.
        startSave()
        zipper.saveTo(files, uri, onSaved = { markSaved(files) }, finished = ::endSave)
    }

    /** [files] have been saved; the result is, once each of its PDFs is. */
    private fun markSaved(files: List<PdfFile>) {
        val names = _savedFiles.value + files.map { it.name }
        setSavedFiles(names)
        if (_result.value?.all { it.name in names } != false) _saved.value = true
    }

    /**
     * Keeps [names] in the saved state as one flag per PDF of the result, in its order: a few bytes
     * each, as the result itself is kept (see [KeptResult]), since a result can have thousands of PDFs.
     */
    private fun setSavedFiles(names: Set<String>) {
        _savedFiles.value = names
        val files = _result.value
        if (names.isEmpty() || files == null) handle.remove<BooleanArray>(KEY_SAVED_FILES)
        else handle[KEY_SAVED_FILES] = BooleanArray(files.size) { files[it].name in names }
    }

    /** The names [setSavedFiles] kept, those of the result's PDFs that were saved. */
    private fun restoredSavedFiles(): Set<String> {
        val files = _result.value ?: return emptySet()
        val saved = handle.get<BooleanArray>(KEY_SAVED_FILES)?.takeIf { it.size == files.size } ?: return emptySet()
        return files.filterIndexed { i, _ -> saved[i] }.mapTo(HashSet()) { it.name }
    }

    fun cancelZip() = zipper.cancel()

    /** The name [tool]'s PDF gets unless another is typed, after [input] (by default the first PDF). */
    fun defaultName(tool: PdfTool, input: PdfInput? = inputs.value.firstOrNull()): String {
        val base = input?.name?.let(FileNames::baseName) ?: "Usugami"
        return when (tool) {
            // A converted document keeps its name: Report.docx becomes Report.pdf.
            PdfTool.WORD, PdfTool.POWERPOINT, PdfTool.EXCEL, PdfTool.EPUB -> documents.value.firstOrNull()?.name?.let(FileNames::baseName) ?: "Usugami"
            PdfTool.SPLIT -> "${base}_split"
            PdfTool.COMPRESS -> "${base}_compressed"
            PdfTool.MERGE -> "Merged_PDF"
            PdfTool.ROTATE -> "${base}_rotated"
            PdfTool.PAGE_NUMBERS -> "${base}_numbered"
            PdfTool.WATERMARK -> "${base}_watermarked"
            PdfTool.SIGN -> "${base}_signed"
            PdfTool.DELETE_PAGES -> "${base}_pages_deleted"
            PdfTool.REORGANIZE -> "${base}_reorganized"
            PdfTool.FLATTEN -> "${base}_flattened"
            PdfTool.EDIT -> "${base}_edited"
            PdfTool.REDACT -> "${base}_redacted"
        }
    }

    private fun setInputs(list: List<PdfInput>) {
        handle[KEY_INPUTS] = ArrayList(list)
        followBatch()
        // The views of PDFs put away go with them (their files are deleted with the copies).
        if (_views.value.isNotEmpty()) _views.value = _views.value.filterKeys { id -> list.any { it.id == id } }
        for (id in viewJobs.keys.filter { id -> list.none { it.id == id } }) viewJobs.remove(id)?.cancel()
        // Whether the pages are all the PDFs' pages depends on the PDFs too.
        savePages()
    }

    private fun setPages(list: List<PdfPageRef>) {
        _pages.value = list
        savePages()
    }

    /**
     * Keeps the pages in the saved state: nothing while they are [allPages], otherwise each page's
     * PDF, and its number and turn in one Int, with the blank pages' sizes beside them.
     */
    private fun savePages() {
        val list = _pages.value
        if (list == allPages()) {
            for (key in listOf(KEY_PAGE_INPUTS, KEY_PAGE_NUMBERS, KEY_BLANK_SIZES)) handle.remove<Any>(key)
            return
        }
        handle[KEY_PAGE_INPUTS] = LongArray(list.size) { list[it].inputId }
        handle[KEY_PAGE_NUMBERS] = IntArray(list.size) { list[it].page shl 2 or list[it].rotation / 90 }
        handle[KEY_BLANK_SIZES] = list.filter { it.isBlank }.flatMap { listOf(it.width, it.height) }.toFloatArray()
    }

    /** The pages [savePages] kept, or all the PDFs' pages. */
    private fun restoredPages(): List<PdfPageRef> {
        val ids = handle.get<LongArray>(KEY_PAGE_INPUTS)
        val numbers = handle.get<IntArray>(KEY_PAGE_NUMBERS)
        val sizes = handle.get<FloatArray>(KEY_BLANK_SIZES)
        if (ids == null || numbers == null || sizes == null || ids.size != numbers.size) return allPages()
        var blanks = 0
        return ids.indices.map { i ->
            val ref = PdfPageRef(ids[i], numbers[i] shr 2, (numbers[i] and 3) * 90)
            if (!ref.isBlank) ref else ref.copy(width = sizes.getOrElse(2 * blanks) { 0f }, height = sizes.getOrElse(2 * blanks + 1) { 0f }).also { blanks++ }
        }
    }
    private fun setLocked(list: List<PdfInput>) { handle[KEY_LOCKED] = ArrayList(list) }
    private fun message(text: String) {
        if (unshown.add(text) && _messages.trySend(text).isFailure) unshown -= text
    }
    private fun pluralMessage(@PluralsRes res: Int, count: Int) = message(strings.getQuantityString(res, count, count))

    private var finishing = false

    /** Called when the activity is finishing for good, so [onCleared] can free the copies. */
    fun activityFinishing() {
        finishing = true
    }

    override fun onCleared() {
        // Not when the system merely destroyed the activity: the saved state still lists the copies.
        if (!finishing) return
        val old = inputs.value + locked.value
        val oldDocuments = documents.value
        Thread {
            old.forEach(engine::delete)
            oldDocuments.forEach(office::delete)
            pictures.deleteAllExcept(emptyList())
        }.start()
    }

    private companion object {
        const val KEY_INPUTS = "pdf_inputs"
        const val KEY_PAGE_INPUTS = "pdf_page_inputs"
        const val KEY_PAGE_NUMBERS = "pdf_page_numbers"
        const val KEY_BLANK_SIZES = "pdf_blank_sizes"
        const val KEY_LOCKED = "pdf_locked"
        const val KEY_SIGNATURE = "signature"
        const val KEY_DOCUMENTS = "documents"
        const val KEY_INSERT_AFTER = "insert_after"
        const val KEY_PICTURE_PAGE = "edit_picture_page"
        const val KEY_SAVED = "saved"
        const val KEY_SAVED_FILES = "saved_files"
        const val KEY_SAVE_AS = "save_as"
        const val KEY_PASSWORD_REMOVED = "password_removed"
        const val KEY_RESULT_NAME = "result_name"
        const val KEY_BATCH = "batch"
        const val KEY_TAKING_SHARED = "taking_shared"

        /** The choice for all PDFs a batch starts with: Compress PDF's Recommended. */
        val DEFAULT_BATCH_CHOICE = PdfCompression.RECOMMENDED.ordinal

        /** Changes Undo can take back, one after another. */
        const val MAX_UNDO = 50

        /**
         * Edit PDF's items are kept in the saved state, which must stay well under the size Android
         * allows it: at most this many items, with at most this many points in their strokes.
         */
        const val MAX_EDIT_ITEMS = 300
        const val MAX_INK_POINTS = 20_000
        const val KEY_REDACT_BOXES = "redact_boxes"
        const val KEY_REDACT_PAGE = "redact_page"
        const val KEY_REDACT_SELECTED = "redact_selected"
        const val KEY_REDACT_SEARCHED = "redact_searched"
    }
}

/** Pictures put on Edit PDF's pages whose copies the system has cleared from the cache. */
private class PicturesCleared(val paths: List<String>) : Exception()

/** No PDF of a batch could be made: [failures] are the files that failed, by name, and why. */
private class BatchFailed(val failures: List<Pair<String, Throwable>>) : Exception()

/**
 * What is said of a file left out or of a PDF made (why it couldn't be used, say): [one] of one
 * file, and [many] of several files of which the same is said, worded for them all.
 */
private class Said(val one: String, val many: String)
