package io.github.nath_goa.usugami.ui

import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import io.github.nath_goa.usugami.core.InfoChange
import io.github.nath_goa.usugami.core.InfoField
import io.github.nath_goa.usugami.core.InfoText
import io.github.nath_goa.usugami.data.DocumentDetails
import io.github.nath_goa.usugami.data.PdfInput
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Document info's own state in the view model (see [ToolState]): the PDF's info as read, and the
 * form as the user has changed it. What was typed, "Remove all info" and what its Undo brings back
 * are in the saved state, for the PDF they were typed for; the PDF's own info is read again (see
 * loadDocumentInfo). On the main thread only, as the saved state must be.
 */
internal class DocumentInfoState(private val handle: SavedStateHandle) : ToolState {
    private val _details = MutableStateFlow<DocumentDetails?>(null)
    /** What the PDF says of itself; null while it is being read. Never in the saved state. */
    val details: StateFlow<DocumentDetails?> = _details.asStateFlow()

    private val _texts = MutableStateFlow(textsAt(KEY_TEXTS))
    /**
     * What the user typed, cleaned and cut: only the fields that differ from the PDF's, or, under
     * [removeAll], from "" (a field emptied there is no change).
     */
    val texts: StateFlow<Map<InfoField, String>> = _texts.asStateFlow()

    private val _removeAll = MutableStateFlow(handle.get<Boolean>(KEY_REMOVE_ALL) == true)
    /** "Remove all info" was chosen: everything goes on saving, but for what is typed after it. */
    val removeAll: StateFlow<Boolean> = _removeAll.asStateFlow()

    private val _changed = MutableStateFlow(false)
    /** Whether saving would change the PDF: once its info is read, a field typed or Remove all info chosen. */
    val changed: StateFlow<Boolean> = _changed.asStateFlow()

    private val _generation = MutableStateFlow(handle.get<Int>(KEY_GENERATION) ?: 0)
    /**
     * Changes when the fields start over from [shown] (Remove all info, its Undo, the form cleared): the
     * fields hold their own text, and are made anew for each. Kept in the saved state and never used
     * twice, so a field restored after Android closed Usugami is never one of an earlier generation.
     */
    val generation: StateFlow<Int> = _generation.asStateFlow()

    /** The read of the PDF's info under way (see loadDocumentInfo); [clear] cancels it. */
    var reading: Job? = null

    /**
     * Whether leaving would lose what was typed or Remove all info: [changed], and also while the PDF's
     * info is read again after Android closed Usugami, before [changed] can say so.
     */
    override val hasUnsavedWork: Boolean get() = _removeAll.value || _texts.value.isNotEmpty()

    /**
     * Starts the form for [input]: what was typed for another PDF is forgotten, what was typed for this
     * one (as after Android closed Usugami) is kept. Returns true when its info is to be read, false
     * when it is there or being read.
     */
    fun begin(input: PdfInput): Boolean {
        if (handle.get<Long>(KEY_INPUT) != input.id) {
            clear()
            handle[KEY_INPUT] = input.id
            return true
        }
        if (_details.value?.inputId == input.id) return false
        return reading?.isActive != true
    }

    /** [details] were read: the form shows them, if they are of the PDF begun. */
    fun loaded(details: DocumentDetails) {
        if (handle.get<Long>(KEY_INPUT) != details.inputId) return
        _details.value = details
        // What was typed before the PDF was read again counts only where it still differs from it.
        setTexts(_texts.value.filter { (field, text) -> !isOriginal(field, text) })
    }

    /** What [field] shows: what was typed, else what the PDF has, or nothing once Remove all info is chosen. */
    fun shown(field: InfoField): String = _texts.value[field] ?: original(field)

    /** [field] holds [text] now: kept cleaned and cut, or forgotten when that is what the PDF has. */
    fun setText(field: InfoField, text: String) {
        val value = InfoText.cut(InfoText.clean(text))
        setTexts(if (isOriginal(field, value)) _texts.value - field else _texts.value + (field to value))
    }

    /**
     * Remove all info chosen: the fields are emptied, what was typed being kept for [undoRemoveAll].
     * Nothing is saved yet.
     */
    fun removeAll() {
        if (_removeAll.value) return
        handle[KEY_UNDO_TEXTS] = bundleOf(_texts.value)
        _removeAll.value = true
        handle[KEY_REMOVE_ALL] = true
        setTexts(emptyMap())
        nextGeneration()
    }

    /** Takes Remove all info back: the fields as they were just before it, what was typed since forgotten. */
    fun undoRemoveAll() {
        if (!_removeAll.value) return
        val before = textsAt(KEY_UNDO_TEXTS)
        handle.remove<Bundle>(KEY_UNDO_TEXTS)
        _removeAll.value = false
        handle.remove<Boolean>(KEY_REMOVE_ALL)
        setTexts(before)
        nextGeneration()
    }

    /** What saving is to do, or null while the PDF's info isn't read or nothing differs from it. */
    fun change(): InfoChange? = if (_changed.value) InfoChange(_texts.value, _removeAll.value) else null

    override fun clear() {
        reading?.cancel()
        reading = null
        _details.value = null
        _removeAll.value = false
        _texts.value = emptyMap()
        for (key in listOf(KEY_INPUT, KEY_TEXTS, KEY_UNDO_TEXTS, KEY_REMOVE_ALL)) handle.remove<Any>(key)
        updateChanged()
        nextGeneration()
    }

    /** What [field] is compared with: the PDF's, or "" once Remove all info is chosen. */
    private fun original(field: InfoField): String =
        if (_removeAll.value) "" else _details.value?.fields?.get(field).orEmpty()

    /**
     * Whether [text], cleaned as typed text is, is what [field] shows unchanged. Cleaned as well: a value
     * longer than a field takes is cut where it may end in a space, which typed text never keeps, so
     * the field typed in and put back as it was would otherwise count as changed and cut the value.
     */
    private fun isOriginal(field: InfoField, text: String) = text == InfoText.clean(original(field))

    private fun setTexts(texts: Map<InfoField, String>) {
        _texts.value = texts
        // A new Bundle each time: one already in the saved state is never changed in place.
        if (texts.isEmpty()) handle.remove<Bundle>(KEY_TEXTS) else handle[KEY_TEXTS] = bundleOf(texts)
        updateChanged()
    }

    private fun updateChanged() {
        _changed.value = _details.value != null && (_removeAll.value || _texts.value.isNotEmpty())
    }

    private fun nextGeneration() {
        _generation.value += 1
        handle[KEY_GENERATION] = _generation.value
    }

    private fun bundleOf(texts: Map<InfoField, String>) = Bundle().apply {
        for ((field, text) in texts) putString(field.name, text)
    }

    /** The texts kept under [key], each field by its name; one Usugami doesn't know (from another version) is left out. */
    private fun textsAt(key: String): Map<InfoField, String> {
        val bundle = handle.get<Bundle>(key) ?: return emptyMap()
        return InfoField.entries.mapNotNull { field -> bundle.getString(field.name)?.let { field to it } }.toMap()
    }

    private companion object {
        // The saved state's keys of tools in files of their own begin with the tool's name.
        /** The PDF the form is for (its id): what is typed is kept for it alone. */
        const val KEY_INPUT = "document_info_input"
        const val KEY_TEXTS = "document_info_texts"
        /** What was typed before Remove all info, for its Undo. */
        const val KEY_UNDO_TEXTS = "document_info_undo_texts"
        const val KEY_REMOVE_ALL = "document_info_remove_all"
        const val KEY_GENERATION = "document_info_generation"
    }
}
