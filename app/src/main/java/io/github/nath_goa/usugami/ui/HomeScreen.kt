package io.github.nath_goa.usugami.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.BrandingWatermark
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.ViewQuilt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.Splitscreen
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.core.RecentFile
import io.github.nath_goa.usugami.core.ToolSearch

// The lists below are written one tool per line, so that a change to one tool changes one line.
// They must stay above PdfToolSections: a list written below it would still be null when it is built.

/** The tools under "View" on Home: the reader. */
private val ViewTools = listOf(
    PdfTool.READER,
)

/**
 * Documents to PDF, under "Create PDF" on Home after Images to PDF: one card for the Word, PowerPoint,
 * Excel and EPUB converters, which picks files and opens the converter for them. The search finds
 * it by everything that finds those converters.
 */
private val DocumentsCard = HomeCard(
    null,
    Icons.Rounded.Description,
    R.string.tool_documents,
    R.string.tool_documents_detail,
    keywords = R.string.search_keywords_word,
    alsoFoundAs = listOf(
        PdfTool.WORD,
        PdfTool.POWERPOINT,
        PdfTool.EXCEL,
        PdfTool.EPUB,
    ),
)

/** The tools under "Organize" on Home, in order: those that change which pages there are and how they lie. */
private val OrganizeTools = listOf(
    PdfTool.MERGE,
    PdfTool.SPLIT,
    PdfTool.REORGANIZE,
    PdfTool.PAGE_LAYOUT,
)

/** The tools under "Edit and sign" on Home, in order: those that add to the pages. */
private val EditTools = listOf(
    PdfTool.SIGN,
    PdfTool.PAGE_NUMBERS,
    PdfTool.WATERMARK,
)

/** The tools under "Optimize and protect" on Home, in order. */
private val OptimizeTools = listOf(
    PdfTool.COMPRESS,
    PdfTool.REDACT,
    PdfTool.FLATTEN,
    PdfTool.DOCUMENT_INFO,
)

/** The tools under "Convert from PDF" on Home, in order: those that make other files of a PDF. */
private val FromPdfTools = listOf(
    PdfTool.PDF_TO_IMAGES_OR_TEXT,
)

/** The tools that work on PDFs, under their headings, as Home shows them (see [ShareScreen]). */
internal val PdfToolSections = listOf(
    R.string.home_section_view to ViewTools,
    R.string.home_section_organize to OrganizeTools,
    R.string.home_section_edit to EditTools,
    R.string.home_section_optimize to OptimizeTools,
    R.string.home_section_from_pdf to FromPdfTools,
)

/** A tool's name: on its card on Home and at the top of its screen. */
@get:StringRes
internal val PdfTool.title: Int
    get() = when (this) {
        PdfTool.SPLIT -> R.string.tool_split
        PdfTool.MERGE -> R.string.tool_merge
        PdfTool.COMPRESS -> R.string.tool_compress
        PdfTool.PAGE_NUMBERS -> R.string.tool_page_numbers
        PdfTool.WATERMARK -> R.string.tool_watermark
        PdfTool.SIGN -> R.string.tool_sign
        PdfTool.WORD -> R.string.tool_word
        PdfTool.POWERPOINT -> R.string.tool_powerpoint
        PdfTool.EXCEL -> R.string.tool_excel
        PdfTool.REORGANIZE -> R.string.tool_reorganize
        PdfTool.FLATTEN -> R.string.tool_flatten
        PdfTool.EPUB -> R.string.tool_epub
        PdfTool.REDACT -> R.string.tool_redact
        PdfTool.PDF_TO_IMAGES_OR_TEXT -> R.string.tool_pdf_to_images_or_text
        PdfTool.PAGE_LAYOUT -> R.string.tool_page_layout
        PdfTool.DOCUMENT_INFO -> R.string.tool_document_info
        PdfTool.READER -> R.string.tool_reader
    }

/** What a tool does, in a short line under its name on Home. */
@get:StringRes
internal val PdfTool.detail: Int
    get() = when (this) {
        PdfTool.SPLIT -> R.string.tool_split_detail
        PdfTool.MERGE -> R.string.tool_merge_detail
        PdfTool.COMPRESS -> R.string.tool_compress_detail
        PdfTool.PAGE_NUMBERS -> R.string.tool_page_numbers_detail
        PdfTool.WATERMARK -> R.string.tool_watermark_detail
        PdfTool.SIGN -> R.string.tool_sign_detail
        PdfTool.WORD -> R.string.tool_word_detail
        PdfTool.POWERPOINT -> R.string.tool_powerpoint_detail
        PdfTool.EXCEL -> R.string.tool_excel_detail
        PdfTool.REORGANIZE -> R.string.tool_reorganize_detail
        PdfTool.FLATTEN -> R.string.tool_flatten_detail
        PdfTool.EPUB -> R.string.tool_epub_detail
        PdfTool.REDACT -> R.string.tool_redact_detail
        PdfTool.PDF_TO_IMAGES_OR_TEXT -> R.string.tool_pdf_to_images_or_text_detail
        PdfTool.PAGE_LAYOUT -> R.string.tool_page_layout_detail
        PdfTool.DOCUMENT_INFO -> R.string.tool_document_info_detail
        PdfTool.READER -> R.string.tool_reader_detail
    }

internal val PdfTool.icon: ImageVector
    get() = when (this) {
        PdfTool.SPLIT -> Icons.Rounded.Splitscreen
        PdfTool.MERGE -> Icons.Rounded.Collections
        PdfTool.COMPRESS -> Icons.Rounded.Compress
        PdfTool.PAGE_NUMBERS -> Icons.Rounded.FormatListNumbered
        PdfTool.WATERMARK -> Icons.AutoMirrored.Rounded.BrandingWatermark
        PdfTool.SIGN -> Icons.Rounded.Draw
        PdfTool.WORD -> Icons.Rounded.Description
        PdfTool.POWERPOINT -> Icons.Rounded.Slideshow
        PdfTool.EXCEL -> Icons.Rounded.TableChart
        PdfTool.REORGANIZE -> Icons.Rounded.GridView
        PdfTool.FLATTEN -> Icons.Rounded.Layers
        PdfTool.EPUB -> Icons.Rounded.AutoStories
        PdfTool.REDACT -> Icons.Rounded.VisibilityOff
        PdfTool.PDF_TO_IMAGES_OR_TEXT -> Icons.Rounded.Image
        PdfTool.PAGE_LAYOUT -> Icons.AutoMirrored.Rounded.ViewQuilt
        PdfTool.DOCUMENT_INFO -> Icons.Rounded.Info
        PdfTool.READER -> Icons.AutoMirrored.Rounded.MenuBook
    }

/** Hidden words, separated by commas, that also find a tool in Home's search. */
@get:StringRes
private val PdfTool.keywords: Int
    get() = when (this) {
        PdfTool.SPLIT -> R.string.search_keywords_split
        PdfTool.MERGE -> R.string.search_keywords_merge
        PdfTool.COMPRESS -> R.string.search_keywords_compress
        PdfTool.PAGE_NUMBERS -> R.string.search_keywords_page_numbers
        PdfTool.WATERMARK -> R.string.search_keywords_watermark
        PdfTool.SIGN -> R.string.search_keywords_sign
        PdfTool.WORD -> R.string.search_keywords_word
        PdfTool.POWERPOINT -> R.string.search_keywords_powerpoint
        PdfTool.EXCEL -> R.string.search_keywords_excel
        PdfTool.REORGANIZE -> R.string.search_keywords_reorganize
        PdfTool.FLATTEN -> R.string.search_keywords_flatten
        PdfTool.EPUB -> R.string.search_keywords_epub
        PdfTool.REDACT -> R.string.search_keywords_redact
        PdfTool.PDF_TO_IMAGES_OR_TEXT -> R.string.search_keywords_pdf_to_images_or_text
        PdfTool.PAGE_LAYOUT -> R.string.search_keywords_page_layout
        PdfTool.DOCUMENT_INFO -> R.string.search_keywords_document_info
        PdfTool.READER -> R.string.search_keywords_reader
    }

/**
 * A card on Home: Images to PDF, Documents to PDF or Scan document, which have no [tool], or one of
 * the PDF tools. The search also finds it by the texts of the tools it stands for, [alsoFoundAs]:
 * their names as its name, their descriptions and keywords as its other text; and by the texts
 * [alsoFoundBy] (string resources), as its other text, never its name.
 */
private class HomeCard(
    val tool: PdfTool?,
    val icon: ImageVector,
    @param:StringRes val title: Int,
    @param:StringRes val detail: Int,
    @param:StringRes val keywords: Int,
    val alsoFoundAs: List<PdfTool> = emptyList(),
    val alsoFoundBy: List<Int> = emptyList(),
)

/**
 * Edit PDF's name, description and hidden words: Edit PDF, a tool until 2.0.0, is the reader's
 * Annotate, so what found it finds Read and annotate, in every language.
 */
private val EditPdfWords = listOf(
    R.string.tool_edit,
    R.string.tool_edit_detail,
    R.string.search_keywords_edit,
)

private fun cards(tools: List<PdfTool>) = tools.map {
    HomeCard(it, it.icon, it.title, it.detail, it.keywords, alsoFoundBy = if (it == PdfTool.READER) EditPdfWords else emptyList())
}

/** Scan document, as the search finds it: it shows with its two buttons, as at the top of Home. */
private val ScanCard = HomeCard(null, Icons.Rounded.DocumentScanner, R.string.scan_title, R.string.scan_detail, R.string.search_keywords_scan)

private class HomeSection(@param:StringRes val heading: Int, val cards: List<HomeCard>)

/** Home's sections in order, each a heading over its cards. The search looks through these same cards. */
private val HomeSections = listOf(
    HomeSection(R.string.home_section_view, cards(ViewTools)),
    HomeSection(
        R.string.home_section_create,
        listOf(
            HomeCard(null, Icons.Rounded.PictureAsPdf, R.string.tool_images, R.string.tool_images_detail, R.string.search_keywords_images),
            DocumentsCard,
        ),
    ),
    HomeSection(R.string.home_section_organize, cards(OrganizeTools)),
    HomeSection(R.string.home_section_edit, cards(EditTools)),
    HomeSection(R.string.home_section_optimize, cards(OptimizeTools)),
    HomeSection(R.string.home_section_from_pdf, cards(FromPdfTools)),
)

/**
 * Home: Scan document at the top, whose two buttons take a photo ([onScanPhoto]) or pick images
 * ([onScanUpload]) to make pages of, and a quieter one scans an ID card's two sides onto one page
 * ([onScanCard]), then the files saved last ([recentFiles], if any), then the tools in their
 * sections, and a search field above them. Documents to PDF picks files ([onDocuments]), and Read
 * and annotate a file to open in the reader ([onOpenFile]).
 * While something is typed, what it finds (Scan document too) shows in one list instead, without the
 * sections, and Scan document and the files leave the top.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    snackbar: SnackbarHostState,
    query: String,
    onQueryChange: (String) -> Unit,
    onScanPhoto: () -> Unit,
    onScanUpload: () -> Unit,
    onScanCard: () -> Unit,
    recentFiles: List<RecentFile>,
    recentActions: RecentFileActions,
    onAllRecentFiles: () -> Unit,
    onImages: () -> Unit,
    onDocuments: () -> Unit,
    onTool: (PdfTool) -> Unit,
    onSettings: () -> Unit,
    onOpenFile: () -> Unit,
) {
    // The bar takes a tint of its own once the page scrolls under it.
    val barScroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(barScroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                scrollBehavior = barScroll,
                title = { BarTitle(stringResource(R.string.home_title)) },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
            )
        },
        // Messages can arrive on any screen, such as about shared images Usugami can't use. One with
        // nowhere to show would wait, and show up later on another screen.
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val open = { card: HomeCard ->
            when {
                card === DocumentsCard -> onDocuments()
                card.tool == null -> onImages()
                card.tool == PdfTool.READER -> onOpenFile()
                else -> onTool(card.tool)
            }
        }
        // The list ends above the keyboard, so every tool found can be scrolled to while typing.
        Column(
            Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Text(stringResource(R.string.home_subtitle), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            // While searching, it shows among what is found, if it is.
            AnimatedVisibility(
                visible = query.isBlank(),
                enter = fadeIn(Motion.medium()) + expandVertically(Motion.medium()),
                exit = fadeOut(Motion.medium()) + shrinkVertically(Motion.medium()),
                label = "scan card",
            ) {
                ScanDocumentCard(onScanPhoto, onScanUpload, onScanCard, Modifier.padding(top = 16.dp))
            }
            AnimatedVisibility(
                visible = query.isBlank() && recentFiles.isNotEmpty(),
                enter = fadeIn(Motion.medium()) + expandVertically(Motion.medium()),
                exit = fadeOut(Motion.medium()) + shrinkVertically(Motion.medium()),
                label = "recent files",
            ) {
                // The last file removed fades out as it was.
                RecentFilesSection(freezeWhen(recentFiles.isEmpty(), recentFiles), recentActions, onAllRecentFiles)
            }
            SearchField(query, onQueryChange, Modifier.padding(top = 16.dp))
            Crossfade(targetState = query.isNotBlank(), animationSpec = Motion.short(), label = "homeSearch") { searching ->
                if (searching) {
                    // Once the search is cleared, the results fade out as they were (not as "no tools
                    // match", which a screen reader would also read out).
                    SearchResults(freezeWhen(query.isBlank(), query), open) { ScanDocumentCard(onScanPhoto, onScanUpload, onScanCard) }
                } else {
                    // To add a tool, add its card to its section.
                    Column {
                        for (section in HomeSections) {
                            ToolSection(section.heading) {
                                for (card in section.cards) ToolCard(card.icon, card.title, card.detail) { open(card) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The files saved last, newest first, under a heading with See all, which opens the Recent files
 * screen with every file on the list.
 */
@Composable
private fun RecentFilesSection(files: List<RecentFile>, actions: RecentFileActions, onSeeAll: () -> Unit) {
    // The file whose menu is open, kept when the activity is recreated.
    val menu = rememberSaveable { mutableStateOf<String?>(null) }
    val today = rememberToday()
    Column {
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.recent_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
            TextButton(onClick = onSeeAll) { Text(stringResource(R.string.recent_see_all)) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (file in files.take(HOME_RECENT_FILES)) {
                key(file.uri) { RecentFileRow(file, today, menu, actions) }
            }
        }
    }
}

/** How many of the files saved last Home shows. */
private const val HOME_RECENT_FILES = 3

/** A rounded search field, like Material's search bar. The keyboard's search key just closes the keyboard. */
@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(stringResource(R.string.home_search_hint)) },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.home_search_clear))
                }
            }
        } else null,
        singleLine = true,
        shape = CircleShape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = {
            keyboard?.hide()
            focus.clearFocus()
        }),
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * The tools [query] finds, in one list without the sections, under a line saying how many there are
 * (or that there are none), which a screen reader reads out as the user types. Scan document shows
 * as [scanCard] when it is found.
 */
@Composable
private fun SearchResults(query: String, open: (HomeCard) -> Unit, scanCard: @Composable () -> Unit) {
    val cards = listOf(ScanCard) + HomeSections.flatMap { it.cards }
    // Each card's name, then what it says it does and its hidden keywords, in the screens' language,
    // with those of the tools it stands for. The tools that take several files at once are also
    // found by words such as "batch".
    val batch = stringResource(R.string.search_keywords_batch)
    val texts = cards.associateWith { card ->
        val names = listOf(stringResource(card.title)) + card.alsoFoundAs.map { stringResource(it.title) }
        val more = listOf(stringResource(card.detail), stringResource(card.keywords)) +
            card.alsoFoundAs.flatMap { listOf(stringResource(it.detail), stringResource(it.keywords)) } +
            // Edit PDF, a tool until 2.0.0, is the reader's Annotate: what found it finds the reader, in every language.
            card.alsoFoundBy.map { stringResource(it) } +
            if (card.tool?.isBatch == true || card.alsoFoundAs.any { it.isBatch }) listOf(batch) else emptyList()
        names.joinToString(" ") to more.joinToString(" ")
    }
    val found = remember(query, texts) {
        ToolSearch.search(cards, query, name = { texts.getValue(it).first }, moreText = { texts.getValue(it).second })
    }
    Column {
        // One line for both, so a screen reader hears each change of it.
        val none = found.isEmpty()
        Text(
            if (none) stringResource(R.string.home_search_none, query.trim()) else pluralStringResource(R.plurals.home_search_results, found.size, found.size),
            style = if (none) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleSmall,
            color = if (none) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(top = if (none) 24.dp else 18.dp, bottom = 8.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (card in found) {
                if (card === ScanCard) scanCard() else ToolCard(card.icon, card.title, card.detail) { open(card) }
            }
        }
    }
}

/**
 * Scan document: a card larger than the tools', in the theme's main colour, with a button that takes
 * a photo and one that picks images, each starting at once, and under them a quieter one that scans
 * an ID card's front and back ([onIdCard]).
 */
@Composable
private fun ScanDocumentCard(onTakePhoto: () -> Unit, onUpload: () -> Unit, onIdCard: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.DocumentScanner, contentDescription = null, modifier = Modifier.size(48.dp))
                Spacer(Modifier.width(16.dp))
                // One item for a screen reader, a heading as the tools' sections have, before the buttons.
                Column(Modifier.semantics(mergeDescendants = true) { heading() }) {
                    Text(stringResource(R.string.scan_title), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.scan_detail), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(16.dp))
            // Side by side, as tall as each other when a long label takes two lines. A word too wide for
            // its button (in German on a narrow phone) makes both labels a little smaller, one size,
            // rather than breaking; set here, not by auto-size, so the buttons are as tall as the labels
            // shown. Where they would get smaller than ScanLabelSmallest (German with a large font size),
            // the buttons go one above the other instead, each across the card, at the font size chosen.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val photo = stringResource(R.string.add_from_camera)
                val upload = stringResource(R.string.scan_upload)
                val labels = listOf(photo, upload)
                val style = MaterialTheme.typography.labelLarge
                val beside = wholeWordsFontSize(labels, style, (maxWidth - ScanButtonGap) / 2 - ScanButtonChrome)
                val stacked = beside < ScanLabelSmallest
                val size = if (stacked) wholeWordsFontSize(labels, style, maxWidth - ScanButtonChrome) else beside
                val labelStyle = style.withEmLineHeight().copy(fontSize = size)
                val takePhoto: @Composable (Modifier) -> Unit = { modifier ->
                    Button(onClick = onTakePhoto, modifier = modifier, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                        Icon(Icons.Rounded.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(photo, style = labelStyle, textAlign = TextAlign.Center, autoSize = WholeWordsAutoSize(size))
                    }
                }
                val pick: @Composable (Modifier) -> Unit = { modifier ->
                    OutlinedButton(onClick = onUpload, modifier = modifier, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                        Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(upload, style = labelStyle, textAlign = TextAlign.Center, autoSize = WholeWordsAutoSize(size))
                    }
                }
                if (stacked) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        takePhoto(Modifier.fillMaxWidth())
                        pick(Modifier.fillMaxWidth())
                    }
                } else {
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(ScanButtonGap)) {
                        takePhoto(Modifier.weight(1f).fillMaxHeight())
                        pick(Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            // Quieter than the two above, in the card's own colour.
            TextButton(
                onClick = onIdCard,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                contentPadding = ButtonDefaults.TextButtonWithIconContentPadding,
            ) {
                Icon(Icons.Rounded.Badge, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.scan_id_card), textAlign = TextAlign.Center)
            }
        }
    }
}

/** Between the Scan document card's two buttons. */
private val ScanButtonGap = 12.dp

/** A Scan document button's width besides its label: its padding (16 and 24 dp), icon and the gap after it. */
private val ScanButtonChrome = 16.dp + 24.dp + 18.dp + 8.dp

/**
 * The smallest the Scan document buttons' labels get side by side, as small as the app's other labels
 * that shrink to fit get: a word that would need smaller puts the buttons one above the other.
 */
private val ScanLabelSmallest = 11.sp

/** A small heading, which a screen reader can jump to, over a group of tool cards. */
@Composable
internal fun ToolSection(@StringRes heading: Int, cards: @Composable ColumnScope.() -> Unit) {
    Text(
        stringResource(heading),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp).semantics { heading() },
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), content = cards)
}

@Composable
internal fun ToolCard(icon: ImageVector, @StringRes title: Int, @StringRes detail: Int, onClick: () -> Unit) {
    // The card sinks back slightly while it is held down, so the tap feels physical.
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        animationSpec = Motion.short(),
        label = "toolCardScale",
    )
    OutlinedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
        interactionSource = interactionSource,
    ) {
        // Compact, so more of the tools fit on a screen.
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(16.dp))
            Column {
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(detail), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
