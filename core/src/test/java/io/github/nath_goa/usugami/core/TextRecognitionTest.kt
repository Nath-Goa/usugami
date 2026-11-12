package io.github.nath_goa.usugami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** CTC decoding and the cutting of long lines for Usugami OCR's recognition model. */
class TextRecognitionTest {
    /** A steps × classes output where [best] has the given probability and the rest share what is left. */
    private fun output(best: List<Pair<Int, Float>>, classes: Int) = FloatArray(best.size * classes).also { values ->
        best.forEachIndexed { step, (index, probability) ->
            val rest = (1 - probability) / (classes - 1)
            for (c in 0 until classes) values[step * classes + c] = if (c == index) probability else rest
        }
    }

    @Test fun decodesLikePaddleOcr() {
        // Real outputs of the latin model (its most likely class and probability at each step) for
        // four lines, with what PaddleOCR made of them (tools/make_ocr_test_fixtures.py).
        val characters = javaClass.getResourceAsStream("/ocr/latin_dictionary.txt")!!.bufferedReader().readLines()
        val lines = javaClass.getResourceAsStream("/ocr/recognition.txt")!!.bufferedReader().readLines().filter { !it.startsWith("#") }
        assertEquals(4, lines.size)
        for (line in lines) {
            val (text, confidence, classes, steps) = line.split('\t')
            val best = steps.split(' ').map { it.substringBefore(':').toInt() to it.substringAfter(':').toFloat() }
            val decoded = TextRecognition.decode(output(best, classes.toInt()), best.size, classes.toInt(), characters)
            assertEquals(text, decoded.text)
            assertEquals(confidence.toFloat(), decoded.confidence, 1e-5f)
        }
    }

    /**
     * German and Portuguese, read by the latin model in Usugami OCR, keep their letters: each is one
     * the model can give (this dictionary is the one in the OCR edition's assets).
     */
    @Test fun theLatinDictionaryHasGermanAndPortugueseLetters() {
        val characters = javaClass.getResourceAsStream("/ocr/latin_dictionary.txt")!!.bufferedReader().readLines().toSet()
        val missing = "äöüßÄÖÜãõçáàâéêíóôúÃÕÇÁÀÂÉÊÍÓÔÚ".map(Char::toString).filter { it !in characters }
        assertEquals(emptyList<String>(), missing)
    }

    @Test fun mergesRepeatsDropsBlanksAndAddsTheSpaceClass() {
        // Class 0 is the blank, 1..4 the characters, 5 the space. "क ि" are two classes: a letter and
        // its vowel sign, which the text keeps in order.
        val characters = listOf("क", "ि", "A", "b")
        val best = listOf(0 to 0.9f, 1 to 0.8f, 1 to 0.6f, 2 to 1f, 0 to 0.9f, 5 to 0.7f, 3 to 0.5f, 0 to 1f, 3 to 0.9f, 4 to 0.4f)
        val decoded = TextRecognition.decode(output(best, 6), best.size, 6, characters)
        assertEquals("कि AAb", decoded.text)
        // The probabilities of the first step of each character kept: 0.8, 1, 0.7, 0.5, 0.9, 0.4.
        assertEquals((0.8f + 1f + 0.7f + 0.5f + 0.9f + 0.4f) / 6, decoded.confidence, 1e-6f)
        assertEquals(OcrText("", 0f), TextRecognition.decode(output(listOf(0 to 1f), 6), 1, 6, characters))
        assertThrows(IllegalArgumentException::class.java) { TextRecognition.decode(FloatArray(5), 1, 5, characters) }
        assertThrows(IllegalArgumentException::class.java) { TextRecognition.decode(FloatArray(5), 1, 6, characters) }
    }

    @Test fun longLinesAreCutBetweenWords() {
        // Words of 30 columns of ink separated by 8 empty columns, 400 columns in all.
        val ink = IntArray(400) { if (it % 38 < 30) 5 else 0 }
        val cuts = TextRecognition.cuts(ink, maxWidth = 160, spaceWidth = 6)
        var start = 0
        for (cut in cuts) {
            assertTrue("Piece $start..${cut.at} too wide", cut.at - start <= 160)
            assertTrue(cut.at > start)
            assertEquals("Cut at ${cut.at} is not in a gap", 0, ink[cut.at])
            assertTrue(cut.betweenWords)
            start = cut.at
        }
        assertTrue(ink.size - start <= 160)
        assertTrue(TextRecognition.cuts(IntArray(160), 160, 6).isEmpty())
    }

    @Test fun linesWithoutGapsAreCutWhereThereIsLeastInk() {
        val ink = IntArray(300) { 10 + it % 7 }
        ink[170] = 2
        val cut = TextRecognition.cuts(ink, maxWidth = 200, spaceWidth = 6).first()
        assertEquals(TextRecognition.Cut(170, false), cut)
        // A narrow gap (between letters) is used, but isn't a space between words.
        ink[150] = 0
        assertEquals(TextRecognition.Cut(150, false), TextRecognition.cuts(ink, 200, 6).first())
    }

    @Test fun aSpaceBetweenWordsCrossingTheEdgeOfWhereACutCanGoIsMeasuredWhole() {
        // Cuts go in columns 100..199. The space, columns 96..107, has only 8 of its 12 there.
        val before = IntArray(300) { if (it in 96..107) 0 else 5 }
        assertEquals(TextRecognition.Cut(103, true), TextRecognition.cuts(before, maxWidth = 200, spaceWidth = 12).first())
        // Columns 192..203: 8 there, 4 after.
        val after = IntArray(300) { if (it in 192..203) 0 else 5 }
        assertEquals(TextRecognition.Cut(195, true), TextRecognition.cuts(after, maxWidth = 200, spaceWidth = 12).first())
    }
}
