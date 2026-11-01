package io.github.nath_goa.usugami.data

import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.util.Matrix
import io.github.nath_goa.usugami.core.EditLayout
import io.github.nath_goa.usugami.core.InkItem
import io.github.nath_goa.usugami.core.PageItem
import io.github.nath_goa.usugami.core.PathOp
import io.github.nath_goa.usugami.core.PictureItem
import io.github.nath_goa.usugami.core.ShapeItem
import io.github.nath_goa.usugami.core.TextItem
import io.github.nath_goa.usugami.core.topLeftMatrix

/**
 * Draws Edit PDF's items into the pages as ordinary page content, in the order they were added,
 * exactly where the preview showed them on each page as it is shown (turned or cropped too).
 * Strokes and shapes become vector paths with round ends and corners, text becomes real text (see
 * [PageText]) and pictures become images. Pages without items aren't touched.
 */
internal object PageEditing {
    /**
     * Draws [items] on [doc]'s pages. [stamps] has a page with the text of each [TextItem] in
     * [items], in order, as [PageText.render] makes it; it must stay open until [doc] is saved.
     */
    fun draw(doc: PDDocument, items: List<PageItem>, stamps: PDDocument?, check: () -> Unit) {
        val texts = items.filterIsInstance<TextItem>()
        require(texts.isEmpty() || stamps?.numberOfPages == texts.size)
        // One importer for all the text, so the font they share is copied once.
        val layer = LayerUtility(doc)
        val forms = HashMap<Long, PDFormXObject>()
        texts.forEachIndexed { index, item ->
            check()
            forms[item.id] = layer.importPageAsForm(requireNotNull(stamps), index)
        }
        // A marker's ink: see-through, and multiplied with the page, so black text under it stays black.
        val highlighter = PDExtendedGraphicsState().apply {
            strokingAlphaConstant = EditLayout.HIGHLIGHTER_ALPHA
            blendMode = BlendMode.MULTIPLY
        }
        for ((index, onPage) in items.groupBy { it.page }) {
            check()
            require(index in 0 until doc.numberOfPages)
            val page = doc.getPage(index)
            val frame = page.frame()
            // Added after the page's own drawing, which is wrapped in q/Q, so whatever state that
            // leaves behind can't move or recolour what is added.
            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { content ->
                content.transform(frame.topLeftMatrix.toMatrix())
                for (item in onPage) {
                    check()
                    content.saveGraphicsState()
                    when (item) {
                        is TextItem -> {
                            val form = forms.getValue(item.id)
                            val scale = item.size / PageText.REFERENCE
                            // The form's y points up; the text box's top-left corner is PAD in from its top-left corner.
                            val top = form.bBox.upperRightY - PageText.PAD
                            content.transform(Matrix(scale, 0f, 0f, -scale, item.x - scale * PageText.PAD, item.y + scale * top))
                            content.drawForm(form)
                        }
                        is PictureItem -> {
                            val image = PagePictures.image(doc, item)
                            // An image fills the unit square with its first row at the top.
                            content.drawImage(image, Matrix(item.width, 0f, 0f, -item.height, item.x, item.y + item.height))
                        }
                        is InkItem -> {
                            if (item.highlighter) content.setGraphicsStateParameters(highlighter)
                            stroke(content, item, item.color, item.width)
                        }
                        is ShapeItem -> {
                            if (item.isBox && item.filled) {
                                content.setNonStrokingColor(red(item.color), green(item.color), blue(item.color))
                                path(content, EditLayout.outline(item))
                                content.fill()
                            } else {
                                stroke(content, item, item.color, item.width)
                            }
                        }
                    }
                    content.restoreGraphicsState()
                }
            }
        }
    }

    private fun stroke(content: PDPageContentStream, item: PageItem, color: Int, width: Float) {
        content.setStrokingColor(red(color), green(color), blue(color))
        content.setLineWidth(width)
        content.setLineCapStyle(1)
        content.setLineJoinStyle(1)
        path(content, EditLayout.outline(item))
        content.stroke()
    }

    private fun path(content: PDPageContentStream, ops: List<PathOp>) {
        for (op in ops) {
            when (op) {
                is PathOp.Move -> content.moveTo(op.x, op.y)
                is PathOp.Line -> content.lineTo(op.x, op.y)
                is PathOp.Cubic -> content.curveTo(op.x1, op.y1, op.x2, op.y2, op.x, op.y)
                PathOp.Close -> content.closePath()
            }
        }
    }

    private fun red(argb: Int) = (argb shr 16 and 0xFF) / 255f
    private fun green(argb: Int) = (argb shr 8 and 0xFF) / 255f
    private fun blue(argb: Int) = (argb and 0xFF) / 255f

    private fun FloatArray.toMatrix() = Matrix(this[0], this[1], this[2], this[3], this[4], this[5])
}
