package app.stickersearch.media

import app.stickersearch.model.StickerFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class FormatSnifferTest {

    // ---- builders for synthetic files ----

    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    private fun le24(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte())
    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
    private fun be32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    private fun chunk(fourcc: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(fourcc.toByteArray()); out.write(le32(payload.size)); out.write(payload)
        if (payload.size % 2 == 1) out.write(0)
        return out.toByteArray()
    }

    private fun riff(vararg chunks: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().apply { write("WEBP".toByteArray()); chunks.forEach { write(it) } }.toByteArray()
        return "RIFF".toByteArray() + le32(body.size) + body
    }

    private fun vp8x(animated: Boolean, w: Int, h: Int) =
        chunk("VP8X", byteArrayOf(if (animated) 0x12 else 0x10, 0, 0, 0) + le24(w - 1) + le24(h - 1))

    private fun vp8lPayload(w: Int, h: Int): ByteArray {
        val bits = (w - 1) or ((h - 1) shl 14)
        return byteArrayOf(0x2f) + le32(bits) + ByteArray(5)
    }

    // ---- WebP ----

    @Test fun staticLossyWebp() {
        val vp8 = byteArrayOf(0, 0, 0, 0x9d.toByte(), 0x01, 0x2a) + le16(512) + le16(512) + ByteArray(7)
        val info = FormatSniffer.sniff(riff(chunk("VP8 ", vp8)))
        assertEquals(StickerFormat.WEBP_STATIC, info.format)
        assertFalse(info.isAnimated)
        assertEquals(512, info.width); assertEquals(512, info.height)
    }

    @Test fun staticLosslessWebp() {
        val info = FormatSniffer.sniff(riff(chunk("VP8L", vp8lPayload(300, 200))))
        assertEquals(StickerFormat.WEBP_STATIC, info.format)
        assertEquals(300, info.width); assertEquals(200, info.height)
    }

    @Test fun animatedWebpCountsFrames() {
        val anim = chunk("ANIM", ByteArray(4) + le16(0))
        val frame = chunk("ANMF", ByteArray(17)) // odd size exercises padding
        val info = FormatSniffer.sniff(riff(vp8x(true, 512, 512), anim, frame, frame, frame))
        assertEquals(StickerFormat.WEBP_ANIMATED, info.format)
        assertTrue(info.isAnimated)
        assertEquals(3, info.frameCount)
        assertEquals(512, info.width)
        assertEquals(0, info.loopCount)
    }

    @Test fun vp8xWithoutAnimationIsStatic() {
        val info = FormatSniffer.sniff(riff(vp8x(false, 512, 512), chunk("VP8L", vp8lPayload(512, 512))))
        assertEquals(StickerFormat.WEBP_STATIC, info.format)
        assertEquals(512, info.width)
    }

    @Test fun truncatedWebpDoesNotThrow() {
        val full = riff(vp8x(true, 512, 512), chunk("ANMF", ByteArray(40)))
        val info = FormatSniffer.sniff(full.copyOf(30))
        assertEquals(StickerFormat.WEBP_STATIC, info.format)
    }

    // ---- GIF ----

    private fun gif(frames: Int, netscapeLoop: Int?): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("GIF89a".toByteArray()); out.write(le16(64)); out.write(le16(48))
        out.write(byteArrayOf(0x80.toByte(), 0, 0)) // global color table, 2 entries
        out.write(ByteArray(6))
        if (netscapeLoop != null) {
            out.write(byteArrayOf(0x21, 0xFF.toByte(), 11)); out.write("NETSCAPE2.0".toByteArray())
            out.write(byteArrayOf(3, 1)); out.write(le16(netscapeLoop)); out.write(0)
        }
        repeat(frames) {
            out.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0, 10, 0, 0, 0)) // graphic control ext
            out.write(0x2C); out.write(ByteArray(8)); out.write(0) // descriptor, no local table
            out.write(2) // LZW min code size
            out.write(byteArrayOf(2, 0x44, 0x01, 0)) // one sub-block + terminator
        }
        out.write(0x3B)
        return out.toByteArray()
    }

    @Test fun animatedGif() {
        val info = FormatSniffer.sniff(gif(4, 0))
        assertEquals(StickerFormat.GIF, info.format)
        assertTrue(info.isAnimated)
        assertEquals(4, info.frameCount)
        assertEquals(64, info.width); assertEquals(48, info.height)
        assertEquals(0, info.loopCount)
    }

    @Test fun singleFrameGifIsStatic() {
        val info = FormatSniffer.sniff(gif(1, null))
        assertFalse(info.isAnimated)
        assertEquals(1, info.frameCount)
    }

    // ---- PNG / APNG ----

    private fun pngChunk(type: String, data: ByteArray) = be32(data.size) + type.toByteArray() + data + ByteArray(4)
    private val pngSig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    @Test fun apngDetected() {
        val bytes = pngSig + pngChunk("IHDR", be32(100) + be32(80) + ByteArray(5)) +
            pngChunk("acTL", be32(6) + be32(0)) + pngChunk("IDAT", ByteArray(3))
        val info = FormatSniffer.sniff(bytes)
        assertEquals(StickerFormat.APNG, info.format)
        assertEquals(6, info.frameCount)
        assertEquals(100, info.width)
    }

    @Test fun plainPng() {
        val bytes = pngSig + pngChunk("IHDR", be32(10) + be32(20) + ByteArray(5)) + pngChunk("IDAT", ByteArray(3))
        val info = FormatSniffer.sniff(bytes)
        assertEquals(StickerFormat.PNG, info.format)
        assertFalse(info.isAnimated)
        assertEquals(20, info.height)
    }

    // ---- others ----

    @Test fun zipAndLottieAndGarbage() {
        assertEquals(StickerFormat.LOTTIE_WAS, FormatSniffer.sniff(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0, 0)).format)
        assertEquals(StickerFormat.LOTTIE_JSON, FormatSniffer.sniff("""{"v":"5.7.4","fr":30,"ip":0}""".toByteArray()).format)
        assertEquals(StickerFormat.UNKNOWN, FormatSniffer.sniff(byteArrayOf(1, 2, 3)).format)
        assertEquals(StickerFormat.UNKNOWN, FormatSniffer.sniff(ByteArray(0)).format)
    }
}
