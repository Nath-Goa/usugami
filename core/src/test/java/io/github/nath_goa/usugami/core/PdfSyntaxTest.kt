package io.github.nath_goa.usugami.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale

class PdfSyntaxTest {

    @Test
    fun numbersUseDotAndNoExponent() {
        assertEquals("0", PdfSyntax.number(0.0))
        assertEquals("0", PdfSyntax.number(-0.0))
        assertEquals("0", PdfSyntax.number(0.00001))
        assertEquals("0", PdfSyntax.number(-0.00004))
        assertEquals("1.5", PdfSyntax.number(1.5))
        assertEquals("-12.25", PdfSyntax.number(-12.25))
        assertEquals("595.2756", PdfSyntax.number(595.275590551))
        assertEquals("841.8898", PdfSyntax.number(841.88976378))
        assertEquals("14400", PdfSyntax.number(14400.0))
        assertEquals("0.0001", PdfSyntax.number(0.0001))
        assertEquals("1000000.1", PdfSyntax.number(1_000_000.1))
        assertEquals("0.5", PdfSyntax.number(0.49999999))
    }

    @Test
    fun numbersIgnoreDefaultLocale() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("1.5", PdfSyntax.number(1.5))
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            assertEquals("D:20261014153012+05'30'", PdfSyntax.date(ZonedDateTime.of(2026, 10, 14, 15, 30, 12, 0, ZoneOffset.ofHoursMinutes(5, 30))))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun textStrings() {
        assertEquals("(Holiday photos)", PdfSyntax.textString("Holiday photos"))
        assertEquals("(a\\(b\\)c\\\\d)", PdfSyntax.textString("a(b)c\\d"))
        assertEquals("<FEFF00E9>", PdfSyntax.textString("é"))
        assertEquals("<FEFFD83DDCF7>", PdfSyntax.textString("📷"))
        assertEquals("<FEFF0061000A>", PdfSyntax.textString("a\n"))
    }

    @Test
    fun dates() {
        assertEquals("D:20260102030405-08'00'", PdfSyntax.date(ZonedDateTime.of(2026, 1, 2, 3, 4, 5, 0, ZoneOffset.ofHours(-8))))
        assertEquals("D:20261231235959+00'00'", PdfSyntax.date(ZonedDateTime.of(2026, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC)))
    }
}
