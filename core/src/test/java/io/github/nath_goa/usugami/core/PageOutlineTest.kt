package io.github.nath_goa.usugami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Finding the page on synthetic photos: a sheet of paper with lines of print, on a darker wooden
 * table with sensor noise, upright, tilted, photographed at a slant, half in shadow, with a folded
 * corner, next to other things; on a brighter table, a white, light grey, textured or pale blue
 * desk; and the photos where no page can be found for sure, tiles and a keyboard among them.
 */
class PageOutlineTest {

    /**
     * A grey photo [width] × [height] of a page whose corners (fractions of the photo, clockwise from
     * the page's top-left) are [corners]. Text lines run across the page; [shade] darkens a point of
     * the photo (1 = no shadow); [extra] paints more things on the table. The table is wood unless
     * [desk] gives its level at each point (fractions of the photo); a page on a desk casts a
     * [shadow] that much darker along its edges. With [deskColour], [argb] makes a colour photo of
     * the grey page on a desk of that colour. [soft] blurs the grey photo that many pixels each way,
     * as a lens and a phone's scaling soften its edges.
     */
    private class Photo(
        val width: Int,
        val height: Int,
        val corners: List<CropPoint>,
        val paper: Int = 226,
        val table: Int = 72,
        val noise: Int = 10,
        val seed: Int = 1,
        val shade: (x: Double, y: Double) -> Double = { _, _ -> 1.0 },
        val fold: Double = 0.0,
        val extra: (x: Double, y: Double) -> Int? = { _, _ -> null },
        val desk: ((x: Double, y: Double) -> Double)? = null,
        val shadow: Int = 0,
        val deskColour: IntArray? = null,
        val soft: Int = 0,
    ) {
        /** The page's homography: page coordinates (0..1 across and down) to photo pixels. */
        private val toPhoto = Homography.squareTo(corners.map { CropPoint(it.x * width, it.y * height) })
        private val toPage = toPhoto.inverse()
        private val xs = DoubleArray(4) { corners[it].x.toDouble() * width }
        private val ys = DoubleArray(4) { corners[it].y.toDouble() * height }

        fun luma(): ByteArray {
            val random = Random(seed)
            val out = ByteArray(width * height)
            for (y in 0 until height) for (x in 0 until width) {
                // Two samples a pixel across and down, for soft edges.
                var sum = 0.0
                for (sy in 0..1) for (sx in 0..1) sum += level(x + 0.25 + sx * 0.5, y + 0.25 + sy * 0.5)
                val v = sum / 4 * shade(x.toDouble() / width, y.toDouble() / height) + random.nextInt(-noise, noise + 1)
                out[y * width + x] = v.roundToInt().coerceIn(0, 255).toByte()
            }
            if (soft == 0) return out
            fun at(x: Int, y: Int) = out[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)].toInt() and 0xFF
            return ByteArray(out.size) { i ->
                var sum = 0
                for (dy in -soft..soft) for (dx in -soft..soft) sum += at(i % width + dx, i / width + dy)
                (sum / ((2 * soft + 1) * (2 * soft + 1))).toByte()
            }
        }

        fun argb(): IntArray {
            val colour = checkNotNull(deskColour)
            val random = Random(seed)
            return IntArray(width * height) { i ->
                val x = i % width
                val y = i / width
                var argb = 0xFF
                for (channel in 0..2) {
                    var sum = 0.0
                    for (sy in 0..1) for (sx in 0..1) {
                        val px = x + 0.25 + sx * 0.5
                        val py = y + 0.25 + sy * 0.5
                        sum += pageLevel(px, py) ?: (colour[channel] - shadowAt(px, py))
                    }
                    val v = sum / 4 * shade(x.toDouble() / width, y.toDouble() / height) + random.nextInt(-noise, noise + 1)
                    argb = (argb shl 8) or v.roundToInt().coerceIn(0, 255)
                }
                argb
            }
        }

        private fun level(x: Double, y: Double): Double {
            extra(x / width, y / height)?.let { return it.toDouble() }
            pageLevel(x, y)?.let { return it }
            val desk = desk ?: return table + 10 * sin(y / 9.0 + 3 * sin(x / 70.0))
            return desk(x / width, y / height) - shadowAt(x, y)
        }

        /** The page's level at a point, or null off the page. */
        private fun pageLevel(x: Double, y: Double): Double? {
            val (u, v) = toPage.map(x, y)
            // A corner folded over: the table shows through a triangle at the page's top-right.
            val folded = fold > 0 && (1 - u) + v < fold
            if (u !in 0.0..1.0 || v !in 0.0..1.0 || folded) return null
            // Lines of print inside the margins, and a heading.
            val printed = u in 0.12..0.88 && ((v in 0.1..0.14) || (v in 0.2..0.9 && ((v - 0.2) / 0.035).toInt() % 2 == 0 && u < 0.88 - 0.2 * ((v * 37).toInt() % 3)))
            return if (printed) 60.0 else paper.toDouble()
        }

        /** How much the page's shadow darkens the desk at a point off the page: most at its edge, none 3 pixels out. */
        private fun shadowAt(x: Double, y: Double): Double {
            if (shadow == 0) return 0.0
            var nearest = Double.MAX_VALUE
            for (i in 0..3) {
                val j = (i + 1) % 4
                val dx = xs[j] - xs[i]
                val dy = ys[j] - ys[i]
                val t = (((x - xs[i]) * dx + (y - ys[i]) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
                nearest = min(nearest, hypot(x - xs[i] - t * dx, y - ys[i] - t * dy))
            }
            return shadow * max(0.0, 1 - nearest / 3)
        }
    }

    /** A 3 × 3 projective map, row by row (the last entry 1). */
    private class Homography(val m: DoubleArray) {
        fun map(x: Double, y: Double): Pair<Double, Double> {
            val w = m[6] * x + m[7] * y + m[8]
            return (m[0] * x + m[1] * y + m[2]) / w to (m[3] * x + m[4] * y + m[5]) / w
        }

        fun inverse(): Homography {
            val (a, b, c, d, e) = m.toList()
            val f = m[5]
            val g = m[6]
            val h = m[7]
            val i = m[8]
            val co = doubleArrayOf(e * i - f * h, -(b * i - c * h), b * f - c * e, -(d * i - f * g), a * i - c * g, -(a * f - c * d), d * h - e * g, -(a * h - b * g), a * e - b * d)
            val det = a * co[0] + b * co[3] + c * co[6]
            return Homography(DoubleArray(9) { co[it] / det })
        }

        companion object {
            /** The unit square's corners (0,0), (1,0), (1,1), (0,1) onto [q] (Heckbert's square-to-quad). */
            fun squareTo(q: List<CropPoint>): Homography {
                val (x0, y0) = q[0].x.toDouble() to q[0].y.toDouble()
                val (x1, y1) = q[1].x.toDouble() to q[1].y.toDouble()
                val (x2, y2) = q[2].x.toDouble() to q[2].y.toDouble()
                val (x3, y3) = q[3].x.toDouble() to q[3].y.toDouble()
                val dx1 = x1 - x2
                val dx2 = x3 - x2
                val dx3 = x0 - x1 + x2 - x3
                val dy1 = y1 - y2
                val dy2 = y3 - y2
                val dy3 = y0 - y1 + y2 - y3
                val den = dx1 * dy2 - dx2 * dy1
                val g = (dx3 * dy2 - dx2 * dy3) / den
                val h = (dx1 * dy3 - dx3 * dy1) / den
                return Homography(
                    doubleArrayOf(
                        x1 - x0 + g * x1, x3 - x0 + h * x3, x0,
                        y1 - y0 + g * y1, y3 - y0 + h * y3, y0,
                        g, h, 1.0,
                    ),
                )
            }
        }
    }

    private fun corners(vararg xy: Float) = List(4) { CropPoint(xy[2 * it], xy[2 * it + 1]) }

    /** [cx], [cy] the centre, [w] × [h] the size (fractions of a [photoWidth] × [photoHeight] photo) turned by [degrees]. */
    private fun turned(cx: Double, cy: Double, w: Double, h: Double, degrees: Double, photoWidth: Int, photoHeight: Int): List<CropPoint> {
        val r = Math.toRadians(degrees)
        return listOf(-1 to -1, 1 to -1, 1 to 1, -1 to 1).map { (sx, sy) ->
            val dx = sx * w / 2 * photoWidth
            val dy = sy * h / 2 * photoWidth
            CropPoint(
                ((cx * photoWidth + dx * cos(r) - dy * sin(r)) / photoWidth).toFloat(),
                ((cy * photoHeight + dx * sin(r) + dy * cos(r)) / photoHeight).toFloat(),
            )
        }
    }

    private fun find(photo: Photo): PageOutline? =
        if (photo.deskColour != null) PageDetection.find(photo.argb(), photo.width, photo.height) else PageDetection.find(photo.luma(), photo.width, photo.height)

    /** Each corner found within [tolerance] of the photo's long side from where the page's is. */
    private fun assertFound(photo: Photo, tolerance: Double = 0.012, expected: List<CropPoint> = photo.corners) {
        val outline = find(photo)
        assertNotNull("No page found", outline)
        outline!!
        val long = max(photo.width, photo.height).toDouble()
        for (i in 0..3) {
            val dx = (outline.quad[i].x - expected[i].x) * photo.width
            val dy = (outline.quad[i].y - expected[i].y) * photo.height
            assertTrue("Corner $i at ${outline.quad[i]}, the page's at ${expected[i]}", kotlin.math.hypot(dx, dy) / long <= tolerance)
        }
        assertTrue("Confidence ${outline.confidence}", outline.confident)
    }

    private fun assertNotConfident(photo: Photo) {
        val outline = find(photo)
        assertTrue("Found $outline", outline == null || !outline.confident)
    }

    @Test fun findsAnUprightPage() = assertFound(Photo(600, 800, corners(0.2f, 0.15f, 0.8f, 0.15f, 0.8f, 0.85f, 0.2f, 0.85f)))

    @Test fun findsATiltedPage() {
        assertFound(Photo(600, 800, turned(0.5, 0.5, 0.62, 0.8, 12.0, 600, 800)))
        assertFound(Photo(800, 600, turned(0.48, 0.52, 0.45, 0.55, -25.0, 800, 600), seed = 2))
    }

    @Test fun findsAPagePhotographedAtASlant() {
        assertFound(Photo(600, 800, corners(0.27f, 0.2f, 0.73f, 0.18f, 0.9f, 0.88f, 0.1f, 0.9f)))
        assertFound(Photo(800, 600, corners(0.1f, 0.12f, 0.85f, 0.25f, 0.82f, 0.75f, 0.12f, 0.9f), seed = 3))
    }

    @Test fun findsAPageHalfInShadow() {
        // A hand's or the phone's shadow over the left of the page, with a soft edge.
        val shadow = { x: Double, _: Double -> if (x < 0.4) 0.62 else if (x < 0.5) 0.62 + (x - 0.4) / 0.1 * 0.38 else 1.0 }
        assertFound(Photo(600, 800, corners(0.15f, 0.1f, 0.85f, 0.12f, 0.88f, 0.9f, 0.12f, 0.88f), shade = shadow))
        // Light falling off towards the bottom.
        assertFound(Photo(600, 800, corners(0.2f, 0.1f, 0.8f, 0.1f, 0.8f, 0.9f, 0.2f, 0.9f), shade = { _, y -> 1.05 - 0.4 * y }, seed = 4))
    }

    @Test fun findsAPageWithAFoldedCorner() {
        // The top-right corner is folded over; the corner is still where the page's edges meet.
        assertFound(Photo(600, 800, corners(0.2f, 0.15f, 0.8f, 0.15f, 0.8f, 0.85f, 0.2f, 0.85f), fold = 0.12), tolerance = 0.02)
    }

    @Test fun otherThingsOnTheTableDontMoveTheCorners() {
        // A white pen beside the page and a pale coffee cup in the corner.
        val things = { x: Double, y: Double ->
            when {
                x in 0.86..0.9 && y in 0.2..0.7 -> 235
                (x - 0.1) * (x - 0.1) + (y - 0.92) * (y - 0.92) < 0.004 -> 200
                else -> null
            }
        }
        assertFound(Photo(600, 800, corners(0.2f, 0.15f, 0.78f, 0.16f, 0.8f, 0.82f, 0.22f, 0.84f), extra = things))
    }

    @Test fun findsThePageOnALargePhoto() {
        assertFound(Photo(1500, 2000, corners(0.22f, 0.18f, 0.8f, 0.14f, 0.84f, 0.86f, 0.18f, 0.84f), noise = 14, seed = 5))
    }

    @Test fun aPageRunningOffThePhotoIsNotSure() {
        // The bottom-right corner is outside the photo.
        assertNotConfident(Photo(600, 800, corners(0.3f, 0.2f, 0.85f, 0.25f, 1.15f, 1.1f, 0.25f, 0.9f)))
        // The page fills the whole photo: its edges aren't on it.
        assertNotConfident(Photo(600, 800, corners(-0.02f, -0.02f, 1.02f, -0.02f, 1.02f, 1.02f, -0.02f, 1.02f)))
    }

    @Test fun nothingLikeAPageIsNotSure() {
        // Just the table.
        assertNotConfident(Photo(600, 800, corners(-1f, -1f, -0.9f, -1f, -0.9f, -0.9f, -1f, -0.9f)))
        // A page too small to be what was photographed.
        assertNotConfident(Photo(600, 800, corners(0.45f, 0.45f, 0.55f, 0.45f, 0.55f, 0.55f, 0.45f, 0.55f)))
        // A round white plate.
        val plate = { x: Double, y: Double -> if ((x - 0.5) * (x - 0.5) + (y - 0.5) * (y - 0.5) * 0.5625 < 0.1) 230 else null }
        assertNotConfident(Photo(600, 800, corners(-1f, -1f, -0.9f, -1f, -0.9f, -0.9f, -1f, -0.9f), extra = plate))
        // A flat grey image.
        assertEquals(null, PageDetection.find(ByteArray(300 * 400) { 128.toByte() }, 300, 400))
    }

    @Test fun findsAPageOnABrighterTable() {
        // The page isn't the bright region here, but its edges outline it all the same.
        assertFound(Photo(600, 800, corners(0.2f, 0.15f, 0.8f, 0.15f, 0.8f, 0.85f, 0.2f, 0.85f), paper = 190, table = 240, noise = 6))
    }

    /** A white desk lit unevenly: 240 at its brightest corner, 230 at the opposite one. */
    private val whiteDesk = { x: Double, y: Double -> 240 - 5 * x - 5 * y }

    @Test fun findsAWhitePageOnAWhiteDesk() {
        // As bright as the desk, the page shows only by the faint shadow along its edges.
        assertFound(Photo(600, 800, turned(0.5, 0.5, 0.6, 0.75, 8.0, 600, 800), paper = 236, desk = whiteDesk, shadow = 16, noise = 5))
        // Photographed at a slant.
        assertFound(Photo(800, 600, corners(0.25f, 0.15f, 0.78f, 0.2f, 0.88f, 0.85f, 0.12f, 0.8f), paper = 236, desk = whiteDesk, shadow = 16, noise = 5, seed = 2))
    }

    @Test fun findsAWhitePageInShadowOnAWhiteDesk() {
        // A hand's shadow over the top of the page and the desk around it.
        val hand = { _: Double, y: Double -> if (y < 0.35) 0.7 else if (y < 0.45) 0.7 + (y - 0.35) / 0.1 * 0.3 else 1.0 }
        assertFound(Photo(600, 800, corners(0.18f, 0.12f, 0.82f, 0.14f, 0.8f, 0.88f, 0.2f, 0.86f), paper = 236, desk = whiteDesk, shadow = 16, noise = 5, shade = hand, seed = 3))
    }

    @Test fun theSidesLieJustInsideThePage() {
        // Fitted to the page's edge, a side runs along the middle of the step from the desk to the
        // paper, or along the soft shadow line a page casts on a light desk, and a thin strip of desk
        // would show along the straightened page. Every side lies on the paper, and the corners are
        // still where the page's are.
        val photos = listOf(
            Photo(600, 800, turned(0.5, 0.5, 0.6, 0.75, -4.0, 600, 800), paper = 236, desk = { _, _ -> 205.0 }, shadow = 40, noise = 4, soft = 1, seed = 3),
            Photo(800, 600, corners(0.2f, 0.18f, 0.8f, 0.14f, 0.83f, 0.85f, 0.17f, 0.82f), noise = 4, seed = 2),
        )
        for (photo in photos) {
            assertFound(photo)
            val quad = find(photo)!!.quad
            val luma = photo.luma()
            for (side in 0..3) {
                val a = quad[side]
                val b = quad[(side + 1) % 4]
                val levels = (10..90).map { k ->
                    val x = ((a.x + (b.x - a.x) * k / 100) * photo.width).toInt()
                    val y = ((a.y + (b.y - a.y) * k / 100) * photo.height).toInt()
                    luma[y * photo.width + x].toInt() and 0xFF
                }
                assertTrue("Side $side at ${levels.average()}, the paper ${photo.paper}", levels.average() >= photo.paper - 5)
            }
        }
    }

    @Test fun findsAPageOnALightGreyDesk() {
        // Too little brighter than the desk for its brightness alone to be sure of it.
        val photo = Photo(600, 800, corners(0.2f, 0.12f, 0.8f, 0.15f, 0.78f, 0.88f, 0.22f, 0.85f), paper = 228, desk = { _, _ -> 214.0 }, noise = 6, seed = 4)
        val brightness = PageDetection.byBrightness(PageDetection.shrink(photo.luma(), photo.width, photo.height))
        assertTrue("$brightness", brightness == null || !brightness.confident)
        assertFound(photo)
    }

    @Test fun findsAPageOnALightTexturedDesk() {
        // A pale desk with a busy grain and speckles, not much darker than the paper.
        val grain = { x: Double, y: Double -> 205 + 12 * sin(y * 140 + 4 * sin(x * 9)) + 6 * sin(x * 410 + y * 230) }
        assertFound(Photo(600, 800, turned(0.48, 0.5, 0.58, 0.72, -10.0, 600, 800), paper = 232, desk = grain, shadow = 10, noise = 8, seed = 5))
    }

    @Test fun findsAWhitePageOnAPaleBlueDeskByItsColour() {
        // As bright as the page, the desk differs from it only in colour.
        val photo = Photo(600, 800, corners(0.2f, 0.15f, 0.8f, 0.13f, 0.82f, 0.86f, 0.18f, 0.84f), paper = 232, deskColour = intArrayOf(214, 237, 255), noise = 5, seed = 6)
        assertFound(photo)
        // Its brightness alone doesn't show it.
        val colours = photo.argb()
        val luma = ByteArray(colours.size) {
            val c = colours[it]
            ((((c shr 16) and 255) * 77 + ((c shr 8) and 255) * 150 + (c and 255) * 29) shr 8).toByte()
        }
        val grey = PageDetection.find(luma, photo.width, photo.height)
        assertTrue("$grey", grey == null || !grey.confident)
    }

    @Test fun linesOnABareLightTableAreNotAPage() {
        // Tiles as large as a page, four of them wholly in the photo, their grout lines crossing at
        // every corner.
        fun grout(at: Double, period: Double) = ((at % period) + period) % period < 6
        val tiles = { x: Double, y: Double -> if (grout(x * 600 - 24, 270.0) || grout(y * 800 - 80, 330.0)) 150.0 else 226.0 }
        assertNotConfident(Photo(600, 800, corners(-1f, -1f, -0.9f, -1f, -0.9f, -0.9f, -1f, -0.9f), desk = tiles, noise = 6))
    }

    @Test fun theSearchIsQuickOnABusyPhoto() {
        // Straight edges all over: a page on a pale desk beside a keyboard's rows of keys. The search
        // runs as the crop screen opens.
        val busy = { x: Double, y: Double ->
            val key = y > 0.8 && y < 0.98 && x > 0.04 && x < 0.96 && (x * 30) % 1 < 0.8 && (y * 40) % 1 < 0.8
            if (key) 50.0 else 215.0
        }
        val photo = Photo(1200, 1600, turned(0.5, 0.4, 0.55, 0.6, 7.0, 1200, 1600), paper = 236, desk = busy, shadow = 16, noise = 8)
        val luma = photo.luma()
        // Once for the JVM to compile the search, then timed.
        PageDetection.find(luma, photo.width, photo.height)
        val start = System.nanoTime()
        val outline = PageDetection.find(luma, photo.width, photo.height)
        val millis = (System.nanoTime() - start) / 1_000_000
        assertTrue("$millis ms", millis < 400)
        assertTrue("$outline", outline != null && outline.confident)
    }

    @Test fun theSearchStaysQuickWithStraightEdgesEveryWay() {
        // The most work the search can have: long sticks every way, so that nearly any four of their
        // lines could be a page's sides. It takes at most a few times as long as on a plain page.
        val size = 800
        val random = Random(2)
        val sticks = ByteArray(size * size) { 224.toByte() }
        repeat(180) {
            val angle = random.nextDouble(0.0, Math.PI)
            val cx = random.nextDouble(0.0, size.toDouble())
            val cy = random.nextDouble(0.0, size.toDouble())
            // 240 pixels long, 3 wide.
            for (step in -240..240) for (across in -1..1) {
                val x = (cx + step * 0.5 * cos(angle) - across * sin(angle)).roundToInt()
                val y = (cy + step * 0.5 * sin(angle) + across * cos(angle)).roundToInt()
                if (x in 0 until size && y in 0 until size) sticks[y * size + x] = 40
            }
        }
        val page = Photo(size, size, corners(0.2f, 0.15f, 0.8f, 0.15f, 0.8f, 0.85f, 0.2f, 0.85f)).luma()
        // The quickest of a few runs, once the JVM has compiled the search, counted in this thread's
        // processor time where the JVM can: on a busy build machine the clock also counts the time
        // other work takes the processor away, which once made a quick search look three times as slow.
        // Each run times five searches, as Windows counts processor time in steps of about 16 ms.
        val threads = ManagementFactory.getThreadMXBean()
        val cpu = threads.isCurrentThreadCpuTimeSupported
        fun now() = if (cpu) threads.currentThreadCpuTime else System.nanoTime()
        fun millis(luma: ByteArray): Long {
            PageDetection.find(luma, size, size)
            return (1..3).minOf {
                val start = now()
                repeat(5) { PageDetection.find(luma, size, size) }
                (now() - start) / 1_000_000
            }
        }
        val plain = millis(page)
        val busy = millis(sticks)
        assertTrue("$busy ms, a plain page $plain ms, five searches each", busy <= 3 * max(plain, 50))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aSizeTooLargeToCountIsRefused() {
        // 65536 × 65537 pixels are 65536 when counted in 32 bits.
        PageDetection.find(IntArray(65536), 65536, 65537)
    }

    @Test fun aKeyboardIsNotAPage() {
        // Rows of small dark keys on a light desk.
        val keys = { x: Double, y: Double ->
            val column = (x - 0.08) / 0.07
            val row = (y - 0.3) / 0.075
            val key = column in 0.0..12.0 && row in 0.0..5.0 && column % 1 < 0.8 && row % 1 < 0.8
            if (key) 50.0 else 215.0
        }
        assertNotConfident(Photo(800, 600, corners(-1f, -1f, -0.9f, -1f, -0.9f, -0.9f, -1f, -0.9f), desk = keys, noise = 6))
    }

    @Test fun theLargestQuadrilateralOnAHullIsFound() {
        val random = Random(7)
        repeat(40) {
            // A random convex polygon: points on an ellipse at random angles.
            val n = random.nextInt(5, 30)
            val angles = List(n) { random.nextDouble(0.0, 2 * Math.PI) }.sorted()
            val points = angles.map { OcrPoint((100 + 80 * cos(it)).toFloat(), (100 + 50 * sin(it)).toFloat()) }
            val hull = MinAreaRect.convexHull(points)
            if (hull.size < 5) return@repeat
            val fast = area(PageDetection.largestQuad(hull).map { hull[it % hull.size] })
            var best = 0.0
            for (a in hull.indices) for (b in a + 1 until hull.size) for (c in b + 1 until hull.size) for (d in c + 1 until hull.size) {
                best = max(best, area(listOf(hull[a], hull[b], hull[c], hull[d])))
            }
            assertEquals(best, fast, best * 1e-6)
        }
    }

    @Test fun otsuSplitsTwoShades() {
        val levels = IntArray(1000) { if (it < 600) 60 + it % 7 else 200 + it % 9 }
        val t = PageDetection.otsu(levels)!!
        assertTrue("$t", t in 66..199)
        assertEquals(null, PageDetection.otsu(IntArray(1000) { 128 + it % 3 }))
    }

    @Test fun theSearchRunsOnASmallCopy() {
        val luma = ByteArray(1600 * 1200) { (it % 256).toByte() }
        val small = PageDetection.shrink(luma, 1600, 1200)
        assertEquals(400, small.width)
        assertEquals(300, small.height)
        // A size that doesn't divide evenly keeps its last pixels.
        val odd = PageDetection.shrink(ByteArray(1001 * 10) { 9 }, 1001, 10)
        assertEquals(334, odd.width)
        assertEquals(9, odd.levels.last())
        assertEquals(min(400, 300), PageDetection.shrink(ByteArray(300 * 200), 300, 200).width)
    }

    private fun area(points: List<OcrPoint>): Double {
        var twice = 0.0
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            twice += a.x.toDouble() * b.y - b.x.toDouble() * a.y
        }
        return abs(twice) / 2
    }
}
