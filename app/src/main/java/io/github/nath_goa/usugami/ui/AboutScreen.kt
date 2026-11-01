package io.github.nath_goa.usugami.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import io.github.nath_goa.usugami.data.LegalDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where Usugami's source code is published. */
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
    // The bar takes a tint of its own once the page scrolls under it.
    val barScroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(barScroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                scrollBehavior = barScroll,
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        // The padding goes inside the scrolling area, so the page scrolls behind the navigation bar.
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(vertical = 8.dp)) {
            AboutHeader()
            PrivacyNote(Modifier.padding(16.dp))
            ListRow(Icons.Rounded.Policy, stringResource(R.string.about_privacy_policy)) { onDocument(LegalDocument.PRIVACY) }
            ListRow(Icons.AutoMirrored.Rounded.LibraryBooks, stringResource(R.string.about_licenses), stringResource(R.string.about_licenses_detail)) {
                onDocument(LegalDocument.NOTICES)
            }
            ListRow(Icons.Rounded.Gavel, stringResource(R.string.about_license), stringResource(R.string.about_license_detail)) {
                onDocument(LegalDocument.LICENSE)
            }
            ListRow(Icons.Rounded.Code, stringResource(R.string.about_source), stringResource(R.string.about_source_detail), external = true, onClick = onSourceCode)
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

@Composable
private fun AboutHeader() {
    Column(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppIcon()
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(if (BuildConfig.OCR_BUNDLED) R.string.about_edition_offline else R.string.about_edition_standard),
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
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp)) {
            Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.about_privacy), style = MaterialTheme.typography.bodyMedium)
                if (!BuildConfig.OCR_BUNDLED) {
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
 * A list row laid out like a Material list item (a plain Row keeps Material's ListItem out of the
 * APK), used by About and Settings. [external] adds an icon saying it opens another app.
 */
@Composable
internal fun ListRow(icon: ImageVector, title: String, detail: String? = null, external: Boolean = false, onClick: () -> Unit) {
    val iconColor = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = iconColor)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium, color = iconColor)
        }
        if (external) {
            Spacer(Modifier.width(16.dp))
            Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, tint = iconColor)
        }
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
    // The bar takes a tint of its own once the page scrolls under it.
    val barScroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(barScroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                scrollBehavior = barScroll,
                title = { Text(stringResource(document.title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
                    modifier = Modifier.padding(padding).padding(horizontal = 20.dp, vertical = 16.dp),
                )
                is DocumentContent.Ready -> DocumentText(state.blocks, padding, listState)
            }
        }
    }
}

@Composable
private fun DocumentText(blocks: List<TextBlock>, padding: PaddingValues, listState: LazyListState) {
    val layoutDirection = LocalLayoutDirection.current
    // A lazy list, so only the paragraphs on screen are laid out.
    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(
            start = padding.calculateStartPadding(layoutDirection) + 20.dp,
            top = padding.calculateTopPadding() + 8.dp,
            end = padding.calculateEndPadding(layoutDirection) + 20.dp,
            bottom = padding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(blocks, contentType = { _, block -> block::class }) { index, block ->
            DocumentBlock(block, first = index == 0)
        }
    }
}

@Composable
private fun DocumentBlock(block: TextBlock, first: Boolean) {
    val text = remember(block) { styled(block) }
    val body = MaterialTheme.typography.bodyMedium
    when (block) {
        is TextBlock.Heading -> Text(
            text,
            style = when (block.level) {
                1 -> MaterialTheme.typography.titleLarge
                2 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            },
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = if (first) 0.dp else 12.dp).semantics { heading() },
        )
        is TextBlock.Paragraph -> Text(text, style = body)
        is TextBlock.ListItem -> Row(Modifier.padding(start = 16.dp * block.depth).semantics(mergeDescendants = true) {}) {
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
