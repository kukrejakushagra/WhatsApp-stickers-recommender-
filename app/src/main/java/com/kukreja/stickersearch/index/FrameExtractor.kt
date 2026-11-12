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
import com.airbnb.lottie.LottieDrawable
import com.kukreja.stickersearch.media.WebpAnim
import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

data class SavedFrame(val index: Int, val timestampMs: Long, val path: String, val changeScore: Float)

/** Keyframes plus a thumbnail; [error] explains an empty result. */
data class FrameResult(
    val frames: List<SavedFrame>,
    val durationMs: Long?,
    val thumbnailPath: String? = null,
    val error: String? = null,
)

/**
 * Extracts keyframes from animated stickers without touching the original file:
 * - animated WebP: each ANMF frame is re-wrapped as a still WebP, decoded with BitmapFactory
 *   (at half size for 512px stickers) and composited per the blend/dispose rules;
 * - Lottie (.was): rendered at evenly spaced progress points via [WasRenderer].
 * [KeyframeSelector] then picks 3–6 frames, saved under frames/<sha>/, plus a thumbnail.
 */
class FrameExtractor(private val context: Context, private val root: File, private val thumbs: File) {

    init { thumbs.mkdirs() }

    fun webp(sha: String, bytes: ByteArray): FrameResult? {
        val anim = WebpAnim.parse(bytes) ?: return null
        val cw = anim.canvasWidth.takeIf { it > 0 } ?: anim.frames.maxOf { it.x + it.width }
        val ch = anim.canvasHeight.takeIf { it > 0 } ?: anim.frames.maxOf { it.y + it.height }
        val n = anim.frames.size
        val keepEvery = max(1, ceil(n / MAX_KEPT.toDouble()).toInt())
        // Half-size decode for big canvases: ~4x fewer pixels; output is ≤256px anyway.
        val sample = if (max(cw, ch) > MAX_PX * 1.5f) 2 else 1
        val w = max(1, cw / sample)
        val h = max(1, ch / sample)
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val canvasBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBmp)
        val clear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        val overwrite = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
        val blend = Paint(Paint.FILTER_BITMAP_FLAG)
        val scale = min(1f, MAX_PX / max(w, h).toFloat())
        val kept = mutableListOf<Kept>()
        var t = 0L
        var decoded = 0
        try {
            anim.frames.forEachIndexed { i, f ->
                val rect = Rect(f.x / sample, f.y / sample, (f.x + f.width) / sample, (f.y + f.height) / sample)
                val still = WebpAnim.standaloneFrame(f)?.let { BitmapFactory.decodeByteArray(it, 0, it.size, opts) }
                if (still != null) {
                    decoded++
                    canvas.drawBitmap(still, null, rect, if (f.blend) blend else overwrite)
                    still.recycle()
                }
                if (i % keepEvery == 0 || i == n - 1) {
                    val copy = if (scale < 1f) Bitmap.createScaledBitmap(canvasBmp, scaled(w, scale), scaled(h, scale), true)
                    else canvasBmp.copy(Bitmap.Config.ARGB_8888, false)
                    kept += Kept(i, t, copy, signature(copy))
                }
                if (f.disposeToBackground) canvas.drawRect(rect, clear)
                t += f.durationMs
            }
            if (decoded == 0) {
                kept.forEach { it.bitmap.recycle() }
                return FrameResult(emptyList(), t, error = "no frame could be decoded")
            }
            return save(sha, kept, t, thumbnailFromFirst = true)
        } finally {
            canvasBmp.recycle()
        }
    }

    /** Renders a WhatsApp .was. On failure the result carries the exact reason, for the report. */
    fun lottie(sha: String, bytes: ByteArray): FrameResult {
        val loaded = when (val r = WasRenderer.load(context, bytes)) {
            is WasRenderer.Result.Failed -> return FrameResult(emptyList(), null, error = r.reason)
            is WasRenderer.Result.Loaded -> r
        }
        val composition = loaded.composition
        val b = composition.bounds
        val scale = min(1f, MAX_PX / max(b.width(), b.height()).toFloat())
        val w = scaled(b.width(), scale)
        val h = scaled(b.height(), scale)
        val drawable = LottieDrawable().apply { setComposition(composition) }
        val kept = (0 until LOTTIE_SAMPLES).map { k ->
            val p = k / (LOTTIE_SAMPLES - 1f)
            drawable.progress = p
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.scale(w / b.width().toFloat(), h / b.height().toFloat())
            drawable.draw(c)
            Kept(k, (composition.duration * p).toLong(), bmp, signature(bmp))
        }
        if (kept.all { KeyframeSelector.isBlank(it.signature) }) {
            kept.forEach { it.bitmap.recycle() }
            return FrameResult(emptyList(), composition.duration.toLong(),
                error = "the animation (${loaded.source}) renders blank; it may need fonts or images that aren't in the file")
        }
        // Lottie animations often start empty, so the thumbnail is the middle keyframe.
        return save(sha, kept, composition.duration.toLong(), thumbnailFromFirst = false)
    }

    private class Kept(val index: Int, val timestampMs: Long, val bitmap: Bitmap, val signature: FloatArray)

    private fun save(sha: String, kept: List<Kept>, durationMs: Long, thumbnailFromFirst: Boolean): FrameResult {
        val choices = KeyframeSelector.select(kept.map { it.signature })
        val dir = File(root, sha).apply { deleteRecursively(); mkdirs() }
        val frames = choices.map { ch ->
            val k = kept[ch.index]
            val f = File(dir, "${k.index}.webp")
            f.outputStream().use { k.bitmap.compress(com.kukreja.stickersearch.compat.Compat.webpLossy, 85, it) }
            SavedFrame(k.index, k.timestampMs, f.absolutePath, ch.changeScore)
        }
        // Thumbnail: the first meaningful keyframe (WebP) or the middle one (Lottie), copied to thumbs/.
        val pick = if (thumbnailFromFirst) frames.firstOrNull() else frames.getOrNull(frames.size / 2)
        val thumb = pick?.let { p -> File(thumbs, "$sha.webp").also { File(p.path).copyTo(it, overwrite = true) }.absolutePath }
        kept.forEach { it.bitmap.recycle() }
        return FrameResult(frames, durationMs, thumb)
    }

    private fun scaled(v: Int, s: Float) = max(1, (v * s).toInt())

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
