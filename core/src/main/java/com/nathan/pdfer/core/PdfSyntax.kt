package com.nathan.pdfer.core

import java.time.ZonedDateTime
import kotlin.math.abs

/** Locale-independent helpers for writing PDF syntax. */
object PdfSyntax {

    /**
     * Formats a real number the way PDF expects it: '.' as decimal separator, no exponent,
     * at most four decimals. String.format would use ',' in many locales and corrupt the file.
     */
    fun number(value: Double): String {
        require(value.isFinite()) { "PDF numbers must be finite: $value" }
        val scaled = Math.round(value * 10_000.0)
        if (scaled == 0L) return "0"
        val magnitude = abs(scaled)
        val sb = StringBuilder()
        if (scaled < 0) sb.append('-')
        sb.append(magnitude / 10_000)
        val fraction = magnitude % 10_000
        if (fraction != 0L) {
            sb.append('.')
            sb.append(fraction.toString().padStart(4, '0').trimEnd('0'))
        }
        return sb.toString()
    }

    /** A PDF text string: a literal for plain ASCII, otherwise UTF-16BE hex with a byte order mark. */
    fun textString(text: String): String {
        if (text.all { it in ' '..'~' }) {
            val sb = StringBuilder("(")
            for (c in text) {
                if (c == '(' || c == ')' || c == '\\') sb.append('\\')
                sb.append(c)
            }
            return sb.append(')').toString()
        }
        return "<FEFF" + hex(text.toByteArray(Charsets.UTF_16BE)) + ">"
    }

    /** A PDF date string, e.g. D:20261014153012+05'30'. */
    fun date(time: ZonedDateTime): String {
        val offsetSeconds = time.offset.totalSeconds
        val sign = if (offsetSeconds < 0) '-' else '+'
        val offsetMinutes = abs(offsetSeconds) / 60
        return "D:%04d%02d%02d%02d%02d%02d%c%02d'%02d'".format(
            java.util.Locale.ROOT,
            time.year, time.monthValue, time.dayOfMonth, time.hour, time.minute, time.second,
            sign, offsetMinutes / 60, offsetMinutes % 60,
        )
    }

    fun hex(bytes: ByteArray): String {
        val digits = "0123456789ABCDEF"
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(digits[v ushr 4]).append(digits[v and 0x0F])
        }
        return sb.toString()
    }
}
