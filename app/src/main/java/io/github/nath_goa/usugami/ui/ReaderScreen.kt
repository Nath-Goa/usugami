package io.github.nath_goa.usugami.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.core.ReaderGeometry

/** What the reader's buttons do outside the screen. */
internal class ReaderActions(
    /** Leaves the reader, as Back does. */
    val back: () -> Unit,
)

/**
 * The reader: the tool slot's PDF in one scroll, with the page showing counted at the bottom.
 * [receiving]: a file opened is being copied in, or waits to be shown. While the file comes in,
 * "Opening…" shows with Cancel, which leaves as Back does; a PDF with a password asks for it, and
 * Cancel there leaves too.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderScreen(vm: PdfToolsViewModel, receiving: Boolean, snackbar: SnackbarHostState, actions: ReaderActions) {
    val inputs by vm.inputs.collectAsStateWithLifecycle()
    val locked by vm.locked.collectAsStateWithLifecycle()
    val documents by vm.documents.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val unlocking by vm.unlocking.collectAsStateWithLifecycle()
    val wrongPassword by vm.wrongPassword.collectAsStateWithLifecycle()
    val session by vm.reader.session.collectAsStateWithLifecycle()
    // Kept while the result shows and through recreation (the screen's state sits with the tool's).
    val list = rememberLazyListState()
    val input = inputs.firstOrNull()
    LaunchedEffect(input?.id) { input?.let(vm.reader::show) }
    // Leaving for Choose a tool closes the session while the PDF stays in the slot: the screen sliding
    // away keeps the pages it showed rather than "Opening…".
    val live = session?.takeIf { it.id == input?.id }
    val shown = freezeWhen(live == null && input != null, live)?.takeIf { it.id == input?.id }
    val opening = (inputs.isEmpty() && locked.isEmpty() && documents.isEmpty() && (receiving || importing)) || (input != null && shown == null)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { BarTitle(input?.name ?: stringResource(R.string.tool_reader)) },
                navigationIcon = { IconButton(onClick = actions.back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back)) } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val direction = LocalLayoutDirection.current
        // The pages run under the navigation bar, and clear it at their end.
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding(), start = padding.calculateStartPadding(direction), end = padding.calculateEndPadding(direction)),
        ) {
            when {
                shown != null -> {
                    ReaderPages(shown, list)
                    PageIndicator(list, shown.document.pageCount, Modifier.align(Alignment.BottomCenter))
                }
                opening -> Opening(onCancel = actions.back, modifier = Modifier.align(Alignment.Center))
            }
        }
    }
    locked.firstOrNull()?.let { pending ->
        PasswordDialog(
            input = pending,
            wrongPassword = wrongPassword,
            unlocking = unlocking,
            onUnlock = { vm.unlock(pending, it) },
            onEdit = vm::clearWrongPassword,
            // Cancel puts the copy away and leaves the reader as Back does: for a file another app
            // opened, back to that app rather than by way of Home.
            onDismiss = {
                vm.dismissPassword(pending)
                actions.back()
            },
        )
    }
}

/** "Opening…" with a moving bar, while a file opened is copied in and read, and Cancel. */
@Composable
private fun Opening(onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .widthIn(max = 360.dp)
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.reader_opening), style = MaterialTheme.typography.titleMedium)
        LinearProgressIndicator(Modifier.fillMaxWidth())
        TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
    }
}

/** The page showing, of [pageCount]: "3 of 12", heard as "Page 3 of 12". */
@Composable
private fun PageIndicator(list: LazyListState, pageCount: Int, modifier: Modifier = Modifier) {
    val page by remember(list) {
        derivedStateOf {
            val info = list.layoutInfo
            val items = info.visibleItemsInfo.map { ReaderGeometry.Item(it.index, it.offset - info.viewportStartOffset, it.size) }
            ReaderGeometry.pageInView(items, info.viewportEndOffset - info.viewportStartOffset) ?: list.firstVisibleItemIndex
        }
    }
    val number = (page + 1).coerceIn(1, maxOf(1, pageCount))
    val spoken = stringResource(R.string.reader_page_of_spoken, number, pageCount)
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        modifier = modifier
            .navigationBarsPadding()
            .padding(bottom = 12.dp),
    ) {
        Text(
            stringResource(R.string.reader_page_of, number, pageCount),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .semantics { contentDescription = spoken },
        )
    }
}
