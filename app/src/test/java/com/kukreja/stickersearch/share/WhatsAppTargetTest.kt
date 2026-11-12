package com.kukreja.stickersearch.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsAppTargetTest {
    private val wa = StickerPacks.WHATSAPP
    private val biz = StickerPacks.WHATSAPP_BUSINESS

    @Test
    fun `one app installed is used without asking`() {
        assertEquals(biz, StickerPacks.pickTarget(listOf(biz), null))
        assertFalse(StickerPacks.shouldAsk(listOf(biz), null))
        assertEquals(wa, StickerPacks.pickTarget(listOf(wa), null))
    }

    @Test
    fun `both installed asks once, then the saved choice wins`() {
        assertTrue(StickerPacks.shouldAsk(listOf(wa, biz), null))
        assertFalse(StickerPacks.shouldAsk(listOf(wa, biz), biz))
        assertEquals(biz, StickerPacks.pickTarget(listOf(wa, biz), biz))
    }

    @Test
    fun `a saved app that was uninstalled falls back to the one still there`() {
        assertEquals(wa, StickerPacks.pickTarget(listOf(wa), biz))
        assertEquals(wa, StickerPacks.pickTarget(emptyList(), null))
    }
}
