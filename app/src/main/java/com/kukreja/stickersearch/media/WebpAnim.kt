package com.kukreja.stickersearch.media

import java.io.ByteArrayOutputStream

/** One ANMF frame of an animated WebP. [bitstream] holds its ALPH/VP8/VP8L sub-chunks verbatim. */
data class AnmfFrame(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val durationMs: Int,
    /** True: alpha-blend over the canvas. False: overwrite the frame rectangle. */
    val blend: Boolean,
    /** True: after showing, clear the frame rectangle to transparent. */
    val disposeToBackground: Boolean,
    val bitstream: ByteArray,
) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

data class WebpAnimation(val canvasWidth: Int, val canvasHeight: Int, val loopCount: Int, val frames: List<AnmfFrame>) {
    val totalDurationMs: Long get() = frames.sumOf { it.durationMs.toLong() }
}

/**
 * Parses animated WebP containers and re-wraps single frames as standalone still WebP files, so the
 * platform's BitmapFactory can decode each frame (ImageDecoder only plays animations, it can't seek).
 * Pure Kotlin; spec: https://developers.google.com/speed/webp/docs/riff_container
 */
object WebpAnim {

    fun parse(b: ByteArray): WebpAnimation? {
        if (b.size < 12 || String(b, 0, 4, Charsets.US_ASCII) != "RIFF" || String(b, 8, 4, Charsets.US_ASCII) != "WEBP") return null
        var pos = 12
        var cw = 0
        var ch = 0
        var loop = 0
        val frames = mutableListOf<AnmfFrame>()
        while (pos + 8 <= b.size) {
            val fourcc = String(b, pos, 4, Charsets.US_ASCII)
            val size = le32(b, pos + 4)
            val data = pos + 8
            if (size < 0 || data + size > b.size) break
            when (fourcc) {
                "VP8X" -> { cw = le24(b, data + 4) + 1; ch = le24(b, data + 7) + 1 }
                "ANIM" -> loop = le16(b, data + 4)
                "ANMF" -> if (size >= 16) {
                    val flags = u8(b, data + 15)
                    frames += AnmfFrame(
                        x = le24(b, data) * 2,
                        y = le24(b, data + 3) * 2,
                        width = le24(b, data + 6) + 1,
                        height = le24(b, data + 9) + 1,
                        durationMs = le24(b, data + 12),
                        blend = (flags and 0x02) == 0,
                        disposeToBackground = (flags and 0x01) != 0,
                        bitstream = b.copyOfRange(data + 16, data + size),
                    )
                }
            }
            pos = data + size + (size and 1)
        }
        if (frames.isEmpty()) return null
        return WebpAnimation(cw, ch, loop, frames)
    }

    /** A frame as a standalone still WebP: VP8L alone, VP8 alone, or VP8X + ALPH + VP8. */
    fun standaloneFrame(f: AnmfFrame): ByteArray? {
        var alph: ByteArray? = null
        var image: Pair<String, ByteArray>? = null
        var pos = 0
        val s = f.bitstream
        while (pos + 8 <= s.size) {
            val fourcc = String(s, pos, 4, Charsets.US_ASCII)
            val size = le32(s, pos + 4)
            val data = pos + 8
            if (size < 0 || data + size > s.size) break
            val payload = s.copyOfRange(data, data + size)
            when (fourcc) {
                "ALPH" -> alph = payload
                "VP8 ", "VP8L" -> image = fourcc to payload
            }
            pos = data + size + (size and 1)
        }
        val (kind, payload) = image ?: return null
        val chunks = ByteArrayOutputStream()
        if (kind == "VP8 " && alph != null) {
            val vp8x = ByteArray(10)
            vp8x[0] = 0x10 // alpha flag
            put24(vp8x, 4, f.width - 1)
            put24(vp8x, 7, f.height - 1)
            chunks.write(chunk("VP8X", vp8x))
            chunks.write(chunk("ALPH", alph))
        }
        chunks.write(chunk(kind, payload))
        val body = chunks.toByteArray()
        return "RIFF".toByteArray(Charsets.US_ASCII) + le32Bytes(body.size + 4) + "WEBP".toByteArray(Charsets.US_ASCII) + body
    }

    private fun chunk(fourcc: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(fourcc.toByteArray(Charsets.US_ASCII))
        out.write(le32Bytes(payload.size))
        out.write(payload)
        if (payload.size % 2 == 1) out.write(0)
        return out.toByteArray()
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun le16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun le24(b: ByteArray, i: Int) = le16(b, i) or (u8(b, i + 2) shl 16)
    private fun le32(b: ByteArray, i: Int) = le24(b, i) or (u8(b, i + 3) shl 24)
    private fun le32Bytes(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    private fun put24(b: ByteArray, i: Int, v: Int) { b[i] = v.toByte(); b[i + 1] = (v shr 8).toByte(); b[i + 2] = (v shr 16).toByte() }
}
