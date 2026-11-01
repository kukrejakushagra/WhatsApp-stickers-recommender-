package com.kukreja.stickersearch.diagnostics

import com.kukreja.stickersearch.media.Hasher
import com.kukreja.stickersearch.model.DiscoveredFile
import com.kukreja.stickersearch.model.FormatInfo
import com.kukreja.stickersearch.model.ProcessedFile
import com.kukreja.stickersearch.model.SourceId
import com.kukreja.stickersearch.model.StickerFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class ScanAggregatorTest {

    private val staticFmt = FormatInfo(StickerFormat.WEBP_STATIC, false, 1, 512, 512)
    private val animFmt = FormatInfo(StickerFormat.WEBP_ANIMATED, true, 8, 512, 512, 0)

    private fun pf(src: SourceId, path: String, sha: String?, fmt: FormatInfo? = staticFmt, t: Long = 1L, err: String? = null) =
        ProcessedFile(DiscoveredFile(src, "file:///$path", path, path.substringAfterLast('/'), 100, t), sha, fmt, null, err)

    @Test fun dedupAcrossAndWithinSources() {
        val processed = listOf(
            pf(SourceId.SAF_TREE, "a/1.webp", "h1"),
            pf(SourceId.SAF_TREE, "a/1-copy.webp", "h1"), // duplicate within a source
            pf(SourceId.FILE_SYSTEM, "a/1.webp", "h1"), // same file via another source
            pf(SourceId.FILE_SYSTEM, "a/2.webp", "h2", animFmt),
            pf(SourceId.SHARE_IN, "x/3.webp", null, null, err = "read failed"),
            // Encrypted backup: hashed but unrecognised. Must not count as a sticker.
            pf(SourceId.SHARE_IN, "x/4.crypt15", "h4", FormatInfo(StickerFormat.UNKNOWN, false, 0, 0, 0)),
            pf(SourceId.SAF_TREE, "a/5.was", "h5", FormatInfo(StickerFormat.LOTTIE_WAS, true, 0, 0, 0)),
        )
        val s = ScanAggregator.aggregate(processed)
        assertEquals(3, s.uniqueCount)
        assertEquals(2, s.animatedCount)
        assertEquals(1, s.lottieCount)
        assertEquals(0, s.animatedDecodeFailures)
        assertEquals(1, s.perSource.getValue(SourceId.SAF_TREE).duplicateFiles)
        assertEquals(2, s.perSource.getValue(SourceId.SAF_TREE).uniqueHashes)
        assertEquals(2, s.perSource.getValue(SourceId.FILE_SYSTEM).uniqueHashes)
        assertEquals(2, s.perSource.getValue(SourceId.SHARE_IN).failures)
        assertEquals(mapOf(SourceId.FILE_SYSTEM to 1, SourceId.SAF_TREE to 1), s.exclusiveToSource)
        assertEquals(3, s.unique.first { it.sha256 == "h1" }.occurrences.size)
        assertEquals(2, s.failures.size)
    }

    @Test fun newestFirstOrdering() {
        val s = ScanAggregator.aggregate(listOf(
            pf(SourceId.SAF_TREE, "old.webp", "old", t = 10),
            pf(SourceId.SAF_TREE, "new.webp", "new", t = 99),
        ))
        assertEquals(listOf("new", "old"), s.unique.map { it.sha256 })
    }

    @Test fun sha256KnownVector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Hasher.sha256("abc".toByteArray()),
        )
        assertEquals(Hasher.sha256("abc".toByteArray()), Hasher.sha256("abc".byteInputStream()))
    }
}
