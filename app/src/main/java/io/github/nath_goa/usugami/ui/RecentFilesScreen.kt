package io.github.nath_goa.usugami.ui

import android.content.Context
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.core.OutputType
import io.github.nath_goa.usugami.core.RecentFile
import io.github.nath_goa.usugami.core.ZipArchive
import io.github.nath_goa.usugami.data.PdfSaver
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Date
import java.util.Locale

/** What can be done with a file on the Recent files list, on Home and on its own screen. */
class RecentFileActions(
    val open: (RecentFile) -> Unit,
    val share: (RecentFile) -> Unit,
    val remove: (RecentFile) -> Unit,
    /** Prints a PDF with Android's print screen; null where the phone can't print. */
    val print: ((RecentFile) -> Unit)? = null,
)

/**
 * Recent files: every file on the list, newest first, each opened with a tap and shared or taken off
 * the list from its menu, and Clear list, which asks first. Neither deletes a file.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentFilesScreen(
    files: List<RecentFile>,
    snackbar: SnackbarHostState,
    actions: RecentFileActions,
    onClear: () -> Unit,
    onBack: () -> Unit,
) {
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    // The file whose menu is open: one for the whole screen, so the saved state doesn't grow with the list.
    val menu = rememberSaveable { mutableStateOf<String?>(null) }
    val today = rememberToday()
    // The bar takes a tint of its own once the page scrolls under it.
    val barScroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(barScroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                scrollBehavior = barScroll,
                title = { BarTitle(stringResource(R.string.recent_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (files.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text(stringResource(R.string.recent_clear)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (files.isEmpty()) {
            // Also once the list is cleared: a screen reader hears it.
            Text(
                stringResource(R.string.recent_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(padding)
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 48.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        } else {
            // The padding goes inside the list, so it scrolls behind the navigation bar.
            val direction = LocalLayoutDirection.current
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .consumeWindowInsets(padding),
                contentPadding = PaddingValues(
                    start = padding.calculateStartPadding(direction) + 20.dp,
                    top = padding.calculateTopPadding() + 12.dp,
                    end = padding.calculateEndPadding(direction) + 20.dp,
                    bottom = padding.calculateBottomPadding() + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(files, key = { it.uri }) { file -> RecentFileRow(file, today, menu, actions, Modifier.animateItem()) }
            }
        }
    }

    if (confirmClear && files.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.recent_clear_title)) },
            text = { Text(stringResource(R.string.recent_clear_body)) },
            confirmButton = {
                TextButton(onClick = {
                    // Once: a second tap before the dialog has gone finds it answered.
                    if (confirmClear) {
                        confirmClear = false
                        onClear()
                    }
                }) { Text(stringResource(R.string.recent_clear)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/**
 * A file on the Recent files list: an icon for its kind, its name, and when it was saved ([today] or
 * another day) and its size. A tap opens it; its menu also shares it, prints it (a PDF, on a phone that
 * prints) or takes it off the list. A screen reader hears the row as one item named by all three, with
 * the menu's choices as its actions. [menu] holds the address of the file whose menu is open.
 */
@Composable
internal fun RecentFileRow(
    file: RecentFile,
    today: LocalDate,
    menu: MutableState<String?>,
    actions: RecentFileActions,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val savedAt = remember(file.savedAt, locale, today) { savedWhen(context, locale, file.savedAt, today) }
    val size = file.size.takeIf { it >= 0 }?.let { Formatter.formatShortFileSize(context, it) }
    val details = if (size != null) stringResource(R.string.result_details, savedAt, size) else savedAt
    val label = listOfNotNull(file.name, savedAt, size).joinToString(", ")
    val share = stringResource(R.string.share)
    val print = stringResource(R.string.print)
    val remove = stringResource(R.string.recent_remove)
    // Only a PDF is printed: not a ZIP file, an image or a text file.
    val onPrint = actions.print?.takeIf { file.type == PdfSaver.PDF_MIME }

    // Read when tapped, not when drawn: a second tap before the screen is drawn again finds it closed.
    fun menuOpen() = menu.value == file.uri

    OutlinedCard(
        onClick = { actions.open(file) },
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = label
                customActions = listOfNotNull(
                    CustomAccessibilityAction(share) { actions.share(file); true },
                    onPrint?.let { printFile -> CustomAccessibilityAction(print) { printFile(file); true } },
                    CustomAccessibilityAction(remove) { actions.remove(file); true },
                )
            },
    ) {
        Row(Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                // A ZIP file, or else a PDF, an image or a text file, as its type says.
                if (file.type == ZipArchive.MIME) Icons.Rounded.FolderZip else OutputType.ofMime(file.type).icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.width(16.dp))
            // The row's label says the same.
            Column(
                Modifier
                    .weight(1f)
                    .padding(vertical = 6.dp)
                    .clearAndSetSemantics {},
            ) {
                Text(file.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                // A screen reader has the menu's choices as the row's actions instead.
                IconButton(onClick = { menu.value = file.uri }, modifier = Modifier.clearAndSetSemantics {}) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = null)
                }
                // The items can still be tapped while the menu fades out: only the first tap counts.
                DropdownMenu(expanded = menuOpen(), onDismissRequest = { if (menuOpen()) menu.value = null }) {
                    MenuItem(Icons.AutoMirrored.Rounded.OpenInNew, stringResource(R.string.open)) {
                        if (menuOpen()) {
                            menu.value = null
                            actions.open(file)
                        }
                    }
                    MenuItem(Icons.Rounded.Share, share) {
                        if (menuOpen()) {
                            menu.value = null
                            actions.share(file)
                        }
                    }
                    if (onPrint != null) {
                        MenuItem(Icons.Rounded.Print, print) {
                            if (menuOpen()) {
                                menu.value = null
                                onPrint(file)
                            }
                        }
                    }
                    MenuItem(Icons.Rounded.RemoveCircleOutline, remove) {
                        if (menuOpen()) {
                            menu.value = null
                            actions.remove(file)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuItem(icon: ImageVector, text: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, leadingIcon = { Icon(icon, contentDescription = null) }, onClick = onClick)
}

/**
 * Today's date, for the rows' [savedWhen]. It moves on at midnight and is read again each time Usugami
 * comes back, so a file saved yesterday doesn't keep showing just a time, as one saved today.
 */
@Composable
internal fun rememberToday(): LocalDate {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val today by produceState(LocalDate.now(), lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val now = ZonedDateTime.now()
                value = now.toLocalDate()
                delay(Duration.between(now, value.plusDays(1).atStartOfDay(now.zone)).toMillis())
            }
        }
    }
    return today
}

/**
 * When a file was saved, in short: the time for one saved [today], else the day, with the year for one
 * saved before this year. In [locale], Usugami's language; the time as the phone shows times (12 or 24 hours).
 */
private fun savedWhen(context: Context, locale: Locale, time: Long, today: LocalDate): String {
    val saved = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate()
    if (saved == today) return DateFormat.getTimeFormat(context).format(Date(time))
    val skeleton = if (saved.year == today.year) "MMMd" else "yMMMd"
    return SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(Date(time))
}
