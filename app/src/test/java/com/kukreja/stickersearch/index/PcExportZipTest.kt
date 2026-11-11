package com.kukreja.stickersearch.index

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
}
