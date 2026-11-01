package com.kukreja.stickersearch.media

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import java.io.File
import java.nio.ByteBuffer

/**
 * Thumbnails via the platform [ImageDecoder] (animated WebP/GIF/APNG supported since API 28).
 * The first frame is used for the grid; the original file is never modified, so animation is preserved.
 */
class Thumbnailer(private val dir: File, private val maxPx: Int = 256) {

    init { dir.mkdirs() }

    fun fileFor(sha256: String) = File(dir, "$sha256.webp")

    /** Returns the thumbnail path; reuses an existing one (incremental: same hash => no work). */
    fun ensureThumbnail(sha256: String, bytes: ByteArray): String {
        val out = fileFor(sha256)
        if (out.exists() && out.length() > 0) return out.absolutePath
        val src = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
        val bmp = ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = minOf(1f, maxPx.toFloat() / maxOf(w, h).coerceAtLeast(1))
            decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        // Unique temp name: duplicates of the same hash may be processed concurrently.
        val tmp = File.createTempFile(sha256, ".tmp", dir)
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, it) }
        bmp.recycle()
        if (!tmp.renameTo(out)) tmp.delete() // another worker won the race; its file is identical
        return out.absolutePath
    }

    companion object {
        /** True when the platform decodes the bytes as a real animation (not a flattened still). */
        fun decodesAsAnimation(bytes: ByteArray): Boolean {
            val d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))
            return d is AnimatedImageDrawable
        }
    }
}
