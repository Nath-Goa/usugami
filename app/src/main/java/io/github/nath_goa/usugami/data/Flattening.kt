package io.github.nath_goa.usugami.data

import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationPopup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField
import com.tom_roush.pdfbox.util.Matrix
import io.github.nath_goa.usugami.core.AppearancePlacement
import java.io.IOException

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
     * Flattens [doc] in place. Returns true if it had a dynamic XFA form, which was dropped: only
     * its ordinary (AcroForm) fields, if any, could be flattened. Throws [NothingToFlatten].
     */
    fun flatten(doc: PDDocument, check: () -> Unit): Boolean {
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

        if (form != null) {
            if (hasXfa) {
                form.xfa = null
                catalog.cosObject.removeItem(NEEDS_RENDERING)
            }
            if (fields.isNotEmpty()) {
                redrawFields(form, fields, check)
                check()
                form.flatten(fields, false)
            }
        }
        // What is left: comments, highlights, drawings, stamps, and widgets no field owns.
        for (page in doc.pages) {
            check()
            flattenAnnotations(doc, page)
        }
        return dynamicXfa
    }

    /**
     * Draws again the fields that need it: all of them when the form asks viewers to (NeedAppearances),
     * otherwise only those with a widget that has no appearance. Fields that already look right
     * keep the appearance their app gave them. A field PDFBox can't draw (say, in a font it can't
     * use) keeps what it has instead of stopping the rest.
     */
    internal fun redrawFields(form: PDAcroForm, fields: List<PDField>, check: () -> Unit) {
        val all = form.needAppearances
        for (field in fields) {
            if (field !is PDTerminalField) continue
            if (!all && field.widgets.all { it.normalAppearanceStream != null }) continue
            check()
            try {
                form.refreshAppearances(listOf(field))
            } catch (_: IOException) {
            } catch (_: RuntimeException) {
            }
        }
    }

    /** Draws [page]'s annotations, except links, into its content, then takes them off the page. */
    private fun flattenAnnotations(doc: PDDocument, page: PDPage) {
        val annotations = page.annotations
        if (annotations.all { it is PDAnnotationLink }) return
        val kept = ArrayList<PDAnnotation>()
        var content: PDPageContentStream? = null
        try {
            for (annotation in annotations) {
                if (annotation is PDAnnotationLink) {
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
