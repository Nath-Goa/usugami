package io.github.nath_goa.usugami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolSearchTest {
    /** A card on Home as the search sees it; the texts are like the app's own in each language. */
    private data class Tool(val name: String, val detail: String, val keywords: String)

    private fun find(tools: List<Tool>, query: String): List<String> =
        ToolSearch.search(tools, query, name = { it.name }, moreText = { "${it.detail} ${it.keywords}" }).map { it.name }

    private val english = listOf(
        Tool("Images to PDF", "JPGs and PNGs to PDF, with text recognition", "photo, picture, JPEG, scan, camera, gallery"),
        Tool("Merge PDF", "Combine files and arrange individual pages", "join, combine, append, put together"),
        Tool("Split PDF", "Extract pages or split into smaller files", "separate, cut, divide, extract"),
        Tool("Reorganize PDF", "Move, turn, delete and add pages", "reorder, rearrange, rotate, turn, flip, delete, remove, erase, discard"),
        Tool("Add page numbers", "Number pages at the top or bottom", "numbering, pagination, footer, header"),
        Tool("Compress PDF", "Shrink images, keep text sharp", "shrink, reduce size, smaller, optimize, file size"),
    )

    @Test fun wordsAreFoldedAndSplitAtPunctuation() {
        assertEquals(listOf("shrink", "images", "keep", "text", "sharp"), ToolSearch.words("Shrink images, keep text sharp"))
        assertEquals(listOf("documents", "docx", "converted"), ToolSearch.words("Documents (.docx), converted"))
        assertEquals(listOf("reduire", "l", "image"), ToolSearch.words("Réduire l’image"))
        assertEquals(listOf("e", "book", "ebook"), ToolSearch.words("e-book; EBOOK"))
        assertEquals(listOf("ete", "nino", "francais"), ToolSearch.words("ÉTÉ Niño FRANÇAIS"))
        // Ligatures and full-width letters become plain letters.
        assertEquals(listOf("file", "pdf"), ToolSearch.words("ﬁle ＰＤＦ"))
        assertEquals(emptyList<String>(), ToolSearch.words("  ,.-  "))
    }

    @Test fun devanagariKeepsItsVowelSignsButNotTheNuktaOrCandrabindu() {
        assertEquals(listOf("पेज", "हटाएं"), ToolSearch.words("पेज हटाएं"))
        assertEquals(listOf("मिलाएं"), ToolSearch.words("मिलाएं"))
        assertEquals(ToolSearch.words("फाइल"), ToolSearch.words("फ़ाइल"))
        // The precomposed letter with a nukta too.
        assertEquals(ToolSearch.words("कागज"), ToolSearch.words("क़ागज"))
        assertEquals(ToolSearch.words("मिलाएं"), ToolSearch.words("मिलाएँ"))
        // A zero-width joiner doesn't split a word.
        assertEquals(listOf("क्षमा"), ToolSearch.words("क्‍षमा"))
    }

    @Test fun caseAndAccentsDontMatter() {
        assertTrue(ToolSearch.matches("MERGE", "Merge PDF"))
        assertTrue(ToolSearch.matches("merge", "MERGE PDF"))
        assertTrue(ToolSearch.matches("resume", "Résumé"))
        assertTrue(ToolSearch.matches("présentation", "presentation, slides"))
        assertTrue(ToolSearch.matches("imagenes", "Imágenes a PDF"))
        assertTrue(ToolSearch.matches("PAGINA", "Numerar páginas"))
        assertTrue(ToolSearch.matches("Ôter", "retirer, effacer, ôter"))
    }

    @Test fun eachWordTypedMustBeginAWordInAnyOrder() {
        val compress = "Compress PDF Shrink images, keep text sharp reduce size"
        assertTrue(ToolSearch.matches("reduce size", compress))
        assertTrue(ToolSearch.matches("size reduce", compress))
        assertTrue(ToolSearch.matches("  red   si ", compress))
        assertTrue(ToolSearch.matches("comp", compress))
        assertFalse("Every word must match", ToolSearch.matches("reduce colour", compress))
        assertFalse("Words match from their start", ToolSearch.matches("press", compress))
        assertFalse("Nothing typed finds nothing", ToolSearch.matches("   ", compress))
        assertFalse(ToolSearch.matches(",.;", compress))
    }

    @Test fun searchFindsByNameDescriptionAndKeywords() {
        assertEquals(listOf("Merge PDF"), find(english, "merge"))
        assertEquals(listOf("Merge PDF"), find(english, "join"))
        assertEquals(listOf("Merge PDF"), find(english, "Put Together"))
        assertEquals(listOf("Images to PDF"), find(english, "scan"))
        assertEquals(listOf("Images to PDF"), find(english, "jpg"))
        assertEquals(listOf("Compress PDF"), find(english, "reduce size"))
        // Words for turning and removing pages find Reorganize PDF.
        for (word in listOf("rotate", "turn", "delete", "remove")) assertEquals(word, listOf("Reorganize PDF"), find(english, word))
        assertEquals(emptyList<String>(), find(english, "zebra"))
        assertEquals(emptyList<String>(), find(english, ""))
        assertEquals(emptyList<String>(), find(english, " - "))
    }

    @Test fun toolsFoundByNameComeFirstThenTheRestInOrder() {
        // Compress's description says "Shrink images"; Images to PDF has the word in its name.
        assertEquals(listOf("Images to PDF", "Compress PDF"), find(english, "images"))
        assertEquals(listOf("Add page numbers", "Merge PDF", "Split PDF", "Reorganize PDF"), find(english, "page"))
        assertEquals(listOf("Images to PDF", "Merge PDF", "Split PDF", "Reorganize PDF", "Compress PDF"), find(english, "pdf"))
    }

    @Test fun keywordsInFrench() {
        val french = listOf(
            Tool("Images en PDF", "JPG et PNG en PDF, avec reconnaissance de texte", "photo, scanner, numériser, appareil photo, galerie"),
            Tool("Fusionner des PDF", "Combiner des fichiers et organiser les pages", "assembler, combiner, joindre, réunir, regrouper"),
            Tool("Compresser un PDF", "Réduire les images, garder le texte net", "alléger, réduire, taille, plus petit, poids"),
        )
        assertEquals(listOf("Fusionner des PDF"), find(french, "assembler"))
        assertEquals(listOf("Fusionner des PDF"), find(french, "REUNIR"))
        assertEquals(listOf("Images en PDF"), find(french, "numeriser"))
        assertEquals(listOf("Compresser un PDF"), find(french, "alleger"))
        assertEquals(listOf("Compresser un PDF"), find(french, "plus petit"))
        assertEquals(listOf("Compresser un PDF"), find(french, "réduire taille"))
        // Every word typed must match, and "la" begins none of this tool's words.
        assertEquals(emptyList<String>(), find(french, "réduire la taille"))
    }

    @Test fun keywordsInSpanish() {
        val spanish = listOf(
            Tool("Imágenes a PDF", "JPG y PNG a PDF, con reconocimiento de texto", "foto, escanear, escáner, cámara, galería"),
            Tool("Firmar PDF", "Dibuja tu firma y colócala", "firma, rúbrica, rubricar, iniciales"),
            Tool("Comprimir PDF", "Reduce las imágenes y mantén el texto nítido", "reducir, achicar, tamaño, más pequeño, peso"),
        )
        assertEquals(listOf("Firmar PDF"), find(spanish, "rubrica"))
        assertEquals(listOf("Imágenes a PDF"), find(spanish, "camara"))
        assertEquals(listOf("Comprimir PDF"), find(spanish, "tamano"))
        assertEquals(listOf("Comprimir PDF"), find(spanish, "mas pequeno"))
        assertEquals(listOf("Imágenes a PDF", "Comprimir PDF"), find(spanish, "imagenes"))
    }

    @Test fun keywordsInGerman() {
        val german = listOf(
            Tool("Bilder in PDF", "Kamerascans und Bilder in PDF, mit Texterkennung", "Foto, Bild, scannen, Scanner, Kamera, Galerie"),
            Tool("PDF zusammenfügen", "Dateien kombinieren und einzelne Seiten anordnen", "verbinden, zusammenführen, zusammenlegen, kombinieren, anhängen, merge"),
            Tool("PDF komprimieren", "Bilder verkleinern, Text bleibt scharf", "verkleinern, kleiner, Größe reduzieren, Dateigröße, schrumpfen"),
        )
        assertEquals(listOf("PDF zusammenfügen"), find(german, "zusammen"))
        assertEquals(listOf("PDF zusammenfügen"), find(german, "MERGE"))
        assertEquals(listOf("PDF zusammenfügen"), find(german, "zusammenfugen"))
        assertEquals(listOf("PDF komprimieren"), find(german, "KLEINER"))
        assertEquals(listOf("Bilder in PDF"), find(german, "kamera"))
        assertEquals(listOf("Bilder in PDF", "PDF komprimieren"), find(german, "bilder"))
    }

    @Test fun keywordsInPortuguese() {
        val portuguese = listOf(
            Tool("Censurar PDF", "Cubra textos e imagens com tarja preta para sempre", "tarja, tarjar, ocultar, esconder, censurar, confidencial"),
            Tool("Juntar PDF", "Combine arquivos e organize páginas individuais", "juntar, unir, combinar, mesclar, agrupar, anexar"),
            Tool("Compactar PDF", "Reduza as imagens e mantenha o texto nítido", "comprimir, reduzir tamanho, diminuir, menor, mais leve, peso"),
        )
        assertEquals(listOf("Censurar PDF"), find(portuguese, "tarja"))
        assertEquals(listOf("Juntar PDF"), find(portuguese, "MESCLAR"))
        assertEquals(listOf("Juntar PDF"), find(portuguese, "paginas"))
        assertEquals(listOf("Compactar PDF"), find(portuguese, "reduzir tamanho"))
        assertEquals(listOf("Compactar PDF"), find(portuguese, "mais leve"))
        assertEquals(listOf("Censurar PDF", "Compactar PDF"), find(portuguese, "imagens"))
    }

    @Test fun keywordsInIndonesian() {
        val indonesian = listOf(
            Tool("Gambar ke PDF", "Pindaian kamera dan gambar ke PDF, dengan pengenalan teks", "foto, gambar, pindai, scan, pemindai, kamera, galeri"),
            Tool("Gabungkan PDF", "Satukan file dan atur tiap halaman", "gabung, gabungkan, satukan, jadikan satu, sambungkan, merge"),
            Tool("Kompres PDF", "Perkecil gambar, teks tetap tajam", "perkecil, kecilkan, kurangi ukuran, ukuran file, optimalkan, ringankan"),
        )
        assertEquals(listOf("Gabungkan PDF"), find(indonesian, "gabung"))
        assertEquals(listOf("Gabungkan PDF"), find(indonesian, "jadikan satu"))
        assertEquals(listOf("Kompres PDF"), find(indonesian, "kecil"))
        assertEquals(listOf("Kompres PDF"), find(indonesian, "KURANGI UKURAN"))
        assertEquals(listOf("Gambar ke PDF"), find(indonesian, "pindai"))
        assertEquals(listOf("Gambar ke PDF", "Kompres PDF"), find(indonesian, "gambar"))
    }

    /**
     * Home adds words for working on several files at once to the hidden words of the tools that do,
     * such as Compress PDF, and not to the others, such as Merge PDF, which makes one PDF of several.
     */
    @Test fun wordsForSeveralFilesFindTheToolsThatTakeThemInEachLanguage() {
        fun tools(merge: Tool, compress: Tool, batch: String) = listOf(merge, compress.copy(keywords = "${compress.keywords} $batch"))
        val english = tools(
            Tool("Merge PDF", "Combine files and arrange individual pages", "join, combine, append, put together"),
            Tool("Compress PDF", "Shrink images, keep text sharp", "shrink, reduce size, smaller, optimize, file size, email"),
            "batch, many, several, multiple, bulk, at once, all at once",
        )
        val french = tools(
            Tool("Fusionner des PDF", "Combiner des fichiers et organiser les pages", "assembler, combiner, joindre, réunir, regrouper, fusion"),
            Tool("Compresser un PDF", "Réduire les images, garder le texte net", "réduire, alléger, taille, plus petit, optimiser, poids, e-mail"),
            "lot, lots, par lots, plusieurs, beaucoup, nombreux, multiples, en masse, à la fois, tous ensemble",
        )
        val spanish = tools(
            Tool("Unir PDF", "Combina archivos y ordena páginas individuales", "juntar, combinar, fusionar, unir, agrupar, anexar"),
            Tool("Comprimir PDF", "Reduce las imágenes y mantén el texto nítido", "reducir, achicar, tamaño, más pequeño, optimizar, peso, correo"),
            "lote, lotes, por lotes, muchos, varios, múltiples, en bloque, a la vez, de una vez, todos juntos",
        )
        val hindi = tools(
            Tool("PDF मर्ज करें", "फ़ाइलें मिलाएं और अलग-अलग पेज क्रम में लगाएं", "जोड़ें, मिलाएं, एक फ़ाइल, merge, combine, join"),
            Tool("PDF कंप्रेस करें", "इमेज छोटी करें, टेक्स्ट साफ़ रखें", "छोटा करें, साइज़ घटाएं, कम करें, compress, shrink, reduce size"),
            "एक साथ, कई, कई सारी, बहुत सारी, सभी, बैच, batch, many, several, multiple, bulk",
        )
        val german = tools(
            Tool("PDF zusammenfügen", "Dateien kombinieren und einzelne Seiten anordnen", "verbinden, zusammenführen, zusammenlegen, kombinieren, anhängen, merge"),
            Tool("PDF komprimieren", "Bilder verkleinern, Text bleibt scharf", "verkleinern, kleiner, Größe reduzieren, Dateigröße, E-Mail"),
            "Stapel, Stapelverarbeitung, Batch, mehrere, viele, auf einmal, alle auf einmal, gleichzeitig",
        )
        val portuguese = tools(
            Tool("Juntar PDF", "Combine arquivos e organize páginas individuais", "juntar, unir, combinar, mesclar, agrupar, anexar"),
            Tool("Compactar PDF", "Reduza as imagens e mantenha o texto nítido", "comprimir, reduzir tamanho, diminuir, menor, peso, e-mail"),
            "lote, lotes, em lote, vários, várias, muitos, muitas, múltiplos, múltiplas, em massa, de uma vez, todos de uma vez, ao mesmo tempo",
        )
        val indonesian = tools(
            Tool("Gabungkan PDF", "Satukan file dan atur tiap halaman", "gabung, gabungkan, satukan, jadikan satu, sambungkan, merge"),
            Tool("Kompres PDF", "Perkecil gambar, teks tetap tajam", "perkecil, kecilkan, kurangi ukuran, ukuran file, optimalkan, email"),
            "batch, banyak, beberapa, sekaligus, massal, semua sekaligus, bersamaan, serentak, bulk",
        )
        for ((tools, queries) in listOf(
            english to listOf("batch", "Many", "several", "multiple", "all at once"),
            french to listOf("lot", "par lots", "plusieurs", "beaucoup", "a la fois", "nombreux"),
            spanish to listOf("lote", "muchos", "varios", "multiples", "a la vez", "de una vez"),
            hindi to listOf("कई", "एक साथ", "बहुत सारी", "बैच", "batch", "several"),
            german to listOf("Stapel", "mehrere", "viele", "auf einmal", "gleichzeitig", "batch"),
            portuguese to listOf("lote", "em lote", "varios", "muitas", "multiplos", "de uma vez", "ao mesmo tempo"),
            indonesian to listOf("banyak", "beberapa", "sekaligus", "massal", "bersamaan", "batch"),
        )) {
            for (query in queries) assertEquals("\"$query\"", listOf(tools[1].name), find(tools, query))
        }
    }

    @Test fun keywordsInHindiInDevanagariAndLatinLetters() {
        val hindi = listOf(
            Tool("PDF मर्ज करें", "फ़ाइलें मिलाएं और अलग-अलग पेज क्रम में लगाएं", "जोड़ें, मिलाएँ, एक फ़ाइल, merge, combine, join"),
            Tool("पेज हटाएं", "जिन पेजों की ज़रूरत नहीं, उन्हें हटाएं", "मिटाएं, निकालें, delete, remove"),
            Tool("PDF कंप्रेस करें", "इमेज छोटी करें, टेक्स्ट साफ़ रखें", "छोटा करें, साइज़ घटाएं, compress, shrink, reduce size"),
        )
        assertEquals(listOf("PDF मर्ज करें"), find(hindi, "जोड़ें"))
        assertEquals(listOf("PDF मर्ज करें"), find(hindi, "जोडें"))
        assertEquals(listOf("PDF मर्ज करें"), find(hindi, "मिलाए"))
        assertEquals(listOf("PDF मर्ज करें"), find(hindi, "MERGE"))
        assertEquals(listOf("पेज हटाएं", "PDF मर्ज करें"), find(hindi, "पेज"))
        assertEquals(listOf("PDF कंप्रेस करें"), find(hindi, "साइज घटा"))
        assertEquals(listOf("PDF कंप्रेस करें"), find(hindi, "shrink"))
        // A vowel sign is part of the word: पेज doesn't begin पज.
        assertEquals(emptyList<String>(), find(hindi, "पज"))
    }
}
