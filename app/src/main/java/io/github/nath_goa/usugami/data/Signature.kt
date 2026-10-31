package io.github.nath_goa.usugami.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Parcelable
import androidx.core.graphics.createBitmap
import io.github.nath_goa.usugami.core.Area
import io.github.nath_goa.usugami.core.SignatureLayout
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import kotlin.math.roundToInt

/** Ink colours for a signature, as ARGB. */
enum class SignatureInk(val argb: Int) {
    BLACK(0xFF000000.toInt()),

    /** The blue of a ballpoint pen. */
    BLUE(0xFF1A3C9C.toInt()),
}

/**
 * A signature drawn with a finger: [strokes] in pad units (see [SignatureLayout]), each its points'
 * coordinates in turn (x0, y0, x1, y1, …), in [ink].
 *
 * It is a picture of a handwritten signature, not a digital (certificate) signature. Usugami keeps
 * it in memory and in the screen state Android saves, for this session only; it is only ever
 * written to a file inside a signed PDF.
 */
@Parcelize
class Signature(val strokes: List<FloatArray>, val ink: SignatureInk) : Parcelable {
    /** What the strokes cover, the pen included; null while nothing is drawn. */
    @IgnoredOnParcel
    val bounds: Area? by lazy { SignatureLayout.inkBounds(strokes) }

    val isEmpty: Boolean get() = bounds == null

    /** Width over height of what is drawn. */
    val aspect: Float get() = bounds?.let { it.width / it.height } ?: 1f

    val pointCount: Int get() = strokes.sumOf { it.size / 2 }

    operator fun plus(stroke: FloatArray) = Signature(strokes + stroke, ink)

    fun withInk(ink: SignatureInk) = Signature(strokes, ink)

    fun cleared() = Signature(emptyList(), ink)

    /** The strokes as one smooth path, in pad units: the screen and the PDF both draw this. */
    fun path(): Path = smoothPath(strokes)

    companion object {
        /** Points closer together than this, in pad units, are dropped while drawing. */
        const val MIN_STEP = 2f

        /**
         * More points than this are ignored: a signature never needs them, and the drawing must
         * stay small enough to keep in the screen's saved state.
         */
        const val MAX_POINTS = 5000

        /**
         * A smooth curve along [strokes], which rounds off the corners of a finger's sampled path:
         * from each stroke's first point it bends through each point in turn to the midpoint
         * between it and the next, then goes straight to the last point. This must stay the curve
         * that [SignatureLayout.inkBounds] measures. A stroke of one point is a dot, as the pen's
         * round end draws it.
         */
        fun smoothPath(strokes: List<FloatArray>): Path {
            val path = Path()
            for (stroke in strokes) {
                val count = stroke.size / 2
                if (count == 0) continue
                path.moveTo(stroke[0], stroke[1])
                for (i in 1 until count - 1) {
                    val x = stroke[2 * i]
                    val y = stroke[2 * i + 1]
                    path.quadTo(x, y, (x + stroke[2 * i + 2]) / 2, (y + stroke[2 * i + 3]) / 2)
                }
                path.lineTo(stroke[2 * count - 2], stroke[2 * count - 1])
            }
            return path
        }

        /** How the pen draws, in pad units. */
        fun pen(argb: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = SignatureLayout.PEN_WIDTH
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = argb
        }
    }
}

/**
 * The signature as an image [width] pixels wide, trimmed to what is drawn: every pixel has the
 * ink's colour, and only its alpha says how much ink is there.
 *
 * Android keeps bitmaps premultiplied, which turns fully transparent pixels black; with a different
 * colour there, the image's colour data would vary for nothing and compress worse, and smoothing
 * at the edges could darken a blue line. So the alpha drawn here is copied into a bitmap that isn't
 * premultiplied, under one colour.
 */
fun Signature.render(width: Int): Bitmap {
    val ink = requireNotNull(bounds) { "Nothing drawn" }
    val scale = width / ink.width
    val height = maxOf(1, (ink.height * scale).roundToInt())
    val drawn = createBitmap(width, height)
    val pixels = IntArray(width * height)
    try {
        Canvas(drawn).apply {
            scale(scale, scale)
            translate(-ink.x, -ink.y)
            drawPath(path(), Signature.pen(0xFF000000.toInt()))
        }
        drawn.getPixels(pixels, 0, width, 0, 0, width, height)
    } finally {
        drawn.recycle()
    }
    val color = this.ink.argb and 0xFFFFFF
    for (i in pixels.indices) pixels[i] = (pixels[i] and 0xFF000000.toInt()) or color
    return createBitmap(width, height).apply {
        isPremultiplied = false
        setPixels(pixels, 0, width, 0, 0, width, height)
    }
}
