package io.github.nath_goa.usugami.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.core.ExportText
import io.github.nath_goa.usugami.core.PageGlyphs
import io.github.nath_goa.usugami.core.TextPlace
import io.github.nath_goa.usugami.core.TextSelection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Text selected on the reader's pages ([session]'s PDF): every glyph from [anchor] to [moving], in
 * either order. A long-press on a word selects it ([select]); a handle dragged moves one end
 * ([take], [dragTo]); Copy ([copy]) puts the text on the clipboard and lets go ([letGo]). [pages]
 * holds the text of the pages the selection is drawn on (those showing, and its two ends' pages),
 * read through the session (ReaderSession.glyphs). Not kept through rotation: the screen makes one
 * for each PDF it shows. Used on the main thread; [scope] is the screen's, so what it starts stops
 * with the screen.
 */
@Stable
internal class ReaderSelection(private val session: ReaderSession, private val vm: PdfToolsViewModel, private val scope: CoroutineScope) {
    /** The end that stays put while the other is dragged; null while nothing is selected. */
    var anchor by mutableStateOf<TextPlace?>(null)
        private set

    /** The end a handle moves; null while nothing is selected. */
    var moving by mutableStateOf<TextPlace?>(null)
        private set

    /** The text of the pages the selection needs: those it is drawn on, and its ends' pages. */
    val pages = mutableStateMapOf<Int, PageGlyphs>()

    /** A handle is being dragged. */
    var dragging by mutableStateOf(false)
        private set

    /** Text is selected: read where only that matters (the bars), it changes only as text is selected or let go, not as a handle moves. */
    val active: Boolean by derivedStateOf { anchor != null && moving != null }

    /** The selection's first glyph, and its last. */
    val start: TextPlace? get() = ends()?.first
    val end: TextPlace? get() = ends()?.second

    private fun ends(): Pair<TextPlace, TextPlace>? {
        val a = anchor ?: return null
        val m = moving ?: return null
        return if (a <= m) a to m else m to a
    }

    /** The long-press being answered, Copy running, and the pages whose text is being read. */
    private var pressing: Job? = null
    private var copying: Job? = null
    private val loading = HashSet<Int>()

    /** The pages whose text couldn't be read for this selection: not read again for drawing or a handle held over them. */
    private val failed = HashSet<Int>()

    /** Where the dragged end was last held, while the page under it was read: page, x, y (points). */
    private var heldAt: Triple<Int, Float, Float>? = null

    /** The glyphs of page [page] that are selected; null when none are, or its text isn't read yet. */
    fun on(page: Int): IntRange? {
        val a = anchor ?: return null
        val m = moving ?: return null
        val glyphs = pages[page] ?: return null
        return TextSelection.onPage(a, m, page, glyphs.glyphs.size)
    }

    /** Whether the selection runs over page [page], whose text may not be read yet. */
    fun covers(page: Int): Boolean {
        val (first, last) = ends() ?: return false
        return page in first.page..last.page
    }

    /**
     * A long-press at ([x], [y]) on page [page] (points on the page as shown): the word there, or
     * within [reach] points of it, is selected and [selected] runs (the phone ticks). On a page without
     * text, or one whose text can't be read, [noText] runs; on blank paper of a page with text,
     * nothing happens. Waits its turn after the lot of pages a search reads now, if one runs.
     */
    fun select(page: Int, x: Float, y: Float, reach: Float, noText: () -> Unit, selected: () -> Unit) {
        pressing?.cancel()
        pressing = scope.launch {
            val glyphs = session.glyphs(page)
            val word = glyphs?.let { TextSelection.wordAt(it, x, y, reach) }
            if (word == null) {
                // Nothing selected: the text's document needn't stay open for this (see ReaderSession.stopSelecting).
                if (!active) session.stopSelecting()
                if (glyphs == null || glyphs.isEmpty) noText()
                return@launch
            }
            dragging = false
            heldAt = null
            pages.clear()
            failed.clear()
            pages[page] = glyphs
            anchor = TextPlace(page, word.first)
            moving = TextPlace(page, word.last)
            session.startSelecting()
            selected()
        }
    }

    /** Lets go of the selection (a tap, Back, Copy done, Annotate); the session may close the text's document. */
    fun letGo() {
        pressing?.cancel()
        pressing = null
        anchor = null
        moving = null
        dragging = false
        heldAt = null
        pages.clear()
        failed.clear()
        session.stopSelecting()
    }

    /**
     * Reads page [page]'s text, if it isn't read: for drawing the selection on it, or a handle held
     * over it. A page whose text can't be read isn't tried again until text is selected anew: a handle
     * held over it would read it again at every move.
     */
    fun need(page: Int) {
        if (!active || pages.containsKey(page) || page in failed || !loading.add(page)) return
        scope.launch {
            try {
                val glyphs = session.glyphs(page)
                if (!active) return@launch
                if (glyphs == null) {
                    failed += page
                    return@launch
                }
                pages[page] = glyphs
                // The dragged end waited for this page: it goes where the finger is.
                heldAt?.let { (held, x, y) -> if (held == page && dragging) dragTo(page, x, y) }
            } finally {
                loading -= page
            }
        }
    }

    /** Page [page] is no longer drawn: its text goes, unless an end of the selection is on it. */
    fun forget(page: Int) {
        if (anchor?.page == page || moving?.page == page) return
        pages.remove(page)
    }

    /** A handle is taken: the [startEnd] (else the end) is dragged, the other end stays. */
    fun take(startEnd: Boolean) {
        val (first, last) = ends() ?: return
        anchor = if (startEnd) last else first
        moving = if (startEnd) first else last
        dragging = true
    }

    /** The dragged end goes to the glyph a handle held at ([x], [y]) on page [page] stands on (TextSelection.glyphNear). */
    fun dragTo(page: Int, x: Float, y: Float) {
        if (!dragging || !active) return
        val glyphs = pages[page]
        if (glyphs == null) {
            // Read first; the end goes there once it is (see need).
            heldAt = Triple(page, x, y)
            need(page)
            return
        }
        heldAt = null
        val glyph = TextSelection.glyphNear(glyphs, x, y) ?: return
        moving = TextPlace(page, glyph)
    }

    /** The handle is let go. */
    fun drop() {
        dragging = false
        heldAt = null
        // Only its ends' pages and those drawn keep their text: the pages scrolled past while dragging are forgotten as they go.
    }

    /**
     * Copy: the selection's text, as PDF to images or text writes it, on the clipboard under [label],
     * then the selection is let go. More than ExportText.COPY_MAX_CHARS characters, or more than the
     * clipboard takes, is not copied: the user is told to select less. Once at a time.
     */
    fun copy(label: String) {
        val (first, last) = ends() ?: return
        if (copying?.isActive == true) return
        copying = scope.launch {
            val text = selectedText(first, last) ?: return@launch
            // Changed meanwhile (a tap let go, a long-press chose another word): this copy isn't wanted.
            if (ends() != first to last) return@launch
            if (text.isEmpty()) {
                letGo()
                return@launch
            }
            putOnClipboard(text, label)
            letGo()
        }
    }

    /**
     * The screen reader's "Copy the text of this page": the text of page [page], as Copy copies a
     * selection. A page without text says so, as a long-press on it does.
     */
    fun copyPage(page: Int, label: String) {
        if (copying?.isActive == true) return
        copying = scope.launch {
            val glyphs = session.glyphs(page)
            if (glyphs == null || glyphs.isEmpty) {
                if (!active) session.stopSelecting()
                vm.message(vm.strings.getString(R.string.reader_no_text))
                return@launch
            }
            val text = TextSelection.text(glyphs, glyphs.glyphs.indices)
            if (!active) session.stopSelecting()
            when {
                text.isEmpty() -> vm.message(vm.strings.getString(R.string.reader_no_text))
                text.length > ExportText.COPY_MAX_CHARS -> vm.message(vm.strings.getString(R.string.reader_copy_too_long))
                else -> putOnClipboard(text, label)
            }
        }
    }

    /**
     * The text from [first] to [last], the pages' parts joined by a line break; null, with the user
     * told, once it passes ExportText.COPY_MAX_CHARS or at a page whose text can't be read (so that
     * nothing is copied with a page missing). Null too once the selection changes meanwhile.
     */
    private suspend fun selectedText(first: TextPlace, last: TextPlace): String? {
        val text = StringBuilder()
        for (page in first.page..last.page) {
            val glyphs = pages[page] ?: session.glyphs(page)
            // Let go or changed meanwhile: no more pages are read, and the text read for nothing isn't kept open.
            if (ends() != first to last) {
                if (!active) session.stopSelecting()
                return null
            }
            if (glyphs == null) {
                vm.message(vm.strings.getString(R.string.reader_copy_unread, page + 1))
                return null
            }
            val range = TextSelection.onPage(first, last, page, glyphs.glyphs.size) ?: continue
            val part = TextSelection.text(glyphs, range)
            if (part.isEmpty()) continue
            if (text.isNotEmpty()) text.append('\n')
            text.append(part)
            if (text.length > ExportText.COPY_MAX_CHARS) {
                vm.message(vm.strings.getString(R.string.reader_copy_too_long))
                return null
            }
        }
        return text.toString()
    }

    /**
     * [text] on the clipboard under [label], said below Android 13 (which says so itself); the
     * clipboard turning it down (too large for it) says to select less.
     */
    private fun putOnClipboard(text: String, label: String) {
        try {
            val clipboard = vm.getApplication<Application>().getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) vm.message(vm.strings.getString(R.string.msg_text_copied))
        } catch (_: RuntimeException) {
            vm.message(vm.strings.getString(R.string.reader_copy_too_long))
        }
    }
}

/**
 * The bar under the pages while text is selected: Copy ([onCopy]), in Annotate's toolbar style. With
 * [clearNavigationBar] (nothing under the bar), it reaches under the navigation bar and clears it.
 */
@Composable
internal fun ReaderCopyBar(onCopy: () -> Unit, clearNavigationBar: Boolean) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (clearNavigationBar) Modifier.windowInsetsPadding(WindowInsets.navigationBars) else Modifier)
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            EditToolButton(Icons.Rounded.ContentCopy, stringResource(R.string.reader_copy), active = null, enabled = true, onClick = onCopy, modifier = Modifier.width(96.dp))
        }
    }
}
