package app.stickersearch.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class PcExportZipTest {

    private fun fakeJpeg(i: Int) = ByteArray(2_000 + i % 500) { (it * 31 + i).toByte() }

    @Test
    fun `a finished export of 5,000 stickers lists every image and the manifest`() {
        val f = File.createTempFile("export", ".zip").apply { deleteOnExit() }
        val names = (0 until 5_000).map { "images/sha$it.jpg" }
        PcExportZip(f).use { z ->
            z.put("describe.py", "print('hi')".toByteArray())
            names.forEachIndexed { i, n -> z.put(n, fakeJpeg(i), store = true) }
            z.finish("""{"format":1,"items":[]}""".toByteArray())
        }
        assertNull(PcExportZip.verify(f, names))
        ZipFile(f).use { z ->
            assertEquals(5_000 + 2, z.size())
            assertEquals(fakeJpeg(4_321).toList(), z.getInputStream(z.getEntry("images/sha4321.jpg")).readBytes().toList())
        }
    }

    @Test
    fun `an export cut off before the end fails verification`() {
        val f = File.createTempFile("export", ".zip").apply { deleteOnExit() }
        val names = (0 until 200).map { "images/sha$it.jpg" }
        PcExportZip(f).use { z -> names.forEachIndexed { i, n -> z.put(n, fakeJpeg(i), store = true) } }
        // Closed without finish(): no manifest. Then simulate the process dying mid-write.
        assertNotNull(PcExportZip.verify(f, names))
        f.writeBytes(f.readBytes().copyOf(f.length().toInt() / 2))
        assertNotNull(PcExportZip.verify(f, names))
    }

    @Test
    fun `the Mac and Linux starters are marked executable, nothing else is`() {
        val f = File.createTempFile("export", ".zip").apply { deleteOnExit() }
        PcExportZip(f).use { z ->
            z.put("describe.py", "print(1)".toByteArray())
            z.put("Start on Mac.command", "#!/bin/sh\n".toByteArray(), executable = true)
            z.put("start-linux.sh", "#!/bin/sh\n".toByteArray(), executable = true)
            z.put("images/a.jpg", ByteArray(100), store = true)
            z.finish("{}".toByteArray())
        }
        val modes = unixModes(f)
        assertEquals(0b111_101_101, modes.getValue("Start on Mac.command") and 0x1FF)
        assertEquals(0b111_101_101, modes.getValue("start-linux.sh") and 0x1FF)
        assertEquals(0, modes.getValue("describe.py"))
        assertNull(PcExportZip.verify(f, listOf("images/a.jpg")))   // still a valid zip
    }

    /** Reads each central-directory entry's Unix mode (high 16 bits of the external attributes). */
    private fun unixModes(f: File): Map<String, Int> {
        val b = f.readBytes()
        fun le16(i: Int) = (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8)
        fun le32(i: Int) = le16(i) or (le16(i + 2) shl 16)
        var eocd = b.size - 22
        while (le32(eocd) != 0x06054b50) eocd--
        var p = le32(eocd + 16)
        val out = mutableMapOf<String, Int>()
        while (le32(p) == 0x02014b50) {
            val n = le16(p + 28); val e = le16(p + 30); val c = le16(p + 32)
            out[String(b, p + 46, n)] = (le32(p + 38) ushr 16)
            p += 46 + n + e + c
        }
        return out
    }
}
