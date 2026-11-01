package com.kukreja.stickersearch.media

import com.kukreja.stickersearch.model.StickerFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class WebpAnimTest {

    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    private fun le24(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte())

    private fun chunk(fourcc: String, payload: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(fourcc.toByteArray()); o.write(le32(payload.size)); o.write(payload)
        if (payload.size % 2 == 1) o.write(0)
        return o.toByteArray()
    }

    private fun riff(vararg chunks: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().apply { write("WEBP".toByteArray()); chunks.forEach { write(it) } }.toByteArray()
        return "RIFF".toByteArray() + le32(body.size) + body
    }

    private fun anmf(x: Int, y: Int, w: Int, h: Int, dur: Int, flags: Int, vararg sub: ByteArray): ByteArray {
        val header = le24(x / 2) + le24(y / 2) + le24(w - 1) + le24(h - 1) + le24(dur) + byteArrayOf(flags.toByte())
        return chunk("ANMF", header + sub.fold(ByteArray(0)) { a, b -> a + b })
    }

    private val vp8 = ByteArray(11) { it.toByte() } // odd size: exercises padding
    private val alph = byteArrayOf(1, 2, 3)
    private val vp8l = byteArrayOf(0x2f, 9, 9, 9, 9)

    private fun twoFrameFile() = riff(
        chunk("VP8X", byteArrayOf(0x12, 0, 0, 0) + le24(511) + le24(511)),
        chunk("ANIM", le32(0) + byteArrayOf(0, 0)),
        anmf(0, 0, 512, 512, 80, 0, chunk("ALPH", alph), chunk("VP8 ", vp8)),
        anmf(10, 20, 100, 50, 120, 0x03, chunk("VP8L", vp8l)),
    )

    @Test fun parsesFramesGeometryTimingAndFlags() {
        val a = WebpAnim.parse(twoFrameFile())!!
        assertEquals(512, a.canvasWidth)
        assertEquals(2, a.frames.size)
        assertEquals(200L, a.totalDurationMs)
        val f1 = a.frames[1]
        assertEquals(10, f1.x); assertEquals(20, f1.y); assertEquals(100, f1.width); assertEquals(50, f1.height)
        assertFalse(f1.blend) // bit 1 set: do not blend
        assertTrue(f1.disposeToBackground)
        assertTrue(a.frames[0].blend)
    }

    @Test fun lossyFrameWithAlphaBecomesVp8xAlphVp8() {
        val still = WebpAnim.standaloneFrame(WebpAnim.parse(twoFrameFile())!!.frames[0])!!
        val info = FormatSniffer.sniff(still)
        assertEquals(StickerFormat.WEBP_STATIC, info.format)
        assertEquals(512, info.width)
        assertEquals("VP8X", String(still, 12, 4))
        // RIFF size field equals file length minus 8.
        assertEquals(still.size - 8, (still[4].toInt() and 0xFF) or ((still[5].toInt() and 0xFF) shl 8) or
            ((still[6].toInt() and 0xFF) shl 16))
        val vp8At = String(still, Charsets.ISO_8859_1).indexOf("VP8 ")
        assertArrayEquals(vp8, still.copyOfRange(vp8At + 8, vp8At + 8 + vp8.size))
    }

    @Test fun losslessFrameIsWrappedAlone() {
        val still = WebpAnim.standaloneFrame(WebpAnim.parse(twoFrameFile())!!.frames[1])!!
        assertEquals("VP8L", String(still, 12, 4))
        assertNotNull(FormatSniffer.sniff(still))
    }

    @Test fun staticFileHasNoAnimation() {
        assertNull(WebpAnim.parse(riff(chunk("VP8L", vp8l))))
        assertNull(WebpAnim.parse(byteArrayOf(1, 2, 3)))
    }
}
