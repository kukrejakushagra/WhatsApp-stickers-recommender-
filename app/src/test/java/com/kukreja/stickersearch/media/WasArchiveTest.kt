package com.kukreja.stickersearch.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class WasArchiveTest {

    private val anim = """{"v":"5.7.4","fr":30,"ip":0,"op":60,"w":512,"h":512,"layers":[]}"""

    private fun zip(stored: Boolean, vararg files: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            files.forEach { (name, text) ->
                val data = text.toByteArray()
                val e = ZipEntry(name)
                if (stored) {
                    e.method = ZipEntry.STORED
                    e.size = data.size.toLong(); e.compressedSize = data.size.toLong()
                    e.crc = CRC32().apply { update(data) }.value
                }
                z.putNextEntry(e); z.write(data); z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test fun picksTheRealAnimationOverMetadataAndSecondary() {
        val bytes = zip(false,
            "animation/animation.json.overridden_metadata" to """{"x":1}""",
            "animation/animation_secondary.json" to anim + " ".repeat(500), // larger, must still lose
            "animation/animation.json" to anim,
        )
        val c = WasArchive.open(bytes)
        assertEquals(WasArchive.Container.ZIP, c.container)
        assertEquals("animation/animation.json", c.jsonCandidates.first().first)
        assertEquals(anim, c.jsonCandidates.first().second)
        assertEquals(3, c.entries.size)
        // The metadata file is not a candidate at all.
        assertTrue(c.jsonCandidates.none { it.first.endsWith("overridden_metadata") })
    }

    /** Built by a non-seekable writer: STORED entries with data descriptors, which ZipInputStream rejects. */
    private val storedWithDescriptors = Base64.getDecoder().decode(
        "UEsDBBQACAAAAKZNYV0AAAAAAAAAAAAAAAAsAAAAYW5pbWF0aW9uL2FuaW1hdGlvbi5qc29uLm92ZXJyaWRkZW5fbWV0YWRhdGF7IngiOjF9UEsHCFxZ6zsHAAAABwAAAFBLAwQUAAgAAACmTWFdAAAAAAAAAAAAAAAAIgAAAGFuaW1hdGlvbi9hbmltYXRpb25fc2Vjb25kYXJ5Lmpzb257InYiOiAiNS43LjQiLCAiZnIiOiAzMCwgImlwIjogMCwgIm9wIjogfVBLBwgVe6x7KQAAACkAAABQSwMEFAAIAAAApk1hXQAAAAAAAAAAAAAAABgAAABhbmltYXRpb24vYW5pbWF0aW9uLmpzb257InYiOiAiNS43LjQiLCAiZnIiOiAzMCwgImlwIjogMCwgIm9wIjogNjAsICJ3IjogNTEyLCAiaCI6IDUxMiwgImxheWVycyI6IFtdfVBLBwj5+IghTQAAAE0AAABQSwECFAMUAAgAAACmTWFdXFnrOwcAAAAHAAAALAAAAAAAAAAAAAAAgAEAAAAAYW5pbWF0aW9uL2FuaW1hdGlvbi5qc29uLm92ZXJyaWRkZW5fbWV0YWRhdGFQSwECFAMUAAgAAACmTWFdFXuseykAAAApAAAAIgAAAAAAAAAAAAAAgAFhAAAAYW5pbWF0aW9uL2FuaW1hdGlvbl9zZWNvbmRhcnkuanNvblBLAQIUAxQACAAAAKZNYV35+IghTQAAAE0AAAAYAAAAAAAAAAAAAACAAdoAAABhbmltYXRpb24vYW5pbWF0aW9uLmpzb25QSwUGAAAAAAMAAwDwAAAAbQEAAAAA",
    )

    @Test fun storedEntriesWithDataDescriptorsAreReadViaTheCentralDirectory() {
        // The old streaming reader can't read this layout at all.
        val streamFailed = runCatching {
            ZipInputStream(ByteArrayInputStream(storedWithDescriptors)).use { z -> while (z.nextEntry != null) z.readBytes() }
        }.isFailure
        assertTrue("ZipInputStream should reject STORED+descriptor", streamFailed)

        val c = WasArchive.open(storedWithDescriptors)
        assertEquals(3, c.entries.size)
        assertEquals("animation/animation.json", c.jsonCandidates.first().first)
        assertTrue(c.jsonCandidates.first().second.contains("\"w\": 512"))
    }

    @Test fun plainStoredZip() {
        val c = WasArchive.open(zip(true, "a/animation.json" to anim))
        assertEquals("a/animation.json", c.jsonCandidates.single().first)
    }

    @Test fun gzipAndRawJson() {
        val gz = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(anim.toByteArray()) } }.toByteArray()
        assertEquals(WasArchive.Container.GZIP, WasArchive.open(gz).container)
        assertEquals(anim, WasArchive.open(gz).jsonCandidates.single().second)
        assertEquals(WasArchive.Container.JSON, WasArchive.open(("\n  " + anim).toByteArray()).container)
    }

    @Test fun failuresExplainThemselves() {
        val garbage = WasArchive.open(byteArrayOf(9, 9, 9, 9))
        assertEquals(WasArchive.Container.UNKNOWN, garbage.container)
        assertNotNull(garbage.readError)

        val noJson = WasArchive.open(zip(false, "images/img_0.png" to "png-bytes", "meta.txt" to "x"))
        assertTrue(noJson.jsonCandidates.isEmpty())
        assertTrue(noJson.describe(), noJson.describe().contains("images/img_0.png"))
        assertTrue(noJson.images.containsKey("img_0.png"))
    }
}
