package io.github.nath_goa.usugami.ui

import android.app.Application
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.core.FileNames
import io.github.nath_goa.usugami.core.PageNumberPosition
import io.github.nath_goa.usugami.core.PageNumberSize
import io.github.nath_goa.usugami.core.PageNumbers
import io.github.nath_goa.usugami.core.PageSelectionException
import io.github.nath_goa.usugami.core.PdfPageSelection
import io.github.nath_goa.usugami.core.SignaturePlacement
import io.github.nath_goa.usugami.data.AppLanguages
import io.github.nath_goa.usugami.data.PdfCertificateEncrypted
import io.github.nath_goa.usugami.data.PdfCompression
import io.github.nath_goa.usugami.data.PdfFile
import io.github.nath_goa.usugami.data.PdfInput
import io.github.nath_goa.usugami.data.PdfPageRef
import io.github.nath_goa.usugami.data.PdfPasswordRequired
import io.github.nath_goa.usugami.data.PdfSaver
import io.github.nath_goa.usugami.data.PdfToolsEngine
import io.github.nath_goa.usugami.data.PdfWrongPassword
import io.github.nath_goa.usugami.data.Signature
import io.github.nath_goa.usugami.data.WatermarkColor
import io.github.nath_goa.usugami.data.WatermarkFontMissing
import io.github.nath_goa.usugami.data.WatermarkOpacity
import io.github.nath_goa.usugami.data.WatermarkText
import io.github.nath_goa.usugami.data.office.OfficeConverter
import io.github.nath_goa.usugami.data.office.OfficeEncrypted
import io.github.nath_goa.usugami.data.office.OfficeFileProblem
import io.github.nath_goa.usugami.data.office.OfficeInput
import io.github.nath_goa.usugami.data.office.OfficeKind
import io.github.nath_goa.usugami.data.office.OfficeLegacyFormat
import io.github.nath_goa.usugami.data.office.OfficeNotRecognized
import io.github.nath_goa.usugami.data.office.OfficeNothingToConvert
import io.github.nath_goa.usugami.data.office.OfficeProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The PDF tools. Each has a screen at its lowercase name. All but MERGE work on one PDF, except WORD
 * and POWERPOINT, which turn one Word or PowerPoint file into a PDF.
 */
enum class PdfTool { SPLIT, MERGE, COMPRESS, ROTATE, PAGE_NUMBERS, WATERMARK, SIGN, WORD, POWERPOINT }

/** The kind of file a tool converts to PDF; null for the tools that work on PDFs. */
val PdfTool.officeKind: OfficeKind?
    get() = when (this) {
        PdfTool.WORD -> OfficeKind.WORD
        PdfTool.POWERPOINT -> OfficeKind.POWERPOINT
        else -> null
    }
enum class SplitMode { SELECTED, CUSTOM, FIXED, EVERY_PAGE }

class PdfToolsViewModel(private val app: Application, private val handle: SavedStateHandle) : AndroidViewModel(app) {
    // Messages are written here, in the language the user picked (see AppLanguages.localized).
    private val strings: Resources get() = AppLanguages.localized(app).resources

    private val engine = PdfToolsEngine(app)
    private val office = OfficeConverter(app, engine)
    private val saver = PdfSaver(app)
    private val fileOps = Dispatchers.IO.limitedParallelism(1)
    private val restored = handle.contains(KEY_INPUTS)

    val inputs: StateFlow<List<PdfInput>> = handle.getStateFlow(KEY_INPUTS, ArrayList<PdfInput>())
    val pages: StateFlow<List<PdfPageRef>> = handle.getStateFlow(KEY_PAGES, ArrayList<PdfPageRef>())
    /** Picked PDFs that need a password, in the order they were picked. The dialog asks for the first. */
    val locked: StateFlow<List<PdfInput>> = handle.getStateFlow(KEY_LOCKED, ArrayList<PdfInput>())

    /**
     * The signature drawn for Sign PDF. It stays for the whole session, so it can go on another PDF
     * without drawing it again: [reset] keeps it. It lives only here, in the saved state.
     */
    val signature: StateFlow<Signature?> = handle.getStateFlow(KEY_SIGNATURE, null)

    /** The Word or PowerPoint file picked for Word to PDF or PowerPoint to PDF. */
    val document: StateFlow<OfficeInput?> = handle.getStateFlow(KEY_DOCUMENT, null)

    /** How far converting [document] has got, while it is being converted. */
    private val _convertProgress = MutableStateFlow<OfficeProgress?>(null)
    val convertProgress = _convertProgress.asStateFlow()

    /** The password is being tried on the first locked PDF. */
    private val _unlocking = MutableStateFlow(false)
    val unlocking = _unlocking.asStateFlow()
    /** The last password tried on the first locked PDF didn't open it. */
    private val _wrongPassword = MutableStateFlow(false)
    val wrongPassword = _wrongPassword.asStateFlow()
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

    private var nextId = maxOf(System.currentTimeMillis(), ((inputs.value + locked.value).maxOfOrNull { it.id } ?: 0) + 1)
    private var task: Job? = null
    private var importJob: Job? = null
    /** Changes when an import starts or is abandoned, so an abandoned one knows to stay quiet. */
    private var importGeneration = 0

    init {
        val keep = if (restored) inputs.value + locked.value else emptyList()
        val keepDocument = document.value
        viewModelScope.launch {
            val (missing, documentMissing) = withContext(fileOps) {
                // Leftovers from an earlier session, including PDFBox scratch files from a killed run.
                engine.deleteAllExcept(keep)
                office.deleteAllExcept(keepDocument)
                engine.clearOutput()
                engine.clearScratch()
                office.clearWork()
                // The copies are in the cache, which the system may have cleared while Usugami was away.
                keep.filterNot { it.file.isFile } to (keepDocument != null && !keepDocument.file.isFile)
            }
            if (missing.isNotEmpty()) forgetInputs(missing)
            if (documentMissing) forgetDocument()
        }
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
            fun failed(@StringRes reason: Int) { if (!abandoned()) message(strings.getString(reason)) }
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
                        failed(R.string.pdf_certificate_unsupported)
                    } catch (_: Exception) {
                        failed(R.string.pdf_import_failed)
                    } catch (_: LinkageError) {
                        // Never crash on a PDF that PDFBox can't handle.
                        failed(R.string.pdf_import_failed)
                    } catch (_: OutOfMemoryError) {
                        // A PDF with a huge number of objects can exhaust the heap while it is read.
                        failed(R.string.pdf_import_failed)
                    }
                }
            } finally {
                // After reset() a newer import may be running, and it owns the flag.
                if (!abandoned()) _importing.value = false
            }
        }
    }

    /** Copies the Word or PowerPoint file at [uri] in, in place of the one picked before. */
    fun addDocument(uri: Uri, kind: OfficeKind) {
        if (_processing.value || _importing.value) return
        _importing.value = true
        val generation = ++importGeneration
        importJob = viewModelScope.launch {
            val job = coroutineContext[Job]!!
            fun abandoned() = generation != importGeneration
            fun failed(text: String) { if (!abandoned()) message(text) }
            try {
                val input = withContext(fileOps) { office.import(uri, nextId++, kind) { job.ensureActive() } }
                if (abandoned()) {
                    withContext(fileOps) { office.delete(input) }
                    return@launch
                }
                val old = document.value
                handle[KEY_DOCUMENT] = input
                if (old != null) viewModelScope.launch(fileOps) { office.delete(old) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OfficeFileProblem) {
                failed(officeMessage(e, kind))
            } catch (_: Exception) {
                failed(officeMessage(OfficeNotRecognized(), kind))
            } catch (_: OutOfMemoryError) {
                failed(officeMessage(OfficeNotRecognized(), kind))
            } finally {
                if (!abandoned()) _importing.value = false
            }
        }
    }

    /** Converts [document] to a PDF named [fileName]. */
    fun runConvert(fileName: String) {
        val input = document.value ?: return
        process(emptyList(), input) { check ->
            listOf(office.convert(input, fileName, check) { _convertProgress.value = it })
        }
    }

    /** Why a Word or PowerPoint file can't be converted, in the user's language. */
    private fun officeMessage(e: OfficeFileProblem, kind: OfficeKind): String = strings.getString(
        when (e) {
            is OfficeLegacyFormat -> if (kind == OfficeKind.WORD) R.string.office_legacy_word else R.string.office_legacy_powerpoint
            is OfficeEncrypted -> R.string.office_encrypted
            is OfficeNothingToConvert -> R.string.office_no_slides
            is OfficeNotRecognized -> if (kind == OfficeKind.WORD) R.string.office_not_word else R.string.office_not_powerpoint
        },
    )

    /** The picked document's copy was cleared from the cache: drop it, and say so. */
    private fun forgetDocument() {
        handle[KEY_DOCUMENT] = null
        pluralMessage(R.plurals.msg_files_cleared, 1)
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
                if (stillAsked()) unlockFailed(pending, if (e is PdfCertificateEncrypted) R.string.pdf_certificate_unsupported else R.string.pdf_import_failed)
            } catch (_: LinkageError) {
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
        setLocked(locked.value.filterNot { it.id in ids })
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

    /** Turns every page by [degrees] more, as Rotate's "all pages" buttons do. */
    fun rotateAll(degrees: Int) {
        setPages(pages.value.map { it.copy(rotation = Math.floorMod(it.rotation + degrees, 360)) })
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
        } catch (e: PageSelectionException) {
            message(pageSelectionMessage(e))
            return
        } catch (_: IllegalArgumentException) {
            message(strings.getString(R.string.pdf_invalid_pages))
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
                message(strings.getString(R.string.pdf_no_reduction))
            }
        }) { check -> listOf(engine.compress(input, level, fileName, check)) }
    }

    /** Saves the PDF with its pages turned as [pages] says; the button is off until one is turned. */
    fun runRotate(fileName: String) {
        val input = inputs.value.singleOrNull() ?: return
        val rotations = pages.value.filter { it.inputId == input.id && it.rotation != 0 }.associate { it.page to it.rotation }
        if (rotations.isEmpty()) return
        process(listOf(input)) { check -> listOf(engine.rotate(input, rotations, fileName, check)) }
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
        process(listOf(input)) { check -> listOf(engine.addPageNumbers(input, numbered, position, size, fileName, check)) }
    }

    fun runWatermark(text: String, color: WatermarkColor, opacity: WatermarkOpacity, fileName: String) {
        val input = inputs.value.singleOrNull() ?: return
        if (text.isBlank()) return
        // Checked here too, so the message comes at once rather than after the progress dialog.
        if (!WatermarkText.canDraw(text)) {
            message(strings.getString(R.string.watermark_no_font))
            return
        }
        process(listOf(input)) { check -> listOf(engine.addWatermark(input, text, color, opacity, fileName, check)) }
    }

    fun setSignature(signature: Signature) {
        if (!signature.isEmpty) handle[KEY_SIGNATURE] = signature
    }

    /** Signs page [page] (zero-based) where [placement] says, or in its lower right when it is null. */
    fun runSign(page: Int, placement: SignaturePlacement?, fileName: String) {
        val input = inputs.value.singleOrNull() ?: return
        val signature = signature.value ?: return
        if (page !in 0 until input.pageCount) return
        process(listOf(input)) { check -> listOf(engine.sign(input, signature, page, placement, fileName, check)) }
    }

    /** Why the page numbers typed can't be used, in the user's language. */
    private fun pageSelectionMessage(e: PageSelectionException): String = when (e.problem) {
        PageSelectionException.Problem.FORMAT -> strings.getString(R.string.pdf_pages_format)
        PageSelectionException.Problem.OUT_OF_RANGE -> strings.getString(R.string.pdf_pages_range, e.pageCount)
        PageSelectionException.Problem.CHUNK_SIZE -> strings.getString(R.string.pdf_pages_chunk, e.pageCount)
    }

    /**
     * Runs [block], which reads [sources] (or the Word or PowerPoint file [document]). Every tool
     * writes PDFs without a password.
     */
    private fun process(
        sources: List<PdfInput>,
        document: OfficeInput? = null,
        afterResult: (List<PdfFile>) -> Unit = {},
        block: (() -> Unit) -> List<PdfFile>,
    ) {
        if (_processing.value || _importing.value) return
        _processing.value = true
        _convertProgress.value = null
        task = viewModelScope.launch {
            try {
                val job = coroutineContext[Job]!!
                val missing = withContext(fileOps) { sources.filterNot { it.file.isFile } }
                if (missing.isNotEmpty()) {
                    forgetInputs(missing)
                    return@launch
                }
                if (document != null && withContext(fileOps) { !document.file.isFile }) {
                    forgetDocument()
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
                message(strings.getString(if (document != null) R.string.office_convert_failed else R.string.pdf_process_failed))
            } catch (e: OfficeFileProblem) {
                message(officeMessage(e, document?.kind ?: OfficeKind.WORD))
            } catch (_: PdfCertificateEncrypted) {
                message(strings.getString(R.string.pdf_certificate_unsupported))
            } catch (_: WatermarkFontMissing) {
                message(strings.getString(R.string.watermark_no_font))
            } catch (e: Exception) {
                message(
                    if (e.isOutOfSpace()) strings.getString(R.string.msg_convert_failed, strings.getString(R.string.msg_out_of_space))
                    else strings.getString(if (document != null) R.string.office_convert_failed else R.string.pdf_process_failed),
                )
            } catch (_: LinkageError) {
                message(strings.getString(if (document != null) R.string.office_convert_failed else R.string.pdf_process_failed))
            } finally {
                _processing.value = false
                _convertProgress.value = null
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
     * The drawn signature stays, for the next PDF to sign.
     */
    fun reset() {
        importJob?.cancel()
        unlockJob?.cancel()
        task?.cancel()
        // The abandoned import stops at its next chunk; the next tool can import straight away.
        importGeneration++
        importJob = null
        _importing.value = false
        _wrongPassword.value = false
        _result.value = null
        setInputs(emptyList())
        setPages(emptyList())
        setLocked(emptyList())
        handle[KEY_DOCUMENT] = null
        // Every copy goes, including one still being imported or waiting for its password. File work
        // runs one task at a time, so this waits for a cancelled import or run to stop first.
        viewModelScope.launch(fileOps) {
            engine.deleteAllExcept(emptyList())
            office.deleteAllExcept(null)
            engine.clearOutput()
            office.clearWork()
        }
    }

    private fun clearInputs() {
        val old = inputs.value + locked.value
        val oldDocument = document.value
        setInputs(emptyList())
        setPages(emptyList())
        setLocked(emptyList())
        handle[KEY_DOCUMENT] = null
        _wrongPassword.value = false
        old.forEach { input -> viewModelScope.launch(fileOps) { engine.delete(input) } }
        oldDocument?.let { viewModelScope.launch(fileOps) { office.delete(it) } }
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
        save {
            files.forEach { saver.saveToDownloads(it.file) }
            strings.getString(R.string.pdf_saved_downloads)
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
        save { saver.saveTo(file.file, uri); strings.getString(R.string.msg_saved) }
    }

    fun saveToFolder(tree: Uri) {
        // As in saveTo; the folder picker offered for several PDFs creates nothing itself.
        val files = _result.value ?: return resultCleared(2)
        save { saver.saveToFolder(files.map { it.file }, tree); strings.getString(R.string.msg_saved) }
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
                else message(strings.getString(R.string.msg_save_failed))
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
            // A converted document keeps its name: Report.docx becomes Report.pdf.
            PdfTool.WORD, PdfTool.POWERPOINT -> document.value?.name?.let(FileNames::baseName) ?: "Usugami"
            PdfTool.SPLIT -> "${base}_split"
            PdfTool.COMPRESS -> "${base}_compressed"
            PdfTool.MERGE -> "Merged_PDF"
            PdfTool.ROTATE -> "${base}_rotated"
            PdfTool.PAGE_NUMBERS -> "${base}_numbered"
            PdfTool.WATERMARK -> "${base}_watermarked"
            PdfTool.SIGN -> "${base}_signed"
        }
    }

    private fun setInputs(list: List<PdfInput>) { handle[KEY_INPUTS] = ArrayList(list) }
    private fun setPages(list: List<PdfPageRef>) { handle[KEY_PAGES] = ArrayList(list) }
    private fun setLocked(list: List<PdfInput>) { handle[KEY_LOCKED] = ArrayList(list) }
    private fun message(text: String) { _messages.trySend(text) }
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
        val oldDocument = document.value
        Thread {
            old.forEach(engine::delete)
            oldDocument?.let(office::delete)
        }.start()
    }

    private companion object {
        const val KEY_INPUTS = "pdf_inputs"
        const val KEY_PAGES = "pdf_pages"
        const val KEY_LOCKED = "pdf_locked"
        const val KEY_SIGNATURE = "signature"
        const val KEY_DOCUMENT = "document"
    }
}
