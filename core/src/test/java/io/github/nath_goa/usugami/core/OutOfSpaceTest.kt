package io.github.nath_goa.usugami.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class OutOfSpaceTest {

    @Test
    fun aFullStorageIsToldApartFromOtherFailures() {
        assertTrue(IOException("write failed: ENOSPC (No space left on device)").isOutOfSpace())
        // As a copy reports it: the cause says why.
        assertTrue(IOException("copy failed", IOException("No space left on device")).isOutOfSpace())
        // As PDFBox reports its scratch files reaching the limit that leaves the phone some storage.
        assertTrue(IOException("Maximum allowed scratch file memory exceeded.").isOutOfSpace())
        assertFalse(IOException("read failed: EIO (I/O error)").isOutOfSpace())
        assertFalse(SecurityException().isOutOfSpace())
    }
}
