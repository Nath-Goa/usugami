package io.github.nath_goa.usugami

import android.graphics.PointF
import com.tom_roush.pdfbox.contentstream.PDFStreamEngine
import com.tom_roush.pdfbox.contentstream.operator.DrawObject
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.contentstream.operator.state.Concatenate
import com.tom_roush.pdfbox.contentstream.operator.state.Restore
import com.tom_roush.pdfbox.contentstream.operator.state.Save
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.util.Matrix

/** An image a page draws, and where: [matrix] takes the image's unit square to the page's own coordinates. */
class PlacedImage(val image: PDImageXObject, private val matrix: Matrix) {
    /** Where the point ([x], [y]) of the unit square lands; (0, 0) is the image's bottom-left corner. */
    fun corner(x: Float, y: Float): PointF = matrix.transformPoint(x, y)
}

/** Every image [page] draws, those inside its forms included, in drawing order. */
fun imagesOn(page: PDPage): List<PlacedImage> {
    val found = ArrayList<PlacedImage>()
    val engine = object : PDFStreamEngine() {
        override fun processOperator(operator: Operator, operands: List<COSBase>) {
            val name = operands.firstOrNull() as? COSName
            val xobject = if (operator.name == "Do" && name != null) resources.getXObject(name) else null
            if (xobject is PDImageXObject) {
                found += PlacedImage(xobject, graphicsState.currentTransformationMatrix.clone())
            } else {
                super.processOperator(operator, operands)
            }
        }
    }
    engine.addOperator(Concatenate())
    engine.addOperator(Save())
    engine.addOperator(Restore())
    engine.addOperator(DrawObject())
    engine.processPage(page)
    return found
}
