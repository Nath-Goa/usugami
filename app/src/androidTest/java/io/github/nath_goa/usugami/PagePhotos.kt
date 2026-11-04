package io.github.nath_goa.usugami

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import io.github.nath_goa.usugami.core.CropPoint
import io.github.nath_goa.usugami.core.CropQuad
import java.io.File
import kotlin.math.sin

/** Synthetic phone photos of a page lying on a table, for the crop tests. */
internal object PagePhotos {
    const val PAGE_WIDTH = 700
    const val PAGE_HEIGHT = 990
    const val PHOTO_WIDTH = 1200
    const val PHOTO_HEIGHT = 1600

    /** Where the page lies on the photo, in its pixels, clockwise from the page's top-left: as seen from a little to the side. */
    val CORNERS = floatArrayOf(300f, 260f, 930f, 330f, 1010f, 1350f, 170f, 1290f)

    /** [CORNERS] as fractions of the photo's size. */
    val QUAD = CropQuad(List(4) { CropPoint(CORNERS[2 * it] / PHOTO_WIDTH, CORNERS[2 * it + 1] / PHOTO_HEIGHT) })

    /** The page's corner squares' colours, clockwise from its top-left. */
    val MARKS = intArrayOf(Color.rgb(220, 30, 30), Color.rgb(30, 170, 60), Color.rgb(30, 60, 220), Color.rgb(230, 190, 20))
    const val MARK = 70

    /** A white page with a coloured square in each corner and a black line across its middle, [MARK] pixels thick. */
    fun page(draw: (Canvas) -> Unit = {}): Bitmap {
        val page = Bitmap.createBitmap(PAGE_WIDTH, PAGE_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(page)
        canvas.drawColor(Color.rgb(242, 240, 234))
        val paint = Paint()
        val spots = listOf(0 to 0, PAGE_WIDTH - MARK to 0, PAGE_WIDTH - MARK to PAGE_HEIGHT - MARK, 0 to PAGE_HEIGHT - MARK)
        spots.forEachIndexed { i, (x, y) ->
            paint.color = MARKS[i]
            canvas.drawRect(x.toFloat(), y.toFloat(), (x + MARK).toFloat(), (y + MARK).toFloat(), paint)
        }
        paint.color = Color.BLACK
        canvas.drawRect(100f, PAGE_HEIGHT / 2f - 4, PAGE_WIDTH - 100f, PAGE_HEIGHT / 2f + 4, paint)
        draw(canvas)
        return page
    }

    /** [page] lying at [corners] on a dark wooden table, [width] × [height]; [table] draws more on the table. */
    fun photo(
        page: Bitmap = page(),
        width: Int = PHOTO_WIDTH,
        height: Int = PHOTO_HEIGHT,
        corners: FloatArray = CORNERS,
        table: (Canvas) -> Unit = {},
    ): Bitmap {
        val photo = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val row = IntArray(width)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val grain = (12 * sin(y / 9.0 + 3 * sin(x / 70.0))).toInt()
                row[x] = Color.rgb(96 + grain, 66 + grain, 42 + grain)
            }
            photo.setPixels(row, 0, width, 0, y, width, 1)
        }
        val canvas = Canvas(photo)
        table(canvas)
        val matrix = Matrix()
        matrix.setPolyToPoly(floatArrayOf(0f, 0f, page.width.toFloat(), 0f, page.width.toFloat(), page.height.toFloat(), 0f, page.height.toFloat()), 0, corners, 0, 4)
        canvas.drawBitmap(page, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return photo
    }

    fun jpeg(bitmap: Bitmap, file: File): File {
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        return file
    }
}
