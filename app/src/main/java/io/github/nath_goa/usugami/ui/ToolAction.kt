package io.github.nath_goa.usugami.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * What the options of a tool kept in a file of its own tell the tool's screen (PdfToolScreen): whether
 * its button is on, what the button says and does, and how the file name field ends. The options
 * say it with [Bind] each time they are drawn, and what they said goes with them when they leave
 * the screen.
 */
@Stable
internal class ToolAction(suffix: String? = ".pdf") {
    /** The name field's ending the screen gave, which shows again once the options are gone. */
    private val screenSuffix = suffix
    /** Whether the button is on, once a PDF is in. */
    var ready by mutableStateOf(false)
    /** The button's words, or null for the tool's own (PdfToolScreen's `action`). */
    var label by mutableStateOf<String?>(null)
    /**
     * How the "File name" field ends (".pdf"); null hides the field, for a tool that names its files
     * itself. It starts as the screen gives it, which is what shows until the options are first drawn
     * and again once they are gone.
     */
    var suffix by mutableStateOf(suffix)
    /** What the button does, given the file name typed. */
    var run: (fileName: String) -> Unit = {}

    /** The options left the screen (their PDF removed or put away): the button is off again, with the tool's own words. */
    fun forget() {
        ready = false
        label = null
        suffix = screenSuffix
        run = {}
    }
}

/**
 * Tells the screen, after each drawing of the options, what its button and name field are to be.
 * Called once by the options, not from a part of them that comes and goes: once it leaves the
 * screen, the screen's button is off, with the tool's own words, until the next options say
 * otherwise (a PDF picked after another was removed doesn't find the button on from the one before).
 */
@Composable
internal fun ToolAction.Bind(ready: Boolean, label: String? = null, suffix: String? = ".pdf", run: (fileName: String) -> Unit) {
    SideEffect {
        this.ready = ready
        this.label = label
        this.suffix = suffix
        this.run = run
    }
    DisposableEffect(this) { onDispose { forget() } }
}

/**
 * What a tool kept in a file of its own keeps in the view model, beside the PDFs (PdfToolsViewModel
 * holds one of each): state that must outlive the screen, such as what a job found. Choices that
 * only the options need stay in the options, with rememberSaveable.
 */
internal interface ToolState {
    /** Whether leaving the tool would throw away work done on its PDF (see PdfToolsViewModel.hasUnsavedWork). */
    val hasUnsavedWork: Boolean get() = false
    /** The tool's PDF was put away, or the tool opened afresh: what was done with it is forgotten. */
    fun clear() {}
}

/** How far a tool's job on one PDF has got: [done] of [total] steps, and [text] saying so ("Page 3 of 40"). */
class ToolProgress(val done: Int, val total: Int, val text: String)

/** A tool's job can't do what was asked, for a reason the user is told: [text], in the user's language. */
internal class ToolMessage(val text: String) : Exception(text)
