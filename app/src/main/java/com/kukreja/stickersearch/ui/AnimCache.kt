package com.kukreja.stickersearch.ui

import android.content.Context
import android.net.Uri
import android.util.LruCache
import com.airbnb.lottie.LottieComposition
import com.kukreja.stickersearch.data.StickerRow
import com.kukreja.stickersearch.index.WasRenderer
import com.kukreja.stickersearch.media.SizeCappedDir
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

/**
 * What the grid needs to animate a sticker, cached so scrolling back (or reopening the app) costs
 * little: the file's bytes in memory and on disk (no trip to WhatsApp's folder), and parsed Lottie
 * animations in memory. At most 4 loads run at once, so a screenful doesn't swamp the phone.
 */
object AnimCache {
    private const val DISK_CAP = 150L * 1024 * 1024

    private val bytes = object : LruCache<String, ByteArray>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }
    private val lottie = LruCache<String, LottieComposition>(60)
    private val loads = Semaphore(4)
    @Volatile private var disk: SizeCappedDir? = null

    fun dir(ctx: Context) = File(ctx.cacheDir, "anim")

    private fun disk(ctx: Context) = disk ?: synchronized(this) {
        disk ?: SizeCappedDir(dir(ctx), DISK_CAP).also { disk = it }
    }

    /** The sticker file: memory, then the disk cache, then the original (copied into the cache). */
    private fun fileBytes(ctx: Context, s: StickerRow): ByteArray? {
        val key = s.sha256 + if (s.isLottie()) ".was" else ".webp"
        bytes.get(key)?.let { return it }
        val cached = disk(ctx).get(key)
        val b = cached ?: s.primaryUri?.let { u ->
            runCatching { ctx.contentResolver.openInputStream(Uri.parse(u))?.use { it.readBytes() } }.getOrNull()
        } ?: return null
        if (cached == null) disk(ctx).put(key, b)
        bytes.put(key, b)
        return b
    }

    /** Bytes of an animated WebP, ready for ImageDecoder. */
    suspend fun webp(ctx: Context, s: StickerRow): ByteArray? = loads.withPermit { fileBytes(ctx, s) }

    /** A parsed Lottie animation for a .was sticker. */
    suspend fun lottie(ctx: Context, s: StickerRow): LottieComposition? = loads.withPermit {
        lottie.get(s.sha256)?.let { return@withPermit it }
        val b = fileBytes(ctx, s) ?: return@withPermit null
        val r = runCatching { WasRenderer.load(ctx, b) }.getOrNull()
        (r as? WasRenderer.Result.Loaded)?.composition?.also { lottie.put(s.sha256, it) }
    }

    fun clearMemory() { bytes.evictAll(); lottie.evictAll() }

    fun diskSize(ctx: Context) = disk(ctx).size()
}
