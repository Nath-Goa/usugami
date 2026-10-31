package io.github.nath_goa.usugami.data.office

import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.max

/**
 * A table cell, laid out: [x] and [width] from the table's left edge, padding inside it, all in
 * layout units. A null [content] is an empty cell, or the lower part of a merged one.
 */
internal data class CellBox(
    val x: Float,
    val width: Float,
    val content: Block?,
    val fill: Int?,
    val top: Border?,
    val left: Border?,
    val bottom: Border?,
    val right: Border?,
    /** 0 top, 1 middle, 2 bottom. */
    val verticalAlign: Int,
    val padLeft: Int,
    val padTop: Int,
    val padRight: Int,
    val padBottom: Int,
)

/** A table row. It grows with its tallest cell, unless its height is [exact]. */
internal class RowBlock(private val cells: List<CellBox>, private val minHeight: Int, private val exact: Boolean) : Block {
    override val height: Int =
        if (exact && minHeight > 0) minHeight
        else max(minHeight, cells.maxOfOrNull { (it.content?.height ?: 0) + it.padTop + it.padBottom } ?: 0)

    override fun draw(canvas: Canvas, x: Float, y: Float) {
        for (cell in cells) {
            val left = x + cell.x
            cell.fill?.let { canvas.drawRect(left, y, left + cell.width, y + height, Paint().apply { color = it }) }
            val content = cell.content ?: continue
            val free = height - cell.padTop - cell.padBottom - content.height
            val top = y + cell.padTop + if (free > 0) free * cell.verticalAlign / 2f else 0f
            if (exact) {
                canvas.save()
                canvas.clipRect(left, y, left + cell.width, y + height)
            }
            content.draw(canvas, left + cell.padLeft, top)
            if (exact) canvas.restore()
        }
        // Borders last, so no cell's fill covers its neighbour's border.
        for (cell in cells) {
            val left = x + cell.x
            val right = left + cell.width
            line(canvas, cell.top, left, y, right, y)
            line(canvas, cell.bottom, left, y + height, right, y + height)
            line(canvas, cell.left, left, y, left, y + height)
            line(canvas, cell.right, right, y, right, y + height)
        }
    }

    private fun line(canvas: Canvas, border: Border?, x1: Float, y1: Float, x2: Float, y2: Float) {
        if (border == null || border.width <= 0f) return
        canvas.drawLine(x1, y1, x2, y2, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = border.color
            strokeWidth = border.width.units
            strokeCap = Paint.Cap.SQUARE
        })
    }

    override fun split(space: Int, force: Boolean): Pair<Block, Block?>? {
        if (exact) return if (force) this to null else null
        val heads = ArrayList<CellBox>()
        val tails = ArrayList<CellBox>()
        var progress = false
        for (cell in cells) {
            val content = cell.content
            val room = space - cell.padTop - cell.padBottom
            if (content == null || content.height <= room) {
                heads += cell
                tails += cell.copy(content = null)
                if (content != null) progress = true
                continue
            }
            val parts = if (room > 0 || force) content.split(max(0, room), force) else null
            if (parts == null) {
                heads += cell.copy(content = null)
                tails += cell
            } else {
                heads += cell.copy(content = parts.first)
                tails += cell.copy(content = parts.second)
                progress = true
            }
        }
        if (!progress) return if (force) this to null else null
        val rest = tails.takeIf { cells -> cells.any { it.content != null } }?.let { RowBlock(it, 0, false) }
        return RowBlock(heads, 0, false) to rest
    }
}

/**
 * A table: rows one under another, [x] units from the left edge it's drawn at. Split between pages
 * at a row, or inside a row that won't fit on a page of its own; the first [headerRows] rows repeat
 * at the top of each page.
 */
internal class TableBlock(private val rows: List<RowBlock>, private val headerRows: Int, private val x: Float) : Block {
    override val height: Int = rows.sumOf { it.height }

    override fun draw(canvas: Canvas, x: Float, y: Float) {
        var top = y
        for (row in rows) {
            row.draw(canvas, x + this.x, top)
            top += row.height
        }
    }

    override fun split(space: Int, force: Boolean): Pair<Block, Block?>? {
        var used = 0
        for ((i, row) in rows.withIndex()) {
            if (used + row.height <= space) {
                used += row.height
                continue
            }
            val room = space - used
            val bodyFits = i > headerRows
            var parts = row.split(room, force = false)
            if (parts == null && !bodyFits) {
                if (!force) return null
                parts = row.split(max(0, room), force = true)
            }
            val head = rows.take(i) + listOfNotNull(parts?.first as RowBlock?)
            if (head.isEmpty()) return null
            val repeat = if (i >= headerRows) rows.take(headerRows) else emptyList()
            val carried = if (parts == null) listOf(row) else listOfNotNull(parts.second as RowBlock?)
            val tail = carried + rows.drop(i + 1)
            if (tail.isEmpty()) return TableBlock(head, headerRows, x) to null
            return TableBlock(head, headerRows, x) to TableBlock(repeat + tail, repeat.size, x)
        }
        return this to null
    }
}
