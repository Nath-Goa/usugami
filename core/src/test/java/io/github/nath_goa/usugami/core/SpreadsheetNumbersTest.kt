package io.github.nath_goa.usugami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SpreadsheetNumbersTest {
    private fun shown(value: Double, code: String, date1904: Boolean = false) =
        SpreadsheetNumbers.format(value, code, date1904 = date1904).text

    /** Each row is a value, a format code and what Excel shows. */
    private fun check(vararg rows: Triple<Double, String, String>) {
        for ((value, code, expected) in rows) assertEquals("$value with $code", expected, shown(value, code))
    }

    private fun row(value: Double, code: String, expected: String) = Triple(value, code, expected)

    @Test fun generalShowsWhatFitsInElevenCharacters() {
        check(
            row(0.0, "General", "0"),
            row(-0.0, "General", "0"),
            row(-1.0, "General", "-1"),
            row(0.1 + 0.2, "General", "0.3"),
            row(1.0 / 3, "General", "0.333333333"),
            row(2.0 / 3, "General", "0.666666667"),
            row(-1.0 / 3, "General", "-0.333333333"),
            row(1234.5678901234, "General", "1234.56789"),
            row(12345678901.0, "General", "12345678901"),
            row(123456789012.0, "General", "1.23457E+11"),
            row(99999999999.5, "General", "1E+11"),
            row(1e15, "General", "1E+15"),
            row(1.23456789e100, "General", "1.2346E+100"),
            row(0.0001, "General", "0.0001"),
            row(0.00001234, "General", "0.00001234"),
            row(0.0000123456789, "General", "1.23457E-05"),
            row(1.5e-10, "General", "1.5E-10"),
        )
    }

    @Test fun generalNarrowsToTheColumn() {
        assertEquals("1234.6", SpreadsheetNumbers.format(1234.5678, "General", maxChars = 6).text)
        assertEquals("1E+08", SpreadsheetNumbers.format(123456789.0, "General", maxChars = 6).text)
        // However wide the column, General stops at eleven.
        assertEquals("0.333333333", SpreadsheetNumbers.format(1.0 / 3, "General", maxChars = 40).text)
    }

    @Test fun impossibleNumbersShowAsNum() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertEquals("#NUM!", shown(value, "0.00"))
        }
    }

    @Test fun builtInFormatsAreExcelsOwn() {
        assertEquals("General", SpreadsheetNumbers.builtInFormat(0))
        assertEquals("#,##0.00", SpreadsheetNumbers.builtInFormat(4))
        assertEquals("m/d/yyyy", SpreadsheetNumbers.builtInFormat(14))
        assertEquals("[h]:mm:ss", SpreadsheetNumbers.builtInFormat(46))
        assertEquals("@", SpreadsheetNumbers.builtInFormat(49))
        assertNull(SpreadsheetNumbers.builtInFormat(50))
        assertNull(SpreadsheetNumbers.builtInFormat(-1))
        val dates = (14..22) + (27..36) + (45..47)
        for (id in 0..49) {
            val code = SpreadsheetNumbers.builtInFormat(id)!!
            assertEquals("id $id", id in dates, SpreadsheetNumbers.isDateFormat(code))
        }
        check(
            row(-1234.5, SpreadsheetNumbers.builtInFormat(8)!!, "($1,234.50)"),
            row(1234.5, SpreadsheetNumbers.builtInFormat(44)!!, " $1,234.50 "),
            row(-1234.5, SpreadsheetNumbers.builtInFormat(43)!!, " (1,234.50)"),
            row(0.0, SpreadsheetNumbers.builtInFormat(41)!!, " - "),
        )
    }

    @Test fun digitPlaceholders() {
        check(
            row(2.5, "0", "3"),
            row(-2.5, "0", "-3"),
            row(1.005, "0.00", "1.01"),
            row(1234567.891, "#,##0.00", "1,234,567.89"),
            row(-1234.0, "#,##0", "-1,234"),
            row(5.0, "0,000", "0,005"),
            row(0.5, "#.##", ".5"),
            row(1.0, "0.#", "1."),
            row(1.234, "0.0#", "1.23"),
            row(1.5, "0.0#", "1.5"),
            row(1.5, "0.0?", "1.5 "),
            row(12.5, ".00", "12.50"),
            row(123.0, "00000", "00123"),
            row(123456789.0, "000-00-0000", "123-45-6789"),
            row(5551234567.0, "(###) ###-####", "(555) 123-4567"),
        )
    }

    @Test fun scalingPercentAndPerMille() {
        check(
            row(1234567.0, "#,##0,", "1,235"),
            row(1234567.0, "0.0,,", "1.2"),
            row(1234567.0, "#,##0.0,,\"M\"", "1.2M"),
            row(0.256, "0%", "26%"),
            row(0.256, "0.00%", "25.60%"),
            row(0.0256, "0‰", "26‰"),
        )
    }

    @Test fun scientific() {
        check(
            row(12345.0, "0.00E+00", "1.23E+04"),
            row(0.000123, "0.00E+00", "1.23E-04"),
            row(9.999, "0.00E+00", "1.00E+01"),
            row(0.0, "0.00E+00", "0.00E+00"),
            row(12345.0, "0.00E-00", "1.23E04"),
            row(12345.0, "##0.0E+0", "12.3E+3"),
            row(0.000123, "##0.0E+0", "123.0E-6"),
        )
    }

    @Test fun fractions() {
        check(
            row(1.5, "# ?/?", "1 1/2"),
            row(Math.PI, "# ??/??", "3 14/99"),
            row(-1.25, "# ??/??", "-1  1/4 "),
            row(2.0, "# ?/?", "2    "),
            row(0.999, "# ?/?", "1    "),
            row(1.5, "?/?", "3/2"),
            row(1.5, "# ?/8", "1 4/8"),
            row(0.5, "0/100", "50/100"),
        )
    }

    @Test fun textAroundTheNumber() {
        check(
            row(-5.0, "\"$\"#,##0.00", "-$5.00"),
            row(5.0, "#,##0.00 \"kg\"", "5.00 kg"),
            row(3.0, "\\$0\\ \\k\\g", "$3 kg"),
            row(5.0, "0_)", "5 "),
            row(5.0, "**0", "5"),
            row(1234.5, "[$€-x-sysdecimal] #,##0.00", "€ 1,234.50"),
            row(1234.0, "[$₹-4009] #,##0", "₹ 1,234"),
            row(1.25, "[$-409]0.0", "1.3"),
            row(5.0, "\"Total: \"General", "Total: 5"),
        )
    }

    @Test fun sectionsForNegativeZeroAndText() {
        check(
            row(-5.0, "0.00;(0.00)", "(5.00)"),
            row(-5.0, "0;-0", "-5"),
            row(0.0, "0;-0;\"zero\"", "zero"),
            row(0.001, "0;-0;\"zero\"", "0"),
            row(0.0, "0;-0;;@", ""),
            row(5.0, ";;;", ""),
            row(-5.0, "0.00;@", "-5.00"),
            row(1.5, "@", "1.5"),
            row(-5.0, "General;[Red]-General", "-5"),
        )
    }

    @Test fun conditionsPickTheSection() {
        val millions = "[>=1000000]0.0,,\"M\";[>=1000]0.0,\"K\";0"
        check(row(1500000.0, millions, "1.5M"), row(2500.0, millions, "2.5K"), row(999.0, millions, "999"))
        val split = "[Red][<=100]0;[Blue][>100]0"
        assertEquals(CellText("50", 0xFFFF0000.toInt()), SpreadsheetNumbers.format(50.0, split))
        assertEquals(CellText("150", 0xFF0000FF.toInt()), SpreadsheetNumbers.format(150.0, split))
        // A negative number in a section of its own condition keeps its minus.
        assertEquals("-5", shown(-5.0, split))
    }

    @Test fun colours() {
        assertEquals(0xFFFF0000.toInt(), SpreadsheetNumbers.format(5.0, "[Red]0").colour)
        assertEquals(CellText("-5", 0xFFFF0000.toInt()), SpreadsheetNumbers.format(-5.0, "0;[Red]-0"))
        assertEquals(0xFF008000.toInt(), SpreadsheetNumbers.format(5.0, "[Color10]0").colour)
        assertEquals(0xFF0000FF.toInt(), SpreadsheetNumbers.format(5.0, "[BLUE]0").colour)
        assertNull(SpreadsheetNumbers.format(5.0, "0;[Red]-0").colour)
    }

    @Test fun textCells() {
        assertEquals(CellText("abc", null), SpreadsheetNumbers.formatText("abc", "@"))
        assertEquals(CellText("abc", null), SpreadsheetNumbers.formatText("abc", "0.00"))
        assertEquals(CellText("abc", null), SpreadsheetNumbers.formatText("abc", "0.00;@"))
        assertEquals(CellText("Text: abc", null), SpreadsheetNumbers.formatText("abc", "0;-0;0;\"Text: \"@"))
        assertEquals(CellText("n/a", null), SpreadsheetNumbers.formatText("abc", "0;-0;0;\"n/a\""))
        assertEquals(CellText("abc", 0xFFFF0000.toInt()), SpreadsheetNumbers.formatText("abc", "0;-0;0;[Red]@"))
        assertEquals(CellText(" x ", null), SpreadsheetNumbers.formatText("x", SpreadsheetNumbers.builtInFormat(41)!!))
    }

    @Test fun brokenCodesFallBackToGeneral() {
        for (code in listOf("", "\"unclosed", "[Red", "0;0;0;0;0", "#".repeat(5000))) {
            assertEquals(code, CellText("1.5", null), SpreadsheetNumbers.format(1.5, code))
        }
    }

    @Test fun dates() {
        // 45000 is Wednesday 15 March 2023.
        check(
            row(45000.0, "m/d/yyyy", "3/15/2023"),
            row(45000.0, "dddd, mmmm d, yyyy", "Wednesday, March 15, 2023"),
            row(45000.0, "d-mmm-yy", "15-Mar-23"),
            row(45000.0, "ddd dd/mm/yy", "Wed 15/03/23"),
            row(45000.0, "mmmmm", "M"),
            row(45000.0, "YYYY-MM-DD", "2023-03-15"),
            row(45000.0, "mm/dd", "03/15"),
            row(45000.5, "m/d/yyyy h:mm", "3/15/2023 12:00"),
        )
    }

    @Test fun times() {
        check(
            row(0.75, "h:mm AM/PM", "6:00 PM"),
            row(0.0, "h:mm:ss AM/PM", "12:00:00 AM"),
            row(0.75, "h:mm a/p", "6:00 p"),
            // Typed in small letters, AM/PM still shows in capitals.
            row(0.6, "h:mm am/pm", "2:24 PM"),
            row(0.5, "hh:mm:ss", "12:00:00"),
            row((12 * 60 + 5) / 1440.0, "h:mm", "12:05"),
            row(307 / 86400.0, "mm:ss", "05:07"),
            // Seconds round, as in Excel, and then the minutes aren't rounded up.
            row((12 * 3600 + 0.6) / 86400, "h:mm:ss", "12:00:01"),
            row((59 * 60 + 59) / 86400.0, "h:mm", "0:59"),
            row(1.5 / 86400, "mm:ss.0", "00:01.5"),
            row(1.5, "[h]:mm:ss", "36:00:00"),
            row(1 / 24.0, "[mm]:ss", "60:00"),
            row(1 / 1440.0, "[ss]", "60"),
        )
    }

    @Test fun excelsCalendarOf1900And1904() {
        check(
            row(0.0, "m/d/yyyy", "1/0/1900"),
            row(1.0, "dddd", "Sunday"),
            row(59.0, "m/d/yyyy", "2/28/1900"),
            row(60.0, "m/d/yyyy", "2/29/1900"),
            row(61.0, "m/d/yyyy", "3/1/1900"),
            row(2958465.0, "m/d/yyyy", "12/31/9999"),
            row(-1.0, "m/d/yyyy", "###########"),
            row(2958466.0, "m/d/yyyy", "###########"),
        )
        assertEquals("1/1/1904", shown(0.0, "m/d/yyyy", date1904 = true))
        assertEquals("3/16/2027", shown(45000.0, "m/d/yyyy", date1904 = true))
    }

    @Test fun theLocalesSymbolsAndNames() {
        val french = SheetLocale(
            decimal = ',',
            grouping = ' ',
            months = listOf(
                "janvier", "février", "mars", "avril", "mai", "juin",
                "juillet", "août", "septembre", "octobre", "novembre", "décembre",
            ),
            shortMonths = listOf("janv.", "févr.", "mars", "avr.", "mai", "juin", "juil.", "août", "sept.", "oct.", "nov.", "déc."),
            days = listOf("dimanche", "lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi"),
            shortDays = listOf("dim.", "lun.", "mar.", "mer.", "jeu.", "ven.", "sam."),
            am = "AM",
            pm = "PM",
        )
        assertEquals("1 234,50", SpreadsheetNumbers.format(1234.5, "#,##0.00", french).text)
        assertEquals("1,5", SpreadsheetNumbers.format(1.5, "General", french).text)
        assertEquals("mercredi 15 mars 2023", SpreadsheetNumbers.format(45000.0, "dddd d mmmm yyyy", french).text)
    }

    @Test fun recognizesDateFormats() {
        for (code in listOf("m/d/yyyy", "[h]:mm", "mm:ss", "[$-409]mmmm", "yyyy\" years\"", "h AM/PM")) {
            assertTrue(code, SpreadsheetNumbers.isDateFormat(code))
        }
        for (code in listOf("0.00", "General", "\"d\"0", "0 \\m", "[Red]0", "@", "0.00E+00", "[$-409]#,##0")) {
            assertFalse(code, SpreadsheetNumbers.isDateFormat(code))
        }
    }

    @Test fun anyCodeAndValueIsShownWithoutAnError() {
        val pieces = listOf(
            "0", "#", "?", ".", ",", "%", "‰", "E+", "e-", "/", "/8", " ", "\"x\"", "\\-", "_)", "*-", "[Red]", "[Color60]",
            "[>=100]", "[<0]", "[=x]", "[$€-x-euro2]", "[$-409]", "[h]", "[mm]", "[ss]", "y", "yyyy", "m", "mmm", "mmmmm",
            "d", "dddd", "h", "hh", "s", "ss", ".000", "AM/PM", "A/P", "@", ";", "General", "\"", "[", "]", "\\", "_", "*", "g", "e",
        )
        val values = listOf(
            0.0, -0.0, 1.0, -1.0, 0.5, 1e-300, 1e300, -1e300, 45000.75, 2958465.99999, 3e6, Double.MAX_VALUE, Double.MIN_VALUE,
            Double.NaN, Double.POSITIVE_INFINITY, 1234.5678, -0.001,
        )
        val random = Random(1904)
        repeat(3000) {
            val code = (0 until random.nextInt(12)).joinToString("") { pieces.random(random) }
            val value = if (random.nextBoolean()) values.random(random) else random.nextDouble(-1e7, 1e7)
            SpreadsheetNumbers.format(value, code, date1904 = random.nextBoolean(), maxChars = random.nextInt(-2, 20))
            SpreadsheetNumbers.formatText("text", code)
            SpreadsheetNumbers.isDateFormat(code)
        }
    }
}
