package io.github.nath_goa.usugami.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.core.ReaderGeometry
import kotlinx.coroutines.launch

/** What the reader's buttons do outside the screen. */
internal class ReaderActions(
    /** Leaves the reader, as Back does. */
    val back: () -> Unit,
)

/**
 * The reader: the tool slot's PDF in one scroll, zoomed by pinching or double-tapping (see
 * ReaderPages), with the page showing counted at the bottom; tapping the count opens Go to page.
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
    // How far the pages are zoomed (1: the screen's width) and moved left (screen pixels), and Go to page.
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    var panX by rememberSaveable { mutableFloatStateOf(0f) }
    var goingToPage by rememberSaveable { mutableStateOf(false) }
    // The page Go to page went to (0 for the first), and the list's first item and offset it left: the
    // indicator says that page while the list stays there, as a page near the end can't come to the top
    // and the one before it may fill more of the screen.
    var wentTo by rememberSaveable { mutableStateOf<IntArray?>(null) }
    val scope = rememberCoroutineScope()
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
                    ReaderPages(
                        session = shown,
                        list = list,
                        zoom = { zoom },
                        panX = { panX },
                        onView = { newZoom, newPan ->
                            zoom = newZoom
                            panX = newPan
                        },
                    )
                    PageIndicator(list, shown.document.pageCount, wentTo, onClick = { goingToPage = true }, modifier = Modifier.align(Alignment.BottomCenter))
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
    // Asked while the pages show; after Android closed Usugami, it comes back once they show again.
    val pageCount = shown?.document?.pageCount
    if (goingToPage && pageCount != null) {
        GoToPageDialog(
            pageCount = pageCount,
            onGo = { page ->
                goingToPage = false
                scope.launch {
                    list.scrollToItem(page - 1)
                    wentTo = intArrayOf(page - 1, list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
                }
            },
            onDismiss = { goingToPage = false },
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

/**
 * The page showing, of [pageCount]: "3 of 12", heard as "Page 3 of 12". Tapped, it opens Go to page
 * ([onClick]). After Go to page, the page it went to while the list stays where it left it ([wentTo]:
 * the page, the list's first item and its offset).
 */
@Composable
private fun PageIndicator(list: LazyListState, pageCount: Int, wentTo: IntArray?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val asked by rememberUpdatedState(wentTo)
    val page by remember(list) {
        derivedStateOf {
            val info = list.layoutInfo
            val items = info.visibleItemsInfo.map { ReaderGeometry.Item(it.index, it.offset - info.viewportStartOffset, it.size) }
            asked?.takeIf { it[1] == list.firstVisibleItemIndex && it[2] == list.firstVisibleItemScrollOffset }?.get(0)
                ?: ReaderGeometry.pageInView(items, info.viewportEndOffset - info.viewportStartOffset)
                ?: list.firstVisibleItemIndex
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
                // A button: a screen reader says "Page 3 of 12, button, double-tap to go to page". Touches a
                // little around it count too (Compose's minimum touch target); the pill's shape clips the ripple.
                .clickable(onClickLabel = stringResource(R.string.reader_go_to_page), role = Role.Button, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .semantics { contentDescription = spoken },
        )
    }
}

/** Go to page: a page number from 1 to [pageCount], and Go, on only while the number is one of them. */
@Composable
private fun GoToPageDialog(pageCount: Int, onGo: (page: Int) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val page = text.toIntOrNull()?.takeIf { it in 1..pageCount }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reader_go_to_page)) },
        text = {
            OutlinedTextField(
                value = text,
                // Digits only, few enough for a number.
                onValueChange = { typed -> text = typed.filter(Char::isDigit).take(9) },
                label = { Text(stringResource(R.string.reader_page_field, pageCount)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { page?.let(onGo) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { page?.let(onGo) }, enabled = page != null) { Text(stringResource(R.string.reader_go)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
