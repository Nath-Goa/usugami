package io.github.nath_goa.usugami.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.core.RecentFile
import io.github.nath_goa.usugami.core.SharedFiles
import io.github.nath_goa.usugami.core.SharedKind
import io.github.nath_goa.usugami.core.ZipArchive
import io.github.nath_goa.usugami.data.AppLanguage
import io.github.nath_goa.usugami.data.AppLanguages
import io.github.nath_goa.usugami.data.Appearance
import io.github.nath_goa.usugami.data.ImageStore
import io.github.nath_goa.usugami.data.LegalDocument
import io.github.nath_goa.usugami.data.PdfSaver
import io.github.nath_goa.usugami.data.RecentFiles
import kotlinx.coroutines.flow.merge

@Composable
fun UsugamiApp(vm: MainViewModel, onChooseLanguage: (AppLanguage) -> Unit, onChooseAppearance: (Appearance) -> Unit) {
    val context = LocalContext.current
    // The first time Usugami opens, it asks for a language before anything else. Choosing one
    // usually recreates the activity in that language; this state carries over, so Home follows.
    var languageChosen by rememberSaveable { mutableStateOf(AppLanguages.isChosen(context)) }
    if (!languageChosen) {
        LanguageScreen { language ->
            languageChosen = true
            onChooseLanguage(language)
        }
        return
    }
    val pdfVm: PdfToolsViewModel = viewModel()
    val items by vm.items.collectAsStateWithLifecycle()
    val layout by vm.layout.collectAsStateWithLifecycle()
    val merge by vm.merge.collectAsStateWithLifecycle()
    val compression by vm.compression.collectAsStateWithLifecycle()
    val pendingImports by vm.pendingImports.collectAsStateWithLifecycle()
    val crop by vm.crop.collectAsStateWithLifecycle()
    val openingCrop by vm.openingCrop.collectAsStateWithLifecycle()
    val cameraOpen by vm.cameraOpen.collectAsStateWithLifecycle()
    val scan by vm.scan.collectAsStateWithLifecycle()
    val cropping by vm.cropping.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val cancelling by vm.cancelling.collectAsStateWithLifecycle()
    val ocrProblem by vm.ocrProblem.collectAsStateWithLifecycle()
    val result by vm.result.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    val busySaving by vm.busySaving.collectAsStateWithLifecycle()
    val pdfResult by pdfVm.result.collectAsStateWithLifecycle()
    val pdfProcessing by pdfVm.processing.collectAsStateWithLifecycle()
    val pdfSaved by pdfVm.saved.collectAsStateWithLifecycle()
    val pdfSaving by pdfVm.saving.collectAsStateWithLifecycle()
    val pdfSavedFiles by pdfVm.savedFiles.collectAsStateWithLifecycle()
    val pdfPasswordRemoved by pdfVm.passwordRemoved.collectAsStateWithLifecycle()
    val zipProgress by vm.zipProgress.collectAsStateWithLifecycle()
    val pdfZipProgress by pdfVm.zipProgress.collectAsStateWithLifecycle()
    val sharedFiles by vm.sharedFiles.collectAsStateWithLifecycle()
    val sharing by vm.sharing.collectAsStateWithLifecycle()
    val pdfInputs by pdfVm.inputs.collectAsStateWithLifecycle()
    val pdfLocked by pdfVm.locked.collectAsStateWithLifecycle()
    val pdfImporting by pdfVm.importing.collectAsStateWithLifecycle()
    val recent = remember(context) { RecentFiles.of(context) }
    val recentFiles by recent.files.collectAsStateWithLifecycle()

    var route by rememberSaveable { mutableStateOf(if (vm.items.value.isNotEmpty()) "images" else "home") }

    val snackbar = remember { SnackbarHostState() }
    // Given to a screen while it animates away, so a message never shows on two screens at once.
    val idleSnackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }
    LaunchedEffect(pdfVm) {
        pdfVm.messages.collect { snackbar.showSnackbar(it) }
    }
    // Android closed Usugami while it took in files shared to it, which are gone: said once.
    LaunchedEffect(vm, pdfVm) {
        if (vm.takeShareInterrupted() or pdfVm.takeShareInterrupted()) vm.showMessage(R.string.msg_share_interrupted)
    }
    // A ZIP file of the result's PDFs is shared once it is ready, while Usugami is on screen: an app
    // in the background can't open the share screen.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(vm, pdfVm, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            merge(vm.zipShares, pdfVm.zipShares).collect { intent ->
                try {
                    context.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    vm.showMessage(R.string.msg_no_share_app)
                }
            }
        }
    }
    // Set when images were shared while a PDF tool had a PDF that would be lost by leaving it.
    var confirmLeavePdf by rememberSaveable { mutableStateOf(false) }
    // The user chose to stay with that PDF: more shared images don't ask again until they leave the tool.
    var stayOnPdf by rememberSaveable { mutableStateOf(false) }
    // What was done to a tool's PDF is asked about before it is thrown away: "leave" when leaving the
    // tool, "remove" when removing its PDF.
    var discardWork by rememberSaveable { mutableStateOf<String?>(null) }
    // Set when PDFs or documents were shared while a PDF tool had a PDF that would be lost by leaving it.
    var confirmShare by rememberSaveable { mutableStateOf(false) }
    // The tool showing was opened from Choose a tool, which it goes back to.
    var toolFromShare by rememberSaveable { mutableStateOf(false) }
    // The converter showing was opened for Word, PowerPoint, Excel or EPUB files shared to Usugami.
    var docsFromShare by rememberSaveable { mutableStateOf(false) }
    // Said once that the files shared open when the PDF being made is done.
    var toldShareWaits by rememberSaveable { mutableStateOf(false) }
    // Back in the editor asks whether to discard its images. Files shared meanwhile open another
    // screen, which the question doesn't belong to: it goes.
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(route) {
        if (route != "images") confirmDiscard = false
        if (!isPdfToolRoute(route)) {
            confirmLeavePdf = false
            stayOnPdf = false
            discardWork = null
            confirmShare = false
        }
    }
    // How many images there were when the list last changed. Restored with the activity, so the list
    // restored with it (after a rotation, a new language or theme, or Android restarting Usugami) isn't
    // taken for images just shared, which would leave the screen showing for the editor.
    var imagesSeen by rememberSaveable { mutableIntStateOf(vm.items.value.size) }
    LaunchedEffect(items.size) {
        val arrived = items.size > imagesSeen
        imagesSeen = items.size
        // Images shared from another app can arrive on any screen, a PDF tool's included: show them.
        // Leaving a tool discards its PDF, so ask first while one is being made, isn't saved yet, or
        // has work on it that would be lost.
        if (!arrived || route == "images" || stayOnPdf) return@LaunchedEffect
        // Not while files shared to Usugami are waiting or being copied in, or are open on Choose a tool,
        // a tool opened from it, or the converter documents shared opened (also while it still takes them
        // in): they would go. The images wait in the editor, which Back from there leads to.
        val tool = PdfTool.entries.firstOrNull { it.name.equals(route, ignoreCase = true) }
        val sharedDocumentsOpen = docsFromShare && (pdfVm.documents.value.isNotEmpty() || pdfVm.importing.value)
        val sharedFilesOpen = route == "share" || tool != null && (toolFromShare || sharedDocumentsOpen)
        if (sharedFilesOpen || vm.sharedFiles.value.isNotEmpty() || vm.sharing.value != null) return@LaunchedEffect
        val unsavedPdf = pdfVm.processing.value ||
            (if (pdfVm.result.value != null) !pdfVm.saved.value else tool != null && pdfVm.hasUnsavedWork(tool))
        if (tool != null && unsavedPdf) confirmLeavePdf = true else route = "images"
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { vm.addImages(it) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.addImages(it) }
    // The editor was opened by Home's Scan document, whose scan is still going on.
    var scanFromHome by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(route) {
        // Files shared to Usugami meanwhile open their tool, or Choose a tool, over the scan, and Back
        // from there comes back to it: it is still the scan from Home.
        val sharedOver = (route == "share" || isPdfToolRoute(route)) && vm.scanUnderWay()
        if (route != "images" && !sharedOver) scanFromHome = false
    }
    // Once the camera and the crop screen have closed, the scan from Home is over. With a page in the
    // editor, the editor is as it always is from then on, also when the user removes that page while
    // it is still being cropped. With no page, once nothing is still being added, it goes back to Home
    // rather than to an empty editor: the camera closed without a photo, the photo discarded, or
    // nothing picked could be added. The view model is asked as it is now: its flows reach the screen
    // one at a time.
    LaunchedEffect(scanFromHome, route, items.isEmpty(), pendingImports, openingCrop, crop, scan, cameraOpen) {
        if (!scanFromHome || route != "images" || vm.scanUnderWay()) return@LaunchedEffect
        if (vm.items.value.isNotEmpty()) scanFromHome = false else if (vm.pendingImports.value == 0) route = "home"
    }
    // Scan document's Upload images: the images picked are added, and cropped one after another.
    // Nothing picked leaves Home as it was.
    val scanPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) {
            vm.scanImages(uris)
            if (route == "home") {
                route = "images"
                scanFromHome = true
            }
        }
    }
    // The phone's own camera app takes the photos, into a file Usugami gives it: Usugami needs no
    // camera permission. Also when Android closed Usugami while it was open, this comes back.
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture(), vm::photoTaken)
    // The PDF tool whose picker is open. What is picked goes to that tool while it shows, and not to
    // Home or another tool when the user left before the picker covered Usugami.
    var pickingFor by rememberSaveable { mutableStateOf<String?>(null) }
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && route == pickingFor) pdfVm.addPdfs(listOf(uri), replace = true)
    }
    val pdfMultiPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (route == pickingFor) pdfVm.addPdfs(uris, replace = false)
    }
    // Reorganize PDF's "Add pages": one more PDF, whose pages go where the tool said.
    val pdfAddPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && route == pickingFor) pdfVm.addPdfs(listOf(uri), replace = false)
    }
    // Edit PDF's pictures, from the system photo picker (or the file picker where there is none).
    val picturePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && route == pickingFor) pdfVm.addPicture(uri)
    }
    // For Word, PowerPoint, Excel and EPUB to PDF: the tool showing says which kind of file it takes.
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val kind = PdfTool.entries.firstOrNull { it.name.equals(route, ignoreCase = true) }?.officeKind
        if (kind != null && route == pickingFor) pdfVm.addDocuments(uris, kind)
    }
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(PdfSaver.PDF_MIME)) { uri ->
        if (uri != null) {
            if (route == "images") vm.saveTo(uri) else pdfVm.saveTo(uri)
        }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            if (route == "images") vm.saveToFolder(uri) else pdfVm.saveToFolder(uri)
        }
    }
    val createZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ZipArchive.MIME)) { uri ->
        if (uri != null) {
            if (route == "images") vm.saveZipTo(uri) else pdfVm.saveZipTo(uri)
        }
    }

    // Images shared while the user stayed with a tool's PDF wait for them in the editor.
    fun afterTool(): String = if (vm.items.value.isNotEmpty() || vm.pendingImports.value > 0) "images" else "home"

    /** Where the shared files waiting, or else being copied in, open (see [sharedRoute]); null for none. */
    fun shareTarget(): String? = sharedRoute(SharedFiles.chosen(vm.sharedFiles.value.map { it.kind }) ?: vm.sharing.value)

    // Another app's screen opens once per burst of taps (see ScreenOpener).
    val opener = remember { ScreenOpener() }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> opener.covered()
                Lifecycle.Event.ON_RESUME -> opener.back()
                // Files may be deleted, moved or renamed while Usugami is away.
                Lifecycle.Event.ON_STOP -> recent.checkAgain()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    /**
     * Starts another app's screen with [block]; [noApp] is shown when no app can handle it. A tap
     * while one is already on its way does nothing.
     */
    fun launch(@StringRes noApp: Int, block: () -> Unit) {
        if (!opener.open()) return
        try {
            block()
        } catch (_: ActivityNotFoundException) {
            opener.notOpened()
            vm.showMessage(noApp)
        }
    }

    fun openSourceCode() = launch(R.string.msg_no_browser) { context.startActivity(Intent(Intent.ACTION_VIEW, SOURCE_CODE_URL.toUri())) }

    // The listed file Usugami found it can no longer open, whose dialog shows.
    var lostFile by rememberSaveable { mutableStateOf<String?>(null) }

    /**
     * Starts another app's screen for a listed file with [intent], as [launch] does. When Usugami's
     * access to the file has ended, the system refuses to pass it on: a dialog says so instead.
     */
    fun launchRecent(file: RecentFile, @StringRes noApp: Int, intent: () -> Intent) {
        if (file.uri in recent.unreachable.value) {
            lostFile = file.uri
            return
        }
        launch(noApp) {
            try {
                context.startActivity(intent())
            } catch (_: SecurityException) {
                opener.notOpened()
                lostFile = file.uri
            }
        }
    }

    val recentActions = RecentFileActions(
        open = { file -> launchRecent(file, if (file.type == ZipArchive.MIME) R.string.msg_no_zip_app else R.string.msg_no_pdf_app) { recent.viewIntent(file) } },
        share = { file -> launchRecent(file, R.string.msg_no_share_app) { recent.shareIntent(file) } },
        remove = recent::remove,
    )

    /**
     * Opens the camera app to take a photo for a page; it is added and cropped when it comes back.
     * Returns whether it opened.
     */
    fun takePhoto(): Boolean {
        if (!opener.open()) return false
        try {
            camera.launch(vm.newCapture())
            return true
        } catch (_: ActivityNotFoundException) {
            opener.notOpened()
            vm.photoTaken(false)
            vm.showMessage(R.string.msg_no_camera)
            return false
        }
    }

    // A tool's choices (its options, a signature's place) are kept while its result shows, so
    // "Back to editing" returns to them. Opening the tool from Home starts afresh.
    val toolStates = rememberSaveableStateHolder()
    // Counts the tools opened from Home. Each opening is a screen of its own, with choices of its own,
    // also when a tool is opened again while its last screen is still sliding away.
    var toolOpening by rememberSaveable { mutableIntStateOf(0) }
    // What the crop screen chose for its page (the corners moved, the step, the look) is kept while
    // files shared to Usugami open their tool over it, so Back returns to the page as it was left.
    val cropStates = rememberSaveableStateHolder()
    // Counts the times the crop screen opened. Each is a screen of its own, whose choices are
    // forgotten once its pages are done with.
    var cropOpening by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(crop == null && scan == null) {
        if (crop == null && scan == null) {
            cropStates.removeState(cropOpening)
            cropOpening++
        }
    }

    /** Opens [target] for files shared to Usugami. What showed goes, as when a tool is opened from Home. */
    fun openForShare(target: String) {
        pdfVm.reset()
        if (target != "share") {
            toolStates.removeState(toolStateKey(toolOpening))
            toolOpening++
        }
        toolFromShare = false
        docsFromShare = target != "share"
        toldShareWaits = false
        // What was asked about the last tool, and the choice to stay with its PDF, go with it: also when
        // a converter opens, a tool's route, which doesn't clear them as other routes do.
        stayOnPdf = false
        confirmLeavePdf = false
        discardWork = null
        confirmShare = false
        confirmDiscard = false
        route = target
    }

    /**
     * Leaves the PDF tool showing: for the files shared meanwhile, if any; else for Choose a tool if the
     * tool was opened from it, with its PDFs (the images shared meanwhile wait, as Back from there
     * leads to them); else for those images, in the editor; else for Home.
     */
    fun exitTool() {
        val target = shareTarget()
        // Leaving the tool shared files are being copied in for, the user doesn't want them any more.
        if (target == route) vm.dropShares() else if (target != null) return openForShare(target)
        docsFromShare = false
        val tool = PdfTool.entries.firstOrNull { it.name.equals(route, ignoreCase = true) }
        route = when {
            toolFromShare && tool != null && (pdfVm.inputs.value.isNotEmpty() || pdfVm.locked.value.isNotEmpty()) -> {
                pdfVm.clearToolWork(tool)
                "share"
            }
            else -> afterTool()
        }
    }

    /** Leaves Choose a tool: its PDFs go, and those still being copied in for it. */
    fun exitShare() {
        vm.dropShares()
        route = afterTool()
    }

    // PDFs, Word, PowerPoint, Excel and EPUB files shared to Usugami open where they belong (see [sharedRoute]).
    // Leaving a PDF tool discards its PDF, so a tool with work that isn't saved asks first, and a PDF
    // being made is waited for. Once copied in, the files go to the tool that opened for them.
    LaunchedEffect(sharedFiles, sharing, route, pdfResult != null, pdfProcessing, progress != null, pdfImporting, confirmShare, stayOnPdf) {
        val target = shareTarget()
        if (target == null) {
            confirmShare = false
            toldShareWaits = false
            return@LaunchedEffect
        }
        if (confirmShare) return@LaunchedEffect
        if (pdfVm.processing.value || vm.progress.value != null) {
            if (!toldShareWaits) {
                toldShareWaits = true
                vm.showMessage(R.string.share_waits)
            }
            return@LaunchedEffect
        }
        if (route != target || pdfVm.result.value != null) {
            val tool = PdfTool.entries.firstOrNull { it.name.equals(route, ignoreCase = true) }
            if (tool != null) {
                // The user chose to stay with the tool's PDF: the files wait until they leave it.
                if (stayOnPdf) return@LaunchedEffect
                val unsaved = if (pdfVm.result.value != null) !pdfVm.saved.value else pdfVm.hasUnsavedWork(tool)
                if (unsaved) {
                    // Asked once the files are copied in, not while they are only being copied: the
                    // question says they are ready, and the copy may yet fail, which would leave
                    // nothing to open for the PDF thrown away. This runs again when they are in.
                    if (vm.sharedFiles.value.isNotEmpty()) confirmShare = true
                    return@LaunchedEffect
                }
            }
            openForShare(target)
        }
        if (vm.sharedFiles.value.isNotEmpty() && !pdfVm.importing.value) pdfVm.addShared(vm.takeSharedFiles())
    }

    // What is typed into Home's search field. It is kept while a tool opened from the results shows,
    // so going back returns to them.
    var homeQuery by rememberSaveable { mutableStateOf("") }

    var showConvertSheet by rememberSaveable { mutableStateOf(false) }

    // Which screen is showing. The result screens sit one level deeper than their editors, which
    // sit one level deeper than home, and so do Recent files ("recent"), Settings ("settings"), About
    // ("settings/about") and the texts About opens ("settings/about/privacy"); that depth decides the
    // slide direction.
    // A tool opened from Choose a tool ("share") sits one level deeper than it. The crop screen sits
    // beside the editor's result; a scan of images picked keeps it showing while its next image is
    // awaited.
    val screen = when {
        route == "home" || route.startsWith("settings") || route == "share" || route == "recent" -> route
        route == "images" -> when {
            result != null -> "images/result"
            crop != null || scan != null -> "images/crop"
            else -> "images"
        }
        else -> (if (toolFromShare) "share/" else "") + if (pdfResult == null) route else "$route/result"
    }
    // Where shared files are going, for the screen they open in to say more are coming.
    val receivingFor = sharedRoute(SharedFiles.chosen(sharedFiles.map { it.kind }) ?: sharing)

    AnimatedContent(
        targetState = Shown(
            screen,
            when {
                isPdfToolRoute(route) -> toolOpening
                screen == "images/crop" -> cropOpening
                else -> 0
            },
        ),
        transitionSpec = { Motion.screenTransition(forward = screenDepth(targetState.screen) >= screenDepth(initialState.screen)) },
        label = "screen",
    ) { shown ->
        val current = shown.screen.removePrefix("share/")
        // While a screen animates away it must not handle Back or touches, and it keeps showing
        // its last data. A screen reader no longer finds it either: its buttons could still be
        // pressed through the screen reader, and parts of it the new screen doesn't cover yet
        // would be read out with the new screen.
        val active = transition.targetState == EnterExitState.Visible
        val screenSnackbar = if (active) snackbar else idleSnackbar
        Box(if (active) Modifier else Modifier.clearAndSetSemantics {}) {
            if (current == "home" || current == "recent") {
                // The files listed are looked over while they show, once each time Usugami comes back.
                LaunchedEffect(recent, lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { recent.check() } }
            }
            if (current == "home" || current.startsWith("images")) {
                // Once a PDF tool's screens have gone (the user went home, or shared images opened the
                // editor), nothing of that tool can be shown again: opening a tool starts afresh. Its
                // copies and results are freed as soon as the new screen has settled. Not when files shared
                // to Usugami opened a tool meanwhile, even before this screen was first drawn.
                val settled = transition.currentState == EnterExitState.Visible && transition.targetState == EnterExitState.Visible
                LaunchedEffect(settled) { if (settled && route == current.substringBefore('/')) pdfVm.reset() }
            }
            when {
                current == "home" -> {
                    // Back clears a search first, then leaves Usugami.
                    BackHandler(enabled = active && homeQuery.isNotEmpty()) { homeQuery = "" }
                    HomeScreen(
                        snackbar = screenSnackbar,
                        query = homeQuery,
                        onQueryChange = { homeQuery = it },
                        // The camera opens over the editor, where its photo is added.
                        onScanPhoto = {
                            if (takePhoto()) {
                                vm.newScan()
                                route = "images"
                                scanFromHome = true
                            }
                        },
                        onScanUpload = {
                            launch(R.string.msg_no_picker) {
                                scanPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }
                        },
                        recentFiles = recentFiles,
                        recentActions = recentActions,
                        onAllRecentFiles = { route = "recent" },
                        onImages = { route = "images" },
                        onTool = { tool ->
                            pdfVm.reset()
                            // The last tool's choices are forgotten, also while its screen is still sliding away.
                            toolStates.removeState(toolStateKey(toolOpening))
                            toolOpening++
                            toolFromShare = false
                            docsFromShare = false
                            route = tool.name.lowercase()
                        },
                        onSettings = { route = "settings" },
                    )
                }
                current == "recent" -> {
                    BackHandler(enabled = active) { route = "home" }
                    RecentFilesScreen(
                        files = recentFiles,
                        snackbar = screenSnackbar,
                        actions = recentActions,
                        onClear = recent::clear,
                        onBack = { route = "home" },
                    )
                }
                current == "settings" -> {
                    BackHandler(enabled = active) { route = "home" }
                    SettingsScreen(
                        snackbar = screenSnackbar,
                        onBack = { route = "home" },
                        onChooseLanguage = onChooseLanguage,
                        onChooseAppearance = onChooseAppearance,
                        onSourceCode = { openSourceCode() },
                        onAbout = { route = "settings/about" },
                    )
                }
                current == "settings/about" -> {
                    BackHandler(enabled = active) { route = "settings" }
                    AboutScreen(
                        snackbar = screenSnackbar,
                        onBack = { route = "settings" },
                        onDocument = { document -> route = "settings/about/${document.name.lowercase()}" },
                        onSourceCode = { openSourceCode() },
                    )
                }
                current.startsWith("settings/about/") -> {
                    val document = LegalDocument.valueOf(current.substringAfterLast('/').uppercase())
                    BackHandler(enabled = active) { route = "settings/about" }
                    LegalDocumentScreen(document, snackbar = screenSnackbar, onBack = { route = "settings/about" })
                }
                current == "share" -> {
                    BackHandler(enabled = active) { exitShare() }
                    // With no PDF left to choose a tool for (each removed, or none could be opened), it goes. The
                    // view models are asked as they are now: their flows reach the screen one at a time.
                    val empty = pdfInputs.isEmpty() && pdfLocked.isEmpty() && !pdfImporting && receivingFor != "share"
                    LaunchedEffect(empty, active) {
                        val none = pdfVm.inputs.value.isEmpty() && pdfVm.locked.value.isEmpty() && !pdfVm.importing.value && shareTarget() != "share"
                        if (empty && none && active && route == "share") exitShare()
                    }
                    ShareScreen(
                        vm = pdfVm,
                        receiving = receivingFor == "share",
                        snackbar = screenSnackbar,
                        onBack = { exitShare() },
                        onTool = { tool ->
                            // Its own screen, with choices of its own, as when opened from Home.
                            toolStates.removeState(toolStateKey(toolOpening))
                            toolOpening++
                            toolFromShare = true
                            docsFromShare = false
                            route = tool.name.lowercase()
                        },
                    )
                }
                current == "images" -> {
                    // Images still being imported count too: they would arrive at home and reopen the editor.
                    // Not while a PDF is being made: Back pressed right after Create, before the progress
                    // shows, would offer to discard the images being made into it.
                    val leave: () -> Unit = {
                        if (vm.progress.value == null) {
                            if (items.isEmpty() && pendingImports == 0) route = "home" else confirmDiscard = true
                        }
                    }
                    BackHandler(enabled = active, onBack = leave)
                    EditorScreen(
                        items = freezeWhen(!active, items),
                        layout = layout,
                        pendingImports = pendingImports,
                        thumbnails = vm.thumbnails,
                        snackbar = screenSnackbar,
                        actions = EditorActions(
                            addFromPhotos = {
                                launch(R.string.msg_no_picker) {
                                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                }
                            },
                            addFromFiles = { launch(R.string.msg_no_picker) { filePicker.launch(ImageStore.pickableTypes) } },
                            takePhoto = ::takePhoto,
                            changeLayout = vm::setLayout,
                            rotate = vm::rotate,
                            crop = vm::openCrop,
                            remove = vm::remove,
                            move = vm::move,
                            moveBy = vm::moveBy,
                            sort = vm::sort,
                            clearAll = vm::clearAll,
                            // Not while a PDF is being made: a tap that lands just after Create would open the
                            // sheet again over the progress. Nor while a page's crop screen is opening, as it
                            // does for a photo just taken: it would open under the sheet, and Create would make
                            // the PDF with that screen still open.
                            convert = { if (vm.progress.value == null && !vm.openingCrop.value) showConvertSheet = true },
                        ),
                        onBack = leave,
                        cropping = cropping,
                    )
                }
                current == "images/crop" -> {
                    // Back is the screen's own: on its second step, it goes back to the first.
                    cropStates.SaveableStateProvider(shown.opening) {
                        CropScreen(
                            session = freezeWhen(!active, crop),
                            items = freezeWhen(!active, items),
                            scan = freezeWhen(!active, scan),
                            active = active,
                            arrived = active && transition.currentState == EnterExitState.Visible,
                            snackbar = screenSnackbar,
                            actions = CropActions(
                                done = { vm.finishCrop(it.corners, it.look, it.allPages, it.adjustments, it.rotation) },
                                // A second tap, once the first has closed the screen, opens no second camera.
                                takeAnother = { if (vm.finishCrop(it.corners, it.look, it.allPages, it.adjustments, it.rotation)) takePhoto() },
                                close = vm::closeCrop,
                            ),
                        )
                    }
                }
                current == "images/result" -> {
                    val files = freezeWhen(!active, result) ?: emptyList()
                    BackHandler(enabled = active) { vm.backToEditing() }
                    ResultScreen(
                        files = files,
                        saved = saved,
                        busy = busySaving,
                        snackbar = screenSnackbar,
                        actions = ResultActions(
                            back = vm::backToEditing,
                            open = { file -> if (vm.checkResult()) launch(R.string.msg_no_pdf_app) { context.startActivity(vm.viewIntent(file)) } },
                            share = { if (vm.checkResult()) launch(R.string.msg_no_share_app) { context.startActivity(vm.shareIntent(files)) } },
                            saveToDownloads = if (vm.canSaveToDownloads) vm::saveToDownloads else null,
                            saveAs = {
                                if (vm.checkResult()) {
                                    launch(R.string.msg_no_file_picker) {
                                        if (files.size == 1) createDocument.launch(files[0].name) else pickFolder.launch(null)
                                    }
                                }
                            },
                            startOver = vm::startOver,
                            shareZip = { if (vm.checkResult()) vm.shareZip() },
                            // Not while a ZIP file is being made, which "Share ZIP" tapped in the same frame starts.
                            saveZip = {
                                if (vm.zipProgress.value == null && vm.checkResult()) {
                                    launch(R.string.msg_no_file_picker) { createZip.launch(vm.zipFileName()) }
                                }
                            },
                        ),
                        zipProgress = zipProgress,
                    )
                }
                current.endsWith("/result") -> {
                    val files = freezeWhen(!active, pdfResult) ?: emptyList()
                    BackHandler(enabled = active) { pdfVm.backToEditing() }
                    ResultScreen(
                        files = files,
                        saved = pdfSaved,
                        busy = pdfSaving,
                        snackbar = screenSnackbar,
                        actions = ResultActions(
                            back = pdfVm::backToEditing,
                            open = { file -> if (pdfVm.checkResult()) launch(R.string.msg_no_pdf_app) { context.startActivity(pdfVm.viewIntent(file)) } },
                            share = { if (pdfVm.checkResult()) launch(R.string.msg_no_share_app) { context.startActivity(pdfVm.shareIntent(files)) } },
                            saveToDownloads = if (pdfVm.canSaveToDownloads) pdfVm::saveToDownloads else null,
                            saveAs = {
                                if (pdfVm.checkResult()) {
                                    launch(R.string.msg_no_file_picker) {
                                        if (files.size == 1) {
                                            pdfVm.prepareSaveAs(null)
                                            createDocument.launch(files[0].name)
                                        } else {
                                            pickFolder.launch(null)
                                        }
                                    }
                                }
                            },
                            startOver = pdfVm::startOver,
                            shareZip = { if (pdfVm.checkResult()) pdfVm.shareZip() },
                            // As for Images to PDF's result.
                            saveZip = {
                                if (pdfVm.zipProgress.value == null && pdfVm.checkResult()) {
                                    launch(R.string.msg_no_file_picker) { createZip.launch(pdfVm.zipFileName()) }
                                }
                            },
                            // Each PDF of several also has Share and Save buttons of its own.
                            shareOne = { file -> if (pdfVm.checkResult()) launch(R.string.msg_no_share_app) { context.startActivity(pdfVm.shareIntent(listOf(file))) } },
                            saveOneToDownloads = if (pdfVm.canSaveToDownloads) pdfVm::saveOneToDownloads else null,
                            saveOneAs = { file ->
                                if (pdfVm.checkResult()) {
                                    launch(R.string.msg_no_file_picker) {
                                        pdfVm.prepareSaveAs(file)
                                        createDocument.launch(file.name)
                                    }
                                }
                            },
                        ),
                        zipProgress = pdfZipProgress,
                        note = if (pdfPasswordRemoved) pluralStringResource(R.plurals.pdf_password_removed, files.size) else null,
                        savedFiles = pdfSavedFiles,
                    )
                }
                else -> {
                    val tool = PdfTool.entries.first { it.name.equals(current, ignoreCase = true) }
                    val leaveTool: () -> Unit = { if (pdfVm.hasUnsavedWork(tool)) discardWork = "leave" else exitTool() }
                    BackHandler(enabled = active, onBack = leaveTool)
                    toolStates.SaveableStateProvider(toolStateKey(shown.opening)) {
                        PdfToolScreen(
                            tool = tool,
                            vm = pdfVm,
                            // Files shared to Usugami are being copied in for it.
                            receiving = receivingFor == current && pdfResult == null,
                            snackbar = screenSnackbar,
                            onBack = leaveTool,
                            onRemove = { if (pdfVm.hasUnsavedWork(tool)) discardWork = "remove" else pdfVm.startOver() },
                            pickSingle = {
                                launch(R.string.msg_no_picker) {
                                    pickingFor = current
                                    pdfPicker.launch(arrayOf(PdfSaver.PDF_MIME))
                                }
                            },
                            // Word, PowerPoint, Excel and EPUB to PDF take several files of their kind.
                            pickMultiple = {
                                val kind = tool.officeKind
                                launch(R.string.msg_no_picker) {
                                    pickingFor = current
                                    if (kind != null) documentPicker.launch(kind.pickerTypes) else pdfMultiPicker.launch(arrayOf(PdfSaver.PDF_MIME))
                                }
                            },
                            pickAdd = {
                                launch(R.string.msg_no_picker) {
                                    pickingFor = current
                                    pdfAddPicker.launch(arrayOf(PdfSaver.PDF_MIME))
                                }
                            },
                            pickPicture = {
                                launch(R.string.msg_no_picker) {
                                    pickingFor = current
                                    picturePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                }
                            },
                        )
                    }
                }
            }
            if (!active) {
                Box(
                    Modifier
                        .matchParentSize()
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                            }
                        },
                )
            }
        }
    }

    // The sheet is closed for good once it can't show (the images went, say, while Usugami was away),
    // so it doesn't pop up by itself when images are added later. It belongs to the editor: a photo
    // taken while it was open (the camera app opened beside Usugami, or a tap landed before it covered
    // Usugami) opens the crop screen, which closes it.
    LaunchedEffect(screen, items.isEmpty()) {
        if (screen != "images" || items.isEmpty()) showConvertSheet = false
    }
    if (showConvertSheet && screen == "images" && items.isNotEmpty()) {
        ConvertSheet(
            imageCount = items.size,
            layout = layout,
            initialMerge = merge,
            initialCompression = compression,
            initialChoices = remember { vm.initialChoices() },
            // A photo just taken is still to go through the crop screen while its page is looked for.
            importing = pendingImports > 0 || openingCrop,
            saving = busySaving,
            onDismiss = { showConvertSheet = false },
            onCreate = { mergeAll, name, level, ocr ->
                showConvertSheet = false
                vm.convert(mergeAll, name, level, ocr)
            },
        )
    }

    progress?.let { ProgressDialog(it, cancelling, onCancel = vm::cancelConversion) }
    // Over whichever screen shows: a ZIP file being saved goes on when images shared meanwhile open
    // the editor, and Create waits for it there.
    ZipProgressDialog(zipProgress, onCancel = vm::cancelZip)
    ZipProgressDialog(pdfZipProgress, onCancel = pdfVm::cancelZip)
    ocrProblem?.let { OcrProblemDialog(it, onDismiss = vm::dismissOcrProblem) }

    // The dialog belongs to the list: it goes when another screen opens (images shared meanwhile, say),
    // and when the file has left the list.
    LaunchedEffect(route, recentFiles) {
        if ((route != "home" && route != "recent") || recentFiles.none { it.uri == lostFile }) lostFile = null
    }
    recentFiles.firstOrNull { it.uri == lostFile }?.let { file ->
        AlertDialog(
            onDismissRequest = { lostFile = null },
            title = { Text(stringResource(R.string.recent_lost_title)) },
            text = { Text(stringResource(R.string.recent_lost_body, file.name)) },
            confirmButton = {
                TextButton(onClick = {
                    lostFile = null
                    recent.remove(file)
                }) { Text(stringResource(R.string.recent_remove)) }
            },
            dismissButton = { TextButton(onClick = { lostFile = null }) { Text(stringResource(R.string.keep)) } },
        )
    }

    if (confirmDiscard && route == "images") {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.discard_title)) },
            text = { Text(stringResource(R.string.discard_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    vm.clearAll()
                    route = "home"
                }) { Text(stringResource(R.string.discard)) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.keep)) } },
        )
    }

    discardWork?.let { action ->
        AlertDialog(
            onDismissRequest = { discardWork = null },
            title = { Text(stringResource(R.string.tool_discard_title)) },
            confirmButton = {
                TextButton(onClick = {
                    discardWork = null
                    if (action == "leave") exitTool() else pdfVm.startOver()
                }) { Text(stringResource(R.string.discard)) }
            },
            dismissButton = { TextButton(onClick = { discardWork = null }) { Text(stringResource(R.string.keep)) } },
        )
    }

    if (confirmShare) {
        val keepPdf = {
            confirmShare = false
            stayOnPdf = true
        }
        // Asked over changes made to the tool's PDF, rather than over a PDF made.
        val changes = pdfResult == null
        AlertDialog(
            onDismissRequest = keepPdf,
            title = { Text(stringResource(if (changes) R.string.tool_discard_title else R.string.pdf_discard_title)) },
            text = { Text(stringResource(if (changes) R.string.share_discard_changes else R.string.share_discard_body)) },
            confirmButton = {
                TextButton(onClick = {
                    // Once: a second tap before the dialog has gone finds it answered.
                    if (confirmShare) {
                        confirmShare = false
                        shareTarget()?.let(::openForShare)
                    }
                }) { Text(stringResource(R.string.discard)) }
            },
            dismissButton = { TextButton(onClick = keepPdf) { Text(stringResource(R.string.keep)) } },
        )
    }

    if (confirmLeavePdf) {
        val keepPdf = {
            confirmLeavePdf = false
            stayOnPdf = true
        }
        // Asked over changes made to the tool's PDF, rather than over a PDF made or being made.
        val changes = pdfResult == null && !pdfProcessing
        AlertDialog(
            onDismissRequest = keepPdf,
            title = { Text(stringResource(if (changes) R.string.tool_discard_title else R.string.pdf_discard_title)) },
            text = { Text(stringResource(if (changes) R.string.pdf_discard_shared_changes else R.string.pdf_discard_shared_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeavePdf = false
                    // The editor frees the tool's files once it has opened.
                    route = "images"
                }) { Text(stringResource(R.string.discard)) }
            },
            dismissButton = { TextButton(onClick = keepPdf) { Text(stringResource(R.string.keep)) } },
        )
    }
}

/**
 * A screen as the screen animation tells them apart: [screen] (see UsugamiApp), and for a PDF tool's
 * screens, which time the tool was opened from Home, for the crop screen which time it opened.
 * Opened again while its last screen is still sliding away, a tool or the crop screen is a new screen
 * rather than that one coming back.
 */
private data class Shown(val screen: String, val opening: Int)

/** Where the choices of the tool opened [opening]th are kept. */
private fun toolStateKey(opening: Int) = "tool$opening"

/**
 * Opens other apps' screens (a picker, the "Save as" screen, the share screen, a PDF viewer, a
 * browser) once per burst of taps. Such a screen takes a moment to cover Usugami, and a second tap
 * before it does would open a second one on top, whose pick or document would then be dropped. Taps
 * open screens again once Usugami is back, or after [COVER_WAIT_MS] if nothing covered it (a screen
 * opened beside Usugami in split screen, say).
 */
private class ScreenOpener {
    private var opening = false
    private var openedAt = 0L
    private var hidden = false

    /** Whether a tap may open a screen now. If so, one is taken to be on its way. */
    fun open(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (opening && (hidden || now - openedAt < COVER_WAIT_MS)) return false
        opening = true
        openedAt = now
        hidden = false
        return true
    }

    /** No screen opened after all (no app can open it). */
    fun notOpened() {
        opening = false
        hidden = false
    }

    /** Usugami was paused: the screen on its way covers it. */
    fun covered() {
        if (opening) hidden = true
    }

    /** Usugami shows again, back from that screen. */
    fun back() {
        if (hidden) notOpened()
    }

    private companion object {
        const val COVER_WAIT_MS = 1_000L
    }
}

/**
 * Where files shared to Usugami of [kind] open: Choose a tool ("share") for PDFs, their converter for
 * Word, PowerPoint, Excel and EPUB files. Null for none, and for images, which go to the editor.
 */
private fun sharedRoute(kind: SharedKind?): String? = when (kind) {
    SharedKind.PDF -> "share"
    SharedKind.WORD -> PdfTool.WORD.name.lowercase()
    SharedKind.POWERPOINT -> PdfTool.POWERPOINT.name.lowercase()
    SharedKind.EPUB -> PdfTool.EPUB.name.lowercase()
    SharedKind.EXCEL -> PdfTool.EXCEL.name.lowercase()
    else -> null
}

/** Whether [route] is a PDF tool's screen (its result included: that shares the tool's route). */
private fun isPdfToolRoute(route: String): Boolean = PdfTool.entries.any { it.name.equals(route, ignoreCase = true) }

/**
 * How deep a screen is: home 0, a tool's editor, Choose a tool, Recent files or Settings 1, a result,
 * a tool opened from Choose a tool or About 2, a text About opens 3.
 */
private fun screenDepth(screen: String): Int = if (screen == "home") 0 else 1 + screen.count { it == '/' }
