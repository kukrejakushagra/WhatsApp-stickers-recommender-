package com.kukreja.stickersearch.index

import android.content.Context
import android.graphics.BitmapFactory
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieCompositionFactory
import com.kukreja.stickersearch.media.WasArchive

/** Loads a WhatsApp .was into a Lottie composition, or says exactly why it couldn't. Shared by indexing and preview. */
object WasRenderer {

    sealed interface Result {
        data class Loaded(val composition: LottieComposition, val source: String) : Result
        data class Failed(val reason: String) : Result
    }

    fun load(context: Context, bytes: ByteArray): Result {
        val content = WasArchive.open(bytes, context.cacheDir)
        if (content.jsonCandidates.isEmpty()) {
            return Result.Failed("no Lottie JSON inside: ${content.describe()}")
        }
        val errors = mutableListOf<String>()
        for ((name, json) in content.jsonCandidates) {
            val r = runCatching { LottieCompositionFactory.fromJsonStringSync(json, null) }
                .getOrElse { errors += "$name: ${it.javaClass.simpleName}: ${it.message}"; continue }
            val comp = r.value
            if (comp == null) {
                errors += "$name: ${r.exception?.javaClass?.simpleName}: ${r.exception?.message}"
                continue
            }
            if (comp.bounds.width() <= 0 || comp.bounds.height() <= 0) {
                errors += "$name: empty bounds"
                continue
            }
            attachImages(comp, content.images)
            return Result.Loaded(comp, name)
        }
        return Result.Failed("Lottie couldn't parse it (${errors.joinToString("; ").take(400)}); ${content.describe()}")
    }

    /** Images referenced by the animation but stored as separate files in the archive. */
    private fun attachImages(comp: LottieComposition, images: Map<String, ByteArray>) {
        if (images.isEmpty()) return
        comp.images.values.forEach { asset ->
            if (asset.bitmap != null) return@forEach
            val data = images[asset.fileName] ?: images[asset.fileName.substringAfterLast('/')] ?: return@forEach
            BitmapFactory.decodeByteArray(data, 0, data.size)?.let(asset::setBitmap)
        }
    }
}
