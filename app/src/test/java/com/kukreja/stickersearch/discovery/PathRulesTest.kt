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
        // Seen on device: a second sticker folder, and the encrypted backup tree that must be skipped.
        assertTrue(PathRules.isStickerCandidate("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Backup Excluded Stickers/x.webp"))
        assertFalse(PathRules.isStickerCandidate("Android/media/com.whatsapp/WhatsApp/Backups/Stickers/x.webp"))
        assertFalse(PathRules.isStickerCandidate("Android/media/com.whatsapp/WhatsApp/Backups/Stickers/x.webp.crypt15"))
    }

    @Test fun descendOnlyTowardsStickers() {
        val media = "Android/media/com.whatsapp/WhatsApp/Media"
        assertTrue(PathRules.shouldDescend(media))
        assertTrue(PathRules.shouldDescend("$media/WhatsApp Stickers"))
        assertTrue(PathRules.shouldDescend("$media/WhatsApp Backup Excluded Stickers"))
        assertFalse(PathRules.shouldDescend("$media/WhatsApp Images"))
        assertFalse(PathRules.shouldDescend("$media/WhatsApp Video/Sent"))
        assertFalse(PathRules.shouldDescend("Android/media/com.whatsapp/WhatsApp/Backups/Stickers"))
        assertTrue(PathRules.shouldDescend("Android/media/com.whatsapp/WhatsApp"))
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

    @Test
    fun `WhatsApp Business and older-Android folders are found too`() {
        assertTrue(PathRules.isStickerCandidate("Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Stickers/a.webp"))
        assertTrue(PathRules.isStickerCandidate("WhatsApp Business/Media/WhatsApp Business Stickers/a.webp"))
        assertTrue(PathRules.isStickerCandidate("WhatsApp/Media/WhatsApp Stickers/a.webp"))
        assertTrue(PathRules.shouldDescend("WhatsApp Business/Media"))
        assertTrue(PathRules.shouldDescend("Android/media/com.whatsapp.w4b/WhatsApp Business/Media"))
        org.junit.Assert.assertFalse(PathRules.shouldDescend("WhatsApp Business/Media/WhatsApp Business Images"))
    }

    @Test
    fun `the folder picker starts where each WhatsApp keeps its media`() {
        org.junit.Assert.assertEquals("Android/media/com.whatsapp/WhatsApp/Media", PathRules.mediaFolder(business = false, sdk = 34))
        org.junit.Assert.assertEquals("WhatsApp/Media", PathRules.mediaFolder(business = false, sdk = 29))
        org.junit.Assert.assertEquals("Android/media/com.whatsapp.w4b/WhatsApp Business/Media", PathRules.mediaFolder(business = true, sdk = 33))
        org.junit.Assert.assertEquals("WhatsApp Business/Media", PathRules.mediaFolder(business = true, sdk = 28))
    }
}
