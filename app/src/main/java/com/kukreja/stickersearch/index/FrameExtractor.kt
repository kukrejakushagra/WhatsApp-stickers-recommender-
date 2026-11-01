package com.kukreja.stickersearch.index

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import com.kukreja.stickersearch.media.WebpAnim
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

data class SavedFrame(val index: Int, val timestampMs: Long, val path: String, val changeScore: Float)

data class FrameResult(val frames: List<SavedFrame>, val durationMs: Long?, val thumbnailPath: String? = null)

/**
 * Extracts keyframes from animated stickers without flattening the original:
 * - animated WebP: each ANMF frame is decoded with BitmapFactory and composited per the
 *   blend/dispose rules, at most [MAX_KEPT] candidates are kept at ≤256px;
 * - Lottie (.was): the composition is rendered at evenly spaced progress points.
 * Then [KeyframeSelector] picks 3–6 frames, saved under frames/<sha>/.
 */
class FrameExtractor(private val context: Context, private val root: File) {

    fun webp(sha: String, bytes: ByteArray): FrameResult? {
        val anim = WebpAnim.parse(bytes) ?: return null
        val cw = anim.canvasWidth.takeIf { it > 0 } ?: anim.frames.maxOf { it.x + it.width }
        val ch = anim.canvasHeight.takeIf { it > 0 } ?: anim.frames.maxOf { it.y + it.height }
        val n = anim.frames.size
        val keepEvery = max(1, ceil(n / MAX_KEPT.toDouble()).toInt())
        val scale = min(1f, MAX_PX / max(cw, ch).toFloat())
        val canvasBmp = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBmp)
        val clear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        val overwrite = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
        val kept = mutableListOf<Kept>()
        var t = 0L
        var decoded = 0
        try {
            anim.frames.forEachIndexed { i, f ->
                val still = WebpAnim.standaloneFrame(f)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                val rect = Rect(f.x, f.y, f.x + f.width, f.y + f.height)
                if (still != null) {
                    decoded++
                    canvas.drawBitmap(still, null, rect, if (f.blend) null else overwrite)
                    still.recycle()
                }
                if (i % keepEvery == 0 || i == n - 1) {
                    val copy = Bitmap.createScaledBitmap(canvasBmp, scaled(cw, scale), scaled(ch, scale), true)
                    kept += Kept(i, t, copy, signature(copy))
                }
                if (f.disposeToBackground) canvas.drawRect(rect, clear)
                t += f.durationMs
            }
            if (decoded == 0) { kept.forEach { it.bitmap.recycle() }; return null }
            return FrameResult(save(sha, kept), t)
        } finally {
            canvasBmp.recycle()
        }
    }

    /** Renders a WhatsApp .was (zip with a Lottie animation). Returns null when the archive isn't Lottie. */
    fun lottie(sha: String, bytes: ByteArray): FrameResult? {
        val composition = LottieCompositionFactory.fromZipStreamSync(context, ZipInputStream(bytes.inputStream()), null).value
            ?: largestJson(bytes)?.let { LottieCompositionFactory.fromJsonStringSync(it, null).value }
            ?: return null
        val b = composition.bounds
        if (b.width() <= 0 || b.height() <= 0) return null
        val scale = min(1f, MAX_PX / max(b.width(), b.height()).toFloat())
        val w = scaled(b.width(), scale)
        val h = scaled(b.height(), scale)
        val drawable = LottieDrawable().apply { setComposition(composition) }
        val samples = LOTTIE_SAMPLES
        val kept = (0 until samples).map { k ->
            val p = k / (samples - 1f)
            drawable.progress = p
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.scale(w / b.width().toFloat(), h / b.height().toFloat())
            drawable.draw(c)
            Kept(k, (composition.duration * p).toLong(), bmp, signature(bmp))
        }
        val frames = save(sha, kept)
        // The grid thumbnail is the middle keyframe: Lottie animations often start empty.
        return FrameResult(frames, composition.duration.toLong(), frames.getOrNull(frames.size / 2)?.path)
    }

    private class Kept(val index: Int, val timestampMs: Long, val bitmap: Bitmap, val signature: FloatArray)

    private fun save(sha: String, kept: List<Kept>): List<SavedFrame> {
        val choices = KeyframeSelector.select(kept.map { it.signature })
        val dir = File(root, sha).apply { deleteRecursively(); mkdirs() }
        val chosen = choices.map { ch ->
            val k = kept[ch.index]
            val f = File(dir, "${k.index}.webp")
            f.outputStream().use { k.bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, it) }
            SavedFrame(k.index, k.timestampMs, f.absolutePath, ch.changeScore)
        }
        kept.forEach { it.bitmap.recycle() }
        return chosen
    }

    private fun scaled(v: Int, s: Float) = max(1, (v * s).toInt())

    private fun largestJson(bytes: ByteArray): String? = runCatching {
        ZipInputStream(bytes.inputStream()).use { zip ->
            var best: ByteArray? = null
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.name.endsWith(".json", ignoreCase = true)) {
                    val data = zip.readBytes()
                    if (best == null || data.size > best.size) best = data
                }
            }
            best?.toString(Charsets.UTF_8)
        }
    }.getOrNull()

    companion object {
        const val MAX_KEPT = 48
        const val MAX_PX = 256f
        const val LOTTIE_SAMPLES = 12
        private const val SIG = 32

        /** 32×32 grayscale; transparency maps to mid-grey so a shape on clear reads as change. */
        fun signature(bmp: Bitmap): FloatArray {
            val small = Bitmap.createScaledBitmap(bmp, SIG, SIG, true)
            val px = IntArray(SIG * SIG)
            small.getPixels(px, 0, SIG, 0, 0, SIG, SIG)
            if (small !== bmp) small.recycle()
            return FloatArray(px.size) { i ->
                val c = px[i]
                val a = Color.alpha(c) / 255f
                val luma = (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f
                a * luma + (1 - a) * 0.5f
            }
        }
    }
}
