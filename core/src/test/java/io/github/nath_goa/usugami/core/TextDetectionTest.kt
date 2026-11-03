package io.github.nath_goa.usugami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.imageio.ImageIO
import kotlin.math.cos
import kotlin.math.sin

/**
 * Usugami OCR's post-processing of the detection model's output, checked against PaddleOCR's own
 * code run with OpenCV and pyclipper (fixtures in resources/ocr, made by tools/make_ocr_test_fixtures.py).
 */
class TextDetectionTest {
    private fun resource(name: String) = javaClass.getResourceAsStream("/ocr/$name") ?: error("Missing fixture $name")

    private fun lines(name: String) = resource(name).bufferedReader().readLines().filter { it.isNotBlank() && !it.startsWith("#") }

    @Test fun boxesMatchPaddleOcr() {
        val image = ImageIO.read(resource("detection.png"))
        val width = image.width
        val height = image.height
        val raster = image.raster
        val levels = ByteArray(width * height) { raster.getSample(it % width, it / width, 0).toByte() }
        val expected = lines("detection.txt").map { line ->
            val v = line.split(' ').map(String::toFloat)
            v[0] to List(4) { OcrPoint(v[1 + 2 * it], v[2 + 2 * it]) }
        }
        val found = TextDetection.boxes(levels, width, height)
        assertEquals(expected.size, found.size)
        for ((score, corners) in expected) {
            val center = OcrPoint(corners.map { it.x }.average().toFloat(), corners.map { it.y }.average().toFloat())
            val (quad, foundScore) = found.minBy { (quad, _) ->
                OcrPoint(quad.corners.map { it.x }.average().toFloat(), quad.corners.map { it.y }.average().toFloat()).distanceTo(center)
            }
            assertEquals(score, foundScore, 0.02f)
            // pyclipper rounds the box to whole pixels before growing it, so allow a little more than one.
            for (i in 0..3) {
                assertTrue("Corner $i: ${quad.corners[i]} vs ${corners[i]}", quad.corners[i].distanceTo(corners[i]) < 2f)
            }
        }
    }

    @Test fun minAreaRectMatchesOpenCv() {
        for (line in lines("min_area_rect.txt")) {
            val v = line.split(' ').map(String::toFloat)
            val points = (2 until v.size step 2).map { OcrPoint(v[it], v[it + 1]) }
            val rect = MinAreaRect.of(points)!!
            assertEquals(v[0], maxOf(rect.width, rect.height), 0.01f + v[0] * 1e-4f)
            assertEquals(v[1], minOf(rect.width, rect.height), 0.01f + v[0] * 1e-4f)
        }
    }

    @Test fun minAreaRectOfARotatedRectangleIsItself() {
        val angle = Math.toRadians(30.0)
        val corners = listOf(0.0 to 0.0, 40.0 to 0.0, 40.0 to 10.0, 0.0 to 10.0).map { (x, y) ->
            OcrPoint((x * cos(angle) - y * sin(angle) + 100).toFloat(), (x * sin(angle) + y * cos(angle) + 50).toFloat())
        }
        val rect = MinAreaRect.of(corners + OcrPoint(110f, 60f))!!
        assertEquals(40f, maxOf(rect.width, rect.height), 1e-3f)
        assertEquals(10f, minOf(rect.width, rect.height), 1e-3f)
        assertEquals(1, MinAreaRect.convexHull(listOf(OcrPoint(1f, 1f), OcrPoint(1f, 1f))).size)
        assertEquals(0f, MinAreaRect.of(listOf(OcrPoint(3f, 4f)))!!.width)
        assertEquals(null, MinAreaRect.of(emptyList()))
    }

    @Test fun boxScoreIsTheMeanInsideTheBox() {
        // A 10 x 10 map: the left half 255, the right half 0.
        val map = ByteArray(100) { if (it % 10 < 5) 255.toByte() else 0 }
        val square = listOf(OcrPoint(0f, 0f), OcrPoint(9f, 0f), OcrPoint(9f, 9f), OcrPoint(0f, 9f))
        assertEquals(0.5f, TextDetection.meanInside(map, 10, 10, square), 1e-6f)
        val left = listOf(OcrPoint(0f, 0f), OcrPoint(4f, 0f), OcrPoint(4f, 9f), OcrPoint(0f, 9f))
        assertEquals(1f, TextDetection.meanInside(map, 10, 10, left), 1e-6f)
    }

    @Test fun emptyOrTinyRegionsGiveNoBoxes() {
        assertTrue(TextDetection.boxes(ByteArray(64 * 32), 64, 32).isEmpty())
        // A 2-pixel-high streak is narrower than PaddleOCR's minimum size of 3.
        val streak = ByteArray(64 * 32) { if (it / 64 in 10..11 && it % 64 in 5..50) 250.toByte() else 0 }
        assertTrue(TextDetection.boxes(streak, 64, 32).isEmpty())
        // A faint region (below the box threshold of 0.6) is dropped too.
        val faint = ByteArray(64 * 32) { if (it / 64 in 8..20 && it % 64 in 5..50) 120.toByte() else 0 }
        assertTrue(TextDetection.boxes(faint, 64, 32).isEmpty())
    }

    @Test fun aSolidRegionIsGrownByItsUnclipDistance() {
        // Rows 10..19, columns 10..49: a 39 x 9 rectangle between pixel centers.
        val map = ByteArray(64 * 32) { if (it / 64 in 10..19 && it % 64 in 10..49) 255.toByte() else 0 }
        val (quad, score) = TextDetection.boxes(map, 64, 32).single()
        assertEquals(1f, score, 1e-6f)
        val d = 39f * 9f * 1.5f / (2 * (39f + 9f))
        assertTrue(quad.corners[0].distanceTo(OcrPoint(10f - d, 10f - d)) < 1e-3f)
        assertEquals(39f + 2 * d, quad.width, 1e-3f)
        assertEquals(9f + 2 * d, quad.height, 1e-3f)
    }

    private fun box(left: Float, top: Float, right: Float, bottom: Float) =
        OcrQuad(listOf(OcrPoint(left, top), OcrPoint(right, top), OcrPoint(right, bottom), OcrPoint(left, bottom)))

    @Test fun readingOrderGoesDownEachColumnInTurn() {
        val lines = listOf(
            box(300f, 100f, 500f, 120f), // right column, line 1
            box(20f, 10f, 500f, 40f), // title across both columns
            box(20f, 130f, 250f, 150f), // left column, line 2
            box(20f, 100f, 250f, 120f), // left column, line 1
            box(300f, 130f, 500f, 150f), // right column, line 2
        )
        assertEquals(listOf(1, 3, 2, 0, 4), ReadingOrder.of(lines))
    }

    @Test fun piecesOfOneLineGoLeftToRight() {
        val lines = listOf(
            box(200f, 52f, 260f, 72f), // "Date:" further along the same line
            box(10f, 50f, 80f, 70f), // "Name:"
            box(10f, 100f, 260f, 120f), // the next line
            box(90f, 51f, 150f, 71f), // a word after "Name:", close by
        )
        assertEquals(listOf(1, 3, 0, 2), ReadingOrder.of(lines))
    }

    @Test fun readingOrderFollowsTiltedAndUpsideDownPages() {
        val angle = Math.toRadians(12.0)
        fun turned(quad: OcrQuad, radians: Double) = quad.map {
            OcrPoint((it.x * cos(radians) - it.y * sin(radians)).toFloat(), (it.x * sin(radians) + it.y * cos(radians)).toFloat())
        }
        // Lines 30 apart with only 10 between them, tilted by 12°: their upright bounds overlap.
        val page = (0 until 5).map { box(0f, it * 30f, 400f, it * 30f + 20f) }
        val tilted = page.map { turned(it, angle) }
        assertEquals(listOf(0, 1, 2, 3, 4), ReadingOrder.of(tilted.reversed()).map { 4 - it })
        // Upside down, each line reads from its bottom-right corner; the order is still the text's.
        val upsideDown = page.map { turned(it, Math.PI) }
        assertEquals(listOf(0, 1, 2, 3, 4), ReadingOrder.of(upsideDown))
    }

    @Test fun readingOrderFollowsLinesFannedOutByASlantedPhoto() {
        // A page photographed at a slant: its long lines turn from 3° at the top to -3° at the bottom,
        // each starting a little further left, their boxes (grown by detection) nearly touching. The
        // bounds of the outer lines, levelled by the mean angle, reach into the lines next to them.
        val lines = (0 until 9).map { i ->
            val angle = Math.toRadians(3.0 - 0.75 * i)
            val x = 1100.0 - 4 * i
            val y = 100.0 + 56 * i
            box(-1000f, -25f, 1000f, 25f).map {
                OcrPoint((x + it.x * cos(angle) - it.y * sin(angle)).toFloat(), (y + it.x * sin(angle) + it.y * cos(angle)).toFloat())
            }
        }
        val shuffled = lines.shuffled(java.util.Random(2))
        assertEquals(lines, ReadingOrder.of(shuffled).map { shuffled[it] })
    }

    @Test fun aLongColumnIsOrderedWithoutDeepRecursion() {
        // As many lines as detection can find (1000), each a little wider than the last.
        val lines = (0 until 1000).map { box(10f, it * 30f, 300f + it * 0.1f, it * 30f + 20f) }
        val shuffled = lines.shuffled(java.util.Random(1))
        assertEquals(lines, ReadingOrder.of(shuffled).map { shuffled[it] })
    }

    @Test fun piecesOfOneLineAreJoined() {
        val lines = TextLines.merge(listOf(
            box(10f, 50f, 60f, 70f), // "THE"
            box(68f, 49f, 300f, 71f), // "QUICK BROWN FOX", one word space away
            box(10f, 100f, 200f, 120f), // the next line
            box(420f, 100f, 500f, 120f), // a column further along: 11 line heights away
            box(205f, 105f, 230f, 113f), // much smaller print right after the next line
        ))
        assertEquals(listOf(listOf(0, 1), listOf(2), listOf(3), listOf(4)), lines.map { it.parts }.sortedBy { it[0] })
        val joined = lines.first { it.parts.size == 2 }.quad
        assertEquals(listOf(OcrPoint(10f, 49f), OcrPoint(300f, 49f), OcrPoint(300f, 71f), OcrPoint(10f, 71f)), joined.corners)
    }

    @Test fun joinedLinesKeepTheirAngleAndDirection() {
        val angle = Math.toRadians(8.0)
        fun turned(quad: OcrQuad, radians: Double) = quad.map {
            OcrPoint((it.x * cos(radians) - it.y * sin(radians)).toFloat(), (it.x * sin(radians) + it.y * cos(radians)).toFloat())
        }
        val tilted = TextLines.merge(listOf(turned(box(0f, 0f, 50f, 20f), angle), turned(box(60f, 0f, 200f, 20f), angle))).single().quad
        assertEquals(200f, tilted.width, 0.01f)
        assertEquals(8.0, Math.toDegrees(kotlin.math.atan2((tilted.corners[1].y - tilted.corners[0].y).toDouble(), (tilted.corners[1].x - tilted.corners[0].x).toDouble())), 0.01)
        // A line running down the page is not joined with one running across.
        val across = box(0f, 0f, 100f, 20f)
        val down = box(110f, 0f, 130f, 100f)
        assertEquals(2, TextLines.merge(listOf(across, down)).size)
    }

    @Test fun linesRunningDownThePage() {
        // A page turned a quarter round: its lines run down it, side by side.
        val turnedPage = (0 until 4).map { box(it * 30f, 0f, it * 30f + 20f, 300f) }
        // Pieces of one such line join; the lines next to each other don't.
        val pieces = TextLines.merge(turnedPage + box(0f, 310f, 20f, 360f))
        assertEquals(4, pieces.size)
        assertEquals(360f, pieces.first { it.parts.size == 2 }.quad.height, 1e-3f)
        val short = box(200f, 0f, 220f, 36f) // a word of one or two letters, 1.8 times as tall as wide
        assertEquals(listOf(true, true, true, true, true), TextLines.vertical(turnedPage + short).toList())
        // On an upright page the same short box is read across (like Hindi's है standing alone)...
        val upright = (0 until 4).map { box(0f, it * 30f, 300f, it * 30f + 20f) }
        assertEquals(listOf(false, false, false, false, false), TextLines.vertical(upright + short).toList())
        // ...and it joins its line when next to it.
        val line = TextLines.merge(listOf(box(0f, 0f, 200f, 30f), box(210f, -2f, 226f, 30f))).single()
        assertEquals(226f, line.quad.width, 1e-3f)
        // A box three times as tall as wide always runs down the page.
        assertEquals(listOf(false, true), TextLines.vertical(listOf(upright[0], box(0f, 0f, 20f, 60f))).toList())
    }

    @Test fun stripsCoverTheMapOnceWithOverlaps() {
        for ((width, height) in listOf(1504 to 2112, 2240 to 1600, 640 to 480, 1280 to 1312, 4096 to 64, 2240 to 6400)) {
            val strips = DetectionStrips.of(width, height, maxPixels = 1_250_000, overlap = 128)
            val kept = IntArray(width * height)
            for (strip in strips) {
                assertTrue(strip.x == 0 && strip.width == width && strip.height % 32 == 0)
                assertTrue(strips.size == 1 || strip.width * strip.height <= 1_250_000)
                assertTrue(strip.y >= 0 && strip.y + strip.height <= height)
                val (_, top, _, bottom) = strip.keep
                for (y in top until bottom) for (x in 0 until width) kept[y * width + x]++
                // What a strip keeps is at least half the overlap from its edges (except at the map's edges).
                if (top > 0) assertTrue(top - strip.y >= 64)
                if (bottom < height) assertTrue(strip.y + strip.height - bottom >= 64)
            }
            assertTrue("$width x $height", kept.all { it == 1 })
        }
        assertEquals(1, DetectionStrips.of(640, 480, 1_250_000, 128).size)
        // An A4 page at full size: 3 strips of 1504 x 800, 14% more work than in one go.
        assertEquals(List(3) { 1504 to 800 }, DetectionStrips.of(1504, 2112, 1_250_000, 128).map { it.width to it.height })
    }

    @Test fun stripMapsAreJoined() {
        val strips = DetectionStrips.of(64, 96, maxPixels = 64 * 64, overlap = 32)
        val map = ByteArray(64 * 96)
        for (strip in strips) {
            // Each strip's map holds the map's own coordinates: y in each byte.
            val values = ByteArray(strip.width * strip.height) { (strip.y + it / strip.width).toByte() }
            DetectionStrips.copy(values, strip, map, 64)
        }
        for (y in 0 until 96) for (x in 0 until 64) assertEquals(y.toByte(), map[y * 64 + x])
    }

    @Test fun quadTurnsKeepTheTextCorners() {
        val quad = box(0f, 0f, 40f, 10f)
        assertEquals(quad.corners[1], quad.turned(1).corners[0])
        assertEquals(quad.corners[2], quad.turned(2).corners[0])
        assertEquals(quad.corners[3], quad.turned(-1).corners[0])
        assertEquals(40f, quad.width)
        assertEquals(10f, quad.height)
    }
}
