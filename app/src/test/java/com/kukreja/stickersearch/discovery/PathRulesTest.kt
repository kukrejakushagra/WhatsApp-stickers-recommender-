package com.kukreja.stickersearch.discovery

import com.kukreja.stickersearch.model.DirStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PathRulesTest {

    @Test fun candidates() {
        assertTrue(PathRules.isStickerCandidate("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Stickers/AbC123==.webp"))
        assertTrue(PathRules.isStickerCandidate("WhatsApp/Media/WhatsApp Stickers/Sent/x.webp"))
        assertTrue(PathRules.isStickerCandidate("WhatsApp Stickers/noext"))
        assertFalse(PathRules.isStickerCandidate("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Stickers/.nomedia"))
        assertFalse(PathRules.isStickerCandidate("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/IMG-1.jpg"))
        assertFalse(PathRules.isStickerCandidate("WhatsApp Stickers/clip.mp4"))
    }

    @Test fun inventoryTracksNoMedia() {
        val inv = mutableMapOf<String, DirStats>()
        PathRules.record(inv, "A/WhatsApp Stickers/.nomedia", 0)
        PathRules.record(inv, "A/WhatsApp Stickers/1.webp", 10)
        PathRules.record(inv, "A/WhatsApp Stickers/2.webp", 20)
        val st = inv.getValue("A/WhatsApp Stickers")
        assertTrue(st.hasNoMedia)
        assertEquals(2, st.fileCount)
        assertEquals(30, st.totalBytes)
    }
}
