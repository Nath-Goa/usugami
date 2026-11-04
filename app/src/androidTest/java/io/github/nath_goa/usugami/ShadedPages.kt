package io.github.nath_goa.usugami

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.io.File
import kotlin.math.exp
import kotlin.random.Random

/**
 * A synthetic phone photo of a printed A4 page, already cropped to the page, as the page looks tests
 * use it: print, a black bar and blank paper, lit as a phone photo lights a page (the light falling
 * off across it, a shadow over its left, a warm lamp) with noise. The places are fractions of the
 * page, x across and y down.
 */
internal object ShadedPages {
    /** Blank paper in the shadow and in the light. */
    val SHADED_PAPER = RectF(0.05f, 0.36f, 0.38f, 0.46f)
    val LIT_PAPER = RectF(0.60f, 0.36f, 0.92f, 0.46f)

    /** A black bar, in the shadow. */
    val BAR = RectF(0.10f, 0.50f, 0.38f, 0.53f)

    /** The shadow: over these, with an edge a few millimetres wide. */
    private const val SHADOW_RIGHT = 0.45f
    private const val SHADOW_TOP = 0.30f
    private const val SHADOW_BOTTOM = 0.70f

    val WORDS = listOf("Invoice", "QUARTZ", "Friday", "brown", "48213")

    /** The page, [width] pixels wide, as a JPEG in [file]. */
    fun photo(file: File, width: Int = 2480): File {
        val height = width * 297 / 210
        val page = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(page)
        canvas.drawColor(Color.rgb(246, 245, 240))
        val unit = width / 1240f
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(22, 22, 28) }
        ink.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        ink.textSize = 80 * unit
        canvas.drawText("QUARTZ REPORT", 90 * unit, 160 * unit, ink)
        ink.typeface = Typeface.SERIF
        ink.textSize = 34 * unit
        val lines = listOf(
            "The quick brown fox jumps over the lazy dog.",
            "Invoice number 48213 is due on Friday.",
            "Pages scanned with a phone come out clean.",
        )
        lines.forEachIndexed { i, line -> canvas.drawText(line, 90 * unit, (260 + 60 * i) * unit, ink) }
        canvas.drawRect(BAR.left * width, BAR.top * height, BAR.right * width, BAR.bottom * height, ink)
        var y = 0.60f * height
        var i = 0
        while (y < 0.92f * height) {
            canvas.drawText(lines[i++ % lines.size], 90 * unit, y, ink)
            y += 60 * unit
        }
        // The light, the shadow, the lamp and the noise.
        val random = Random(2)
        val row = IntArray(width)
        val soft = width * 0.004f
        val across = FloatArray(width) { px -> 1 / (1 + exp((px - SHADOW_RIGHT * width) / soft)) }
        for (py in 0 until height) {
            page.getPixels(row, 0, width, 0, py, width, 1)
            val fy = py.toFloat() / height
            val down = (1 / (1 + exp((SHADOW_TOP * height - py) / soft))) * (1 / (1 + exp((py - SHADOW_BOTTOM * height) / soft)))
            for (px in 0 until width) {
                val fx = px.toFloat() / width
                val light = (1f - 0.4f * (fx + fy) / 2) * (1 - 0.45f * across[px] * down)
                val noise = random.nextInt(-4, 5)
                val c = row[px]
                row[px] = Color.rgb(
                    (Color.red(c) * light + noise).toInt().coerceIn(0, 255),
                    (Color.green(c) * light * 0.93f + noise).toInt().coerceIn(0, 255),
                    (Color.blue(c) * light * 0.8f + noise).toInt().coerceIn(0, 255),
                )
            }
            page.setPixels(row, 0, width, 0, py, width, 1)
        }
        file.parentFile?.mkdirs()
        file.outputStream().use { page.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        page.recycle()
        return file
    }

    /** The part of [bitmap] in [area] (fractions of it) whose pixels pass [test]. */
    fun share(bitmap: Bitmap, area: RectF, margin: Float = 0.005f, test: (Int) -> Boolean): Double {
        val left = ((area.left + margin) * bitmap.width).toInt()
        val right = ((area.right - margin) * bitmap.width).toInt()
        val top = ((area.top + margin) * bitmap.height).toInt()
        val bottom = ((area.bottom - margin) * bitmap.height).toInt()
        var passed = 0
        var all = 0
        val row = IntArray(right - left)
        for (y in top until bottom) {
            bitmap.getPixels(row, 0, row.size, left, y, row.size, 1)
            for (c in row) {
                all++
                if (test(c)) passed++
            }
        }
        return passed.toDouble() / all
    }

    fun luma(c: Int) = (Color.red(c) * 77 + Color.green(c) * 150 + Color.blue(c) * 29) shr 8
}
