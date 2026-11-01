package com.kukreja.stickersearch.media

import com.kukreja.stickersearch.model.FormatInfo
import com.kukreja.stickersearch.model.StickerFormat

/**
 * Identifies sticker formats from raw bytes without the Android decoders, so it is unit-testable
 * and does not trust file extensions (WhatsApp names stickers by hash, all ending ".webp").
 *
 * Spec references:
 *  - WebP container: https://developers.google.com/speed/webp/docs/riff_container
 *  - GIF89a: https://www.w3.org/Graphics/GIF/spec-gif89a.txt
 *  - APNG: https://wiki.mozilla.org/APNG_Specification
 */
object FormatSniffer {

    fun sniff(bytes: ByteArray): FormatInfo = try {
        when {
            isWebp(bytes) -> sniffWebp(bytes)
            startsWith(bytes, "GIF87a") || startsWith(bytes, "GIF89a") -> sniffGif(bytes)
            startsWith(bytes, PNG_SIG) -> sniffPng(bytes)
            bytes.size >= 3 && u8(bytes, 0) == 0xFF && u8(bytes, 1) == 0xD8 && u8(bytes, 2) == 0xFF ->
                FormatInfo(StickerFormat.JPEG, false, 1, 0, 0)
            startsWith(bytes, byteArrayOf(0x50, 0x4B, 0x03, 0x04)) ->
                FormatInfo(StickerFormat.ZIP_ARCHIVE, true, 0, 0, 0)
            looksLikeLottieJson(bytes) -> FormatInfo(StickerFormat.LOTTIE_JSON, true, 0, 0, 0)
            else -> unknown()
        }
    } catch (e: IndexOutOfBoundsException) {
        unknown() // truncated / corrupt file
    }

    private fun unknown() = FormatInfo(StickerFormat.UNKNOWN, false, 0, 0, 0)

    // ---------- WebP ----------

    private fun isWebp(b: ByteArray) =
        b.size >= 12 && startsWith(b, "RIFF") && String(b, 8, 4, Charsets.US_ASCII) == "WEBP"

    private fun sniffWebp(b: ByteArray): FormatInfo {
        var pos = 12
        var width = 0
        var height = 0
        var animFlag = false
        var frames = 0
        var loop: Int? = null
        var sawVp8x = false
        while (pos + 8 <= b.size) {
            val fourcc = String(b, pos, 4, Charsets.US_ASCII)
            val size = le32(b, pos + 4)
            val data = pos + 8
            if (size < 0 || data + size > b.size) break
            when (fourcc) {
                "VP8X" -> {
                    sawVp8x = true
                    animFlag = (u8(b, data) and 0x02) != 0
                    width = le24(b, data + 4) + 1
                    height = le24(b, data + 7) + 1
                }
                "ANIM" -> loop = le16(b, data + 4)
                "ANMF" -> frames++
                "VP8 " -> if (!sawVp8x) {
                    // 3-byte frame tag, 3-byte start code, then 14-bit width/height.
                    width = le16(b, data + 6) and 0x3FFF
                    height = le16(b, data + 8) and 0x3FFF
                }
                "VP8L" -> if (!sawVp8x) {
                    val bits = le32(b, data + 1)
                    width = (bits and 0x3FFF) + 1
                    height = ((bits ushr 14) and 0x3FFF) + 1
                }
            }
            pos = data + size + (size and 1) // chunks are padded to even sizes
        }
        // A VP8X animation flag with a single ANMF frame looks static to a viewer; treat it as static.
        val animated = frames > 1
        return FormatInfo(
            if (animated) StickerFormat.WEBP_ANIMATED else StickerFormat.WEBP_STATIC,
            animated, maxOf(frames, 1), width, height, if (animFlag) loop else null,
        )
    }

    // ---------- GIF ----------

    private fun sniffGif(b: ByteArray): FormatInfo {
        val width = le16(b, 6)
        val height = le16(b, 8)
        val packed = u8(b, 10)
        var pos = 13
        if (packed and 0x80 != 0) pos += 3 * (1 shl ((packed and 0x07) + 1))
        var frames = 0
        var loop: Int? = null
        loop@ while (pos < b.size) {
            when (u8(b, pos)) {
                0x2C -> { // image descriptor
                    frames++
                    val lp = u8(b, pos + 9)
                    pos += 10
                    if (lp and 0x80 != 0) pos += 3 * (1 shl ((lp and 0x07) + 1))
                    pos += 1 // LZW minimum code size
                    pos = skipSubBlocks(b, pos)
                }
                0x21 -> { // extension
                    val label = u8(b, pos + 1)
                    if (label == 0xFF && pos + 14 < b.size &&
                        String(b, pos + 3, 11, Charsets.US_ASCII) == "NETSCAPE2.0"
                    ) {
                        loop = le16(b, pos + 16)
                    }
                    pos = skipSubBlocks(b, pos + 2)
                }
                0x3B -> break@loop // trailer
                else -> break@loop // corrupt; keep what we counted
            }
        }
        return FormatInfo(StickerFormat.GIF, frames > 1, frames, width, height, loop)
    }

    private fun skipSubBlocks(b: ByteArray, start: Int): Int {
        var pos = start
        while (pos < b.size) {
            val len = u8(b, pos)
            pos += 1
            if (len == 0) break
            pos += len
        }
        return pos
    }

    // ---------- PNG / APNG ----------

    private fun sniffPng(b: ByteArray): FormatInfo {
        var pos = 8
        var width = 0
        var height = 0
        var frames = 1
        var loop: Int? = null
        var apng = false
        while (pos + 8 <= b.size) {
            val len = be32(b, pos)
            val type = String(b, pos + 4, 4, Charsets.US_ASCII)
            val data = pos + 8
            when (type) {
                "IHDR" -> { width = be32(b, data); height = be32(b, data + 4) }
                "acTL" -> { apng = true; frames = be32(b, data); loop = be32(b, data + 4) }
                "IDAT", "IEND" -> break // acTL must precede IDAT
            }
            if (len < 0) break
            pos = data + len + 4 // + CRC
        }
        return if (apng) FormatInfo(StickerFormat.APNG, frames > 1, frames, width, height, loop)
        else FormatInfo(StickerFormat.PNG, false, 1, width, height)
    }

    // ---------- helpers ----------

    private fun looksLikeLottieJson(b: ByteArray): Boolean {
        val head = String(b, 0, minOf(b.size, 256), Charsets.UTF_8).trimStart()
        return head.startsWith("{") && head.contains("\"v\"") && head.contains("\"fr\"")
    }

    private val PNG_SIG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun startsWith(b: ByteArray, s: String) = startsWith(b, s.toByteArray(Charsets.US_ASCII))
    private fun startsWith(b: ByteArray, p: ByteArray): Boolean {
        if (b.size < p.size) return false
        for (i in p.indices) if (b[i] != p[i]) return false
        return true
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun le16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun le24(b: ByteArray, i: Int) = le16(b, i) or (u8(b, i + 2) shl 16)
    private fun le32(b: ByteArray, i: Int) = le24(b, i) or (u8(b, i + 3) shl 24)
    private fun be32(b: ByteArray, i: Int) =
        (u8(b, i) shl 24) or (u8(b, i + 1) shl 16) or (u8(b, i + 2) shl 8) or u8(b, i + 3)
}
