package io.github.nath_goa.usugami.core

import io.github.nath_goa.usugami.core.SharedKind.EPUB
import io.github.nath_goa.usugami.core.SharedKind.EXCEL
import io.github.nath_goa.usugami.core.SharedKind.IMAGE
import io.github.nath_goa.usugami.core.SharedKind.OTHER
import io.github.nath_goa.usugami.core.SharedKind.PDF
import io.github.nath_goa.usugami.core.SharedKind.POWERPOINT
import io.github.nath_goa.usugami.core.SharedKind.WORD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentPicksTest {

    @Test
    fun aPickedFileIsOneOfTheFourKindsOrOther() {
        assertEquals(WORD, DocumentPicks.kindOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "Report.docx"))
        // A type that says nothing gives way to the name.
        assertEquals(WORD, DocumentPicks.kindOf("application/octet-stream", "Report.docx"))
        // The old binary formats count: their converter says how to make them into ones it reads.
        assertEquals(WORD, DocumentPicks.kindOf(null, "minutes.doc"))
        assertEquals(EPUB, DocumentPicks.kindOf("application/octet-stream", "Book.epub"))
        assertEquals(EXCEL, DocumentPicks.kindOf(null, "Budget.xlsx"))
        assertEquals(POWERPOINT, DocumentPicks.kindOf("application/vnd.ms-powerpoint", null))
        // The tile doesn't take PDFs or images.
        assertEquals(OTHER, DocumentPicks.kindOf("application/pdf", "alpha.pdf"))
        assertEquals(OTHER, DocumentPicks.kindOf("image/jpeg", "photo.jpg"))
        assertEquals(OTHER, DocumentPicks.kindOf("text/plain", "notes.txt"))
    }

    @Test
    fun onlyAFileWhoseTypeAndNameSayNothingIsLookedInto() {
        assertTrue(DocumentPicks.untold("application/octet-stream", "download"))
        assertTrue(DocumentPicks.untold(null, null))
        assertFalse(DocumentPicks.untold("application/pdf", "alpha.pdf"))
        assertFalse(DocumentPicks.untold("application/octet-stream", "Book.epub"))
    }

    @Test
    fun theKindMostOfTheFilesAreIsTaken() {
        assertEquals(EXCEL, DocumentPicks.chosen(listOf(EXCEL, WORD, EXCEL)))
        // As many of two kinds: Word before PowerPoint before EPUB before Excel.
        assertEquals(WORD, DocumentPicks.chosen(listOf(EXCEL, WORD)))
        assertEquals(POWERPOINT, DocumentPicks.chosen(listOf(EPUB, POWERPOINT)))
        assertEquals(EPUB, DocumentPicks.chosen(listOf(EXCEL, EPUB)))
        // PDFs, images and files of no kind never win, however many there are.
        assertEquals(EXCEL, DocumentPicks.chosen(listOf(PDF, PDF, IMAGE, IMAGE, OTHER, OTHER, EXCEL)))
        assertNull(DocumentPicks.chosen(listOf(PDF, IMAGE, OTHER)))
        assertNull(DocumentPicks.chosen(emptyList()))
    }

    @Test
    fun aZipFileIsWhatItsEntriesSay() {
        // An EPUB by its mimetype entry's text (written first in every EPUB), or by its container.
        assertEquals(EPUB, DocumentPicks.kindOfEntries(listOf("mimetype"), "application/epub+zip"))
        assertEquals(EPUB, DocumentPicks.kindOfEntries(listOf("mimetype"), " application/epub+zip\n"))
        assertEquals(EPUB, DocumentPicks.kindOfEntries(listOf("OEBPS/book.opf", "META-INF/container.xml"), null))
        // Office files by the folder of their first part, after the parts every Office file has.
        val common = listOf("[Content_Types].xml", "_rels/.rels", "docProps/core.xml")
        assertNull(DocumentPicks.kindOfEntries(common, null))
        assertEquals(WORD, DocumentPicks.kindOfEntries(common + "word/document.xml", null))
        assertEquals(POWERPOINT, DocumentPicks.kindOfEntries(common + "ppt/presentation.xml", null))
        assertEquals(EXCEL, DocumentPicks.kindOfEntries(common + "xl/workbook.xml", null))
        assertEquals(WORD, DocumentPicks.kindOfEntries(common + "Word/document.xml", null))
        // A plain ZIP file, or one of another format, doesn't tell.
        assertNull(DocumentPicks.kindOfEntries(listOf("photos/1.jpg", "notes.txt"), null))
        assertNull(DocumentPicks.kindOfEntries(listOf("mimetype", "content.xml"), "application/vnd.oasis.opendocument.text"))
        assertNull(DocumentPicks.kindOfEntries(emptyList(), null))
    }
}
