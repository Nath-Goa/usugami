package io.github.nath_goa.usugami.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Policy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.nath_goa.usugami.BuildConfig
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.core.TextBlock
import io.github.nath_goa.usugami.data.Edition
import io.github.nath_goa.usugami.data.LegalDocument
import io.github.nath_goa.usugami.ui.kit.DividedColumn
import io.github.nath_goa.usugami.ui.kit.GlyphButton
import io.github.nath_goa.usugami.ui.kit.Sheet
import io.github.nath_goa.usugami.ui.kit.SheetRow
import io.github.nath_goa.usugami.ui.kit.Space
import io.github.nath_goa.usugami.ui.kit.UsugamiTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where Usugami's source code is published. Its Sponsor button takes donations (see DonateScreen). */
const val SOURCE_CODE_URL = "https://github.com/Nath-Goa/usugami"

/** The app's version and edition, what it does with data, and its license, notices and source code. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onDocument: (LegalDocument) -> Unit,
    onSourceCode: () -> Unit,
) {
    // A hairline shows under the bar once the page scrolls under it.
    val barScroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(barScroll.nestedScrollConnection),
        topBar = {
            UsugamiTopBar(
                scrollBehavior = barScroll,
                title = { BarTitle(stringResource(R.string.about_title)) },
                navigationIcon = {
                    GlyphButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        // The padding goes inside the scrolling area, so the page scrolls behind the navigation bar.
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = Space.content).fillMaxWidth()) {
                AboutHeader()
                Spacer(Modifier.height(Space.gap))
                PrivacyNote()
                Spacer(Modifier.height(Space.gap))
                // The rows lay on the ground in 2.0.1: their sheet is drawn, and is no node of its own.
                Sheet(Modifier.fillMaxWidth(), group = false) {
                    DividedColumn {
                        ListRow(Icons.Rounded.Policy, stringResource(R.string.about_privacy_policy)) { onDocument(LegalDocument.PRIVACY) }
                        ListRow(Icons.AutoMirrored.Rounded.LibraryBooks, stringResource(R.string.about_licenses), stringResource(R.string.about_licenses_detail)) {
                            onDocument(LegalDocument.NOTICES)
                        }
                        ListRow(Icons.Rounded.Gavel, stringResource(R.string.about_license), stringResource(R.string.about_license_detail)) {
                            onDocument(LegalDocument.LICENSE)
                        }
                        ListRow(Icons.Rounded.Code, stringResource(R.string.about_source), stringResource(R.string.about_source_detail), external = true, onClick = onSourceCode)
                    }
                }
                Text(
                    stringResource(R.string.about_copyright),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                )
            }
        }
    }
}

@Composable
private fun AboutHeader() {
    Column(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(horizontal = 8.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppIcon()
        Spacer(Modifier.height(16.dp))
        Text(stringResource(Edition.current.title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(Edition.current.description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** The launcher icon: its background color with its artwork, cropped to the part a launcher shows. */
@Composable
internal fun AppIcon() {
    Box(
        Modifier.size(72.dp).clip(RoundedCornerShape(20.dp)).background(colorResource(R.color.brand)),
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.requiredSize(108.dp))
    }
}

@Composable
private fun PrivacyNote(modifier: Modifier = Modifier) {
    // In place of 2.0.1's surface, read as a group as that was.
    Sheet(modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp)) {
            Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.about_privacy), style = MaterialTheme.typography.bodyMedium)
                if (Edition.current == Edition.STANDARD) {
                    Text(
                        stringResource(R.string.about_privacy_standard),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * A row of a sheet laid out like a Material list item (a plain row keeps Material's ListItem out of
 * the APK), used by About and Settings. [external] adds an icon saying it opens another app.
 */
@Composable
internal fun ListRow(icon: ImageVector, title: String, detail: String? = null, external: Boolean = false, onClick: () -> Unit) {
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    SheetRow(
        icon = icon,
        onClick = onClick,
        trailing = if (external) {
            {
                Spacer(Modifier.width(16.dp))
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(20.dp), tint = quiet)
            }
        } else {
            null
        },
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = quiet)
    }
}

private sealed interface DocumentContent {
    data object Loading : DocumentContent
    data object Missing : DocumentContent
    class Ready(val blocks: List<TextBlock>) : DocumentContent
}

/** Shows one of the bundled legal texts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegalDocumentScreen(document: LegalDocument, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val assets = LocalContext.current.assets
    // Read off the main thread: the GPL alone is about 35 KB.
    val content by produceState<DocumentContent>(DocumentContent.Loading, document) {
        value = withContext(Dispatchers.IO) { document.load(assets) }?.let(DocumentContent::Ready) ?: DocumentContent.Missing
    }
    // Out here, not in the Crossfade, so the reading position survives rotation: the Crossfade keys
    // its content by the Ready instance, and a recreated screen reads the document into a new one.
    val listState = rememberLazyListState()
    // A hairline shows under the bar once the page scrolls under it.
    val barScroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(barScroll.nestedScrollConnection),
        topBar = {
            UsugamiTopBar(
                scrollBehavior = barScroll,
                title = { BarTitle(stringResource(document.title)) },
                navigationIcon = {
                    GlyphButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        // As on every screen: a message with nowhere to show would wait, and show up later elsewhere.
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Crossfade(content, animationSpec = Motion.medium(), label = "document") { state ->
            when (state) {
                DocumentContent.Loading -> Box(Modifier.fillMaxSize())
                DocumentContent.Missing -> Text(
                    stringResource(R.string.about_document_missing),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(padding).padding(start = TextSide, top = 8.dp, end = TextSide, bottom = 16.dp),
                )
                is DocumentContent.Ready -> DocumentText(state.blocks, padding, listState)
            }
        }
    }
}

@Composable
private fun DocumentText(blocks: List<TextBlock>, padding: PaddingValues, listState: LazyListState) {
    val layoutDirection = LocalLayoutDirection.current
    // A lazy list, so only the paragraphs on screen are laid out. The list is as wide as the screen,
    // so it scrolls from anywhere on it; the text in it is centred and at most as wide as any content.
    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(
            start = padding.calculateStartPadding(layoutDirection) + TextSide,
            top = padding.calculateTopPadding() + 8.dp,
            end = padding.calculateEndPadding(layoutDirection) + TextSide,
            bottom = padding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        itemsIndexed(blocks, contentType = { _, block -> block::class }) { index, block ->
            DocumentBlock(block, first = index == 0, Modifier.widthIn(max = TextWidth).fillMaxWidth())
        }
    }
}

/** The legal text lies on the ground: the screen margin and the inset of text on the ground (16 + 8). */
private val TextSide = Space.screen + Space.ground

/** The widest the legal text runs: the widest content less the inset of text on the ground at each side (560 − 2 × 8). */
private val TextWidth = Space.content - Space.ground * 2

@Composable
private fun DocumentBlock(block: TextBlock, first: Boolean, modifier: Modifier = Modifier) {
    val text = remember(block) { styled(block) }
    val body = MaterialTheme.typography.bodyMedium
    when (block) {
        is TextBlock.Heading -> Text(
            text,
            style = when (block.level) {
                1 -> MaterialTheme.typography.headlineSmall
                2 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            },
            modifier = modifier.padding(top = if (first) 0.dp else 12.dp).semantics { heading() },
        )
        is TextBlock.Paragraph -> Text(text, style = body, modifier = modifier)
        is TextBlock.ListItem -> Row(modifier.padding(start = 16.dp * block.depth).semantics(mergeDescendants = true) {}) {
            // A bullet says nothing to a screen reader; a number does.
            val marker = if (block.marker == "•") Modifier.clearAndSetSemantics {} else Modifier
            Text(block.marker, style = body, modifier = marker.widthIn(min = 20.dp).padding(end = 4.dp))
            Text(text, style = body, modifier = Modifier.weight(1f))
        }
    }
}

private fun styled(block: TextBlock): AnnotatedString = buildAnnotatedString {
    append(block.text)
    for (range in block.bold) addStyle(SpanStyle(fontWeight = FontWeight.Bold), range.first, range.last + 1)
}
