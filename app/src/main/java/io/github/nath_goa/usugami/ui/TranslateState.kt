package io.github.nath_goa.usugami.ui

import android.os.Parcelable
import androidx.lifecycle.SavedStateHandle
import io.github.nath_goa.usugami.data.OcrProblem
import io.github.nath_goa.usugami.data.TranslationProblem
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.parcelize.Parcelize

// Translate PDF's state, which the view model keeps for every edition: only the standard edition
// has the tool (see translationAvailable), and its screen and runs are in that edition's files.

/**
 * What a Translate PDF run left as it was, which the result's notes say: the pages with no text to
 * translate ([noText]), those that look scanned but couldn't be read ([unread]: why is [unreadWhy],
 * [UNREAD_LANGUAGE] or [UNREAD_FAILED]), and those where some text was left untranslated ([partly]).
 */
@Parcelize
data class TranslateNotes(
    val noText: NotedPages? = null,
    val unread: NotedPages? = null,
    val unreadWhy: Int = UNREAD_FAILED,
    val partly: NotedPages? = null,
) : Parcelable {
    companion object {
        /** Text recognition doesn't read the language of the PDF. */
        const val UNREAD_LANGUAGE = 0
        /** Text recognition reads the language, but couldn't read those pages. */
        const val UNREAD_FAILED = 1
    }
}

/**
 * A Translate PDF run asked for, waiting for the user to agree to download the language packs it
 * needs: the PDF ([inputId]), its zero-based [pages], the languages ([source], [target], as tags), the
 * name typed, and the packs [missing] from the phone.
 */
internal class TranslateRequest(
    val inputId: Long,
    val pages: List<Int>,
    val source: String,
    val target: String,
    val fileName: String,
    val missing: List<String>,
)

/** Translate PDF's own state in the view model (see [ToolState]). */
internal class TranslateState(private val handle: SavedStateHandle) : ToolState {
    /** What the result's notes say, for the result showing; kept while Android stops Usugami in the background. */
    val notes: StateFlow<TranslateNotes?> = handle.getStateFlow<TranslateNotes?>(KEY_NOTES, null)

    /** Written on the main thread only, as the saved state must be. */
    fun setNotes(notes: TranslateNotes?) {
        handle[KEY_NOTES] = notes
    }

    /** Why translating couldn't start (a language pack couldn't be downloaded, say), while the dialog saying so shows. */
    val problem = MutableStateFlow<TranslationProblem?>(null)

    /** Why text recognition couldn't start for the scanned pages, while the dialog saying so shows. */
    val ocrProblem = MutableStateFlow<OcrProblem?>(null)

    /** The run waiting while the dialog asks whether to download the language packs it needs. */
    val download = MutableStateFlow<TranslateRequest?>(null)

    /** Counts up whenever language packs may have come or gone: what lists them reads them again. */
    val packs = MutableStateFlow(0)

    /** Looking at which language packs a run needs, after the button was pressed. Main thread only. */
    var checking: Job? = null

    override fun clear() {
        setNotes(null)
        problem.value = null
        ocrProblem.value = null
        download.value = null
        checking?.cancel()
        checking = null
    }

    private companion object {
        // The saved state's keys of tools in files of their own begin with the tool's name.
        const val KEY_NOTES = "translate_notes"
    }
}
