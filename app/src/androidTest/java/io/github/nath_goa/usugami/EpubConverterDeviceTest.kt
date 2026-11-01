package io.github.nath_goa.usugami

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import io.github.nath_goa.usugami.core.PageSize
import io.github.nath_goa.usugami.data.PdfToolsEngine
import io.github.nath_goa.usugami.data.office.EpubProtected
import io.github.nath_goa.usugami.data.office.OfficeConverter
import io.github.nath_goa.usugami.data.office.OfficeInput
import io.github.nath_goa.usugami.data.office.OfficeKind
import io.github.nath_goa.usugami.data.office.OfficeNotRecognized
import io.github.nath_goa.usugami.data.office.OfficeNothingToConvert
import io.github.nath_goa.usugami.data.office.OfficeProgress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs

/**
 * EPUB e-books converted on the phone, checked by reading the PDFs back: page sizes, chapters in
 * reading order on new pages, text, headings, pictures and the cover, bookmarks from the table of
 * contents, fixed-layout books, books with DRM and other files refused, and cancelling. The books
 * are made up here, as small EPUB 2 and EPUB 3 files.
 */
class EpubConverterDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val engine = PdfToolsEngine(context)
    private val converter = OfficeConverter(context, engine)
    private val results = File(context.getExternalFilesDir(null), "test-results").apply { mkdirs() }
    private val work = File(context.cacheDir, "epub-test").apply { mkdirs() }
    private var nextId = 5001L

    @After fun tearDown() {
        work.deleteRecursively()
    }

    private fun import(file: File): OfficeInput = converter.import(Uri.fromFile(file), nextId++, OfficeKind.EPUB)

    /** Converts [file], keeping a copy of the PDF in test-results for a look at it. */
    private fun convert(file: File, name: String, page: PageSize = PageSize.A5, progress: (OfficeProgress) -> Unit = {}): File {
        val input = import(file)
        val pdf = converter.convert(input, name, page, {}, progress).file
        converter.delete(input)
        return pdf.copyTo(File(results, "$name.pdf"), overwrite = true)
    }

    private fun pageTexts(file: File): List<String> = PDDocument.load(file).use { doc ->
        (1..doc.numberOfPages).map { n -> PDFTextStripper().apply { startPage = n; endPage = n }.getText(doc).replace(Regex("""\s+"""), " ").trim() }
    }

    private fun pageWith(texts: List<String>, text: String): Int = texts.indexOfFirst { text in it }.also { assertTrue("\"$text\" is in the PDF", it >= 0) }

    /** The first letter of [word] on page [number] (1-based). */
    private fun word(file: File, number: Int, word: String): TextPosition = PDDocument.load(file).use { doc ->
        val all = ArrayList<TextPosition>()
        object : PDFTextStripper() {
            override fun processTextPosition(text: TextPosition) {
                all += text
            }
        }.apply { startPage = number; endPage = number }.getText(doc)
        val starts = IntArray(all.size)
        val text = StringBuilder()
        all.forEachIndexed { i, glyph ->
            starts[i] = text.length
            text.append(glyph.unicode)
        }
        val at = text.indexOf(word)
        assertTrue("\"$word\" is on page $number", at >= 0)
        all[starts.indexOfFirst { it >= at }]
    }

    private fun near(expected: Float, actual: Float, tolerance: Float = 1.5f) =
        assertTrue("expected $expected, got $actual", abs(expected - actual) <= tolerance)

    // ---- Writing small books ----

    private fun epub(name: String, files: Map<String, Any>, mimetype: Boolean = true): File = File(work, name).also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            if (mimetype) {
                // Stored first and uncompressed, as EPUB asks.
                val bytes = "application/epub+zip".toByteArray()
                zip.putNextEntry(
                    ZipEntry("mimetype").apply {
                        method = ZipEntry.STORED
                        size = bytes.size.toLong()
                        compressedSize = bytes.size.toLong()
                        crc = CRC32().apply { update(bytes) }.value
                    },
                )
                zip.write(bytes)
                zip.closeEntry()
            }
            for ((path, content) in files) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(if (content is ByteArray) content else (content as String).toByteArray())
                zip.closeEntry()
            }
        }
    }

    private fun container(opf: String = "OEBPS/content.opf") =
        """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">""" +
            """<rootfiles><rootfile full-path="$opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""

    private fun xhtml(body: String, head: String = "", doctype: String = XHTML11) =
        """<?xml version="1.0" encoding="utf-8"?>$doctype<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">""" +
            """<head><title>Part</title>$head</head><body>$body</body></html>"""

    private fun png(width: Int, height: Int, color: Int = Color.rgb(70, 130, 180)): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(color)
            drawCircle(width * 0.6f, height * 0.4f, minOf(width, height) * 0.25f, Paint().apply { this.color = Color.YELLOW })
        }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray().also { bitmap.recycle() }
    }

    private fun paragraphs(count: Int, word: String) = (1..count).joinToString("") { i ->
        "<p>$word paragraph $i goes on for a while, so that the chapter needs more than one page of text to hold it all.</p>"
    }

    /** An EPUB 2 book with an NCX table of contents, a style sheet and three chapters, listed out of order in the manifest. */
    private fun epub2(name: String = "orchard.epub", extraChapter3: String = ""): File = epub(
        name,
        mapOf(
            "META-INF/container.xml" to container(),
            "OEBPS/content.opf" to """<?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>The Quiet Orchard</dc:title>
                <dc:creator>A. Made-Up Writer</dc:creator><dc:language>en</dc:language><dc:identifier id="id">urn:uuid:0001</dc:identifier></metadata>
                <manifest>
                <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                <item id="css" href="styles/book.css" media-type="text/css"/>
                <item id="c3" href="text/chapter3.xhtml" media-type="application/xhtml+xml"/>
                <item id="c1" href="text/chapter1.xhtml" media-type="application/xhtml+xml"/>
                <item id="c2" href="text/chapter%202.xhtml" media-type="application/xhtml+xml"/>
                </manifest>
                <spine toc="ncx"><itemref idref="c1"/><itemref idref="c2"/><itemref idref="c3"/></spine>
                </package>""",
            "OEBPS/styles/book.css" to "h1 { text-align: center } .secret { display: none } p.newpage { page-break-before: always } p { margin: 0 0 0.4em }",
            "OEBPS/text/chapter1.xhtml" to xhtml(
                """<h1 id="start">Chapter One: Apples</h1>
                <p>The caf&eacute; opened at dawn&nbsp;&mdash; nobody came.</p>
                <p class="secret">This sentence is hidden.</p>
                <script type="text/javascript">var note = "script text";</script>
                <ul><li>First apple</li><li>Second apple</li></ul>
                <ol><li>Step one</li><li>Step two</li></ol>
                <blockquote><p>A quoted line.</p></blockquote>
                <h2 id="later">Later that day</h2>
                <p>Line one<br/>Line two, with <b>bold</b>, <i>italic</i> and <u>underlined</u> words.</p>
                <table><tr><th>Fruit</th><th>Count</th></tr><tr><td>Plums</td><td>12</td></tr></table>""",
                head = """<link rel="stylesheet" type="text/css" href="../styles/book.css"/>""",
            ),
            "OEBPS/text/chapter 2.xhtml" to xhtml(
                """<h1>Chapter Two: Plums</h1>${paragraphs(40, "Plum")}<h2 id="deep">Deep section</h2><p>The end of chapter two.</p>""",
                head = """<link rel="stylesheet" type="text/css" href="../styles/book.css"/>""",
            ),
            "OEBPS/text/chapter3.xhtml" to xhtml(
                """<h1>Chapter Three: Pears</h1><p>Before the break.</p><p class="newpage">After a break.</p>$extraChapter3""",
                head = """<link rel="stylesheet" type="text/css" href="../styles/book.css"/>""",
            ),
            "OEBPS/toc.ncx" to """<?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN" "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd">
                <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><head/><docTitle><text>The Quiet Orchard</text></docTitle>
                <navMap>
                <navPoint id="n1" playOrder="1"><navLabel><text>Chapter One</text></navLabel><content src="text/chapter1.xhtml"/>
                  <navPoint id="n1b" playOrder="2"><navLabel><text>Later that day</text></navLabel><content src="text/chapter1.xhtml#later"/></navPoint>
                </navPoint>
                <navPoint id="n2" playOrder="3"><navLabel><text>Chapter Two</text></navLabel><content src="text/chapter%202.xhtml"/>
                  <navPoint id="n2b" playOrder="4"><navLabel><text>Deep section</text></navLabel><content src="text/chapter%202.xhtml#deep"/></navPoint>
                </navPoint>
                <navPoint id="n3" playOrder="5"><navLabel><text>Chapter Three</text></navLabel><content src="text/chapter3.xhtml"/></navPoint>
                </navMap></ncx>""",
        ),
    )

    /** An EPUB 3 book with a navigation document, a cover image and a chapter with a picture. */
    private fun epub3(name: String = "garden.epub", coverPage: Boolean = true): File {
        val manifest = StringBuilder(
            """<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
            <item id="cover-image" href="images/cover.png" media-type="image/png" properties="cover-image"/>
            <item id="pic" href="images/garden.png" media-type="image/png"/>
            <item id="drawing" href="images/drawing.svg" media-type="image/svg+xml"/>
            <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>""",
        )
        if (coverPage) manifest.append("""<item id="cover" href="cover.xhtml" media-type="application/xhtml+xml"/>""")
        val spine = (if (coverPage) """<itemref idref="cover"/>""" else "") + """<itemref idref="ch1"/>"""
        val files = linkedMapOf<String, Any>(
            "META-INF/container.xml" to container("content.opf"),
            "content.opf" to """<?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>A Small Garden</dc:title><dc:language>en</dc:language>
                <dc:identifier id="id">urn:uuid:0002</dc:identifier><meta property="dcterms:modified">2026-01-01T00:00:00Z</meta></metadata>
                <manifest>$manifest</manifest><spine>$spine</spine></package>""",
            "nav.xhtml" to xhtml(
                """<nav epub:type="toc" id="toc"><h1>Contents</h1><ol>
                <li><a href="cover.xhtml">Cover</a></li>
                <li><a href="ch1.xhtml">Hello garden</a><ol><li><a href="ch1.xhtml#beds">The beds</a></li></ol></li>
                </ol></nav>
                <nav epub:type="landmarks" hidden=""><ol><li><a epub:type="bodymatter" href="ch1.xhtml">Start here</a></li></ol></nav>""",
                doctype = "<!DOCTYPE html>",
            ),
            "images/cover.png" to png(600, 900, Color.rgb(120, 60, 140)),
            "images/garden.png" to png(320, 200),
            "images/drawing.svg" to """<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"><rect width="10" height="10"/></svg>""",
            "ch1.xhtml" to xhtml(
                """<section epub:type="chapter"><h1>Hello garden</h1><p>Some words before the picture.</p>
                <div class="figure"><img src="images/garden.png" alt="A garden"/></div>
                <p>Words after it, and a drawing Android can't show: <img src="images/drawing.svg" alt="A tiny drawing"/></p>
                <h2 id="beds">The beds</h2><p>Carrots and beans.</p></section>""",
                doctype = "<!DOCTYPE html>",
            ),
        )
        if (coverPage) {
            files["cover.xhtml"] = xhtml(
                """<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" version="1.1" viewBox="0 0 600 900" width="100%" height="100%">
                <image width="600" height="900" xlink:href="images/cover.png"/></svg>""",
                doctype = "<!DOCTYPE html>",
            )
        }
        return epub(name, files)
    }

    // ---- What converts ----

    @Test fun pagesAreA5UnlessA4IsChosen() {
        PDDocument.load(convert(epub2(), "orchard-a5")).use { doc ->
            val box = doc.getPage(0).mediaBox
            near(420f, box.width)
            near(595f, box.height)
        }
        PDDocument.load(convert(epub2("orchard4.epub"), "orchard-a4", PageSize.A4)).use { doc ->
            val box = doc.getPage(0).mediaBox
            near(595f, box.width)
            near(842f, box.height)
        }
    }

    @Test fun chaptersFollowTheReadingOrderEachOnANewPage() {
        val texts = pageTexts(convert(epub2(), "orchard-order"))
        val one = pageWith(texts, "Chapter One")
        val two = pageWith(texts, "Chapter Two")
        val three = pageWith(texts, "Chapter Three")
        // The spine's order, not the manifest's.
        assertTrue("$one < $two < $three", one < two && two < three)
        // Each starts at the top of a page; chapter two takes more than one.
        assertTrue(texts[two].startsWith("Chapter Two"))
        assertTrue(texts[three].startsWith("Chapter Three"))
        assertTrue("Chapter two runs over pages", three - two >= 2)
        // The style sheet's page break.
        assertTrue(texts[pageWith(texts, "After a break")].startsWith("After a break"))
        assertTrue(pageWith(texts, "Before the break") < pageWith(texts, "After a break"))
    }

    @Test fun textListsAndTablesAreKeptAndHiddenPartsLeftOut() {
        val all = pageTexts(convert(epub2(), "orchard-text")).joinToString(" ")
        // HTML's named characters, which XML doesn't know.
        assertTrue(all, "café opened at dawn\u00A0— nobody came" in all || "café opened at dawn — nobody came" in all)
        for (text in listOf("• First apple", "• Second apple", "1. Step one", "2. Step two", "A quoted line.", "Line one Line two", "Fruit Count", "Plums 12")) {
            assertTrue("\"$text\" in: $all", text in all)
        }
        assertFalse("display:none is left out", "hidden" in all)
        assertFalse("scripts are left out", "script text" in all)
    }

    @Test fun headingsAreBiggerAndBold() {
        val pdf = convert(epub2(), "orchard-headings")
        val heading = word(pdf, 1, "Chapter")
        val body = word(pdf, 1, "nobody")
        val subheading = word(pdf, 1, "Later")
        assertTrue("${heading.yScale} > ${subheading.yScale} > ${body.yScale}", heading.yScale > subheading.yScale + 2f && subheading.yScale > body.yScale + 1f)
        assertTrue("Heading font ${heading.font.name}", heading.font.name.orEmpty().contains("Bold"))
        assertFalse("Body font ${body.font.name}", body.font.name.orEmpty().contains("Bold"))
        // Centred by the style sheet: about as far from the right edge as from the left.
        PDDocument.load(pdf).use { doc ->
            val width = doc.getPage(0).mediaBox.width
            val texts = PDFTextStripper().apply { startPage = 1; endPage = 1 }.getText(doc)
            assertTrue(texts.contains("Chapter One: Apples"))
            assertTrue("The heading starts right of the margin", heading.x > 44f + 20f && heading.x < width / 2)
        }
    }

    @Test fun bookmarksComeFromTheTableOfContents() {
        val pdf = convert(epub2(), "orchard-bookmarks")
        val texts = pageTexts(pdf)
        PDDocument.load(pdf).use { doc ->
            val outline = doc.documentCatalog.documentOutline
            assertNotNull("The PDF has bookmarks", outline)
            val top = outline.children().toList()
            assertEquals(listOf("Chapter One", "Chapter Two", "Chapter Three"), top.map { it.title })
            fun page(item: PDOutlineItem) = (item.destination as PDPageDestination).retrievePageNumber()
            assertEquals(pageWith(texts, "Chapter One"), page(top[0]))
            assertEquals(pageWith(texts, "Chapter Two"), page(top[1]))
            assertEquals(pageWith(texts, "Chapter Three"), page(top[2]))
            assertEquals(listOf("Later that day"), top[0].children().map { it.title })
            // A place further into a chapter: the page it is on, not the chapter's first.
            val deep = top[1].children().single()
            assertEquals("Deep section", deep.title)
            assertEquals(pageWith(texts, "Deep section"), page(deep))
            assertTrue(page(deep) > page(top[1]))
            // The book's title, author and language go into the PDF.
            assertEquals("The Quiet Orchard", doc.documentInformation.title)
            assertEquals("A. Made-Up Writer", doc.documentInformation.author)
            assertEquals("en", doc.documentCatalog.language)
        }
    }

    @Test fun onlyTheGlyphsDrawnAreEmbedded() {
        // Android's PDF writer embeds the phone's serif font whole, about 140 KB for each of the
        // three styles used here; the book draws a few dozen glyphs of each.
        val pdf = convert(epub2(), "orchard-small")
        assertTrue("${pdf.length()} bytes", pdf.length() < 120_000)
        assertTrue(pageTexts(pdf).joinToString(" ").contains("Chapter Three: Pears"))
    }

    @Test fun theCoverComesFirstAndPicturesArePlaced() {
        val pdf = convert(epub3(), "garden")
        val texts = pageTexts(pdf)
        PDDocument.load(pdf).use { doc ->
            assertEquals(2, doc.numberOfPages)
            // The cover fills the page's height, centred.
            val cover = imagesOn(doc.getPage(0)).single()
            val bottom = cover.corner(0f, 0f)
            val topRight = cover.corner(1f, 1f)
            near(595f, topRight.y - bottom.y, 2f)
            near(420f - topRight.x, bottom.x, 2f)
            assertEquals("", texts[0])
            assertEquals(1, imagesOn(doc.getPage(1)).size)
            // The SVG drawing can't be shown: its description stands in.
            assertTrue(texts[1], "A tiny drawing" in texts[1])
            // Bookmarks from the EPUB 3 navigation document: the hidden landmarks aren't.
            val top = doc.documentCatalog.documentOutline.children().toList()
            assertEquals(listOf("Cover", "Hello garden"), top.map { it.title })
            assertEquals(0, (top[0].destination as PDPageDestination).retrievePageNumber())
            assertEquals(1, (top[1].destination as PDPageDestination).retrievePageNumber())
            assertEquals(listOf("The beds"), top[1].children().map { it.title })
        }
    }

    @Test fun aCoverTheBookDoesntShowIsAddedFirst() {
        val pdf = convert(epub3("no-cover-page.epub", coverPage = false), "garden-cover")
        PDDocument.load(pdf).use { doc ->
            assertEquals(2, doc.numberOfPages)
            val cover = imagesOn(doc.getPage(0)).single()
            near(595f, cover.corner(1f, 1f).y - cover.corner(0f, 0f).y, 2f)
            assertTrue(PDFTextStripper().apply { startPage = 2; endPage = 2 }.getText(doc).contains("Hello garden"))
        }
    }

    @Test fun fixedLayoutBooksGetAPagePerDocument() {
        val page = { n: Int -> xhtml("""<div><img src="p$n.png" alt="Page $n"/></div>""", head = """<meta name="viewport" content="width=800, height=600"/>""", doctype = "<!DOCTYPE html>") }
        val file = epub(
            "comic.epub",
            mapOf(
                "META-INF/container.xml" to container("book.opf"),
                "book.opf" to """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
                    <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Comic</dc:title><dc:identifier id="id">x</dc:identifier>
                    <meta property="rendition:layout">pre-paginated</meta></metadata>
                    <manifest><item id="i1" href="p1.png" media-type="image/png" properties="cover-image"/><item id="i2" href="p2.png" media-type="image/png"/>
                    <item id="d1" href="p1.xhtml" media-type="application/xhtml+xml"/><item id="d2" href="p2.xhtml" media-type="application/xhtml+xml"/></manifest>
                    <spine><itemref idref="d1"/><itemref idref="d2"/></spine></package>""",
                "p1.png" to png(800, 600),
                "p2.png" to png(800, 600, Color.rgb(200, 90, 40)),
                "p1.xhtml" to page(1),
                "p2.xhtml" to page(2),
            ),
        )
        PDDocument.load(convert(file, "comic")).use { doc ->
            // The cover is the first page itself, so it isn't added again.
            assertEquals(2, doc.numberOfPages)
            for (i in 0 until 2) {
                val box = doc.getPage(i).mediaBox
                assertTrue("Landscape, as the viewport", box.width > box.height)
                // 4:3 is a little narrower than A5 sideways: the picture fills the height, centred.
                val image = imagesOn(doc.getPage(i)).single()
                near(box.height, image.corner(0f, 1f).y - image.corner(0f, 0f).y, 2f)
                near(box.width - image.corner(1f, 0f).x, image.corner(0f, 0f).x, 2f)
            }
        }
    }

    @Test fun malformedDocumentsDontStopTheBook() {
        val broken = """<?xml version="1.0"?><html><body><p>Unclosed <b>bold<br> text &unknown; Tom & Jerry<p>Second paragraph</body>"""
        val file = epub2("broken.epub", extraChapter3 = "").let { plain ->
            val parts = linkedMapOf<String, Any>()
            java.util.zip.ZipFile(plain).use { zip -> zip.entries().asSequence().filter { it.name != "mimetype" }.forEach { parts[it.name] = zip.getInputStream(it).readBytes() } }
            parts["OEBPS/text/chapter3.xhtml"] = broken
            epub("broken2.epub", parts)
        }
        val all = pageTexts(convert(file, "broken")).joinToString(" ")
        assertTrue(all, "Unclosed" in all && "Tom" in all)
        assertTrue("The chapters before are all there", "Chapter Two" in all)
    }

    @Test fun progressCountsChapters() {
        val seen = ArrayList<OfficeProgress>()
        convert(epub2(), "orchard-progress") { seen += it }
        val chapters = seen.filterNot { it.compressing }
        assertEquals(listOf(1, 2, 3), chapters.map { it.done })
        assertTrue(chapters.all { it.total == 3 })
        assertTrue(seen.last().compressing)
    }

    @Test fun longBooksAreSplitIntoPartsAndKeepTheirBookmarks() {
        // More than 400 pages, so the pages are written in two parts and joined. No table of
        // contents: the chapters' headings become the bookmarks.
        val count = 405
        val files = linkedMapOf<String, Any>("META-INF/container.xml" to container("long.opf"))
        val manifest = StringBuilder()
        val spine = StringBuilder()
        for (i in 1..count) {
            files["c$i.xhtml"] = xhtml("<h1>Part $i</h1><p>Text of part $i.</p>")
            manifest.append("""<item id="c$i" href="c$i.xhtml" media-type="application/xhtml+xml"/>""")
            spine.append("""<itemref idref="c$i"/>""")
        }
        files["long.opf"] = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id">
            <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Long</dc:title><dc:identifier id="id">x</dc:identifier></metadata>
            <manifest>$manifest</manifest><spine>$spine</spine></package>"""
        var last: OfficeProgress? = null
        val pdf = convert(epub("long.epub", files), "long") { if (!it.compressing) last = it }
        assertEquals(count, last?.done)
        PDDocument.load(pdf).use { doc ->
            assertEquals(count, doc.numberOfPages)
            val bookmarks = doc.documentCatalog.documentOutline.children().toList()
            assertEquals(count, bookmarks.size)
            assertEquals("Part 402", bookmarks[401].title)
            assertEquals(401, (bookmarks[401].destination as PDPageDestination).retrievePageNumber())
            assertTrue(PDFTextStripper().apply { startPage = 402; endPage = 402 }.getText(doc).contains("Text of part 402"))
            // Both parts embedded the same fonts: each font's two copies became one smaller file.
            fun fontFiles(index: Int) = doc.getPage(index).resources.let { resources ->
                resources.fontNames.mapNotNull { name ->
                    val font = resources.getFont(name) as? PDType0Font ?: return@mapNotNull null
                    font.descendantFont.fontDescriptor?.fontFile2?.cosObject?.let { font.name.substringAfter('+') to it }
                }.toMap()
            }
            val first = fontFiles(0)
            val last = fontFiles(401)
            assertTrue("$first and $last", first.keys.any { it in last })
            for ((name, file) in first) last[name]?.let { assertTrue(name, it === file) }
        }
    }

    // ---- Files that aren't converted ----

    private fun refused(file: File): Exception {
        val id = nextId
        val problem = try {
            import(file)
            null
        } catch (e: Exception) {
            e
        }
        assertNotNull("${file.name} was accepted", problem)
        assertFalse("The copy doesn't stay in the cache", File(context.cacheDir, "office-inputs/$id.epub").exists())
        return problem!!
    }

    /** The orchard book with [extra] files added (or replaced). */
    private fun orchardWith(name: String, extra: Map<String, Any>): File {
        val parts = linkedMapOf<String, Any>()
        java.util.zip.ZipFile(epub2("$name-plain.epub")).use { zip ->
            zip.entries().asSequence().filter { it.name != "mimetype" }.forEach { parts[it.name] = zip.getInputStream(it).readBytes() }
        }
        parts.putAll(extra)
        return epub(name, parts)
    }

    private fun encryption(algorithm: String) =
        """<?xml version="1.0"?><encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container" xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
            <enc:EncryptedData><enc:EncryptionMethod Algorithm="$algorithm"/><enc:CipherData><enc:CipherReference URI="OEBPS/text/chapter1.xhtml"/></enc:CipherData></enc:EncryptedData>
            </encryption>"""

    @Test fun booksWithDrmAreRefused() {
        assertTrue(refused(orchardWith("adept.epub", mapOf("META-INF/encryption.xml" to encryption("http://www.w3.org/2001/04/xmlenc#aes128-cbc")))) is EpubProtected)
        assertTrue(refused(orchardWith("rights.epub", mapOf("META-INF/rights.xml" to "<rights/>"))) is EpubProtected)
        // Fonts scrambled so they can't be copied out of the book: not DRM, and Usugami doesn't use them anyway.
        val fonts = orchardWith("fonts.epub", mapOf("META-INF/encryption.xml" to encryption("http://www.idpf.org/2008/embedding")))
        assertTrue(pageTexts(convert(fonts, "fonts")).joinToString(" ").contains("Chapter One"))
    }

    @Test fun otherFilesAreNotTakenForBooks() {
        assertTrue(refused(File(work, "notes.epub").apply { writeText("Just some text, not a book.") }) is OfficeNotRecognized)
        // A Word document picked as a book, and a zip with no package in it.
        val word = epub("report.epub", mapOf("[Content_Types].xml" to "<Types/>", "word/document.xml" to "<w:document/>"), mimetype = false)
        assertTrue(refused(word) is OfficeNotRecognized)
        assertTrue(refused(File(work, "tiny.epub").apply { writeBytes(ByteArray(3)) }) is OfficeNotRecognized)
        // A package with nothing in its reading order.
        val empty = epub(
            "empty.epub",
            mapOf(
                "META-INF/container.xml" to container("e.opf"),
                "e.opf" to """<package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata/><manifest><item id="x" href="x.xhtml" media-type="application/xhtml+xml"/></manifest><spine/></package>""",
            ),
        )
        assertTrue(refused(empty) is OfficeNothingToConvert)
    }

    @Test fun aBookWithoutAContainerFileStillOpens() {
        val parts = linkedMapOf<String, Any>()
        java.util.zip.ZipFile(epub2("orphan-plain.epub")).use { zip ->
            zip.entries().asSequence().filter { it.name != "mimetype" && it.name != "META-INF/container.xml" }.forEach { parts[it.name] = zip.getInputStream(it).readBytes() }
        }
        assertTrue(pageTexts(convert(epub("orphan.epub", parts), "orphan")).joinToString(" ").contains("Chapter Three"))
    }

    @Test fun cancellingLeavesNothingBehind() {
        val input = import(epub2())
        var cancelled = false
        try {
            converter.convert(input, "cancelled", PageSize.A5, { if (cancelled) throw CancellationException("Cancelled") }, { if (it.done >= 2) cancelled = true })
            fail("Not cancelled")
        } catch (_: CancellationException) {
        }
        assertFalse(File(context.cacheDir, "output/tools/cancelled.pdf").exists())
        assertFalse("Working files removed", File(context.cacheDir, "office-work").exists())
        assertTrue("The picked file stays, for another try", input.file.isFile)
        assertTrue(converter.convert(input, "after-cancel", {}, {}).pageCount >= 4)
        converter.delete(input)
    }

    private companion object {
        const val XHTML11 = """<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">"""
    }
}
