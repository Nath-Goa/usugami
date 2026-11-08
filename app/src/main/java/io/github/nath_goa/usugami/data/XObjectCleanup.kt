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
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDTransparencyGroup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.util.Matrix
import io.github.nath_goa.usugami.core.Areas
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.max
import kotlin.math.min

/**
 * Images and forms (XObjects), patterns and soft masks that only pages which have gone drew. Pages
 * often share one list of resources (many PDF makers put every image in it), so such an image would
 * otherwise stay in the file through the pages that are left. Used when pages are redacted, deleted
 * or left out.
 */
internal object XObjectCleanup {
    /**
     * Finds the images and forms a page's content draws, those inside forms included, and the
     * patterns it paints with and the soft masks of the graphics states it sets, with all they hold:
     * those draw content of their own, text too.
     */
    private class DrawnXObjects(private val drawn: MutableSet<COSBase>, private val contexts: MutableSet<COSBase>) : PDFStreamEngine() {
        /** The patterns and graphics states already followed: a page may set the same one thousands of times. */
        private val followed = identitySet<COSBase>()

        init {
            addOperator(DrawObject())
        }

        override fun processOperator(operator: Operator, operands: List<COSBase>) {
            when (operator.name) {
                "Do" -> drawnXObject(operands)?.let { xobject ->
                    drawn += xobject
                    if (xobject.getCOSName(COSName.SUBTYPE) == COSName.FORM) (xobject.getDictionaryObject(COSName.RESOURCES) as? COSDictionary)?.let(contexts::add)
                }
                // A pattern is named last, after any colour it is painted in.
                "scn", "SCN" -> follow(resource(COSName.PATTERN, operands.lastOrNull()))
                "gs" -> follow(resource(COSName.EXT_G_STATE, operands.firstOrNull()))
            }
            super.processOperator(operator, operands)
        }

        private fun follow(resource: COSBase?) {
            if (resource != null && followed.add(resource)) collect(resource, drawn)
        }

        /** The resource of the kind [kind] that [name] names, among those the content is drawn with. */
        private fun resource(kind: COSName, name: COSBase?): COSBase? =
            (name as? COSName)?.let { resources?.cosObject?.getCOSDictionary(kind)?.getDictionaryObject(it) }
    }

    /**
     * An image or form [xobject] drawn by a page: [matrix] (a, b, c, d, e, f) takes the space it is
     * drawn in, where it covers [extent] (left, bottom, right, top: the unit square for an image,
     * the bounding box for a form), to the page's own coordinates. [clip] is the area, in those, to
     * which the forms it is drawn inside and the clipping paths set before it cut it (at most: the
     * bounds of each path); null if nothing does.
     */
    class Placement(val xobject: COSBase, val matrix: FloatArray, val extent: FloatArray, val clip: FloatArray?)

    /**
     * Finds where a page's content draws each image and form, those inside forms included; with
     * [annotationsOnly], only where its annotations' appearances do (see [place]).
     */
    private class PlacedXObjects(private val annotationsOnly: Boolean = false) : PDFStreamEngine() {
        val found = ArrayList<Placement>()
        private var inAnnotation = false
        private var clip: FloatArray? = null

        /** [clip] as each q saved it, for its Q; those below [floor] belong to the content a form is drawn in. */
        private val saved = ArrayList<FloatArray?>()
        private var floor = 0

        /** The bounds, in the page's coordinates, of the path being built, and whether it then clips (W, W*). */
        private var path: FloatArray? = null
        private var clips = false

        init {
            addOperator(Save())
            addOperator(Restore())
            addOperator(Concatenate())
            addOperator(DrawObject())
        }

        override fun processOperator(operator: Operator, operands: List<COSBase>) {
            if (annotationsOnly && !inAnnotation) return
            when (operator.name) {
                "Do" -> drawnXObject(operands)?.let { xobject ->
                    val ctm = graphicsState.currentTransformationMatrix
                    when (xobject.getCOSName(COSName.SUBTYPE)) {
                        COSName.IMAGE -> found += Placement(xobject, ctm.values(), floatArrayOf(0f, 0f, 1f, 1f), clip)
                        COSName.FORM -> PDFormXObject(xobject).let { form ->
                            form.bBox?.let { box -> found += Placement(xobject, form.matrix.multiply(ctm).values(), box.corners(), clip) }
                        }
                    }
                }
                "q" -> saved += clip
                "Q" -> if (saved.size > floor) clip = saved.removeAt(saved.size - 1)
                // A picture cropped in Word or PowerPoint is the whole image drawn inside "x y w h re W n".
                "re" -> numbers(operands, 4)?.let { (x, y, w, h) -> extend(x, y); extend(x + w, y); extend(x, y + h); extend(x + w, y + h) }
                "m", "l" -> numbers(operands, 2)?.let { extend(it[0], it[1]) }
                // A curve stays within its points and control points.
                "c", "v", "y" -> numbers(operands, if (operator.name == "c") 6 else 4)?.let { for (i in it.indices step 2) extend(it[i], it[i + 1]) }
                "W", "W*" -> clips = true
                "n", "S", "s", "f", "F", "f*", "B", "B*", "b", "b*" -> {
                    if (clips) path?.let { area -> clip = cut(clip, area) }
                    path = null
                    clips = false
                }
            }
            super.processOperator(operator, operands)
        }

        private fun numbers(operands: List<COSBase>, count: Int): FloatArray? {
            if (operands.size < count) return null
            return FloatArray(count) { (operands[it] as? COSNumber)?.floatValue() ?: return null }
        }

        /** Adds the point ([x], [y]), in the space the content draws in, to [path]'s bounds. */
        private fun extend(x: Float, y: Float) {
            val m = graphicsState.currentTransformationMatrix
            val px = m.scaleX * x + m.shearX * y + m.translateX
            val py = m.shearY * x + m.scaleY * y + m.translateY
            if (!px.isFinite() || !py.isFinite()) return
            path = path?.let { floatArrayOf(min(it[0], px), min(it[1], py), max(it[2], px), max(it[3], py)) } ?: floatArrayOf(px, py, px, py)
        }

        /** [outer] cut to [area]: nothing at all if they don't overlap. */
        private fun cut(outer: FloatArray?, area: FloatArray) = outer?.let { Areas.intersection(it, area) ?: floatArrayOf(0f, 0f, 0f, 0f) } ?: area

        // A form draws only inside its bounding box.
        override fun showForm(form: PDFormXObject) = inside(form) { super.showForm(form) }

        override fun showTransparencyGroup(form: PDTransparencyGroup) = inside(form) { super.showTransparencyGroup(form) }

        private fun inside(form: PDFormXObject, draw: () -> Unit) {
            within(form.bBox?.let { box -> Areas.mapped(form.matrix.multiply(graphicsState.currentTransformationMatrix).values(), box.corners()) }, draw)
        }

        /** Runs [draw] cut to [area] (in the page's coordinates; null for no cut), as a form's content is to its box. */
        private fun within(area: FloatArray?, draw: () -> Unit) {
            val outer = clip
            val outerFloor = floor
            if (area != null) clip = cut(outer, area)
            floor = saved.size
            try {
                draw()
            } finally {
                // What the form saved and didn't restore, and the clip it set, end with it.
                while (saved.size > floor) saved.removeAt(saved.size - 1)
                floor = outerFloor
                clip = outer
                path = null
                clips = false
            }
        }

        /**
         * Where [annotation]'s appearance, as viewers show it in its current state, draws images and
         * forms: PDFBox fits it to the annotation's rectangle and cuts it to that. Only once [processPage]
         * has set the page up.
         */
        fun place(annotation: PDAnnotation): List<Placement> {
            val rectangle = annotation.rectangle ?: return emptyList()
            val start = found.size
            inAnnotation = true
            try {
                within(rectangle.corners()) { showAnnotation(annotation) }
            } finally {
                inAnnotation = false
            }
            return found.subList(start, found.size).toList()
        }

        private fun Matrix.values() = floatArrayOf(scaleX, shearY, shearX, scaleY, translateX, translateY)

        private fun PDRectangle.corners() = floatArrayOf(lowerLeftX, lowerLeftY, upperRightX, upperRightY)
    }

    /**
     * The image or form a Do operator with [operands] draws, from the resources it is drawn with, as
     * it is stored. PDFBox would read a JPEG 2000 image to make one of it, and fails without a
     * decoder for them: such an image would seem not to be drawn, and be dropped or kept wrongly.
     */
    private fun PDFStreamEngine.drawnXObject(operands: List<COSBase>): COSStream? {
        val name = operands.firstOrNull() as? COSName ?: return null
        return resources?.cosObject?.getCOSDictionary(COSName.XOBJECT)?.getDictionaryObject(name) as? COSStream
    }

    /** Where [page]'s content draws each image and form (see [Placement]); null if its content can't be read. */
    fun placements(page: PDPage): List<Placement>? = try {
        PlacedXObjects().apply { processPage(page) }.found
    } catch (_: Exception) {
        null
    }

    /**
     * Where the appearances of [page]'s annotations draw any of [xobjects] (see [Placement]), as
     * viewers show them: each in its current state, cut to its annotation's rectangle. What else of
     * [xobjects] an annotation holds goes in [unseen], as kept out of sight: all it holds when it is
     * hidden or not for the screen, what only its other looks (pressed, another state) draw, and
     * all it holds when its appearance can't be read.
     */
    fun annotationPlacements(page: PDPage, xobjects: Set<COSBase>, unseen: MutableSet<COSBase>): List<Placement> {
        val annotations = page.cosObject.getCOSArray(COSName.ANNOTS) ?: return emptyList()
        val placed = ArrayList<Placement>()
        // Set up for the page once, and only for a page with such an annotation.
        var reader: PlacedXObjects? = null
        for (item in annotations) {
            val dictionary = item.resolve() as? COSDictionary ?: continue
            val held = identitySet<COSBase>().also { collect(dictionary.getDictionaryObject(COSName.AP), it) }
            held.retainAll(xobjects)
            if (held.isEmpty()) continue
            val shown = try {
                val annotation = PDAnnotation.createAnnotation(dictionary)
                if (annotation.isHidden || annotation.isNoView) {
                    emptyList()
                } else {
                    val engine = reader ?: PlacedXObjects(annotationsOnly = true).also { it.processPage(page) }
                    reader = engine
                    engine.place(annotation).filter { it.xobject in xobjects }
                }
            } catch (_: Exception) {
                // The next one is read afresh, not after an appearance cut short.
                reader = null
                emptyList()
            }
            placed += shown
            for (xobject in held) if (shown.none { it.xobject === xobject }) unseen += xobject
        }
        return placed
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
     * Adds to [into] the images, forms, patterns and soft masks [page] draws (see [DrawnXObjects]).
     * If its content can't be read, everything its resources list counts, so nothing it might have
     * drawn is missed.
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
        // What an image drawn there needs to look as it does: its soft mask, its mask, its
        // alternates. [drawn] can hold them too, when a page that went couldn't be read.
        for (xobject in used.toList()) {
            if (xobject is COSStream && xobject.getCOSName(COSName.SUBTYPE) == COSName.IMAGE) collect(xobject, used)
        }
        for (xobject in drawn) if (xobject !in used && xobject is COSStream) empty(xobject)
    }

    /**
     * Adds to [into] every image, form and pattern [page] might draw: what its content draws and
     * paints with, and, to be safe, all those reachable from its fonts, shadings and annotation
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
                    for (key in listOf(COSName.FONT, COSName.SHADING, COSName.PROPERTIES)) {
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

    /** Adds to [into] every image, form and (tiling) pattern stream reachable from [start], not following links up to pages. */
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
                        if (subtype == COSName.IMAGE || subtype == COSName.FORM || item.containsKey(COSName.PATTERN_TYPE)) into += item
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

    /** Empties an image, form or pattern that only pages which have gone drew: a white pixel, or nothing to draw. */
    private fun empty(stream: COSStream) {
        val image = stream.getCOSName(COSName.SUBTYPE) == COSName.IMAGE
        val keep = when {
            image -> setOf(COSName.TYPE, COSName.SUBTYPE)
            stream.containsKey(COSName.PATTERN_TYPE) -> setOf(
                COSName.TYPE, COSName.PATTERN_TYPE, COSName.PAINT_TYPE, COSName.TILING_TYPE, COSName.BBOX, COSName.X_STEP, COSName.Y_STEP, COSName.MATRIX,
            )
            else -> setOf(COSName.TYPE, COSName.SUBTYPE, COSName.BBOX, COSName.MATRIX, COSName.FORMTYPE)
        }
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
