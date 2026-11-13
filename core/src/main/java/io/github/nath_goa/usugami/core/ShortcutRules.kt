package io.github.nath_goa.usugami.core

/**
 * The launcher shortcuts, in the order they show: their rank (which end of the list sits nearest
 * Usugami's icon is the launcher's choice). [id] names one in its intent and to the launcher; never
 * change it (shortcuts pinned to the home screen keep it).
 */
enum class ShortcutTarget(val id: String) {
    SCAN("scan"),
    IMAGES("images"),
    MERGE("merge"),
    COMPRESS("compress");

    companion object {
        /** The shortcut named [id], or null for anything else. */
        fun of(id: String?): ShortcutTarget? = entries.firstOrNull { it.id == id }
    }
}

/** Where Usugami is when a shortcut is used: Home (also Recent files, Settings and About), Choose a tool, Images to PDF, or a PDF tool or converter (the reader among them). */
enum class ShortcutPlace { HOME, CHOOSE_TOOL, EDITOR, TOOL }

/** Where Usugami is when a shortcut is used. */
data class ShortcutScene(
    val place: ShortcutPlace,
    /** A job is under way: a tool's run or Images to PDF's. */
    val working: Boolean,
    /** Files shared to Usugami wait or are being copied in, a file opened is being identified, or a "Discard?" question already shows. */
    val waiting: Boolean,
    /** TOOL: leaving would lose changes or a result not saved yet. */
    val unsaved: Boolean,
    /** EDITOR: the plain image list shows (no crop screen, no scan under way, no result). */
    val editorIdle: Boolean,
)

/**
 * What a shortcut does: open its tool, ask before discarding unsaved work, add a photo to the images
 * in Images to PDF, just bring Usugami to the front, or say that Usugami is busy.
 */
enum class ShortcutStep { OPEN, ASK, ADD_PHOTO, STAY, BUSY }

object ShortcutRules {
    /** What the shortcut to [target] does where [scene] says Usugami is. */
    fun step(target: ShortcutTarget, scene: ShortcutScene): ShortcutStep = when {
        // Not remembered: it would ask "Discard?" minutes later, when the job ends.
        scene.working -> ShortcutStep.BUSY
        scene.waiting -> ShortcutStep.STAY
        scene.place == ShortcutPlace.TOOL -> if (scene.unsaved) ShortcutStep.ASK else ShortcutStep.OPEN
        scene.place == ShortcutPlace.EDITOR -> when (target) {
            ShortcutTarget.IMAGES -> ShortcutStep.STAY
            ShortcutTarget.SCAN -> if (scene.editorIdle) ShortcutStep.ADD_PHOTO else ShortcutStep.STAY
            // The tool opens over the editor, whose images wait for Back, as PDFs shared do.
            ShortcutTarget.MERGE, ShortcutTarget.COMPRESS -> ShortcutStep.OPEN
        }
        // HOME (also Recent files, Settings, About) and CHOOSE_TOOL.
        else -> ShortcutStep.OPEN
    }
}
