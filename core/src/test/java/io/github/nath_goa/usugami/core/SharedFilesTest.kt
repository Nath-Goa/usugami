package io.github.nath_goa.usugami.core

import io.github.nath_goa.usugami.core.SharedKind.EPUB
import io.github.nath_goa.usugami.core.SharedKind.EXCEL
import io.github.nath_goa.usugami.core.SharedKind.IMAGE
import io.github.nath_goa.usugami.core.SharedKind.OTHER
import io.github.nath_goa.usugami.core.SharedKind.PDF
import io.github.nath_goa.usugami.core.SharedKind.POWERPOINT
import io.github.nath_goa.usugami.core.SharedKind.WORD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedFilesTest {

    @Test
    fun theTypeSaysWhatAFileIs() {
        assertEquals(PDF, SharedFiles.kindOf("application/pdf", null))
        assertEquals(PDF, SharedFiles.kindOf("application/x-pdf", null))
        assertEquals(IMAGE, SharedFiles.kindOf("image/jpeg", null))
        assertEquals(IMAGE, SharedFiles.kindOf("image/heic", null))
        assertEquals(WORD, SharedFiles.kindOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document", null))
        assertEquals(WORD, SharedFiles.kindOf("application/vnd.ms-word.document.macroEnabled.12", null))
        assertEquals(WORD, SharedFiles.kindOf("application/msword", null))
        assertEquals(POWERPOINT, SharedFiles.kindOf("application/vnd.openxmlformats-officedocument.presentationml.presentation", null))
        assertEquals(POWERPOINT, SharedFiles.kindOf("application/vnd.ms-powerpoint", null))
        assertEquals(EPUB, SharedFiles.kindOf("application/epub+zip", null))
        assertEquals(EXCEL, SharedFiles.kindOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", null))
        assertEquals(EXCEL, SharedFiles.kindOf("application/vnd.ms-excel", null))
        assertEquals(OTHER, SharedFiles.kindOf("text/plain", null))
        assertEquals(OTHER, SharedFiles.kindOf(null, null))
    }

    @Test
    fun typesAreReadWhateverTheirCaseAndParameters() {
        assertEquals(PDF, SharedFiles.kindOf(" Application/PDF ", null))
        assertEquals(PDF, SharedFiles.kindOf("application/pdf; name=\"Report.pdf\"", null))
        assertEquals(EPUB, SharedFiles.kindOf("APPLICATION/EPUB+ZIP", null))
    }

    @Test
    fun aTypeThatSaysNothingGivesWayToTheName() {
        assertEquals(EPUB, SharedFiles.kindOf("application/octet-stream", "Book.epub"))
        assertEquals(WORD, SharedFiles.kindOf("application/zip", "Report.DOCX"))
        assertEquals(POWERPOINT, SharedFiles.kindOf("*/*", "Talk.pptx"))
        assertEquals(PDF, SharedFiles.kindOf(null, "scan.final.pdf"))
        assertEquals(IMAGE, SharedFiles.kindOf(null, "IMG_1234.JPG"))
        assertEquals(WORD, SharedFiles.kindOf(null, "old.doc"))
        assertEquals(EXCEL, SharedFiles.kindOf("application/octet-stream", "Budget.XLSX"))
        assertEquals(EXCEL, SharedFiles.kindOf(null, "old.xls"))
        assertEquals(OTHER, SharedFiles.kindOf(null, "notes.txt"))
        assertEquals(OTHER, SharedFiles.kindOf(null, "README"))
        // The file's own type comes first: a PDF named like a picture is a PDF.
        assertEquals(PDF, SharedFiles.kindOf("application/pdf", "photo.jpg"))
    }

    @Test
    fun theShareTypeIsTheLastResort() {
        assertEquals(IMAGE, SharedFiles.kindOf(null, "1000000033", sharedAs = "image/*"))
        assertEquals(PDF, SharedFiles.kindOf("application/octet-stream", "download", sharedAs = "application/pdf"))
        assertEquals(OTHER, SharedFiles.kindOf(null, "download", sharedAs = "*/*"))
        assertEquals(OTHER, SharedFiles.kindOf(null, "download", sharedAs = "application/*"))
        // What the file and its name say comes before the share's type.
        assertEquals(PDF, SharedFiles.kindOf("application/pdf", null, sharedAs = "image/*"))
        assertEquals(EPUB, SharedFiles.kindOf(null, "Book.epub", sharedAs = "image/*"))
        assertEquals(OTHER, SharedFiles.kindOf("text/plain", "notes.txt", sharedAs = "*/*"))
    }

    @Test
    fun aShareIsTakenAsTheKindMostOfItsFilesAre() {
        assertEquals(PDF, SharedFiles.chosen(listOf(PDF)))
        assertEquals(WORD, SharedFiles.chosen(listOf(WORD, PDF, WORD, OTHER)))
        assertEquals(IMAGE, SharedFiles.chosen(listOf(IMAGE, IMAGE, PDF, EPUB, OTHER, OTHER, OTHER)))
        assertEquals(EPUB, SharedFiles.chosen(listOf(OTHER, EPUB)))
    }

    @Test
    fun asManyOfTwoKindsGoesByTheKindsOrder() {
        assertEquals(PDF, SharedFiles.chosen(listOf(IMAGE, PDF)))
        assertEquals(PDF, SharedFiles.chosen(listOf(WORD, PDF, WORD, PDF)))
        assertEquals(IMAGE, SharedFiles.chosen(listOf(EPUB, IMAGE)))
        assertEquals(WORD, SharedFiles.chosen(listOf(POWERPOINT, WORD, EPUB)))
        assertEquals(POWERPOINT, SharedFiles.chosen(listOf(EPUB, POWERPOINT)))
        assertEquals(EPUB, SharedFiles.chosen(listOf(EXCEL, EPUB)))
    }

    @Test
    fun aShareOfNothingUsugamiTakesHasNoKind() {
        assertNull(SharedFiles.chosen(emptyList()))
        assertNull(SharedFiles.chosen(listOf(OTHER, OTHER)))
    }
}
