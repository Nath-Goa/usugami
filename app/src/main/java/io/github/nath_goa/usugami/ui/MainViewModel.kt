package io.github.nath_goa.usugami.ui

import android.app.Application
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.os.SystemClock
import android.util.Log
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.core.content.FileProvider
import androidx.core.os.bundleOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.core.Compression
import io.github.nath_goa.usugami.core.CropPoint
import io.github.nath_goa.usugami.core.CropQuad
import io.github.nath_goa.usugami.core.FileNames
import io.github.nath_goa.usugami.core.ImageTransform
import io.github.nath_goa.usugami.core.LayoutSettings
import io.github.nath_goa.usugami.core.NaturalOrder
import io.github.nath_goa.usugami.core.OpenedFiles
import io.github.nath_goa.usugami.core.LookAdjustments
import io.github.nath_goa.usugami.core.MarkStyle
import io.github.nath_goa.usugami.core.PageCrop
import io.github.nath_goa.usugami.core.PageGroups
import io.github.nath_goa.usugami.core.PageLook
import io.github.nath_goa.usugami.core.ReadFrom
import io.github.nath_goa.usugami.core.SharedFiles
import io.github.nath_goa.usugami.core.SharedKind
import io.github.nath_goa.usugami.core.ShortcutTarget
import io.github.nath_goa.usugami.core.ZipProgress
import io.github.nath_goa.usugami.core.isOutOfSpace
import io.github.nath_goa.usugami.data.AppLanguages
import io.github.nath_goa.usugami.data.CropQuadParceler
import io.github.nath_goa.usugami.data.DownloadsCopy
import io.github.nath_goa.usugami.data.ImageFormat
import io.github.nath_goa.usugami.data.ImageItem
import io.github.nath_goa.usugami.data.ImageStore
import io.github.nath_goa.usugami.data.ImageUnreadableException
import io.github.nath_goa.usugami.data.LookAdjustmentsParceler
import io.github.nath_goa.usugami.data.MarkSetting
import io.github.nath_goa.usugami.data.OcrEngine
import io.github.nath_goa.usugami.data.OcrLanguage
import io.github.nath_goa.usugami.data.OcrProblem
import io.github.nath_goa.usugami.data.OcrUnavailableException
import io.github.nath_goa.usugami.data.OriginalImage
import io.github.nath_goa.usugami.data.PageCropper
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
import io.github.nath_goa.usugami.data.probeKind
import io.github.nath_goa.usugami.data.office.DocumentSorter
import io.github.nath_goa.usugami.data.office.PickedDocuments
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.TypeParceler
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class SortMode { NAME_ASC, NAME_DESC, REVERSE }

/** [done] of [total] steps of [stage]; a [total] of 0 means the amount isn't known yet. */
data class Progress(val done: Int, val total: Int, val stage: Stage = Stage.CREATE) {
    enum class Stage { DOWNLOAD_OCR, CREATE, READ_TEXT, ADD_TEXT, ADD_MARK }
}

/** What the user chose in the Convert sheet. [ocrLanguage] is null when OCR is off. */
data class ConvertChoices(val fileName: String, val ocrLanguage: OcrLanguage?)

/**
 * The crop screen, showing image [itemId] turned [rotation] degrees clockwise, as on its page. Its
 * corners start at [start]; Reset puts them on [found], the page the automatic search found
 * ([foundPage]), or else a little in from the image's edges. The corners are on the image as it shows
 * upright, [width] × [height] pixels (see [shown] and [upright]). [fromCamera] says the image is a
 * photo taken. An image of a scan ([staged], see [ScanQueue]) has its corners chosen alone, one image
 * after another: Back goes to the image before, and a photo can be discarded there, an uploaded image
 * left as it is with the rest. [opened] tells one opening from the next. The page's look starts as
 * [look], with [adjustments]. [side] says which side of an ID card the page is (by its place on the
 * card's page, also when cropped again from the editor), null for a page of its own; a side being
 * added by the ID card flow ([cardScan]) goes if the screen is left without cropping, however it was
 * added.
 */
@Parcelize
@TypeParceler<CropQuad, CropQuadParceler>
@TypeParceler<LookAdjustments, LookAdjustmentsParceler>
data class CropSession(
    val itemId: Long,
    val fromCamera: Boolean,
    val rotation: Int,
    val width: Int,
    val height: Int,
    val start: CropQuad,
    val found: CropQuad,
    val foundPage: Boolean,
    val opened: Long,
    val look: PageLook = PageLook.ORIGINAL,
    val adjustments: LookAdjustments = LookAdjustments.NONE,
    val staged: Boolean = false,
    val side: CardSide? = null,
    val cardScan: Boolean = false,
) : Parcelable {
    /** [quad] on the image as the screen shows it, turned [turn] degrees clockwise. */
    fun shown(quad: CropQuad, turn: Int = rotation): CropQuad {
        val sideways = turn % 180 != 0
        return quad.transformed(ImageTransform.rotation(turn)).ordered(if (sideways) height else width, if (sideways) width else height)
    }

    /** [quad], as the screen shows the image turned [turn] degrees clockwise, on the image upright. */
    fun upright(quad: CropQuad, turn: Int = rotation): CropQuad = quad.transformed(ImageTransform.rotation(turn).inverse()).ordered(width, height)
}

/** The stages of a scan, in order: its photos taken, each image cropped, the pages' looks. */
enum class ScanStage { PHOTOS, CROP, LOOKS }

/**
 * A scan, from its first photo (or the images picked with Home's Upload images) until Save puts its
 * pages in the editor, at [stage]. [pages] are its images that are still there, in the order taken or
 * picked: [camera] says they are photos taken for it, which go when it is discarded. While they are
 * cropped, [waiting] have not been through the crop screen yet (the first is the one it shows, while
 * it does: they are always the last of [pages]), and [whole] were left whole there. [coming] are images
 * picked that are still being added. On the looks, [selected] is the page shown large ([shownPage]),
 * and [sameLook] gives a look to every page of the scan; [styled] says its pages have had their first
 * look, which they get once. Its pages are in the editor's list from the moment each is added, so that
 * the saved state keeps them, but the editor and its PDF wait for Save.
 */
@Parcelize
data class ScanQueue(
    val stage: ScanStage = ScanStage.CROP,
    val camera: Boolean = false,
    val pages: List<Long> = emptyList(),
    val waiting: List<Long> = emptyList(),
    val whole: List<Long> = emptyList(),
    val coming: Int = 0,
    val selected: Long = 0,
    val sameLook: Boolean = false,
    val styled: Boolean = false,
) : Parcelable {
    /** The image showing on the crop screen, or next to, counted from 1 among [total]. */
    val place: Int get() = pages.size - waiting.size + 1

    /** How many images the scan has, those dropped (unreadable, or removed) left out. */
    val total: Int get() = pages.size + coming

    /** Whether the image showing is the scan's last. */
    val isLast: Boolean get() = waiting.size <= 1 && coming == 0

    /** The page the looks show large: the one [selected], or else the first. */
    val shownPage: Long? get() = selected.takeIf { it in pages } ?: pages.firstOrNull()
}

/**
 * Image [itemId]'s page being cropped inside [quad] (on the image it is cropped from, upright). Kept
 * in the saved state until the page is made, so a crop Android stopped Usugami in the middle of is
 * made again.
 */
@Parcelize
@TypeParceler<CropQuad, CropQuadParceler>
data class PendingCrop(val itemId: Long, val quad: CropQuad) : Parcelable

/**
 * A photo taken, in the camera app's file at [path], to be added as [name]: as a side of ID card
 * [card], or as a page of its own (0). Kept in the saved state until it is added, so a photo Android
 * closed Usugami before adding (it waits behind images still being added) is added when Usugami
 * starts again.
 */
@Parcelize
data class PendingPhoto(val path: String, val name: String, val card: Long = 0) : Parcelable

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

    /** Pages are cropped one at a time (each takes a large picture's memory), beside the file work. */
    private val cropOps = Dispatchers.IO.limitedParallelism(1)

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

    /** Images being added, and pages being cropped: a PDF waits for them. */
    private val _pendingImports = MutableStateFlow(0)
    val pendingImports: StateFlow<Int> = _pendingImports.asStateFlow()

    /** The crop screen, while it shows. Kept in the saved state, as the images are. */
    private val _crop = MutableStateFlow<CropSession?>(handle[KEY_CROP])
    val crop: StateFlow<CropSession?> = _crop.asStateFlow()

    /** The images whose page is being cropped. */
    private val _cropping = MutableStateFlow<Set<Long>>(emptySet())
    val cropping: StateFlow<Set<Long>> = _cropping.asStateFlow()

    /** Set while the crop screen is being opened (the page looked for), so a second tap does nothing. */
    private val _openingCrop = MutableStateFlow(false)
    val openingCrop: StateFlow<Boolean> = _openingCrop.asStateFlow()

    /**
     * The ID card being scanned, while Home's ID card flow goes on: its prompt shows for the side it
     * wants, unless that side's photo or image is on its way or on the crop screen. Kept in the saved
     * state, as [crop] is.
     */
    private val _card = MutableStateFlow<CardScan?>(handle[KEY_CARD])
    val card: StateFlow<CardScan?> = _card.asStateFlow()

    /**
     * The scan going on, if one is (see [ScanQueue]). Kept in the saved state, at every stage, while it
     * has a page, or while its first photo is being taken or added; the images picked that were still
     * being added when Android closed Usugami are gone (the user is told), so it goes on without them.
     */
    private val _scan = MutableStateFlow(restoredScan())
    val scan: StateFlow<ScanQueue?> = _scan.asStateFlow()

    /**
     * The scan is in the saved state as soon as it changes, not a moment later: a scan's camera opens
     * right after it starts, and Android may stop Usugami while it is open.
     */
    private fun setScan(scan: ScanQueue?) {
        _scan.value = scan
        handle[KEY_SCAN] = scan
    }

    /** Counts the scans started: the images of one that has ended are added as any others are. */
    private var scans = 0

    /** Counts the batches of images picked or shared, in the order they came (see [allPagesChoice]). */
    private var importBatches = 0

    /**
     * What Apply to all pages gave every page last. The images of the batches that were still being
     * added then take it too, as each is added: they are pages of the PDF as much as those already in.
     */
    private var allPagesChoice: AllPagesChoice? = null

    private val _cameraOpen = MutableStateFlow(handle.get<String>(KEY_CAPTURE) != null)
    /** Whether the camera app is open for a photo (see [newCapture]). */
    val cameraOpen: StateFlow<Boolean> = _cameraOpen.asStateFlow()

    /**
     * The file the camera app saves the next photo in, while it is open. In the saved state: Android
     * often stops Usugami while the camera app is open.
     */
    private var capture: String?
        get() = handle[KEY_CAPTURE]
        set(value) {
            handle[KEY_CAPTURE] = value
            _cameraOpen.value = value != null
        }

    /**
     * The photos taken that are waiting to be added, in the saved state until each is (see [addPhoto]).
     * One taken out of it before it is added (its scan discarded, or every image) isn't added.
     */
    private var pendingPhotos: List<PendingPhoto>
        get() = handle.get<ArrayList<PendingPhoto>>(KEY_PENDING_PHOTOS).orEmpty()
        set(value) {
            handle[KEY_PENDING_PHOTOS] = ArrayList(value)
            _photosAdding.value = value.count { it.card == 0L }
        }

    private val _photosAdding = MutableStateFlow(pendingPhotos.count { it.card == 0L })
    /** How many photos taken for a scan are still being added. */
    val photosAdding: StateFlow<Int> = _photosAdding.asStateFlow()

    /**
     * The files of the photos that the state Android saved last names as waiting: Usugami may start
     * again from that state, so each one's file stays once it is added, until a state that no longer
     * names it is saved. On the main thread only.
     */
    private val photosInSavedState = pendingPhotos.mapTo(HashSet()) { it.path }

    /**
     * The pages being cropped, in the saved state from the moment their corners are chosen: the
     * pages themselves change only once each is made, often after Android has stopped Usugami.
     */
    private var pendingCrops: List<PendingCrop>
        get() = handle.get<ArrayList<PendingCrop>>(KEY_PENDING_CROPS).orEmpty()
        set(value) { handle[KEY_PENDING_CROPS] = ArrayList(value) }

    /**
     * Pages cropped again, replaced by their new crop, deleted once Android has saved a state that no
     * longer names them. A crop is often made after Android has stopped Usugami and saved its state,
     * which names the page as it was: if Android then closes Usugami, it starts again from that page,
     * which must still be there. Photos added that such a state names as waiting are kept the same
     * way (see [photosInSavedState]). On the main thread only.
     */
    private val superseded = ArrayList<File>()

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
    /**
     * The result's copies in Downloads, once it has been saved there: saving it again copies only what
     * has gone. Set while saving, and read for the saved state (see [KeptCopies]): a copy's place is
     * only ever filled in, so a read sees each copy made or not yet.
     */
    @Volatile private var inDownloads: Pair<List<PdfFile>, MutableList<DownloadsCopy?>>? = null
    /** The result's copies in the folder it was saved to last, the same way. */
    @Volatile private var inFolder: Triple<List<PdfFile>, Uri, MutableList<Uri?>>? = null
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
    private val zipper = ResultZipper(app, exporter.zipDir, viewModelScope, { strings }, { _messages.trySend(it) }, ::resultCleared, turn = saveTurn)
    /** How far the ZIP file of the result's PDFs has got, while it is being made. */
    val zipProgress: StateFlow<ZipProgress?> = zipper.progress
    /** Share screens for ZIP files of the result's PDFs, once each is ready. */
    val zipShares: Flow<Intent> = zipper.shares

    /**
     * The Usugami mark on the result's PDFs. A change rewrites them after a save under way, and the
     * copies saved before are then of the PDFs as they were: saving again saves them anew.
     */
    private val marker = ResultMarker(
        app, handle, viewModelScope, fileOps, result,
        changed = { files ->
            _result.value = files
            _saved.value = false
        },
        failed = { message(R.string.mark_failed) },
        refused = { pluralMessage(R.plurals.mark_refused, it) },
        turn = saveTurn,
    )
    /** Whether the PDFs made carry the Usugami mark, and its size and opacity: one setting for the whole app. */
    val mark: StateFlow<MarkSetting> = marker.setting
    /** The result's PDFs are being changed to [mark]: they can't be opened, shared or saved meanwhile. */
    val marking: StateFlow<Boolean> = marker.working
    /** The result's first page, which the result screen shows the mark on while it is changed. */
    val markPreview: StateFlow<MarkPreview?> = marker.preview

    // Cropped pages' files and ID cards are numbered from the same count, so a new card never takes
    // the number of one kept in the saved state, even after the phone's clock was turned back.
    private var nextId = maxOf(
        System.currentTimeMillis(),
        (items.value.maxOfOrNull { maxOf(it.id, it.card, File(it.path).nameWithoutExtension.toLongOrNull() ?: 0L) } ?: 0L) + 1,
    )
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
     * PDFs, Word, PowerPoint, Excel and EPUB files shared to Usugami, copied in and waiting for the tool
     * they open in to take them (see [takeSharedFiles]). Kept in the saved state.
     */
    val sharedFiles: StateFlow<List<SharedFile>> = _sharedFiles.asStateFlow()

    private val _sharing = MutableStateFlow<SharedKind?>(null)
    /** The kind of the shared files being copied in, while they are. */
    val sharing: StateFlow<SharedKind?> = _sharing.asStateFlow()

    /**
     * How many shares are being sorted and copied in, or waiting their turn. Kept in the saved state:
     * when Android closes Usugami meanwhile, their files are gone, and the user is told (see
     * [takeShareInterrupted]).
     */
    private var sharesUnderWay: Int
        get() = handle[KEY_SHARES_UNDER_WAY] ?: 0
        set(value) {
            if (value > 0) handle[KEY_SHARES_UNDER_WAY] = value else handle.remove<Int>(KEY_SHARES_UNDER_WAY)
        }

    /** Android closed Usugami while files shared to it were being copied in. */
    private var shareInterrupted = (handle.remove<Int>(KEY_SHARES_UNDER_WAY) ?: 0) > 0

    /**
     * Where the file being copied in for the reader, or waiting for it, was opened from (a
     * [ReadFrom]'s name); null when there is none. It is then the only file in [sharedFiles].
     * Kept in the saved state.
     */
    val reading: StateFlow<String?> = handle.getStateFlow(KEY_READING, null)

    /** Bumped when a file opened gives way to a newer one, or to files shared. */
    private var openGeneration = 0

    /**
     * Android closed Usugami while a file opened was being copied in (see [open]): the copy cut short
     * left nothing, so nothing waits for the reader. A file that was in and waiting keeps [reading],
     * and still opens in the reader.
     */
    private var openInterrupted = (handle.remove<Boolean>(KEY_OPENING) == true).also { if (it) handle[KEY_READING] = null }

    /**
     * How many images picked or shared are still to be added. Kept in the saved state, as [sharesUnderWay] is:
     * when Android closes Usugami meanwhile, those not added yet are gone, and the user is told.
     */
    private var picksUnderWay: Int
        get() = handle[KEY_PICKS_UNDER_WAY] ?: 0
        set(value) {
            if (value > 0) handle[KEY_PICKS_UNDER_WAY] = value else handle.remove<Int>(KEY_PICKS_UNDER_WAY)
        }

    init {
        if ((handle.remove<Int>(KEY_PICKS_UNDER_WAY) ?: 0) > 0) message(R.string.msg_picks_interrupted)
        // Android closed Usugami while it made a PDF, which is gone with it (see convert).
        if (handle.remove<Boolean>(KEY_MAKING) == true) message(R.string.msg_making_interrupted)
        handle.setSavedStateProvider(KEY_ITEMS) {
            packItems(_items.value).also {
                photosInSavedState.clear()
                pendingPhotos.mapTo(photosInSavedState) { it.path }
                // This state names the pages as they are now: those they replaced can go.
                if (superseded.isNotEmpty()) {
                    val gone = superseded.toList()
                    superseded.clear()
                    viewModelScope.launch(fileOps) { gone.forEach(File::delete) }
                }
            }
        }
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
                // A file opened that the system cleared: nothing waits for the reader.
                if (_sharedFiles.value.isEmpty()) handle[KEY_READING] = null
            }
        }
        // The result that was showing when Android stopped Usugami in the background, if any.
        val shown = _result.value
        if (shown != null) restoreCopies(shown)
        handle.setSavedStateProvider(KeptCopies.KEY) { packCopies() }
        // Read here: photoTaken() forgets it once the camera app is back, which may be before the
        // cleanup below runs, and the photo would be deleted.
        val keepCapture = capture
        // Photos taken that weren't added yet when Android closed Usugami are added below.
        val photos = pendingPhotos
        // Pages Android stopped Usugami in the middle of cropping are cropped again (see below).
        val resumed = pendingCrops
        val croppedAgain = resumed.mapTo(HashSet()) { it.itemId }
        // Those the cleanup put back whole on the image they are cropped from, once it is done.
        val putBack = CompletableDeferred<Set<Long>>()
        viewModelScope.launch {
            val (found, resultGone, wholeAgain) = withContext(fileOps) {
                // Leftovers from an earlier session (or half-copied files) are removed.
                store.deleteAllExcept(keep)
                store.clearCaptures(keep = listOfNotNull(keepCapture) + photos.map { it.path })
                val stillThere = shown?.takeIf { files -> files.all { it.file.isFile } }
                exporter.clearOutput(keep = stillThere?.map { it.file }.orEmpty())
                saver.clearLeftovers()
                // The copies are in the cache, which the system may have cleared while Usugami was away.
                // A page being cropped again is made from the image it is cropped from, which is all it
                // needs once its own copy has gone.
                val wholeAgain = HashSet<Long>()
                val found = keep.map { item ->
                    val whole = if (item.id in croppedAgain) store.putBackWhole(item) else null
                    if (whole != null) wholeAgain += item.id
                    whole ?: store.stillThere(item)
                }
                Triple(found, stillThere == null, wholeAgain)
            }
            takeIn(keep, found)
            if (shown != null && resultGone && _result.value === shown) resultCleared(shown.size)
            putBack.complete(wholeAgain)
        }
        // Pages Android stopped Usugami in the middle of cropping are cropped again, once the cleanup
        // has left only the files in use.
        pendingCrops = emptyList()
        for (pending in resumed) {
            val item = items.value.firstOrNull { it.id == pending.itemId } ?: continue
            crop(item, item.source, pending.quad, after = putBack)
        }
        // After the cleanup, which keeps their files, and before images added from now on.
        photos.forEach(::addPhoto)
        viewModelScope.launch { _result.collect(keptResult::keep) }
        viewModelScope.launch { _saved.collect { handle[KEY_SAVED] = it } }
        viewModelScope.launch { _crop.collect { handle[KEY_CROP] = it } }
        viewModelScope.launch { _card.collect { handle[KEY_CARD] = it } }
        // As it came back (see setScan).
        handle[KEY_SCAN] = _scan.value
        // A scan Android closed Usugami in the middle of cropping goes on, once the cleanup is done (see openCrop).
        advanceScan()
    }

    /**
     * The scan Android closed Usugami in the middle of, if it is still to go on (see [scan]): its
     * images that came back, and none of those that were still being added.
     */
    private fun restoredScan(): ScanQueue? {
        val saved = handle.get<ScanQueue>(KEY_SCAN) ?: return null
        val ids = _items.value.mapTo(HashSet()) { it.id }
        val scan = saved.copy(
            pages = saved.pages.filter { it in ids },
            waiting = saved.waiting.filter { it in ids },
            whole = saved.whole.filter { it in ids },
            coming = 0,
        )
        // Its first photo is still in the camera app, or waits to be added (see addPhoto).
        val photoComing = scan.stage == ScanStage.PHOTOS &&
            (handle.get<String>(KEY_CAPTURE) != null || handle.get<ArrayList<PendingPhoto>>(KEY_PENDING_PHOTOS).orEmpty().any { it.card == 0L })
        return scan.takeIf { it.pages.isNotEmpty() || photoComing }
    }

    // ---- Picking images ----

    /** [refused] counts shared files that were turned away before import; they're reported as unreadable. */
    fun addImages(uris: List<Uri>, refused: Int = 0) {
        importImages(uris, refused)
    }

    /**
     * Adds the images picked with Home's Upload images as a scan, which takes each through the crop
     * screen in turn, the first as soon as it is added (see [ScanQueue]). Over a scan or an ID card
     * already going on, they are added as images picked in the editor are.
     */
    fun scanImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (_scan.value != null || _card.value != null) return addImages(uris)
        val scan = ++scans
        setScan(ScanQueue(stage = ScanStage.CROP, coming = uris.size))
        importImages(uris, refused = 0) { added ->
            val queue = _scan.value
            if (scan != scans || queue == null) return@importImages
            val left = queue.copy(coming = queue.coming - 1)
            when {
                added == null -> setScan(left)
                // Stopped cropping while it was being added: it is one of the pages given their looks.
                left.stage == ScanStage.LOOKS -> {
                    replaceItem(added.withScanLook(left))
                    setScan(left.copy(pages = left.pages + added.id))
                }
                else -> setScan(left.copy(pages = left.pages + added.id, waiting = left.waiting + added.id))
            }
            advanceScan()
            endScanIfEmpty()
        }
    }

    /**
     * Adds the images at [uris] after any still being added, and returns that work (null when there
     * is none). Picked or shared, each is counted in [picksUnderWay] until it is added: when Android
     * closes Usugami meanwhile, the user is told to add the missing ones again (sharing them all
     * again would add twice those already in). [each] is told of each image in turn: the page it
     * was added as, or null when it couldn't be. An ID card's side is added as a side of [card]
     * (after its other side, if it has one), named [name].
     */
    private fun importImages(
        uris: List<Uri>,
        refused: Int,
        card: Long = 0,
        name: String? = null,
        each: (ImageItem?) -> Unit = {},
    ): Job? {
        if (uris.isEmpty()) {
            if (refused > 0) pluralMessage(R.plurals.msg_failed_import, refused)
            return null
        }
        val batch = ++importBatches
        putAwayResult()
        // Shared images open the editor: a ZIP file to share the PDFs that showed is no longer wanted.
        // One being saved where the user chose goes on, as a save of the PDFs does.
        zipper.stopSharing()
        _pendingImports.update { it + uris.size }
        picksUnderWay += uris.size
        val generation = importGeneration
        return viewModelScope.launch {
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
                        val r = withContext(fileOps) { store.import(uri, id, name) }
                        if (generation != importGeneration) {
                            // Discarded while it was being copied.
                            if (r is ImageStore.ImportResult.Added) viewModelScope.launch(fileOps) { store.delete(r.item) }
                            return@launch
                        }
                        var added: ImageItem? = null
                        when (r) {
                            is ImageStore.ImportResult.Added -> {
                                added = withAllPagesChoice(r.item, batch).copy(card = card)
                                setItems(PageGroups.withAdded(items.value, added) { it.card })
                            }
                            ImageStore.ImportResult.Unsupported -> unsupported++
                            ImageStore.ImportResult.Failed -> failed++
                            ImageStore.ImportResult.NoSpace -> noSpace++
                        }
                        _pendingImports.update { it - 1 }
                        picksUnderWay--
                        each(added)
                    }
                }
            } finally {
                // Nothing to report about images the user has discarded.
                if (generation == importGeneration) {
                    if (unsupported > 0) pluralMessage(unsupportedMessage, unsupported)
                    if (failed > 0) pluralMessage(R.plurals.msg_failed_import, failed)
                    if (noSpace > 0) pluralMessage(R.plurals.msg_import_no_space, noSpace)
                }
            }
        }
    }

    /** The message for images skipped as of a type Usugami can't read, naming those this Android version reads. */
    private val unsupportedMessage: Int
        get() = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> R.plurals.msg_skipped_unsupported
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> R.plurals.msg_skipped_unsupported_no_avif
            else -> R.plurals.msg_skipped_unsupported_no_heic
        }

    /**
     * [item], just added from batch [batch], with the look and adjustments Apply to all pages gave
     * every page while the batch was being added, if it did (see [allPagesChoice]).
     */
    private fun withAllPagesChoice(item: ImageItem, batch: Int): ImageItem {
        val choice = allPagesChoice?.takeIf { batch <= it.through } ?: return item
        return item.copy(look = choice.look, adjustments = choice.adjustments)
    }

    // ---- Files shared from other apps ----

    /**
     * Takes in the files at [uris], shared to Usugami from another app as [sharedAs] (the share's
     * type), all of one kind (see [SharedFiles.chosen]): images go to the editor ([addImages]), PDFs,
     * Word, PowerPoint, Excel and EPUB files are copied in at once (the share lets Usugami read them for a
     * while only) and wait in [sharedFiles] for the tool they open in. The files of other kinds, and
     * those Usugami can't use, are left out, naming them. [refused] counts files turned away before.
     */
    fun share(uris: List<Uri>, sharedAs: String?, refused: Int = 0) {
        if (uris.isEmpty()) {
            // Images are told about as before, other files as shared files.
            if (SharedFiles.kindOf(sharedAs, null) == SharedKind.IMAGE) return addImages(emptyList(), refused)
            if (refused > 0) pluralMessage(R.plurals.msg_share_unreadable, refused)
            return
        }
        // A file opened that still waits gives way to what is shared now.
        if (reading.value != null) dropShares()
        // Until the share's last step: counted down on the main thread once its files are copied in, or
        // its images are handed on (from then on they count in picksUnderWay until each is added).
        sharesUnderWay++
        viewModelScope.launch {
            // One share at a time, in the order they came: the files of one shared while another is
            // being copied in go after them.
            shareLock.withLock {
                val described = withContext(shareOps) { uris.map(inbox::describe) }
                // The share's own type tells what a file is only when its type and name don't.
                val kinds = described.map { (type, name) -> SharedFiles.kindOf(type, name, sharedAs) }
                val kind = SharedFiles.chosen(kinds)
                val names = uris.indices.map { i -> described[i].second ?: inbox.fallbackName(uris[i], kinds[i]) }
                leftOut(R.plurals.share_unsupported, names.filterIndexed { i, _ -> kinds[i] == SharedKind.OTHER })
                leftOut(R.plurals.share_other_kind, names.filterIndexed { i, _ -> kinds[i] != SharedKind.OTHER && kinds[i] != kind })
                val taken = uris.indices.filter { kinds[it] == kind }
                when (kind) {
                    null -> if (refused > 0) pluralMessage(R.plurals.msg_share_unreadable, refused)
                    SharedKind.IMAGE -> importImages(taken.map { uris[it] }, refused)
                    else -> {
                        if (refused > 0) pluralMessage(R.plurals.msg_share_unreadable, refused)
                        // A file opened since this share came is the one wanted: the share gives way,
                        // rather than landing beside that file or being taken by the reader in its place.
                        if (reading.value == null) copyShared(kind, taken.map { uris[it] to names[it] })
                    }
                }
            }
        }.invokeOnCompletion { sharesUnderWay-- }
    }

    /**
     * Copies [files] (each one's address and name), shared files of [kind], into [sharedFiles]. A file
     * [opened] (see [open]) that can't be read is told about in words of its own.
     */
    private suspend fun copyShared(kind: SharedKind, files: List<Pair<Uri, String>>, opened: Boolean = false) {
        // Read as the files start to show as being copied in: [dropShares] stops the files the user
        // sees coming, not those of a share still waiting its turn behind them.
        val generation = shareGeneration
        fun dropped() = generation != shareGeneration
        _sharing.value = kind
        val copies = ArrayList<SharedFile>()
        var failed = 0
        // The files there wasn't enough free storage to copy in, by name.
        val noSpace = ArrayList<String>()
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
                    if (e.isOutOfSpace()) noSpace += name else failed++
                }
            }
        } finally {
            if (dropped()) {
                withContext(NonCancellable + shareOps) { copies.forEach(inbox::delete) }
            } else {
                // Listed before the copying is said to be over, so they are never taken for gone.
                setShared(_sharedFiles.value + copies)
                if (failed > 0) {
                    if (opened) message(R.string.reader_unreadable) else pluralMessage(R.plurals.msg_share_unreadable, failed)
                }
                // One file shared is told about as before; of several, those left out are named.
                when {
                    noSpace.isEmpty() -> Unit
                    files.size == 1 -> message(R.string.open_no_space)
                    else -> message(
                        R.string.file_problem,
                        strings.listOfNames(noSpace),
                        strings.getString(if (noSpace.size == 1) R.string.open_no_space else R.string.open_no_space_many),
                    )
                }
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
        // The reader has taken the file opened, if that is what waited.
        handle[KEY_READING] = null
        if (others.isNotEmpty()) {
            leftOut(R.plurals.share_other_kind, others.map { it.name })
            viewModelScope.launch(shareOps) { others.forEach(inbox::delete) }
        }
        return taken
    }

    /** Forgets the shared files waiting or being copied in: the user left the screen they were opening in. */
    fun dropShares() {
        shareGeneration++
        // A file opened, being copied in or waiting, goes too.
        openGeneration++
        handle[KEY_READING] = null
        handle.remove<Boolean>(KEY_OPENING)
        val dropped = _sharedFiles.value
        setShared(emptyList())
        _sharing.value = null
        if (dropped.isNotEmpty()) viewModelScope.launch(shareOps) { dropped.forEach(inbox::delete) }
    }

    /**
     * Whether Android closed Usugami while files shared to it were being copied in, so that they are
     * gone: true once, for the user to be told.
     */
    fun takeShareInterrupted(): Boolean = shareInterrupted.also { shareInterrupted = false }

    /**
     * Opens the file at [uri] in the reader: looks at what it is (its content, else its type as
     * the app that has it or [type] gives it, and its name), then copies it in at once, as shared
     * files are, to wait in [sharedFiles] for the reader ([reading] says it is for the reader).
     * A null [uri] is an address Usugami can't read. The file opened last is the one wanted: one
     * still coming in or waiting, and files shared before, give way to it.
     */
    fun open(uri: Uri?, type: String?, from: ReadFrom) {
        if (uri == null) return message(R.string.reader_unreadable)
        dropShares()
        val mine = ++openGeneration
        handle[KEY_READING] = from.name
        // Until it is in: when Android closes Usugami meanwhile, the user is told (takeOpenInterrupted).
        handle[KEY_OPENING] = true
        viewModelScope.launch {
            shareLock.withLock {
                fun current() = mine == openGeneration
                try {
                    if (!current()) return@withLock
                    val (given, name) = withContext(shareOps) { inbox.describe(uri) }
                    // An app that gives no name (it didn't let Usugami ask) still has the address's
                    // ending, which "Open with" matched: a PDF there that can't be read is said to be so.
                    val declared = SharedFiles.kindOf(given, name ?: uri.lastPathSegment, type)
                    // An image is an image: the others are worth a look inside.
                    val probed = if (declared == SharedKind.IMAGE) null else withContext(shareOps) { probeKind(app, uri) }
                    val kind = OpenedFiles.kind(declared, probed)
                    val shown = name ?: inbox.fallbackName(uri, kind ?: declared)
                    if (!current()) return@withLock
                    if (kind == null) message(R.string.reader_cant_open, shown) else copyShared(kind, listOf(uri to shown), opened = true)
                } finally {
                    if (current()) {
                        handle.remove<Boolean>(KEY_OPENING)
                        // Nothing came in (it couldn't be opened, or read): nothing waits for the reader.
                        if (_sharedFiles.value.isEmpty()) handle[KEY_READING] = null
                    }
                }
            }
        }
    }

    /** Whether Android closed Usugami while a file opened was being copied in: true once, for the user to be told. */
    fun takeOpenInterrupted(): Boolean = openInterrupted.also { openInterrupted = false }

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
        if (names.isNotEmpty()) _messages.trySend(strings.getQuantityString(res, names.size, names.size, strings.listOfNames(names)))
    }

    /** Sorts the files picked with Home's Documents to PDF by kind. */
    private val sorter = DocumentSorter(app)

    private val _pickedDocuments = MutableStateFlow<PickedDocuments?>(null)

    /**
     * The files picked with Home's Documents to PDF, sorted, until the converter they open in takes
     * them (see [takePickedDocuments]). Not kept in the saved state: a pick lost with the process
     * leaves the user on Home with nothing changed.
     */
    val pickedDocuments: StateFlow<PickedDocuments?> = _pickedDocuments.asStateFlow()

    /** Set while the files picked with Documents to PDF are being sorted. */
    private var sortingPick = false

    /**
     * Home's Documents to PDF: sorts the files picked at [uris] by kind (see [DocumentSorter]), says
     * which were left out, as for files shared, and offers the rest in [pickedDocuments]. The files
     * aren't copied: the converter takes them as files picked there.
     */
    fun pickDocuments(uris: List<Uri>) {
        if (uris.isEmpty() || sortingPick) return
        sortingPick = true
        viewModelScope.launch {
            try {
                val sorted = withContext(Dispatchers.IO) { sorter.sort(uris) }
                // In the order a share says them.
                leftOut(R.plurals.share_unsupported, sorted.unsupported)
                leftOut(R.plurals.share_other_kind, sorted.otherKind)
                _pickedDocuments.value = sorted.picked
            } finally {
                sortingPick = false
            }
        }
    }

    /** The files picked with Documents to PDF and sorted, for the converter they open in; null when there are none. */
    fun takePickedDocuments(): PickedDocuments? = _pickedDocuments.value.also { _pickedDocuments.value = null }

    /** For tests: opens the [kind] converter empty, as after picking files of that kind that were all refused. */
    @VisibleForTesting
    internal fun openConverter(kind: SharedKind) {
        _pickedDocuments.value = PickedDocuments(kind, emptyList())
    }

    // ---- Editing ----

    fun rotate(id: Long, degrees: Int) = setItems(items.value.map { if (it.id == id) it.rotatedBy(degrees) else it })

    fun remove(id: Long) {
        val item = items.value.firstOrNull { it.id == id } ?: return
        setItems(items.value - item)
        thumbnails.remove(item)
        viewModelScope.launch(fileOps) { store.delete(item) }
    }

    /** Removes the page that holds the image [id]: that image, or both sides of an ID card. */
    fun removePage(id: Long) {
        val page = pages().firstOrNull { page -> page.any { it.id == id } } ?: return
        val ids = page.mapTo(HashSet()) { it.id }
        setItems(items.value.filterNot { it.id in ids })
        page.forEach(thumbnails::remove)
        viewModelScope.launch(fileOps) { page.forEach(store::delete) }
    }

    /** The pages the images make: one image each, but for an ID card's sides, which share one. */
    private fun pages(list: List<ImageItem> = items.value): List<List<ImageItem>> = PageGroups.pages(list) { it.card }

    /** Removes every image, including those still being imported. */
    fun clearAll() {
        importGeneration++
        _pendingImports.value = 0
        picksUnderWay = 0
        // Photos waiting their turn are discarded too: a state saved now mustn't bring them back.
        pendingPhotos = emptyList()
        setScan(null)
        _card.value = null
        val all = items.value
        val replaced = superseded.toList()
        superseded.clear()
        setItems(emptyList())
        all.forEach(thumbnails::remove)
        viewModelScope.launch(fileOps) {
            all.forEach(store::delete)
            replaced.forEach(File::delete)
        }
    }

    /** Moves the page that holds the image [fromId] to where the page holding [toId] is (drag and drop). */
    fun move(fromId: Long, toId: Long) {
        val list = pages().toMutableList()
        val from = list.indexOfFirst { page -> page.any { it.id == fromId } }
        val to = list.indexOfFirst { page -> page.any { it.id == toId } }
        if (from < 0 || to < 0 || from == to) return
        list.add(to, list.removeAt(from))
        setItems(list.flatten())
    }

    /** Moves the page that holds the image [id] one place earlier (-1) or later (+1). */
    fun moveBy(id: Long, offset: Int) {
        val list = pages().toMutableList()
        val from = list.indexOfFirst { page -> page.any { it.id == id } }
        val to = from + offset
        if (from < 0 || to !in list.indices) return
        list.add(to, list.removeAt(from))
        setItems(list.flatten())
    }

    /** Sorts the pages, each by its first image's name; an ID card's front stays above its back. */
    fun sort(mode: SortMode) {
        val list = pages()
        setItems(
            when (mode) {
                SortMode.NAME_ASC -> list.sortedWith(compareBy(NaturalOrder) { it[0].name })
                SortMode.NAME_DESC -> list.sortedWith(compareBy(NaturalOrder) { it[0].name }).reversed()
                SortMode.REVERSE -> list.reversed()
            }.flatten(),
        )
    }

    fun setLayout(layout: LayoutSettings) {
        _layout.value = layout
        settingsStore.layout = layout
    }

    private fun setItems(list: List<ImageItem>) {
        _items.value = list
        if (list.isEmpty()) lastChoices = null
        // The ID card being scanned wants its back once it has a side, its front again once that has
        // gone (discarded, or its copy cleared). Before the crop screen of an image that has gone is
        // closed below, so that the prompt then showing is the right side's.
        _card.update { scan -> scan?.copy(side = if (list.any { it.card == scan.card }) CardSide.BACK else CardSide.FRONT) }
        // A scan's images that have gone are left out of it at once, so that it counts its images
        // and finishes on its last as it is now. A scan left with none is over.
        _scan.value?.let { scan ->
            val ids = list.mapTo(HashSet()) { it.id }
            if (!ids.containsAll(scan.pages)) {
                setScan(
                    scan.copy(
                        pages = scan.pages.filter { it in ids },
                        waiting = scan.waiting.filter { it in ids },
                        whole = scan.whole.filter { it in ids },
                        selected = scan.selected.takeIf { it in ids } ?: 0,
                    ),
                )
                endScanIfEmpty()
            }
        }
        // The crop screen's image has gone (removed, or its copy cleared): there is nothing to crop. A
        // scan goes on with its next image.
        _crop.value?.let { session ->
            if (list.none { it.id == session.itemId }) {
                _crop.value = null
                if (session.staged) advanceScan()
            }
        }
    }

    /** Puts [item] in place of the image with its id. */
    private fun replaceItem(item: ImageItem) = setItems(items.value.map { if (it.id == item.id) item else it })

    // ---- Taking photos ----

    /**
     * A new file for the camera app to save a photo in, as the URI to give it: Usugami's file
     * provider lets only the app it is given to write it. See [photoTaken].
     */
    fun newCapture(): Uri {
        capture?.let { File(it).delete() }
        val file = store.newCapture(nextId++)
        capture = file.path
        return FileProvider.getUriForFile(app, "${app.packageName}.files", file)
    }

    /**
     * Starts a scan with its camera (see [ScanQueue]), unless one is going on already, as when Take
     * another opens the camera for the next photo. In the saved state before the camera opens: the
     * photo may come back after Android has started Usugami again. Returns whether the scan takes
     * photos now: not while another scan is past its photos, a page's crop screen shows or opens, the
     * ID card flow goes on or a PDF is being made.
     */
    fun startScan(): Boolean {
        _scan.value?.let { return it.stage == ScanStage.PHOTOS && it.camera }
        if (_card.value != null || _crop.value != null || _openingCrop.value || _progress.value != null) return false
        scans++
        setScan(ScanQueue(stage = ScanStage.PHOTOS, camera = true))
        return true
    }

    /**
     * The camera app has closed, having saved a photo ([taken]) or not. The photo is added after any
     * images still being added: to the scan taking photos (see [startScan]), or during the ID card
     * flow as the side of the card it asked for, whose crop screen then opens. A photo not taken
     * leaves nothing, and a scan that has no photo is then over.
     */
    fun photoTaken(taken: Boolean) {
        val path = capture ?: return
        capture = null
        val file = File(path)
        if (!taken) {
            viewModelScope.launch(fileOps) { file.delete() }
            endScanIfEmpty()
            return
        }
        val scan = _card.value
        val photo = if (scan != null) PendingPhoto(path, cardSideName(), scan.card) else PendingPhoto(path, "Scan_${timestamp()}.jpg")
        // In the saved state from now until it is added: Android may close Usugami while it waits.
        pendingPhotos = pendingPhotos + photo
        addPhoto(photo)
    }

    /** "20261112_101500" for now: the time in the names Usugami gives the photos it takes. */
    private fun timestamp(): String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT))

    /** The name an ID card's side gets, taken or chosen: a PDF of the card alone is suggested under it. */
    private fun cardSideName(): String = "ID_card_${timestamp()}"

    /**
     * Adds [photo] after any images still being added: as the scan's next photo, or as an ID card's
     * side (after the card's other side), whose crop screen opens. A side of a card whose flow has
     * ended meanwhile (Cancel, No back side) is not added, nor is a photo of a scan discarded
     * meanwhile. With no scan taking photos, it is a page as it is.
     */
    private fun addPhoto(photo: PendingPhoto) {
        _pendingImports.update { it + 1 }
        val generation = importGeneration
        val file = File(photo.path)
        viewModelScope.launch {
            importLock.withLock {
                val id = nextId++
                // Copied, and moved in only when there is no room for a copy: Android may save a state
                // while it is being added, which names it as waiting, and Usugami may start again
                // from that state, which adds it from its file.
                val r = withContext(fileOps) {
                    // Some camera apps return without saving the photo.
                    if (generation != importGeneration || file.length() == 0L) null else store.addPhoto(file, id, photo.name, keepFile = true)
                }
                // Its scan was discarded while it was being added (see discardScan).
                val dropped = photo !in pendingPhotos
                pendingPhotos = pendingPhotos - photo
                // A photo added that the state saved last names as waiting is added from its file
                // again if Usugami starts again from that state: the file goes with the next one.
                if (r is ImageStore.ImportResult.Added && generation == importGeneration && photo.path in photosInSavedState) {
                    superseded += file
                } else {
                    viewModelScope.launch(fileOps) { file.delete() }
                }
                if (generation != importGeneration) {
                    // Discarded while it was being added.
                    if (r is ImageStore.ImportResult.Added) viewModelScope.launch(fileOps) { store.delete(r.item) }
                    return@launch
                }
                _pendingImports.update { it - 1 }
                when {
                    r is ImageStore.ImportResult.Added && (dropped || photo.card != 0L && _card.value?.card != photo.card) ->
                        viewModelScope.launch(fileOps) { store.delete(r.item) }
                    r is ImageStore.ImportResult.Added -> {
                        // Taken after going back to the photos of a scan whose pages have had their first
                        // look: it starts as they did (see toLooks), which is given only once.
                        val styled = _scan.value?.takeIf { photo.card == 0L && it.stage == ScanStage.PHOTOS && it.camera && it.styled }
                        val item = r.item.copy(card = photo.card).let { if (styled != null) it.withScanLook(styled) else it }
                        setItems(PageGroups.withAdded(items.value, item) { it.card })
                        if (photo.card != 0L) {
                            openCrop(item, fromCamera = true)
                        } else {
                            // In the same step as it joins the list: the scan is never seen without it.
                            _scan.value?.takeIf { it.stage == ScanStage.PHOTOS && it.camera }?.let { setScan(it.copy(pages = it.pages + item.id)) }
                        }
                    }
                    dropped -> Unit
                    r == ImageStore.ImportResult.NoSpace -> pluralMessage(R.plurals.msg_import_no_space, 1)
                    else -> pluralMessage(R.plurals.msg_failed_import, 1)
                }
                // A scan whose only photo couldn't be added is over.
                endScanIfEmpty()
            }
        }
    }

    // ---- ID card ----

    /**
     * Starts Home's ID card flow for a new card, asking for its front (see [card]). Not while a PDF
     * is being made, a page's crop screen shows or opens, a scan goes on (at any stage) or another
     * card's flow does.
     */
    fun startCard() {
        if (cardBlocked()) return
        _card.value = CardScan(nextId++)
    }

    /**
     * Asks for the back of ID card [card], which has its front alone (a one-sided card's Add back
     * side), starting with the front's look. As [startCard], not while something else is under way.
     */
    fun addCardBack(card: Long) {
        if (card == 0L || cardBlocked()) return
        val front = items.value.filter { it.card == card }.singleOrNull() ?: return
        _card.value = CardScan(card, CardSide.BACK, look = front.look)
    }

    /**
     * Ends the ID card flow: Cancel on the front's prompt (nothing was added), No back side or Back on
     * the back's (the card keeps its front alone). A side still on its way is then not added.
     */
    fun endCard() {
        _card.value = null
    }

    /** Adds the image at [uri], chosen on the ID card flow's prompt, as the side it asks for, and opens its crop screen. */
    fun cardImage(uri: Uri) {
        val scan = _card.value ?: return
        importImages(listOf(uri), refused = 0, card = scan.card, name = cardSideName()) { added ->
            if (added == null) return@importImages
            // The flow was ended while it was being added.
            if (_card.value?.card == scan.card) openCrop(added, fromCamera = false) else remove(added.id)
        }
    }

    private fun cardBlocked(): Boolean =
        _progress.value != null || _crop.value != null || _openingCrop.value || _scan.value != null || _card.value != null

    // ---- Cropping ----

    /**
     * Opens the crop screen for image [id], the editor's page, with its corners on the page if one is
     * found. Not while a scan goes on, whose pages the editor doesn't show.
     */
    fun openCrop(id: Long) {
        val item = items.value.firstOrNull { it.id == id } ?: return
        // Not while its last crop is being made: it would start from the page before.
        if (id in _cropping.value || _scan.value != null) return
        openCrop(item, fromCamera = false)
    }

    /**
     * Opens the crop screen for [item], once its page has been looked for: a side the ID card flow is
     * adding starts with the flow's look, any other page with its own. An image of a scan ([staged],
     * the first it waits for) whose page is still being made is opened once it is, with the corners
     * it was made with; one that can't be opened is left out of the scan's cropping, and a side whose
     * flow has ended meanwhile is removed.
     */
    private fun openCrop(item: ImageItem, fromCamera: Boolean, staged: Boolean = false) {
        // A second tap while the page is looked for, or with the screen open, does nothing.
        if (_openingCrop.value || _crop.value != null || _progress.value != null) return
        // Asked now, not once the page has been looked for: Cancel and No back side can be pressed meanwhile.
        val cardScan = item.card != 0L && _card.value?.card == item.card
        // The scan's next image, as long as it still is: Back may go to the image before meanwhile.
        fun next() = !staged || _scan.value?.waiting?.firstOrNull() == item.id
        _openingCrop.value = true
        viewModelScope.launch {
            try {
                // Gone back to, as its page is made: opened now, it would be cropped again from the
                // photo, and the page being made would then take the place of the new one.
                var opening = item
                if (staged && item.id in _cropping.value) {
                    _cropping.first { item.id !in it }
                    opening = items.value.firstOrNull { it.id == item.id }?.takeIf { next() } ?: return@launch
                }
                // The image a page was cropped from may have been cleared from the cache: the page is
                // then cropped as it is.
                val checked = withContext(fileOps) { store.stillThere(opening) }
                val source = takeIn(listOf(opening), listOf(checked)).singleOrNull()?.source ?: return@launch
                val found = withContext(Dispatchers.IO) {
                    try {
                        PageCropper.findPage(source)?.takeIf { it.confident }?.quad
                    } catch (e: Exception) {
                        Log.w("Usugami", "Looking for the page failed", e)
                        null
                    } catch (e: OutOfMemoryError) {
                        null
                    }
                }
                // Removed or cropped meanwhile, or being made into a PDF; or the scan it is next in went
                // back, or on, while its page was looked for.
                val current = items.value.firstOrNull { it.id == item.id }
                if (current == null || current.source != source || _progress.value != null || !next()) return@launch
                // A side of the ID card flow, which was ended while its page was looked for: nothing was added.
                val flow = _card.value
                if (cardScan && flow?.card != item.card) return@launch remove(item.id)
                // Which side of its card the image is, by its place on the card's page.
                val side = if (current.card == 0L) null else {
                    val page = pages().firstOrNull { page -> page.any { it.id == item.id } }
                    if (page?.first()?.id == item.id) CardSide.FRONT else CardSide.BACK
                }
                val reset = found ?: CropQuad.inset(PageCrop.INSET)
                val scan = _scan.value?.takeIf { staged }
                _crop.value = CropSession(
                    itemId = item.id,
                    fromCamera = fromCamera || scan?.camera == true,
                    rotation = current.rotation,
                    width = source.uprightWidth,
                    height = source.uprightHeight,
                    // A scan's image left whole comes back whole, as a page cropped comes back on its corners.
                    start = if (scan != null && item.id in scan.whole) CropQuad.WHOLE else current.original?.corners ?: reset,
                    found = reset,
                    foundPage = found != null,
                    opened = SystemClock.uptimeMillis(),
                    look = if (cardScan && flow != null) flow.look else current.look,
                    adjustments = current.adjustments,
                    staged = staged,
                    side = side,
                    cardScan = cardScan,
                )
            } finally {
                _openingCrop.value = false
                if (staged && _crop.value == null) {
                    // It couldn't be opened, and is left as it is. Once the scan has gone back to the image
                    // before (or on), that one opens instead.
                    _scan.value?.takeIf { it.waiting.firstOrNull() == item.id }?.let { setScan(it.copy(waiting = it.waiting.drop(1))) }
                    advanceScan()
                }
            }
        }
    }

    /**
     * While a scan's images are cropped, opens the crop screen for the next, unless it shows (or opens)
     * already, and goes on to the looks once each image has been through it; a scan left with none
     * is over. Images removed meanwhile are left out; one still being added is waited for.
     */
    private fun advanceScan() {
        val scan = _scan.value?.takeIf { it.stage == ScanStage.CROP } ?: return
        if (scan.waiting.isEmpty() && scan.coming == 0) {
            if (scan.pages.isEmpty()) setScan(null) else toLooks(scan)
            return
        }
        // Not over a PDF being made, which has the pages as they are (Make PDF waits for the scan).
        if (_crop.value != null || _openingCrop.value || _progress.value != null) return
        val next = scan.waiting.firstOrNull() ?: return
        openCrop(items.value.first { it.id == next }, fromCamera = false, staged = true)
    }

    /**
     * The scan goes on to its pages' looks, the first page shown large: the first time, each page
     * starts on Enhanced, as a scanner's pages are. Only the scan's pages: those in the editor before
     * keep theirs.
     */
    private fun toLooks(scan: ScanQueue) {
        if (!scan.styled) {
            val ids = scan.pages.toHashSet()
            setItems(items.value.map { if (it.id in ids) it.copy(look = PageLook.ENHANCED, adjustments = LookAdjustments.NONE) else it })
        }
        setScan(scan.copy(stage = ScanStage.LOOKS, waiting = emptyList(), styled = true))
    }

    /** Ends the scan once it has nothing left: no image, none still being added, and no photo on its way. */
    private fun endScanIfEmpty() {
        val scan = _scan.value ?: return
        val photoComing = scan.stage == ScanStage.PHOTOS && (capture != null || pendingPhotos.any { it.card == 0L })
        if (scan.pages.isEmpty() && scan.coming == 0 && !photoComing) setScan(null)
    }

    /**
     * Done on a scan's photos: each is cropped in turn, in the order taken (see [advanceScan]). Not
     * while a photo is still on its way, which would miss its turn.
     */
    fun cropScan() {
        val scan = _scan.value?.takeIf { it.stage == ScanStage.PHOTOS } ?: return
        if (scan.pages.isEmpty() || _cameraOpen.value || pendingPhotos.any { it.card == 0L }) return
        setScan(scan.copy(stage = ScanStage.CROP, waiting = scan.pages))
        advanceScan()
    }

    /**
     * Back while a scan's images are cropped: the image before, with the corners it was given (once
     * its page is made, if it still is being made). On the first image, a scan's photos show again,
     * nothing lost; images picked stop being cropped (see [stopScanCropping]). The corners moved on
     * the image left, and not given to it with Next, are forgotten: it starts again as it was.
     */
    fun scanBack() {
        val scan = _scan.value?.takeIf { it.stage == ScanStage.CROP } ?: return
        val done = scan.pages.size - scan.waiting.size
        if (done == 0) {
            if (!scan.camera) return stopScanCropping()
            _crop.value = null
            setScan(scan.copy(stage = ScanStage.PHOTOS, waiting = emptyList()))
            return
        }
        _crop.value = null
        setScan(scan.copy(waiting = listOf(scan.pages[done - 1]) + scan.waiting))
        advanceScan()
    }

    /**
     * A scan's photo on the crop screen discarded: it goes, and the scan goes on with the next (or to
     * the looks, after its last; the scan is over with none left).
     */
    fun discardScanPhoto() {
        val session = _crop.value?.takeIf { it.staged } ?: return
        if (_scan.value?.camera != true) return
        // Which closes the screen and goes on (see setItems).
        remove(session.itemId)
    }

    /**
     * Stop cropping, on a scan of images picked: those not cropped yet stay whole, also those still
     * being added, and the scan goes on to its pages' looks. With none added yet, the scan is over,
     * and they are added as they come, as images picked in the editor are.
     */
    fun stopScanCropping() {
        val scan = _scan.value?.takeIf { it.stage == ScanStage.CROP && !it.camera } ?: return
        _crop.value = null
        if (scan.pages.isEmpty()) setScan(null) else toLooks(scan)
    }

    /**
     * Leaving a scan's photos for good: they go, also those still being added, and the scan is over.
     */
    fun discardScan() {
        val scan = _scan.value?.takeIf { it.stage == ScanStage.PHOTOS && it.camera } ?: return
        // A photo taken out of the list while it is being added isn't added (see addPhoto).
        pendingPhotos = pendingPhotos.filter { it.card != 0L }
        setScan(null)
        val ids = scan.pages.toHashSet()
        val gone = items.value.filter { it.id in ids }
        setItems(items.value.filterNot { it.id in ids })
        gone.forEach(thumbnails::remove)
        viewModelScope.launch(fileOps) { gone.forEach(store::delete) }
    }

    /** Shows the scan's page [id] large on its looks. */
    fun selectScanPage(id: Long) {
        val scan = _scan.value?.takeIf { it.stage == ScanStage.LOOKS && id in it.pages } ?: return
        setScan(scan.copy(selected = id))
    }

    /** Gives [look] to the page shown large on the scan's looks, or to each of its pages with [ScanQueue.sameLook]. */
    fun setScanLook(look: PageLook) = styleScan { it.copy(look = look) }

    /** As [setScanLook], for the brightness and contrast. */
    fun setScanAdjustments(adjustments: LookAdjustments) = styleScan { it.copy(adjustments = adjustments) }

    /**
     * The same look for every page of the scan, or not: turned on, each takes the look, brightness
     * and contrast of the page shown; turned off, each keeps what it has.
     */
    fun setScanSameLook(on: Boolean) {
        val scan = _scan.value?.takeIf { it.stage == ScanStage.LOOKS } ?: return
        setScan(scan.copy(sameLook = on))
        if (on) {
            val shown = items.value.firstOrNull { it.id == scan.shownPage } ?: return
            styleScan { it.copy(look = shown.look, adjustments = shown.adjustments) }
        }
    }

    /** [style] given to the page shown large on the scan's looks, or to each of its pages with [ScanQueue.sameLook]. */
    private fun styleScan(style: (ImageItem) -> ImageItem) {
        val scan = _scan.value?.takeIf { it.stage == ScanStage.LOOKS } ?: return
        val ids = if (scan.sameLook) scan.pages.toHashSet() else setOfNotNull(scan.shownPage)
        setItems(items.value.map { if (it.id in ids) style(it) else it })
    }

    /** [this], a page coming to the scan's looks late (see [stopScanCropping]), with the look the scan's pages start on. */
    private fun ImageItem.withScanLook(scan: ScanQueue): ImageItem {
        val shown = items.value.firstOrNull { it.id == scan.shownPage }?.takeIf { scan.sameLook }
        return copy(look = shown?.look ?: PageLook.ENHANCED, adjustments = shown?.adjustments ?: LookAdjustments.NONE)
    }

    /** Back on the scan's looks: its last image's crop screen again. */
    fun scanLooksBack() {
        val scan = _scan.value?.takeIf { it.stage == ScanStage.LOOKS } ?: return
        val last = scan.pages.lastOrNull() ?: return
        setScan(scan.copy(stage = ScanStage.CROP, waiting = listOf(last)))
        advanceScan()
    }

    /**
     * Save on the scan's looks: the scan is over, and its pages are in the editor, in its order, after
     * the pages that were there.
     */
    fun saveScan() {
        val scan = _scan.value?.takeIf { it.stage == ScanStage.LOOKS } ?: return
        setScan(null)
        val byId = items.value.associateBy { it.id }
        val ids = scan.pages.toHashSet()
        setItems(items.value.filterNot { it.id in ids } + scan.pages.mapNotNull(byId::get))
    }

    /**
     * Crops the crop screen's page to [shown] (its corners as the screen shows the image, turned
     * [rotation] degrees clockwise, which the page takes), gives it [look] and [adjustments] (and
     * every page, with [allPages]) and closes the screen; the page is made in the background. The
     * whole image puts the page back as it was picked or taken. Returns false, leaving the screen
     * open, when the corners don't make a page. What isn't given stays as the page has it. An image
     * of a scan takes its corners and turn alone, and the scan goes on (see [finishScanImage]).
     */
    fun finishCrop(
        shown: CropQuad,
        look: PageLook? = null,
        allPages: Boolean = false,
        adjustments: LookAdjustments? = null,
        rotation: Int? = null,
    ): Boolean {
        val session = _crop.value ?: return false
        val picked = items.value.firstOrNull { it.id == session.itemId }
        if (picked == null) {
            _crop.value = null
            return false
        }
        if (session.staged) return finishScanImage(session, picked, shown, rotation)
        val source = picked.source
        val turn = Math.floorMod(rotation ?: session.rotation, 360)
        val quad = session.upright(shown, turn)
        val whole = quad.isWhole()
        if (!whole && !quad.isUsable(source.uprightWidth, source.uprightHeight)) return false
        // The look first, so the page cropped in the background keeps it.
        val chosen = look ?: picked.look
        val adjusted = adjustments ?: picked.adjustments
        // A side the ID card flow added: the card's back starts with this look, or, once the card has
        // both sides, the flow is over. Before the screen closes, so that the back's prompt never shows
        // for a moment after the back. The back is the card's last side also when its front has gone
        // meanwhile (its copy cleared): the card keeps its back alone, and the flow doesn't ask for
        // a second back.
        if (session.cardScan && _card.value?.card == picked.card) {
            if (session.side == CardSide.BACK || items.value.count { it.card == picked.card } >= 2) _card.value = null
            else _card.update { it?.copy(side = CardSide.BACK, look = chosen) }
        }
        _crop.value = null
        if (allPages) {
            setItems(items.value.map { it.copy(look = chosen, adjustments = adjusted) })
            // And the images still being added, such as those shared meanwhile, as each comes.
            allPagesChoice = AllPagesChoice(chosen, adjusted, through = importBatches)
        }
        val item = picked.copy(look = chosen, adjustments = adjusted, rotation = turn)
        if (item != picked) replaceItem(item)
        cropTo(item, source, quad)
        return true
    }

    /**
     * Next on a scan's image: its page is cropped to [shown] (as the screen shows the image, turned
     * [rotation] degrees clockwise, which the page takes) in the background, and the scan goes on with
     * its next image, or to its pages' looks after the last. As [finishCrop], false keeps the screen.
     */
    private fun finishScanImage(session: CropSession, picked: ImageItem, shown: CropQuad, rotation: Int?): Boolean {
        val source = picked.source
        val turn = Math.floorMod(rotation ?: session.rotation, 360)
        val quad = session.upright(shown, turn)
        val whole = quad.isWhole()
        if (!whole && !quad.isUsable(source.uprightWidth, source.uprightHeight)) return false
        _crop.value = null
        val item = picked.copy(rotation = turn)
        if (item != picked) replaceItem(item)
        cropTo(item, source, quad)
        _scan.value?.let { scan ->
            val others = scan.whole - item.id
            setScan(scan.copy(waiting = scan.waiting - item.id, whole = if (whole) others + item.id else others))
        }
        // In the same step: the next image's opening (or the looks) follows this one at once.
        advanceScan()
        return true
    }

    /** [item]'s page made from [source], cropped inside [quad], in the background; the whole image puts it back as it was. */
    private fun cropTo(item: ImageItem, source: OriginalImage, quad: CropQuad) {
        val whole = quad.isWhole()
        when {
            // Nothing changed.
            if (whole) item.original == null else item.original?.corners?.isCloseTo(quad) == true -> Unit
            whole -> {
                replaceItem(item.copy(path = source.path, format = source.format, width = source.width, height = source.height, exifOrientation = source.exifOrientation, original = null))
                thumbnails.remove(item)
                viewModelScope.launch(fileOps) { File(item.path).delete() }
            }
            else -> crop(item, source, quad)
        }
    }

    /**
     * Leaves the crop screen without cropping: a side the ID card flow added is discarded, and its
     * prompt shows again; a page cropped again stays as it was. A scan's image has ways of its own
     * ([scanBack], [discardScanPhoto], [stopScanCropping]).
     */
    fun closeCrop() {
        val session = _crop.value?.takeIf { !it.staged } ?: return
        if (session.cardScan) {
            // Removed first: that closes the screen (see setItems) with the flow's side already right.
            remove(session.itemId)
            if (_crop.value === session) _crop.value = null
            return
        }
        _crop.value = null
    }

    /**
     * Whether a scan's page is still to reach the editor: the camera app is open, a scan goes on (at
     * any stage), the crop screen shows or is being opened, or the ID card flow goes on. A page
     * cropped again in the background is in the editor already.
     */
    fun scanUnderWay(): Boolean = _cameraOpen.value || _openingCrop.value || _crop.value != null || _scan.value != null || _card.value != null

    /**
     * Makes [item]'s page from [source], straightened inside [quad], and puts it in place. In the saved
     * state until then (see [pendingCrops]). [after], when given, is the cleanup as Usugami starts, which
     * the crop waits for: done, it gives the pages it put back whole on the image they are cropped from.
     */
    private fun crop(item: ImageItem, source: OriginalImage, quad: CropQuad, after: Deferred<Set<Long>>? = null) {
        val id = item.id
        val target = store.fileFor(nextId++, source.format)
        val generation = importGeneration
        _cropping.update { it + id }
        _pendingImports.update { it + 1 }
        pendingCrops = pendingCrops.filterNot { it.itemId == id } + PendingCrop(id, quad)
        // The page as it was when its corners were chosen, if it still is: not removed, nor its
        // files changed.
        var chosen = item
        fun stillAsChosen(): ImageItem? = items.value.firstOrNull { it.id == id }?.takeIf { it.path == chosen.path && it.source.path == source.path }
        viewModelScope.launch {
            // Cropped again as Usugami starts: its cleanup may have put the page back whole on the image
            // it is cropped from (see ImageStore.putBackWhole), which it is made from all the same. Not a
            // page that outlasted that image, though, and took its file name (see ImageStore.stillThere):
            // the page is no longer made from it, and stays as it is.
            if (after != null && id in after.await()) items.value.firstOrNull { it.id == id }?.takeIf { it.source.path == source.path }?.let { chosen = it }
            val page = try {
                // Not made for a page removed while it waited its turn.
                withContext(cropOps) { if (stillAsChosen() == null) null else PageCropper.crop(source, quad, target) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Usugami", "Cropping failed", e)
                null
            } catch (e: OutOfMemoryError) {
                null
            } finally {
                _cropping.update { it - id }
            }
            pendingCrops = pendingCrops.filterNot { it.itemId == id }
            if (generation != importGeneration) {
                // Discarded while it was being cropped.
                viewModelScope.launch(fileOps) { target.delete() }
                return@launch
            }
            _pendingImports.update { it - 1 }
            val current = stillAsChosen()
            when {
                // Removed, or its copy cleared, meanwhile: there is nothing to say about it.
                current == null -> viewModelScope.launch(fileOps) { target.delete() }
                page == null -> message(R.string.crop_failed)
                else -> {
                    replaceItem(
                        current.copy(
                            path = target.path,
                            format = source.format,
                            width = page.width,
                            height = page.height,
                            exifOrientation = 1,
                            original = source.copy(corners = quad),
                        ),
                    )
                    thumbnails.remove(current)
                    // The page as it was cropped before, once no saved state names it.
                    if (current.original != null) superseded += File(current.path)
                }
            }
        }
    }

    /** The images [packItems] kept in the saved state, if Android restarted Usugami. */
    private fun restoredItems(): List<ImageItem> {
        val saved = handle.get<Bundle>(KEY_ITEMS) ?: return emptyList()
        val ids = saved.getLongArray("ids") ?: return emptyList()
        val sizes = saved.getIntArray("sizes") ?: return emptyList()
        val details = saved.getIntArray("details") ?: return emptyList()
        if (ids.isEmpty() || sizes.size != 2 * ids.size || details.size != ids.size) return emptyList()
        val names = saved.getByteArray("names")?.decodeToString()?.split('/').orEmpty()
        val crops = restoredCrops(saved, ids)
        val looks = saved.getByteArray("looks")?.takeIf { it.size == ids.size }
        val adjustments = saved.getIntArray("adjustments")?.takeIf { it.size == ids.size }
        val cards = restoredCards(saved, ids)
        return ids.indices.map { i ->
            val format = ImageFormat.entries[details[i] shr 16]
            val crop = crops[i]
            ImageItem(
                id = ids[i],
                path = store.fileFor(crop?.first ?: ids[i], format).path,
                name = names.getOrNull(i) ?: "Image",
                format = format,
                width = sizes[2 * i],
                height = sizes[2 * i + 1],
                exifOrientation = details[i] shr 8 and 0xFF,
                rotation = (details[i] and 0xFF) * 90,
                original = crop?.second,
                look = looks?.getOrNull(i)?.toInt()?.let { PageLook.entries.getOrNull(it) } ?: PageLook.ORIGINAL,
                adjustments = adjustments?.getOrNull(i)?.let(::unpackAdjustments) ?: LookAdjustments.NONE,
                card = cards[i] ?: 0L,
            )
        }
    }

    /** The ID card numbers [packItems] kept, by the places of the cards' sides in the list. */
    private fun restoredCards(saved: Bundle, ids: LongArray): Map<Int, Long> {
        val places = saved.getIntArray("cardSides") ?: return emptyMap()
        val cards = saved.getLongArray("cards") ?: return emptyMap()
        if (cards.size != places.size || places.any { it !in ids.indices }) return emptyMap()
        return places.indices.associate { places[it] to cards[it] }
    }

    /** The cropped pages [packItems] kept, by their place in the list: each page's file and its original. */
    private fun restoredCrops(saved: Bundle, ids: LongArray): Map<Int, Pair<Long, OriginalImage>> {
        val places = saved.getIntArray("crops") ?: return emptyMap()
        val files = saved.getLongArray("cropFiles") ?: return emptyMap()
        val sizes = saved.getIntArray("cropSizes") ?: return emptyMap()
        val details = saved.getIntArray("cropDetails") ?: return emptyMap()
        val corners = saved.getFloatArray("cropCorners") ?: return emptyMap()
        val n = places.size
        if (files.size != n || sizes.size != 2 * n || details.size != n || corners.size != 8 * n || places.any { it !in ids.indices }) return emptyMap()
        return (0 until n).associate { c ->
            val format = ImageFormat.entries[details[c] shr 8]
            val original = OriginalImage(
                path = store.fileFor(ids[places[c]], format).path,
                format = format,
                width = sizes[2 * c],
                height = sizes[2 * c + 1],
                exifOrientation = details[c] and 0xFF,
                corners = CropQuad(List(4) { CropPoint(corners[8 * c + 2 * it], corners[8 * c + 2 * it + 1]) }),
            )
            places[c] to (files[c] to original)
        }
    }

    /**
     * Takes in what [ImageStore.stillThere] found of [pages]' files ([found], in the same order): the
     * pages whose copy has gone are dropped, saying so, and those that only lost the image they were
     * cropped from stay, as they are now. Returns the pages still there.
     */
    private fun takeIn(pages: List<ImageItem>, found: List<ImageItem?>): List<ImageItem> {
        val moved = pages.zip(found).filter { (was, now) -> now != null && now != was }
        if (moved.isNotEmpty()) {
            val paths = moved.associate { (was, now) -> was.id to (was.path to now!!) }
            setItems(
                items.value.map { item ->
                    val (from, now) = paths[item.id] ?: return@map item
                    // Its file as found, which a page put back on the image it was cropped from takes
                    // the size and orientation of.
                    if (item.path != from) item
                    else item.copy(path = now.path, format = now.format, width = now.width, height = now.height, exifOrientation = now.exifOrientation, original = null)
                },
            )
            moved.forEach { (was, now) ->
                thumbnails.remove(was)
                thumbnails.remove(now!!)
            }
        }
        val missing = pages.filterIndexed { i, _ -> found[i] == null }
        if (missing.isNotEmpty()) forgetMissing(missing)
        return found.filterNotNull()
    }

    /** Drops images whose copies the system has cleared from the cache, and says so. */
    private fun forgetMissing(missing: List<ImageItem>) {
        val ids = missing.mapTo(HashSet()) { it.id }
        setItems(items.value.filterNot { it.id in ids })
        missing.forEach(thumbnails::remove)
        pluralMessage(R.plurals.msg_files_cleared, ids.size)
    }

    // ---- Converting ----

    /** What the Convert sheet starts with: the last attempt's choices if it made no PDF, otherwise the defaults. */
    fun initialChoices(): ConvertChoices = lastChoices ?: ConvertChoices(defaultFileName(), ocrLanguage = null)

    /**
     * Suggested name for a merged PDF: the image's own name for one page (an ID card's: its front's),
     * otherwise a timestamp.
     */
    private fun defaultFileName(): String {
        val list = items.value
        if (pages(list).size == 1) return FileNames.sanitize(FileNames.baseName(list[0].name), PdfExporter.DEFAULT_NAME)
        return "Usugami_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT))
    }

    fun convert(merge: Boolean, fileName: String, compression: Compression, ocrLanguage: OcrLanguage? = null) {
        val engine = ocrEngine
        // Usugami Lite has no text recognition, so it never adds a text layer.
        val language = ocrLanguage?.takeIf { engine != null }
        val snapshot = items.value
        if (snapshot.isEmpty() || _progress.value != null || _pendingImports.value > 0) return
        // Not while a page's crop screen shows or is opening, as it does for an ID card's side just
        // added: the page would go into the PDF uncropped, and closing that screen later would remove
        // it. Nor while the ID card flow goes on, whose card isn't finished, or a scan, whose photos are
        // in the list before they are cropped and given their looks.
        if (_crop.value != null || _openingCrop.value || _card.value != null || _scan.value != null) return
        // The setting and the sheet count pages: an ID card's sides make one.
        val single = pages(snapshot).size == 1
        if (!single) {
            _merge.value = merge
            settingsStore.merge = merge
        }
        _compression.value = compression
        settingsStore.compression = compression
        lastChoices = ConvertChoices(fileName, language)
        val layout = _layout.value
        _progress.value = Progress(0, snapshot.size)
        // In the saved state until the PDF is made: when Android closes Usugami meanwhile, the user is told.
        handle[KEY_MAKING] = true
        conversion = viewModelScope.launch {
            // The pages as their files are now (see takeIn).
            var pages = snapshot
            // The PDFs made so far, which a cancel deletes: they aren't the result yet.
            var made = emptyList<PdfFile>()
            try {
                pages = takeIn(snapshot, withContext(fileOps) { snapshot.map(store::stillThere) })
                if (pages.size < snapshot.size) return@launch
                val files = withContext(Dispatchers.IO) {
                    if (engine != null && language != null) {
                        // Before any PDF work, so a missing recognizer is reported straight away.
                        engine.ensureReady(language) { fraction ->
                            _progress.value = if (fraction == null) Progress(0, 0, Progress.Stage.DOWNLOAD_OCR)
                            else Progress((fraction * 100).toInt(), 100, Progress.Stage.DOWNLOAD_OCR)
                        }
                        _progress.value = Progress(0, snapshot.size)
                    }
                    val files = exporter.export(pages, layout, compression, merge || single, fileName, strings.getString(R.string.app_name)) { done ->
                        _progress.value = Progress(done, snapshot.size)
                    }
                    made = files
                    val read = if (engine == null || language == null) files else addTextLayer(files, engine, language).also { made = it }
                    val job = coroutineContext.job
                    marker.markMade(read, { job.ensureActive() }) { index, count ->
                        // Which PDF, when there are several.
                        _progress.value = if (count > 1) Progress(index, count, Progress.Stage.ADD_MARK) else Progress(0, 0, Progress.Stage.ADD_MARK)
                    }
                }
                _saved.value = false
                resultPutAway = false
                resultName = fileName
                _result.value = files
                lastChoices = null
            } catch (e: CancellationException) {
                // Cancelled while their text was being read, or just as they were done.
                val unwanted = made
                if (unwanted.isNotEmpty()) viewModelScope.launch(fileOps) { unwanted.forEach { it.file.delete() } }
                throw e
            } catch (e: OcrUnavailableException) {
                if (e.problem == OcrProblem.FAILED_TO_START) Log.e("Usugami", "Text recognition couldn't start", e)
                else Log.w("Usugami", "Text recognition unavailable", e)
                _ocrProblem.value = e.problem
            } catch (e: ImageUnreadableException) {
                // Its copy may have been cleared from the cache while the PDF was being made.
                val missing = withContext(Dispatchers.IO) { pages.filterNot(store::exists) }
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
                handle.remove<Boolean>(KEY_MAKING)
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
        marker.stop()
    }

    /**
     * Puts the Usugami mark on the result's PDFs and those made from now on, or takes it off. Not while
     * the PDFs or a ZIP file of them are being saved, which would save them half changed.
     */
    fun setMark(on: Boolean) {
        if (!_busySaving.value && !zipper.busy) marker.setOn(on)
    }

    /** Gives the Usugami mark [style], on the result's PDFs too; as [setMark], not while saving. */
    fun setMarkStyle(style: MarkStyle) {
        if (!_busySaving.value && !zipper.busy) marker.setStyle(style)
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
        marker.stop()
        _messages.trySend(strings.getQuantityString(R.plurals.msg_result_cleared, count))
    }

    fun saveToDownloads() {
        val files = _result.value ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        // A second tap that lands before the button is turned off does nothing, as does one that lands
        // as the Usugami mark starts changing.
        if (_busySaving.value || marker.working.value) return
        save {
            // Saved there already (a second tap, say), it only says so again.
            // Each copy is kept as it is made: when a later PDF fails, saving again doesn't copy the first ones twice.
            val known = inDownloads?.takeIf { it.first === files }?.second
                ?: MutableList<DownloadsCopy?>(files.size) { null }.also { inDownloads = files to it }
            val copies = saver.saveToDownloads(files.map { it.file }, earlier = known.toList()) { i, copy -> known[i] = copy }
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

    /** Takes back where [files], the result showing again, were saved before Android stopped Usugami. */
    private fun restoreCopies(files: List<PdfFile>) {
        KeptCopies.downloads(handle, files.size)?.let { uris ->
            inDownloads = files to uris.mapIndexedTo(ArrayList()) { i, uri -> uri?.let { DownloadsCopy(it, files[i].name) } }
        }
        KeptCopies.folder(handle, files.size)?.let { (tree, uris) -> inFolder = Triple(files, tree, uris.toMutableList()) }
    }

    /** Where the result showing was saved, for the saved state (see [KeptCopies]). */
    @VisibleForTesting
    internal fun packCopies(): Bundle {
        val files = _result.value
        val downloads = inDownloads?.takeIf { it.first === files }?.second
        val folder = inFolder?.takeIf { it.first === files }
        return KeptCopies.pack(downloads?.map { it?.uri }, folder?.second, folder?.third?.toList())
    }

    /** Saves every result into a folder picked with the system folder picker. */
    fun saveToFolder(tree: Uri) {
        // As in saveTo; the folder picker offered for several PDFs creates nothing itself.
        val files = _result.value ?: return if (resultPutAway) notSaved() else resultCleared(2)
        save {
            // As in saveToDownloads: saving again into that folder copies only what isn't there.
            val known = inFolder?.takeIf { it.first === files && it.second == tree }?.third
                ?: MutableList<Uri?>(files.size) { null }.also { inFolder = Triple(files, tree, it) }
            saver.saveToFolder(files.map { it.file }, tree, earlier = known.toList()) { i, copy -> known[i] = copy }
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
        if (marker.working.value) return
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
        val replaced = superseded.toList()
        Thread {
            all.forEach(store::delete)
            shared.forEach(inbox::delete)
            replaced.forEach(File::delete)
        }.start()
    }

    // ---- Launcher shortcuts ----

    /**
     * The launcher shortcut just used, by its [ShortcutTarget.id], until the screen acts on it (see
     * UsugamiApp). Kept in the saved state, so it outlives the activity being recreated, as it is
     * when the first launch's language screen has a language chosen.
     */
    val shortcut: StateFlow<String?> = handle.getStateFlow<String?>(KEY_SHORTCUT, null)

    /** A launcher shortcut was used: [id] names its target (see MainActivity.handleShortcut). */
    fun openShortcut(id: String) {
        handle[KEY_SHORTCUT] = id
    }

    /** The shortcut used and not acted on yet, which is then forgotten; null when there is none. */
    fun takeShortcut(): ShortcutTarget? =
        // Set to null, never removed: removing a key also cuts the flow read from it (shortcut) off the
        // handle for good, and the next shortcut used would never reach the screen.
        ShortcutTarget.of(handle.get<String>(KEY_SHORTCUT)).also { handle[KEY_SHORTCUT] = null }

    companion object {
        private const val KEY_ITEMS = "items"
        private const val KEY_SAVED = "saved"
        private const val KEY_RESULT_NAME = "result_name"
        private const val KEY_SHARED = "shared_files"
        private const val KEY_SHARES_UNDER_WAY = "shares_under_way"
        private const val KEY_PICKS_UNDER_WAY = "picks_under_way"
        private const val KEY_CROP = "crop"
        private const val KEY_CAPTURE = "capture"
        private const val KEY_PENDING_CROPS = "pending_crops"
        private const val KEY_PENDING_PHOTOS = "pending_photos"
        private const val KEY_SCAN = "scan"
        private const val KEY_CARD = "card"
        private const val KEY_MAKING = "making"
        private const val KEY_READING = "reading"
        private const val KEY_OPENING = "opening"
        private const val KEY_SHORTCUT = "shortcut"

        /**
         * [list] for the saved state, in a few bytes per image: a list of [ImageItem]s took hundreds,
         * and a few thousand images were more than the saved state can hold, which crashed Usugami as
         * it went to the background. The copies' paths follow from their ids (see [ImageStore.fileFor]),
         * and names never contain "/" (they are what follows the last one). A cropped page's file has
         * a number of its own, and the image it was cropped from has the page's id. Each page's look
         * is a byte, and its brightness and contrast a number. The ID cards' sides are kept by their
         * places with their card numbers, as the cropped pages are, so a list without cards takes no
         * more room for them.
         */
        @VisibleForTesting
        internal fun packItems(list: List<ImageItem>): Bundle {
            val cropped = list.indices.filter { list[it].original != null }
            val sides = list.indices.filter { list[it].card != 0L }
            fun original(c: Int) = list[cropped[c]].original!!
            return bundleOf(
                "ids" to LongArray(list.size) { list[it].id },
                "names" to list.joinToString("/") { it.name }.encodeToByteArray(),
                "sizes" to IntArray(2 * list.size) { if (it % 2 == 0) list[it / 2].width else list[it / 2].height },
                "details" to IntArray(list.size) { list[it].run { format.ordinal shl 16 or (exifOrientation shl 8) or rotation / 90 } },
                "crops" to cropped.toIntArray(),
                "cropFiles" to LongArray(cropped.size) { File(list[cropped[it]].path).nameWithoutExtension.toLong() },
                "cropSizes" to IntArray(2 * cropped.size) { if (it % 2 == 0) original(it / 2).width else original(it / 2).height },
                "cropDetails" to IntArray(cropped.size) { original(it).run { format.ordinal shl 8 or exifOrientation } },
                "cropCorners" to FloatArray(8 * cropped.size) { i -> original(i / 8).corners[i % 8 / 2].let { if (i % 2 == 0) it.x else it.y } },
                "looks" to ByteArray(list.size) { list[it].look.ordinal.toByte() },
                "adjustments" to IntArray(list.size) { packAdjustments(list[it].adjustments) },
                "cardSides" to sides.toIntArray(),
                "cards" to LongArray(sides.size) { list[sides[it]].card },
            )
        }

        /** A page's brightness and contrast in one number: each moved up by 128 into a byte of its own. */
        private fun packAdjustments(a: LookAdjustments): Int = (a.brightness + 128) shl 8 or (a.contrast + 128)

        /** What [packAdjustments] packed, or null for a number it can't have made. */
        private fun unpackAdjustments(packed: Int): LookAdjustments? {
            val brightness = (packed shr 8 and 0xFF) - 128
            val contrast = (packed and 0xFF) - 128
            val range = -LookAdjustments.MAX..LookAdjustments.MAX
            return if (packed ushr 16 == 0 && brightness in range && contrast in range) LookAdjustments(brightness, contrast) else null
        }
    }
}

/** The shared files being copied in were dropped (see [MainViewModel.dropShares]). */
private class ShareDropped : Exception()

/**
 * The [look] and [adjustments] Apply to all pages gave every page, which reach the images of the
 * batches picked or shared up to batch [through] that were still being added.
 */
private class AllPagesChoice(val look: PageLook, val adjustments: LookAdjustments, val through: Int)
