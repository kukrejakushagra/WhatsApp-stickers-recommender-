package app.stickersearch.index

import android.content.Context
import android.graphics.BitmapFactory
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieCompositionFactory
import app.stickersearch.media.WasArchive

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

    /**
     * Decodes every image the animation uses, up front: pictures embedded in the JSON as
     * `data:image/...;base64,` and pictures stored as separate files in the archive.
     *
     * Lottie only decodes embedded pictures itself when it has a Context, which a LottieDrawable
     * drawn onto a bitmap (indexing) doesn't have. Without this, stickers made of pictures render
     * blank when indexed even though they play fine in the app.
     */
    private fun attachImages(comp: LottieComposition, images: Map<String, ByteArray>) {
        comp.images.values.forEach { asset ->
            if (asset.bitmap != null) return@forEach
            val data = dataUriBytes(asset.fileName)
                ?: images[asset.fileName] ?: images[asset.fileName.substringAfterLast('/')] ?: return@forEach
            val bmp = runCatching { BitmapFactory.decodeByteArray(data, 0, data.size) }.getOrNull() ?: return@forEach
            // Same sizing Lottie applies to the pictures it decodes itself.
            val w = asset.width; val h = asset.height
            asset.bitmap = if (w > 0 && h > 0 && (bmp.width != w || bmp.height != h))
                android.graphics.Bitmap.createScaledBitmap(bmp, w, h, true).also { if (it !== bmp) bmp.recycle() } else bmp
        }
    }

    /** The bytes of a `data:...;base64,` image, or null for a file name or broken base64. */
    fun dataUriBytes(fileName: String?): ByteArray? {
        if (fileName == null || !fileName.startsWith("data:")) return null
        val i = fileName.indexOf("base64,").takeIf { it > 0 } ?: return null
        return runCatching { java.util.Base64.getMimeDecoder().decode(fileName.substring(i + 7)) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }
}
