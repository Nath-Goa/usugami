package io.github.nath_goa.usugami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import kotlin.math.abs
import kotlin.math.hypot

class PageEditsTest {
    private fun assertPoint(message: String, expected: Pair<Float, Float>, actual: Pair<Float, Float>, delta: Float = 0.001f) {
        assertEquals("$message x", expected.first, actual.first, delta)
        assertEquals("$message y", expected.second, actual.second, delta)
    }

    // A crop box that doesn't start at the origin: 400 wide, 600 tall, from (10, 20).
    private fun frame(rotation: Int) = PageFrame(10f, 20f, 400f, 600f, rotation)

    /** Where [matrix] (a, b, c, d, e, f) takes ([x], [y]). */
    private fun apply(matrix: FloatArray, x: Float, y: Float) =
        (matrix[0] * x + matrix[2] * y + matrix[4]) to (matrix[1] * x + matrix[3] * y + matrix[5])

    @Test fun topLeftCornersLandOnTheShownCorners() {
        // The shown top-left corner of each rotation, in the page's own coordinates.
        val topLeft = mapOf(0 to (10f to 620f), 90 to (10f to 20f), 180 to (410f to 20f), 270 to (410f to 620f))
        for ((rotation, expected) in topLeft) {
            val frame = frame(rotation)
            assertPoint("Rotation $rotation, top-left", expected, frame.fromTopLeft(0f, 0f))
            // The shown bottom-right corner is the opposite one.
            assertPoint("Rotation $rotation, bottom-right", 420f - expected.first to 640f - expected.second, frame.fromTopLeft(frame.shownWidth, frame.shownHeight))
        }
    }

    @Test fun topLeftMatrixMatchesTheFrame() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val frame = frame(rotation)
            val matrix = frame.topLeftMatrix
            for ((x, y) in listOf(0f to 0f, 37f to 51f, frame.shownWidth to 12f, 250f / 2 to frame.shownHeight)) {
                // From the top with y down is from the bottom with y up, shownHeight - y.
                assertPoint("Rotation $rotation at ($x, $y)", frame.toPage(x, frame.shownHeight - y), apply(matrix, x, y))
            }
            // It only turns and flips, so line widths and sizes stay as they are.
            val determinant = matrix[0] * matrix[3] - matrix[1] * matrix[2]
            assertEquals(-1f, determinant, 0.0001f)
        }
    }

    @Test fun movingDownTheScreenMovesDownThePageAsShown() {
        // Turned clockwise, the shown page's top is the box's left edge, and down the screen is
        // along the box's x axis.
        val frame = frame(90)
        val (x1, y1) = frame.fromTopLeft(100f, 50f)
        val (x2, y2) = frame.fromTopLeft(100f, 150f)
        assertEquals(100f, x2 - x1, 0.001f)
        assertEquals(0f, y2 - y1, 0.001f)
    }

    /** The point at [t] of the cubic curve from ([x0], [y0]) by [op]'s control points, on each axis. */
    private fun cubicAt(x0: Float, y0: Float, op: PathOp.Cubic, t: Float): Pair<Float, Float> {
        val u = 1 - t
        fun at(a: Float, b: Float, c: Float, d: Float) = u * u * u * a + 3 * u * u * t * b + 3 * u * t * t * c + t * t * t * d
        return at(x0, op.x1, op.x2, op.x) to at(y0, op.y1, op.y2, op.y)
    }

    @Test fun strokesFollowTheSignatureCurveAsCubics() {
        val points = floatArrayOf(10f, 10f, 50f, 80f, 90f, 20f, 130f, 60f)
        val ops = EditLayout.outline(InkItem(1, 0, points, 2f, 0, false))
        assertEquals(PathOp.Move(10f, 10f), ops.first())
        assertEquals(PathOp.Line(130f, 60f), ops.last())
        // Bends through each middle point to the midpoint between it and the next.
        val curves = ops.filterIsInstance<PathOp.Cubic>()
        assertEquals(2, curves.size)
        assertEquals(70f, curves[0].x, 0.0001f)
        assertEquals(50f, curves[0].y, 0.0001f)
        // Each cubic is exactly the quadratic curve: compare along the first one.
        for (t in listOf(0.1f, 0.35f, 0.5f, 0.8f)) {
            val u = 1 - t
            val quadratic = (u * u * 10f + 2 * u * t * 50f + t * t * 70f) to (u * u * 10f + 2 * u * t * 80f + t * t * 50f)
            assertPoint("t = $t", quadratic, cubicAt(10f, 10f, curves[0], t), 0.001f)
        }
        // One point is a dot: a line of no length, which round ends draw.
        assertEquals(listOf(PathOp.Move(5f, 6f), PathOp.Line(5f, 6f)), EditLayout.outline(InkItem(2, 0, floatArrayOf(5f, 6f), 2f, 0, false)))
    }

    @Test fun ellipseFollowsItsBox() {
        val ops = EditLayout.ellipse(100f, 200f, 300f, 300f)
        // It starts at the top and passes through the middle of each side.
        assertEquals(PathOp.Move(200f, 200f), ops.first())
        assertEquals(PathOp.Close, ops.last())
        val ends = ops.filterIsInstance<PathOp.Cubic>().map { it.x to it.y }
        assertEquals(listOf(300f to 250f, 200f to 300f, 100f to 250f, 200f to 200f), ends)
        // Halfway along a quarter, it is on the ellipse, within a tiny fraction.
        val first = ops[1] as PathOp.Cubic
        val (x, y) = cubicAt(200f, 200f, first, 0.5f)
        val onEllipse = ((x - 200f) / 100f).let { it * it } + ((y - 250f) / 50f).let { it * it }
        assertEquals(1f, onEllipse, 0.001f)
    }

    @Test fun arrowHeadsPointBackAlongTheLine() {
        val head = EditLayout.arrowHead(0f, 0f, 100f, 0f, 2f)
        // Both barbs end behind the tip, one on each side, as far from it.
        assertTrue(head[0] < 100f && head[2] < 100f)
        assertEquals(-head[1], head[3], 0.001f)
        assertEquals(hypot(100f - head[0], head[1]), hypot(100f - head[2], head[3]), 0.001f)
        // At least 10 points long, longer for a thicker line, and never more than half the arrow.
        assertEquals(10f, hypot(100f - head[0], head[1]), 0.001f)
        assertEquals(40f, EditLayout.arrowHead(0f, 0f, 100f, 0f, 10f).let { hypot(100f - it[0], it[1]) }, 0.001f)
        assertEquals(10f, EditLayout.arrowHead(0f, 0f, 20f, 0f, 10f).let { hypot(20f - it[0], it[1]) }, 0.001f)
    }

    private val text = TextItem(1, 0, 100f, 200f, "Hello", 10f, 0, unitWidth = 2.5f, unitHeight = 1.2f)

    private fun assertArea(expected: Area, actual: Area) {
        assertEquals(expected.x, actual.x, 0.001f)
        assertEquals(expected.y, actual.y, 0.001f)
        assertEquals(expected.width, actual.width, 0.001f)
        assertEquals(expected.height, actual.height, 0.001f)
    }

    @Test fun textBoxScalesWithItsSize() {
        assertArea(Area(100f, 200f, 25f, 12f), text.bounds)
        // Twice as big about its top-left corner: the box doubles and stays put.
        val bigger = text.scaled(2f, 100f, 200f)
        assertEquals(20f, bigger.size, 0.0001f)
        assertArea(Area(100f, 200f, 50f, 24f), bigger.bounds)
    }

    @Test fun movesStopAtThePageEdges() {
        val box = ShapeItem(1, 0, ShapeKind.RECTANGLE, 50f, 50f, 150f, 100f, 2f, 0)
        // The outline's width counts: the box covers 49..151 by 49..101.
        val moved = EditLayout.moved(box, 1000f, -1000f, 400f, 600f) as ShapeItem
        assertEquals(399f, moved.x2, 0.001f)
        assertEquals(1f, moved.y1, 0.001f)
        // Moves inside the page go as far as asked.
        assertEquals(box.moved(10f, 20f), EditLayout.moved(box, 10f, 20f, 400f, 600f))
        // Something bigger than the page is centred on it.
        val wide = TextItem(2, 0, 0f, 0f, "x", 10f, 0, unitWidth = 50f, unitHeight = 1f)
        val centred = EditLayout.onPage(wide, 400f, 600f).bounds
        assertEquals(-50f, centred.x, 0.001f)
    }

    @Test fun boxCornersMoveWhileTheOppositeOneStays() {
        val box = ShapeItem(1, 0, ShapeKind.ELLIPSE, 100f, 100f, 200f, 150f, 2f, 0)
        val dragged = EditLayout.dragHandle(box, Handle.TOP_LEFT, 50f, 60f, 400f, 600f) as ShapeItem
        assertEquals(listOf(50f, 60f, 200f, 150f), listOf(dragged.x1, dragged.y1, dragged.x2, dragged.y2))
        // Dragged past the opposite corner, the box turns inside out rather than vanishing.
        val flipped = EditLayout.dragHandle(box, Handle.BOTTOM_RIGHT, 40f, 120f, 400f, 600f) as ShapeItem
        assertEquals(listOf(40f, 100f, 100f, 120f), listOf(flipped.x1, flipped.y1, flipped.x2, flipped.y2))
        // Never smaller than the smallest size, and never off the page.
        val tiny = EditLayout.dragHandle(box, Handle.BOTTOM_RIGHT, 101f, 900f, 400f, 600f) as ShapeItem
        assertEquals(100f + EditLayout.MIN_SIZE, tiny.x2, 0.001f)
        assertEquals(600f, tiny.y2, 0.001f)
    }

    @Test fun picturesKeepTheirShape() {
        val picture = PictureItem(1, 0, "p", 100f, 100f, 200f, 100f, 1, true)
        val bigger = EditLayout.dragHandle(picture, Handle.BOTTOM_RIGHT, 350f, 120f, 400f, 600f) as PictureItem
        assertEquals(100f, bigger.x, 0.001f)
        assertEquals(100f, bigger.y, 0.001f)
        assertEquals(250f, bigger.width, 0.001f)
        assertEquals(125f, bigger.height, 0.001f)
        // The top-left corner pulls up and left from the bottom-right one, and stops at the page.
        val upLeft = EditLayout.dragHandle(picture, Handle.TOP_LEFT, -500f, 90f, 400f, 600f) as PictureItem
        assertEquals(300f, upLeft.x + upLeft.width, 0.001f)
        assertEquals(200f, upLeft.y + upLeft.height, 0.001f)
        assertEquals(0f, upLeft.x, 0.001f)
        assertEquals(2f, upLeft.width / upLeft.height, 0.001f)
    }

    @Test fun lineEndsMove() {
        val line = ShapeItem(1, 0, ShapeKind.ARROW, 10f, 10f, 100f, 10f, 2f, 0)
        val handles = EditLayout.handles(line)
        assertEquals(listOf(Handle.START, Handle.END), handles.map { it.first })
        val moved = EditLayout.dragHandle(line, Handle.END, 120f, 700f, 400f, 600f) as ShapeItem
        assertEquals(listOf(10f, 10f, 120f, 600f), listOf(moved.x1, moved.y1, moved.x2, moved.y2))
    }

    @Test fun textGrowsFromItsCornerWithinTheFontSizes() {
        val handle = EditLayout.handles(text).single()
        assertEquals(Handle.BOTTOM_RIGHT, handle.first)
        assertPoint("handle", 125f to 212f, handle.second)
        // Dragged to make the box 1.5 times as wide.
        val grown = EditLayout.dragHandle(text, Handle.BOTTOM_RIGHT, 137.5f, 212f, 400f, 600f) as TextItem
        assertEquals(15f, grown.size, 0.001f)
        assertEquals(100f, grown.x, 0.001f)
        val shrunk = EditLayout.dragHandle(text, Handle.BOTTOM_RIGHT, 100f, 200f, 400f, 600f) as TextItem
        assertEquals(EditLayout.MIN_TEXT, shrunk.size, 0.001f)
    }

    @Test fun sizeSliderScalesAboutTheMiddle() {
        val box = ShapeItem(1, 0, ShapeKind.RECTANGLE, 100f, 100f, 200f, 150f, 0f, 0, filled = true)
        assertEquals(100f, EditLayout.size(box), 0.001f)
        val bigger = EditLayout.resized(box, 200f, 400f, 600f) as ShapeItem
        assertEquals(listOf(50f, 75f, 250f, 175f), listOf(bigger.x1, bigger.y1, bigger.x2, bigger.y2))
        // At most as big as fits on the page.
        assertEquals(400f, EditLayout.sizeRange(box, 400f, 600f).endInclusive, 0.001f)
        // A text's size is its font size.
        assertEquals(10f, EditLayout.size(text), 0.001f)
        assertEquals(EditLayout.MIN_TEXT, EditLayout.sizeRange(text, 400f, 600f).start, 0.001f)
        assertEquals(EditLayout.MAX_TEXT, EditLayout.sizeRange(text, 4000f, 6000f).endInclusive, 0.001f)
    }

    @Test fun outlinesAreTouchedByTheirLineFilledShapesAnywhere() {
        val outlined = ShapeItem(1, 0, ShapeKind.RECTANGLE, 100f, 100f, 300f, 300f, 2f, 0)
        assertTrue(EditLayout.hits(outlined, 101f, 200f, 5f))
        // Inside, what the box frames can still be touched.
        assertFalse(EditLayout.hits(outlined, 200f, 200f, 5f))
        assertTrue(EditLayout.hits(outlined.copy(filled = true), 200f, 200f, 5f))
        assertFalse(EditLayout.hits(outlined, 320f, 200f, 5f))
        val ellipse = outlined.copy(kind = ShapeKind.ELLIPSE)
        assertTrue(EditLayout.hits(ellipse, 200f, 102f, 5f))
        assertFalse(EditLayout.hits(ellipse, 200f, 200f, 5f))
        // The box's corner is outside the ellipse.
        assertFalse(EditLayout.hits(ellipse, 105f, 105f, 5f))
        val line = ShapeItem(2, 0, ShapeKind.LINE, 0f, 0f, 100f, 100f, 2f, 0)
        assertTrue(EditLayout.hits(line, 52f, 48f, 5f))
        assertFalse(EditLayout.hits(line, 70f, 30f, 5f))
        val ink = InkItem(3, 0, floatArrayOf(0f, 0f, 100f, 0f, 100f, 100f), 4f, 0, false)
        assertTrue(EditLayout.hits(ink, 50f, 6f, 5f))
        assertFalse(EditLayout.hits(ink, 50f, 50f, 5f))
    }

    @Test fun touchesPickTheChosenItemFirstThenTheTopmost() {
        val bottom = ShapeItem(1, 0, ShapeKind.RECTANGLE, 0f, 0f, 100f, 100f, 2f, 0, filled = true)
        val top = ShapeItem(2, 0, ShapeKind.RECTANGLE, 50f, 50f, 150f, 150f, 2f, 0, filled = true)
        val items = listOf(bottom, top)
        assertSame(top, EditLayout.pick(items, 75f, 75f, 1f, null))
        assertSame(bottom, EditLayout.pick(items, 75f, 75f, 1f, bottom.id))
        assertSame(top, EditLayout.pick(items, 140f, 140f, 1f, bottom.id))
        assertNull(EditLayout.pick(items, 300f, 300f, 1f, null))
    }

    @Test fun newItemsStartInTheMiddle() {
        val box = EditLayout.newShape(1, 2, ShapeKind.RECTANGLE, false, 2f, 0, 400f, 600f)
        assertEquals(2, box.page)
        assertEquals(200f, (box.x1 + box.x2) / 2, 0.001f)
        assertEquals(300f, (box.y1 + box.y2) / 2, 0.001f)
        val picture = EditLayout.newPicture(2, 0, "p", 0.5f, 1, true, 400f, 600f)
        assertEquals(0.5f, picture.width / picture.height, 0.001f)
        assertTrue(picture.height <= 300f && picture.width <= 200f)
        assertEquals(200f, picture.x + picture.width / 2, 0.001f)
        // Text too wide for the page gets a smaller font size.
        val long = EditLayout.newText(3, 0, "long", 40f, 0, 30f, 1.2f, 400f, 600f)
        assertEquals(12f, long.size, 0.001f)
        assertTrue(long.bounds.x >= 0f && abs(long.bounds.x + long.bounds.width / 2 - 200f) < 0.01f)
    }

    @Test fun newItemsDontHideTheOnesBefore() {
        val first = EditLayout.newShape(1, 0, ShapeKind.RECTANGLE, false, 2f, 0, 400f, 600f)
        val second = EditLayout.clearOf(EditLayout.newShape(2, 0, ShapeKind.RECTANGLE, false, 2f, 0, 400f, 600f), listOf(first), 400f, 600f) as ShapeItem
        // A step down and to the right: 4% of the page's shorter side.
        assertEquals(first.x1 + 16f, second.x1, 0.001f)
        assertEquals(first.y1 + 16f, second.y1, 0.001f)
        val third = EditLayout.clearOf(EditLayout.newShape(3, 0, ShapeKind.RECTANGLE, true, 2f, 0, 400f, 600f), listOf(first, second), 400f, 600f) as ShapeItem
        assertEquals(first.x1 + 32f, third.x1, 0.001f)
        // Something elsewhere, or on another page, isn't in the way.
        val moved = first.moved(100f, 0f)
        val ellipse = EditLayout.newShape(4, 0, ShapeKind.ELLIPSE, false, 2f, 0, 400f, 600f)
        assertSame(ellipse, EditLayout.clearOf(ellipse, listOf(moved), 400f, 600f))
        val elsewhere = EditLayout.newShape(5, 1, ShapeKind.RECTANGLE, false, 2f, 0, 400f, 600f)
        assertSame(elsewhere, EditLayout.clearOf(elsewhere, listOf(first, second), 400f, 600f))
    }

    @Test fun itemsSurviveBeingSavedAndRestored() {
        val items = arrayListOf<PageItem>(
            text,
            InkItem(2, 1, floatArrayOf(1f, 2f, 3f, 4f), 14f, 0x7FFFE600, true),
            ShapeItem(3, 0, ShapeKind.ARROW, 1f, 2f, 3f, 4f, 2f, 0, false),
            PictureItem(4, 2, "/cache/edit-pictures/4.picture", 1f, 2f, 3f, 4f, 6, false),
        )
        val bytes = ByteArrayOutputStream().also { out -> ObjectOutputStream(out).use { it.writeObject(items) } }.toByteArray()
        val restored = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() }
        assertEquals(items, restored)
        assertEquals(EditStyle(), ObjectInputStream(ByteArrayInputStream(
            ByteArrayOutputStream().also { out -> ObjectOutputStream(out).use { it.writeObject(EditStyle()) } }.toByteArray(),
        )).use { it.readObject() })
    }
}
