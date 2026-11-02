package com.kukreja.stickersearch.index

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import com.kukreja.stickersearch.data.StickerDatabase
import com.kukreja.stickersearch.data.StickerEntity
import java.io.File

/** The pictures the AI is shown for a sticker. Shared by the on-phone passes and the PC export. */
object StickerImages {
    /** Keyframes for animated stickers (motion), the full-size original or thumbnail for static ones. */
    suspend fun load(ctx: Context, db: StickerDatabase, s: StickerEntity, preferOriginal: Boolean): List<Bitmap> {
        if (s.isAnimated) {
            val frames = db.frames().forSticker(s.id).mapNotNull { BitmapFactory.decodeFile(it.framePath) }
            if (frames.isNotEmpty()) return frames
        }
        if (preferOriginal && !s.isAnimated) {
            db.stickers().liveUri(s.id)?.let { uri ->
                runCatching {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, Uri.parse(uri))) { d, _, _ ->
                        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        d.setTargetSize(512, 512)
                    }
                }.getOrNull()?.let { return listOf(it) }
            }
        }
        return listOfNotNull(s.thumbnailPath?.takeIf { File(it).exists() }?.let(BitmapFactory::decodeFile))
    }
}
