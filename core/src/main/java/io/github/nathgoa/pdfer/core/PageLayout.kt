package io.github.nathgoa.pdfer.core

import kotlin.math.max
import kotlin.math.min

/** Paper sizes in PDF points (1/72 inch), portrait. FIT makes every page the size of its image. */
enum class PageSize(val shortSide: Double, val longSide: Double) {
    FIT(0.0, 0.0),
    A4(mm(210.0), mm(297.0)),
    LETTER(612.0, 792.0),
    LEGAL(612.0, 1008.0),
    A3(mm(297.0), mm(420.0)),
    A5(mm(148.0), mm(210.0)),
}

enum class PageOrientation { AUTO, PORTRAIT, LANDSCAPE }

/** Margin around the image, in points on a fixed paper size. */
enum class Margin(val points: Double) {
    NONE(0.0),
    SMALL(18.0), // 1/4 inch
    BIG(54.0),   // 3/4 inch
}

data class LayoutSettings(
    val pageSize: PageSize = PageSize.A4,
    val orientation: PageOrientation = PageOrientation.PORTRAIT,
    val margin: Margin = Margin.NONE,
)

/**
 * Page box and the rectangle the image is drawn into, in points.
 * [imageX]/[imageY] are measured from the bottom-left corner, as in PDF.
 */
data class PageGeometry(
    val pageWidth: Double,
    val pageHeight: Double,
    val imageX: Double,
    val imageY: Double,
    val imageWidth: Double,
    val imageHeight: Double,
) {
    /** Distance from the top edge of the page to the top edge of the image. */
    val imageTop: Double get() = pageHeight - imageY - imageHeight
}

object PageLayout {
    /** Largest page side Acrobat and most viewers accept (200 inches). */
    const val MAX_PAGE_SIDE = 14_400.0

    /** Smallest page side Acrobat accepts. */
    const val MIN_PAGE_SIDE = 3.0

    /**
     * Lays out one image on its page.
     * [imageWidth] and [imageHeight] are the pixel size of the image as it will appear,
     * i.e. after EXIF orientation and the user's rotation.
     */
    fun compute(imageWidth: Int, imageHeight: Int, settings: LayoutSettings): PageGeometry {
        require(imageWidth > 0 && imageHeight > 0) { "Invalid image size ${imageWidth}x$imageHeight" }
        val w = imageWidth.toDouble()
        val h = imageHeight.toDouble()
        return if (settings.pageSize == PageSize.FIT) fit(w, h, settings.margin) else onPaper(w, h, settings)
    }

    /**
     * One pixel becomes one point. The margin scales with the image so a "small" border looks
     * the same on a 500 px image as on a 4000 px one (relative to an A4 page's short side).
     */
    private fun fit(w: Double, h: Double, margin: Margin): PageGeometry {
        val m = margin.points / PageSize.A4.shortSide * min(w, h)
        val pageW = w + 2 * m
        val pageH = h + 2 * m
        var scale = min(1.0, MAX_PAGE_SIDE / max(pageW, pageH))
        if (min(pageW, pageH) * scale < MIN_PAGE_SIDE) {
            scale = min(MIN_PAGE_SIDE / min(pageW, pageH), MAX_PAGE_SIDE / max(pageW, pageH))
        }
        return PageGeometry(pageW * scale, pageH * scale, m * scale, m * scale, w * scale, h * scale)
    }

    /** Fits the image inside the margins of a fixed paper size, keeping its aspect ratio, centred. */
    private fun onPaper(w: Double, h: Double, settings: LayoutSettings): PageGeometry {
        val landscape = when (settings.orientation) {
            PageOrientation.AUTO -> w > h
            PageOrientation.PORTRAIT -> false
            PageOrientation.LANDSCAPE -> true
        }
        val size = settings.pageSize
        val pageW = if (landscape) size.longSide else size.shortSide
        val pageH = if (landscape) size.shortSide else size.longSide
        val m = settings.margin.points
        val scale = min((pageW - 2 * m) / w, (pageH - 2 * m) / h)
        val drawnW = w * scale
        val drawnH = h * scale
        return PageGeometry(pageW, pageH, (pageW - drawnW) / 2, (pageH - drawnH) / 2, drawnW, drawnH)
    }
}

private fun mm(value: Double) = value * 72.0 / 25.4
