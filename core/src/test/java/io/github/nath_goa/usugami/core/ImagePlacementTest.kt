package io.github.nath_goa.usugami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Where a point of an image as shown lands on its page: text recognition reads the original image
 * and puts each line's text there, so it has to be exactly where the PDF draws that point.
 */
class ImagePlacementTest {
    private val eps = 1e-6

    /** Adds one [width] × [height] image (as stored) to a PDF; returns its placement and the PDF. */
    private fun place(width: Int, height: Int, settings: LayoutSettings, exif: Int = 1, rotation: Int = 0): Pair<ImagePlacement, String> {
        val out = ByteArrayOutputStream()
        val builder = ImagePdfBuilder(out, settings, "Test", "Usugami")
        val placement = builder.addPage(PageImage.Raster(TestImages.BufferedImageSource(TestImages.pattern(width, height))), exif, rotation)
        builder.finish()
        return placement to String(out.toByteArray(), Charsets.ISO_8859_1)
    }

    private fun assertPoint(message: String, x: Double, y: Double, actual: Pair<Double, Double>, tolerance: Double = eps) {
        assertEquals("$message x", x, actual.first, tolerance)
        assertEquals("$message y", y, actual.second, tolerance)
    }

    @Test
    fun a4WithMargin() {
        val (placement, pdf) = place(300, 400, LayoutSettings(PageSize.A4, PageOrientation.PORTRAIT, Margin.SMALL))
        // 559.28 pt wide between the margins, so 745.70 pt high, centred down the page.
        assertPoint("top-left", 18.0, 793.7953, placement.toPage(0.0, 0.0), 1e-4)
        assertPoint("bottom-right", 577.2756, 48.0945, placement.toPage(1.0, 1.0), 1e-4)
        assertPoint("a word a quarter across, halfway down", 18 + 559.2756 / 4, 48.0945 + 745.7008 / 2, placement.toPage(0.25, 0.5), 1e-4)
        // The page draws the image with this very matrix.
        assertTrue(pdf.contains(placement.matrix().joinToString(" ") { PdfSyntax.number(it) } + " cm\n/Im0 Do"))
    }

    @Test
    fun fitWithMargin() {
        // Fit: a point per pixel, with a big margin scaled to the image (54/595.28 of its short side).
        val (placement, pdf) = place(1000, 500, LayoutSettings(PageSize.FIT, PageOrientation.AUTO, Margin.BIG))
        val m = 54.0 / PageSize.A4.shortSide * 500
        assertEquals(1000 + 2 * m, placement.geometry.pageWidth, eps)
        assertPoint("top-left", m, m + 500, placement.toPage(0.0, 0.0))
        assertPoint("middle", m + 500, m + 250, placement.toPage(0.5, 0.5))
        assertPoint("pixel (200, 100)", m + 200, m + 400, placement.toPage(200 / 1000.0, 100 / 500.0))
        assertTrue(pdf.contains(placement.matrix().joinToString(" ") { PdfSyntax.number(it) } + " cm\n/Im0 Do"))
    }

    @Test
    fun landscapePage() {
        val (placement, _) = place(400, 300, LayoutSettings(PageSize.A4, PageOrientation.LANDSCAPE, Margin.NONE))
        // As high as the page (595.28 pt), so 793.70 pt wide and centred across it.
        assertPoint("top-left", 24.0945, 595.2756, placement.toPage(0.0, 0.0), 1e-4)
        assertPoint("bottom-right", 817.7953, 0.0, placement.toPage(1.0, 1.0), 1e-4)
    }

    @Test
    fun turnedAQuarter() {
        // Stored 400 × 300, turned a quarter clockwise by the user: shown 300 × 400, filling A4's width.
        val (placement, pdf) = place(400, 300, LayoutSettings(PageSize.A4, PageOrientation.PORTRAIT, Margin.NONE), rotation = 90)
        assertPoint("top-left as shown", 0.0, 817.7953, placement.toPage(0.0, 0.0), 1e-4)
        assertPoint("bottom-right as shown", 595.2756, 24.0945, placement.toPage(1.0, 1.0), 1e-4)
        // The stored image's top-left corner (unit square (0, 1)) is drawn at the shown top-right.
        val m = placement.matrix()
        assertPoint("stored top-left", m[2] + m[4], m[3] + m[5], placement.toPage(1.0, 0.0))
        assertTrue(pdf.contains(m.joinToString(" ") { PdfSyntax.number(it) } + " cm\n/Im0 Do"))
        // The same photo stored sideways with EXIF orientation 6 lands the same way.
        assertEquals(placement, place(400, 300, LayoutSettings(PageSize.A4, PageOrientation.PORTRAIT, Margin.NONE), exif = 6).first)
    }

    /** Every orientation and turn: a stored point and the same point as shown land on the same spot. */
    @Test
    fun followsTheDrawingMatrixForEveryOrientation() {
        val geometry = PageGeometry(600.0, 800.0, 30.0, 40.0, 500.0, 700.0)
        for (exif in 1..8) {
            for (rotation in listOf(0, 90, 180, 270)) {
                val transform = ImageTransform.of(exif, rotation)
                assertEquals(ImageTransform.IDENTITY, transform.then(transform.inverse()))
                val placement = ImagePlacement(geometry, transform)
                val m = placement.matrix()
                for ((s, t) in listOf(0.0 to 0.0, 1.0 to 0.0, 0.3 to 0.8, 0.9 to 0.25)) {
                    val shownS = transform.a * s + transform.b * t + transform.c
                    val shownT = transform.d * s + transform.e * t + transform.f
                    assertPoint(
                        "exif=$exif rotation=$rotation ($s, $t)",
                        m[0] * s + m[2] * (1 - t) + m[4],
                        m[1] * s + m[3] * (1 - t) + m[5],
                        placement.toPage(shownS.toDouble(), shownT.toDouble()),
                    )
                }
            }
        }
    }
}
