package io.github.nath_goa.usugami.core

import java.io.BufferedOutputStream
import java.io.OutputStream

/**
 * Minimal PDF 1.4 file writer: numbered objects written one after another, a classic
 * cross-reference table and trailer. Objects may be written in any order; every reserved
 * object must be written before [finish].
 */
class PdfWriter(output: OutputStream) {
    private val out = CountingOutputStream(BufferedOutputStream(output, 64 * 1024))
    private val offsets = ArrayList<Long>()

    init {
        // Header, then a comment with high-bit bytes so tools treat the file as binary.
        write("%PDF-1.4\n")
        out.write(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))
    }

    /** Allocates an object number to be written later. */
    fun reserve(): Int {
        offsets.add(-1L)
        return offsets.size
    }

    fun writeObject(number: Int, body: String) {
        begin(number)
        write(body)
        write("\nendobj\n")
    }

    /** A stream object; [dictionary] holds the entries without the << >> and without /Length. */
    fun writeStream(number: Int, dictionary: String, data: ByteArray) {
        begin(number)
        write("<< $dictionary /Length ${data.size} >>\nstream\n")
        out.write(data)
        write("\nendstream\nendobj\n")
    }

    /**
     * A stream object whose data is produced by [body] while writing, so it never has to be held
     * in memory. Its length goes into a separate object written right after it.
     */
    fun writeStream(number: Int, dictionary: String, body: (OutputStream) -> Unit) {
        val lengthNumber = reserve()
        begin(number)
        write("<< $dictionary /Length $lengthNumber 0 R >>\nstream\n")
        val start = out.count
        body(out)
        val length = out.count - start
        write("\nendstream\nendobj\n")
        writeObject(lengthNumber, length.toString())
    }

    /** Writes the cross-reference table and trailer and flushes. Does not close the underlying stream. */
    fun finish(rootNumber: Int, infoNumber: Int, fileId: ByteArray) {
        val missing = offsets.indexOfFirst { it < 0 }
        check(missing < 0) { "Object ${missing + 1} was reserved but never written" }
        val xrefOffset = out.count
        val sb = StringBuilder()
        sb.append("xref\n0 ").append(offsets.size + 1).append('\n')
        sb.append("0000000000 65535 f \n")
        for (offset in offsets) {
            sb.append(offset.toString().padStart(10, '0')).append(" 00000 n \n")
        }
        val id = PdfSyntax.hex(fileId)
        sb.append("trailer\n<< /Size ").append(offsets.size + 1)
            .append(" /Root ").append(rootNumber).append(" 0 R")
            .append(" /Info ").append(infoNumber).append(" 0 R")
            .append(" /ID [<").append(id).append("> <").append(id).append(">] >>\n")
        sb.append("startxref\n").append(xrefOffset).append("\n%%EOF\n")
        write(sb.toString())
        out.flush()
    }

    private fun begin(number: Int) {
        require(number in 1..offsets.size) { "Object $number was not reserved" }
        check(offsets[number - 1] < 0) { "Object $number written twice" }
        offsets[number - 1] = out.count
        write("$number 0 obj\n")
    }

    private fun write(text: String) = out.write(text.toByteArray(Charsets.ISO_8859_1))
}

private class CountingOutputStream(private val target: OutputStream) : OutputStream() {
    var count = 0L
        private set

    override fun write(b: Int) {
        target.write(b)
        count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        target.write(b, off, len)
        count += len
    }

    override fun flush() = target.flush()
    override fun close() = target.close()
}
