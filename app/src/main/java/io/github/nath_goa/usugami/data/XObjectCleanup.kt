package io.github.nath_goa.usugami.data

import com.tom_roush.pdfbox.contentstream.PDFStreamEngine
import com.tom_roush.pdfbox.contentstream.operator.DrawObject
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.contentstream.operator.state.Concatenate
import com.tom_roush.pdfbox.contentstream.operator.state.Restore
import com.tom_roush.pdfbox.contentstream.operator.state.Save
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDTransparencyGroup
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.util.Matrix
import io.github.nath_goa.usugami.core.Areas
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Images and forms (XObjects) that only pages which have gone drew. Pages often share one list of
 * resources (many PDF makers put every image in it), so such an image would otherwise stay in the
 * file through the pages that are left. Used when pages are redacted, deleted or left out.
 */
internal object XObjectCleanup {
    /** Finds the images and forms a page's content draws, those inside forms included. */
    private class DrawnXObjects(private val drawn: MutableSet<COSBase>, private val contexts: MutableSet<COSBase>) : PDFStreamEngine() {
        init {
            addOperator(DrawObject())
        }

        override fun processOperator(operator: Operator, operands: List<COSBase>) {
            if (operator.name == "Do") {
                val name = operands.firstOrNull() as? COSName
                val xobject = if (name != null) try { resources?.getXObject(name) } catch (_: IOException) { null } else null
                if (xobject != null) {
                    drawn += xobject.cosObject
                    (xobject as? PDFormXObject)?.resources?.cosObject?.let(contexts::add)
                }
            }
            super.processOperator(operator, operands)
        }
    }

    /**
     * An image or form [xobject] drawn by a page: [matrix] (a, b, c, d, e, f) takes the space it is
     * drawn in, where it covers [extent] (left, bottom, right, top: the unit square for an image,
     * the bounding box for a form), to the page's own coordinates. [clip] is the area, in those, to
     * which the forms it is drawn inside cut it; null if none do.
     */
    class Placement(val xobject: COSBase, val matrix: FloatArray, val extent: FloatArray, val clip: FloatArray?)

    /** Finds where a page's content draws each image and form, those inside forms included. */
    private class PlacedXObjects : PDFStreamEngine() {
        val found = ArrayList<Placement>()
        private var clip: FloatArray? = null

        init {
            addOperator(Save())
            addOperator(Restore())
            addOperator(Concatenate())
            addOperator(DrawObject())
        }

        override fun processOperator(operator: Operator, operands: List<COSBase>) {
            if (operator.name == "Do") {
                val name = operands.firstOrNull() as? COSName
                val xobject = if (name != null) try { resources?.getXObject(name) } catch (_: IOException) { null } else null
                val ctm = graphicsState.currentTransformationMatrix
                when (xobject) {
                    is PDImageXObject -> found += Placement(xobject.cosObject, ctm.values(), floatArrayOf(0f, 0f, 1f, 1f), clip)
                    is PDFormXObject -> xobject.bBox?.let { box ->
                        found += Placement(xobject.cosObject, xobject.matrix.multiply(ctm).values(), box.corners(), clip)
                    }
                }
            }
            super.processOperator(operator, operands)
        }

        // A form draws only inside its bounding box.
        override fun showForm(form: PDFormXObject) = inside(form) { super.showForm(form) }

        override fun showTransparencyGroup(form: PDTransparencyGroup) = inside(form) { super.showTransparencyGroup(form) }

        private fun inside(form: PDFormXObject, draw: () -> Unit) {
            val outer = clip
            form.bBox?.let { box ->
                val area = Areas.mapped(form.matrix.multiply(graphicsState.currentTransformationMatrix).values(), box.corners())
                clip = outer?.let { Areas.intersection(it, area) ?: floatArrayOf(0f, 0f, 0f, 0f) } ?: area
            }
            try {
                draw()
            } finally {
                clip = outer
            }
        }

        private fun Matrix.values() = floatArrayOf(scaleX, shearY, shearX, scaleY, translateX, translateY)

        private fun PDRectangle.corners() = floatArrayOf(lowerLeftX, lowerLeftY, upperRightX, upperRightY)
    }

    /** Where [page]'s content draws each image and form (see [Placement]); null if its content can't be read. */
    fun placements(page: PDPage): List<Placement>? = try {
        PlacedXObjects().apply { processPage(page) }.found
    } catch (_: Exception) {
        null
    }

    /**
     * Whether [page]'s resources list any of [xobjects], also inside forms and the like: if not, it
     * can't draw them, and its content needn't be read. [known] keeps the answer for resources
     * shared by several pages.
     */
    fun mayDraw(page: PDPage, xobjects: Set<COSBase>, known: MutableMap<COSBase, Boolean>): Boolean {
        val resources = page.resources?.cosObject ?: return false
        return known.getOrPut(resources) { identitySet<COSBase>().also { collect(resources, it) }.any { it in xobjects } }
    }

    /**
     * Adds to [into] the images and forms [page] draws. If its content can't be read, everything its
     * resources list counts, so nothing it might have drawn is missed.
     */
    fun drawnBy(page: PDPage, into: MutableSet<COSBase>) {
        val found = identitySet<COSBase>()
        try {
            DrawnXObjects(found, identitySet()).processPage(page)
            into += found
        } catch (_: Exception) {
            collect(page.resources?.cosObject, into)
        }
    }

    /**
     * Empties, in place, each of [drawn] that none of [users] (the pages that stay) nor the form's
     * default resources might still draw. Only for objects that belong to [doc] alone: a document
     * still open that shares them would lose them too.
     */
    fun emptyUnused(doc: PDDocument, drawn: Set<COSBase>, users: Iterable<PDPage>, check: () -> Unit) {
        if (drawn.isEmpty()) return
        val used = identitySet<COSBase>()
        // Most pages' resources don't reach any of [drawn], and their content needn't be read.
        val reaches = IdentityHashMap<COSBase, Boolean>()
        for (page in users) {
            check()
            usedBy(page, used, readContent = mayDraw(page, drawn, reaches))
        }
        collect(doc.documentCatalog.cosObject.getCOSDictionary(COSName.ACRO_FORM)?.getDictionaryObject(COSName.DR), used)
        for (xobject in drawn) if (xobject !in used && xobject is COSStream) empty(xobject)
    }

    /**
     * Adds to [into] every image and form [page] might draw: what its content draws, and, to be safe,
     * all those reachable from its patterns, fonts, graphics states, shadings and annotation
     * appearances, which aren't followed in detail. If its content can't be read, everything its
     * resources list counts. Without [readContent], only its annotations' appearances are looked at.
     */
    private fun usedBy(page: PDPage, into: MutableSet<COSBase>, readContent: Boolean) {
        if (readContent) {
            val contexts = identitySet<COSBase>()
            // Its own resources, or those it inherits from the page tree.
            page.resources?.cosObject?.let(contexts::add)
            try {
                DrawnXObjects(into, contexts).processPage(page)
                for (context in contexts) {
                    val resources = context as? COSDictionary ?: continue
                    for (key in listOf(COSName.PATTERN, COSName.FONT, COSName.EXT_G_STATE, COSName.SHADING, COSName.PROPERTIES)) {
                        collect(resources.getDictionaryObject(key), into)
                    }
                }
            } catch (_: Exception) {
                collect(page.resources?.cosObject, into)
            }
        }
        page.cosObject.getCOSArray(COSName.ANNOTS)?.forEach { annotation ->
            collect((annotation.resolve() as? COSDictionary)?.getDictionaryObject(COSName.AP), into)
        }
    }

    /** Adds to [into] every image and form stream reachable from [start], not following links up to pages. */
    private fun collect(start: COSBase?, into: MutableSet<COSBase>) {
        val seen = identitySet<COSBase>()
        val pending = ArrayDeque<COSBase>()
        start?.resolve()?.let(pending::add)
        while (pending.isNotEmpty()) {
            val item = pending.removeLast()
            if (!seen.add(item)) continue
            when (item) {
                is COSDictionary -> {
                    if (item is COSStream) {
                        val subtype = item.getCOSName(COSName.SUBTYPE)
                        if (subtype == COSName.IMAGE || subtype == COSName.FORM) into += item
                    }
                    for ((key, value) in item.entrySet()) {
                        if (key == COSName.PARENT || key == COSName.P) continue
                        value.resolve()?.let(pending::add)
                    }
                }
                is COSArray -> item.forEach { value -> value.resolve()?.let(pending::add) }
            }
        }
    }

    /** Empties an image or form that only pages which have gone drew: a white pixel, or nothing to draw. */
    private fun empty(stream: COSStream) {
        val image = stream.getCOSName(COSName.SUBTYPE) == COSName.IMAGE
        val keep = if (image) setOf(COSName.TYPE, COSName.SUBTYPE) else setOf(COSName.TYPE, COSName.SUBTYPE, COSName.BBOX, COSName.MATRIX, COSName.FORMTYPE)
        for (key in stream.keySet().toList()) if (key !in keep) stream.removeItem(key)
        if (image) {
            stream.setInt(COSName.WIDTH, 1)
            stream.setInt(COSName.HEIGHT, 1)
            stream.setInt(COSName.BITS_PER_COMPONENT, 8)
            stream.setItem(COSName.COLORSPACE, COSName.DEVICEGRAY)
            stream.createOutputStream().use { it.write(0xFF) }
        } else {
            stream.setItem(COSName.RESOURCES, COSDictionary())
            stream.createOutputStream().close()
        }
    }

    /**
     * A copy of [resources] (a page's) whose list of images and forms keeps only those [page] draws,
     * for a page copied into another document: the others would go with it. The rest is shared.
     * [resources] itself if the page's content can't be read.
     */
    fun drawnOnly(page: PDPage, resources: COSDictionary): COSDictionary {
        val xobjects = resources.getDictionaryObject(COSName.XOBJECT) as? COSDictionary ?: return resources
        val drawn = identitySet<COSBase>()
        try {
            DrawnXObjects(drawn, identitySet()).processPage(page)
        } catch (_: Exception) {
            return resources
        }
        val kept = COSDictionary()
        for (name in xobjects.keySet()) {
            if (xobjects.getDictionaryObject(name) in drawn) kept.setItem(name, xobjects.getItem(name))
        }
        if (kept.size() == xobjects.size()) return resources
        return COSDictionary(resources).apply { setItem(COSName.XOBJECT, kept) }
    }

    private fun COSBase.resolve(): COSBase? = if (this is COSObject) `object` else this

    private fun <T> identitySet(): MutableSet<T> = Collections.newSetFromMap(IdentityHashMap())
}
