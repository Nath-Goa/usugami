package io.github.nath_goa.usugami.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.core.ReaderGeometry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** What the reader's buttons do outside the screen. */
internal class ReaderActions(
    /** Leaves the reader, as Back does. */
    val back: () -> Unit,
)

/**
 * The reader: the tool slot's PDF in one scroll, zoomed by pinching or double-tapping (see
 * ReaderPages), with the page showing counted at the bottom; tapping the count opens Go to page.
 * Find text turns the top bar into a search bar, which searches from the page showing when the
 * keyboard's search key is pressed and moves between the matches; closing it (its arrow, or Back)
 * takes the matches away. [receiving]: a file opened is being copied in, or waits to be shown. While
 * the file comes in, "Opening…" shows with Cancel, which leaves as Back does; a PDF with a password
 * asks for it, and Cancel there leaves too.
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
    // Find text: whether the search bar shows, what is typed in it, and what was searched for last (searched
    // again after Android closed Usugami: a search isn't kept in the saved state).
    var finding by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var searched by rememberSaveable { mutableStateOf("") }
    // Whether Find text was tapped since this screen was made: only then does the field take the focus and
    // bring up the keyboard, not when the bar shows again after rotation, over what was found.
    var typing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val input = inputs.firstOrNull()
    LaunchedEffect(input?.id) { input?.let(vm.reader::show) }
    // Leaving for Choose a tool closes the session while the PDF stays in the slot: the screen sliding
    // away keeps the pages it showed rather than "Opening…".
    val live = session?.takeIf { it.id == input?.id }
    val shown = freezeWhen(live == null && input != null, live)?.takeIf { it.id == input?.id }
    val opening = (inputs.isEmpty() && locked.isEmpty() && documents.isEmpty() && (receiving || importing)) || (input != null && shown == null)
    val found by remember(shown) { shown?.found ?: MutableStateFlow(Found()) }.collectAsStateWithLifecycle()
    // The search again, from the page showing, once the PDF shows again after Android closed Usugami.
    LaunchedEffect(live) {
        if (finding && searched.isNotBlank() && live != null && live.found.value.query != searched) live.find(searched, pageShowing(list, wentTo))
    }
    val keyboard = LocalSoftwareKeyboardController.current
    fun closeSearch() {
        finding = false
        typing = false
        query = ""
        searched = ""
        shown?.stopFinding()
    }
    // Only while the search bar shows: while the PDF is opened again, Back leaves as it does then.
    BackHandler(enabled = finding && shown != null) { closeSearch() }
    Scaffold(
        topBar = {
            if (finding && shown != null) {
                SearchBar(
                    query = query,
                    onQuery = { query = it },
                    onSearch = {
                        searched = query
                        shown.find(query, pageShowing(list, wentTo))
                        // The pages, not the keyboard, show what was found.
                        if (query.isNotBlank()) keyboard?.hide()
                    },
                    found = found,
                    onStep = shown::step,
                    onClose = ::closeSearch,
                    takeFocus = typing,
                )
            } else {
                TopAppBar(
                    title = { BarTitle(input?.name ?: stringResource(R.string.tool_reader)) },
                    navigationIcon = { IconButton(onClick = actions.back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back)) } },
                    actions = {
                        if (shown != null) {
                            IconButton(onClick = {
                                finding = true
                                typing = true
                            }) { Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.redact_find)) }
                        }
                    },
                )
            }
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
                        found = found,
                    )
                    PageIndicator(list, shown.document.pageCount, wentTo, onClick = { goingToPage = true }, modifier = Modifier.align(Alignment.BottomCenter))
                    // How far the search has got, under the search bar: over the pages, so they don't move.
                    if (finding && found.searching) {
                        LinearProgressIndicator(
                            progress = { found.pagesDone.toFloat() / maxOf(1, shown.document.pageCount) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp)
                                .align(Alignment.TopCenter),
                        )
                    }
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
 * The page showing (0 for the first), as the page indicator says it: the one that fills most of the
 * screen, or the page Go to page went to ([wentTo]: that page, and the list's first item and offset
 * it left) while the list stays there. Before the pages are laid out, the list's first item.
 */
private fun pageShowing(list: LazyListState, wentTo: IntArray?): Int {
    val info = list.layoutInfo
    val items = info.visibleItemsInfo.map { ReaderGeometry.Item(it.index, it.offset - info.viewportStartOffset, it.size) }
    return wentTo?.takeIf { it[1] == list.firstVisibleItemIndex && it[2] == list.firstVisibleItemScrollOffset }?.get(0)
        ?: ReaderGeometry.pageInView(items, info.viewportEndOffset - info.viewportStartOffset)
        ?: list.firstVisibleItemIndex
}

/**
 * Find text's bar, in place of the reader's: Close search, the field ([query], searched for with the
 * keyboard's search key, [onSearch]) and, once something is found, "3 of 17" (heard as "Match 3 of 17",
 * and said again as it changes) with Previous match and Next match ([onStep] -1 and 1). With [takeFocus],
 * the field takes the focus, and the keyboard comes up, as the bar opens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit, onSearch: () -> Unit, found: Found, onStep: (Int) -> Unit, onClose: () -> Unit, takeFocus: Boolean) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (takeFocus) focus.requestFocus() }
    TopAppBar(
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.reader_find_close)) } },
        title = {
            TextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text(stringResource(R.string.reader_find_hint), maxLines = 1) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
            )
        },
        actions = {
            val count = found.matches.size
            if (count > 0 && found.current >= 0) {
                val spoken = stringResource(R.string.reader_find_count_spoken, found.current + 1, count)
                Text(
                    stringResource(R.string.reader_find_count, found.current + 1, count),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .semantics {
                            contentDescription = spoken
                            liveRegion = LiveRegionMode.Polite
                        },
                )
                IconButton(onClick = { onStep(-1) }) { Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = stringResource(R.string.reader_find_previous)) }
                IconButton(onClick = { onStep(1) }) { Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = stringResource(R.string.reader_find_next)) }
            }
        },
    )
}

/**
 * The page showing, of [pageCount]: "3 of 12", heard as "Page 3 of 12". Tapped, it opens Go to page
 * ([onClick]). After Go to page, the page it went to while the list stays where it left it ([wentTo]:
 * the page, the list's first item and its offset).
 */
@Composable
private fun PageIndicator(list: LazyListState, pageCount: Int, wentTo: IntArray?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val asked by rememberUpdatedState(wentTo)
    val page by remember(list) { derivedStateOf { pageShowing(list, asked) } }
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
