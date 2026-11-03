package io.github.nath_goa.usugami.data

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationPopup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import com.tom_roush.pdfbox.util.Matrix
import io.github.nath_goa.usugami.core.AppearancePlacement
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap

/**
 * The PDF has nothing flattening would change: no form fields and no annotations but links.
 * [dynamicXfa] is true when its only form is a dynamic XFA form, which has no fields to flatten.
 */
class NothingToFlatten(val dynamicXfa: Boolean) : IOException("Nothing to flatten")

/**
 * Bakes form fields and annotations into the pages they are on, as they look, so nothing can be
 * changed or filled in any more. Links stay: they aren't something anyone edits, and they keep
 * working.
 */
internal object Flattening {
    private val NEEDS_RENDERING: COSName = COSName.getPDFName("NeedsRendering")

    /**
     * Flattens [doc] in place. [Flattened.dynamicXfa] is true when it had a dynamic XFA form, which
     * was dropped: only its ordinary (AcroForm) fields, if any, could be flattened. Throws
     * [NothingToFlatten].
     */
    fun flatten(doc: PDDocument, check: () -> Unit): Flattened {
        val catalog = doc.documentCatalog
        // Without PDFBox's fix-ups, which would redraw every field at once and give up on the
        // first it can't draw; the fields are redrawn one at a time below instead.
        val form: PDAcroForm? = catalog.getAcroForm(null)
        val fields: List<PDField> = form?.fieldTree?.toList().orEmpty()
        val hasXfa = form?.hasXFA() == true
        // A dynamic XFA form draws the pages itself; its AcroForm part, if it has one, is a stand-in.
        val dynamicXfa = hasXfa && (catalog.cosObject.getBoolean(NEEDS_RENDERING, false) || fields.isEmpty())
        val annotated = doc.pages.any { page -> page.annotations.any { it !is PDAnnotationLink } }
        if (fields.isEmpty() && !annotated) throw NothingToFlatten(dynamicXfa)

        // The widgets of the fields left as they are, which stay on their pages.
        val keptWidgets = identitySet()
        var kept = 0
        if (form != null) {
            if (hasXfa) {
                form.xfa = null
                catalog.cosObject.removeItem(NEEDS_RENDERING)
            }
            if (fields.isNotEmpty()) {
                // A field whose text can't be drawn and that has no look of its own, or only one the
                // form says is out of date, would come out blank or wrong: it stays a field, value
                // and all, for viewers to draw, rather than be lost.
                val undrawn = redrawFields(form, fields, check)
                val keep = identitySet()
                for (field in undrawn) {
                    keep += field.cosObject
                    // Its parents too: taking a parent off the form would take the field with it.
                    generateSequence(field.parent) { it.parent }.forEach { keep += it.cosObject }
                    field.widgets.forEach { keptWidgets += it.cosObject }
                }
                check()
                form.flatten(fields.filter { it.cosObject !in keep }, false)
                if (undrawn.isNotEmpty()) form.needAppearances = true
                kept = undrawn.size
            }
        }
        // What is left: comments, highlights, drawings, stamps, and widgets no field owns.
        for (page in doc.pages) {
            check()
            flattenAnnotations(doc, page, keptWidgets)
        }
        return Flattened(dynamicXfa, kept)
    }

    /**
     * What [flatten] did besides flattening: [dynamicXfa] says a dynamic XFA form was dropped, and
     * [keptFields] is how many form fields were left fillable because their text couldn't be drawn.
     */
    class Flattened(val dynamicXfa: Boolean, val keptFields: Int)

    /**
     * Draws again the fields that need it: all of them when the form asks viewers to (NeedAppearances),
     * otherwise only those with a widget that has no appearance. Fields that already look right
     * keep the appearance their app gave them. A field PDFBox can't draw (say, text in a script its
     * font doesn't have) keeps what it has instead of stopping the rest. Returns those of them that
     * have a value but nothing that shows it: a widget with no appearance, or, when the form asks
     * viewers to draw its fields, any appearance at all, which the form itself says is out of date
     * (apps that fill forms that way set the value and leave the look as it was, often blank).
     * Drawn into the page, they would come out blank, or show an older value.
     */
    internal fun redrawFields(form: PDAcroForm, fields: List<PDField>, check: () -> Unit): List<PDTerminalField> {
        val all = form.needAppearances
        val undrawn = ArrayList<PDTerminalField>()
        for (field in fields) {
            if (field !is PDTerminalField) continue
            if (!all && field.widgets.all { it.normalAppearanceStream != null }) continue
            check()
            // PDFBox redraws a widget's appearance in place, its background and border first, and
            // leaves it like that when it then can't draw the value: what it had is put back.
            val saved = try { field.widgets.map { SavedAppearance(it.cosObject) } } catch (_: IOException) { null }
            val failed = try {
                form.refreshAppearances(listOf(field))
                false
            } catch (_: IOException) {
                true
            } catch (_: RuntimeException) {
                true
            }
            if (!failed) continue
            saved?.forEach { it.restore() }
            if (hasValue(field) && field.widgets.any { shown(it) && (all || !drawable(it)) }) undrawn += field
        }
        return undrawn
    }

    private fun hasValue(field: PDTerminalField) = when (field) {
        is PDTextField -> field.value.isNotEmpty()
        is PDChoice -> field.value.isNotEmpty()
        else -> false
    }

    private fun shown(annotation: PDAnnotation) = !annotation.isHidden && !annotation.isNoView

    private fun drawable(widget: PDAnnotationWidget) =
        widget.normalAppearanceStream?.bBox?.let { it.width > 0f && it.height > 0f } == true

    private fun identitySet(): MutableSet<COSDictionary> = Collections.newSetFromMap(IdentityHashMap())

    /**
     * A widget's appearance as it was, to [restore]: the widget's /AP entry, the appearance
     * dictionary's entries, and its normal appearance's stream, entries and data.
     */
    private class SavedAppearance(private val widget: COSDictionary) {
        private val item: COSBase? = widget.getItem(COSName.AP)
        private val appearances = widget.getDictionaryObject(COSName.AP) as? COSDictionary
        private val appearanceEntries = appearances?.let(::entries)
        private val normal = appearances?.getDictionaryObject(COSName.N) as? COSStream
        private val normalEntries = normal?.let(::entries)
        private val data = normal?.createRawInputStream()?.use { it.readBytes() }

        fun restore() {
            if (item == null) widget.removeItem(COSName.AP) else widget.setItem(COSName.AP, item)
            if (appearances != null && appearanceEntries != null) put(appearances, appearanceEntries)
            if (normal != null && normalEntries != null && data != null) {
                put(normal, normalEntries)
                // As stored, filters and all; this sets its /Length again too.
                normal.createRawOutputStream().use { it.write(data) }
            }
        }

        private fun entries(dictionary: COSDictionary) = dictionary.entrySet().map { it.key to it.value }

        private fun put(dictionary: COSDictionary, entries: List<Pair<COSName, COSBase>>) {
            dictionary.clear()
            for ((key, value) in entries) dictionary.setItem(key, value)
        }
    }

    /** Draws [page]'s annotations, except links and the widgets in [keep], into its content, then takes them off the page. */
    private fun flattenAnnotations(doc: PDDocument, page: PDPage, keep: Set<COSDictionary>) {
        val annotations = page.annotations
        if (annotations.all { it is PDAnnotationLink || it.cosObject in keep }) return
        val kept = ArrayList<PDAnnotation>()
        var content: PDPageContentStream? = null
        try {
            for (annotation in annotations) {
                if (annotation is PDAnnotationLink || annotation.cosObject in keep) {
                    kept += annotation
                    continue
                }
                // Hidden ones aren't seen, so they aren't drawn. A popup is only the window of a
                // comment, which goes with the comment.
                if (annotation.isHidden || annotation.isNoView || annotation.isInvisible || annotation is PDAnnotationPopup) continue
                val appearance = appearanceOf(doc, annotation) ?: continue
                val stream = content ?: PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).also { content = it }
                draw(stream, annotation, appearance)
            }
        } finally {
            content?.close()
        }
        page.annotations = kept
    }

    /**
     * Draws into [page]'s content the annotations seen on it (form fields, comments, stamps; not
     * links) that overlap any of [areas], each a rectangle in the page's own coordinates (left,
     * bottom, right, top), and takes them off the page. What Edit PDF and Sign PDF then add there
     * goes on top of them, as their previews show it: viewers draw annotations over the page, but
     * the previews have every annotation drawn into its page, since Android's renderer leaves them
     * out. A form field drawn in this way can't be filled in there any more. Its widgets on other
     * pages are left as they are, and so are fields whose text can't be drawn.
     */
    fun bakeUnder(doc: PDDocument, page: PDPage, areas: List<FloatArray>, check: () -> Unit) {
        // A page whose annotations PDFBox can't read keeps them as they are, as before.
        val annotations = try { page.annotations } catch (_: IOException) { return }
        val under = annotations.filter { annotation ->
            annotation !is PDAnnotationLink && annotation !is PDAnnotationPopup && shown(annotation) &&
                annotation.rectangle?.let { rect -> areas.any { overlaps(rect, it) } } == true
        }
        if (under.isEmpty()) return
        check()
        // The field each widget belongs to: it is drawn as the preview draws it, then taken off it.
        val form = doc.documentCatalog.getAcroForm(null)
        val owners = IdentityHashMap<COSDictionary, PDTerminalField>()
        form?.fieldTree?.forEach { field -> if (field is PDTerminalField) field.widgets.forEach { owners[it.cosObject] = field } }
        val seen = identitySet()
        val fields = under.mapNotNull { owners[it.cosObject] }.filter { seen.add(it.cosObject) }
        val undrawn = identitySet()
        if (form != null && fields.isNotEmpty()) redrawFields(form, fields, check).mapTo(undrawn) { it.cosObject }
        val baked = identitySet()
        under.filter { annotation -> owners[annotation.cosObject]?.let { it.cosObject in undrawn } != true }.mapTo(baked) { it.cosObject }
        if (baked.isEmpty()) return

        val kept = ArrayList<PDAnnotation>()
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { content ->
            for (annotation in annotations) {
                when {
                    annotation.cosObject in baked -> appearanceOf(doc, annotation)?.let { draw(content, annotation, it) }
                    // A comment's popup window goes with it.
                    annotation is PDAnnotationPopup && (annotation.cosObject.getDictionaryObject(COSName.PARENT) as? COSDictionary)?.let { it in baked } == true -> Unit
                    else -> kept += annotation
                }
            }
        }
        page.annotations = kept
        if (form != null) {
            for (widget in baked) owners[widget]?.let { field -> detach(form, field, widget) }
        }
    }

    /** Takes [widget] off [field], and the field off the form once it has no widget left, as PDFBox's flatten does. */
    private fun detach(form: PDAcroForm, field: PDTerminalField, widget: COSDictionary) {
        if (widget !== field.cosObject) {
            val kids = field.cosObject.getDictionaryObject(COSName.KIDS) as? COSArray
            kids?.removeObject(widget)
            if (kids != null && kids.size() > 0) return
        }
        val siblings = field.parent?.cosObject?.getDictionaryObject(COSName.KIDS) ?: form.cosObject.getDictionaryObject(COSName.FIELDS)
        (siblings as? COSArray)?.removeObject(field.cosObject)
    }

    private fun overlaps(rect: PDRectangle, area: FloatArray) =
        rect.lowerLeftX < area[2] && area[0] < rect.upperRightX && rect.lowerLeftY < area[3] && area[1] < rect.upperRightY

    /**
     * The page's own coordinates (left, bottom, right, top) of what the rectangle at ([x], [y]),
     * [width] by [height], covers once [m] (a, b, c, d, e, f, as for a PDF `cm` operator) takes it there.
     */
    fun pageArea(m: FloatArray, x: Float, y: Float, width: Float, height: Float): FloatArray {
        val corners = listOf(x to y, x + width to y, x to y + height, x + width to y + height)
        val xs = corners.map { (cx, cy) -> m[0] * cx + m[2] * cy + m[4] }
        val ys = corners.map { (cx, cy) -> m[1] * cx + m[3] * cy + m[5] }
        return floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
    }

    /** What [annotation] looks like; made by PDFBox for the kinds it can draw when the PDF has none. */
    internal fun appearanceOf(doc: PDDocument, annotation: PDAnnotation): PDAppearanceStream? {
        var appearance = annotation.normalAppearanceStream
        if (appearance == null) {
            try {
                annotation.constructAppearances(doc)
            } catch (_: IOException) {
            } catch (_: RuntimeException) {
            }
            appearance = annotation.normalAppearanceStream
        }
        val box = appearance?.bBox ?: return null
        if (annotation.rectangle == null || box.width <= 0f || box.height <= 0f) return null
        return appearance
    }

    internal fun draw(content: PDPageContentStream, annotation: PDAnnotation, appearance: PDAppearanceStream) {
        val rect = annotation.rectangle
        val box = appearance.bBox
        val form = appearance.matrix
        val m = AppearancePlacement.matrix(
            floatArrayOf(rect.lowerLeftX, rect.lowerLeftY, rect.upperRightX, rect.upperRightY),
            floatArrayOf(box.lowerLeftX, box.lowerLeftY, box.upperRightX, box.upperRightY),
            floatArrayOf(form.scaleX, form.shearY, form.shearX, form.scaleY, form.translateX, form.translateY),
        )
        content.saveGraphicsState()
        // A comment's own opacity (/CA) applies to its whole appearance.
        val opacity = annotation.cosObject.getFloat(COSName.CA, 1f)
        if (opacity in 0f..0.999f) {
            content.setGraphicsStateParameters(PDExtendedGraphicsState().apply {
                strokingAlphaConstant = opacity
                nonStrokingAlphaConstant = opacity
            })
        }
        content.transform(Matrix(m[0], m[1], m[2], m[3], m[4], m[5]))
        content.drawForm(appearance)
        content.restoreGraphicsState()
    }
}
