package com.kukreja.stickersearch.index

import com.kukreja.stickersearch.data.StickerStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DescribedByTest {
    @Test
    fun `the export says who described each sticker`() {
        assertNull(PcBridge.describedBy(StickerStatus.PENDING, null))
        assertNull(PcBridge.describedBy(StickerStatus.NEEDS_REVIEW, "gemma-3n-e2b-it-int4"))
        assertEquals("phone", PcBridge.describedBy(StickerStatus.INDEXED, "gemma-3n-e2b-it-int4"))
        assertEquals("phone", PcBridge.describedBy(StickerStatus.INDEXED, null))
        assertEquals("pc", PcBridge.describedBy(StickerStatus.INDEXED, "pc:gemma3:12b:p2"))
        assertEquals("shared", PcBridge.describedBy(StickerStatus.INDEXED, "shared:gemma-3n-e2b-it-int4"))
    }
}
