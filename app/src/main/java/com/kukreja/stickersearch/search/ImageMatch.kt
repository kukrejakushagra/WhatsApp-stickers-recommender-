package com.kukreja.stickersearch.search

/** Pure helpers for finding library stickers inside images; unit-tested. */
object ImageMatch {
    /** Same-sticker SigLIP2 image similarity is ~0.95+; different stickers rarely pass 0.88. */
    const val FLOOR = 0.88f
    /** Keep near-ties with the best match (the same sticker in two packs, or a crop between two). */
    const val MARGIN = 0.04f

    data class Crop(val x: Int, val y: Int, val size: Int)

    /** The whole image (as one square-ish view) plus overlapping squares at three scales. */
    fun crops(width: Int, height: Int): List<Crop> {
        val out = mutableListOf<Crop>()
        val short = minOf(width, height)
        for (frac in floatArrayOf(0.6f, 0.45f, 0.33f)) {
            val s = (short * frac).toInt().coerceAtLeast(64)
            val step = (s * 0.6f).toInt().coerceAtLeast(16)
            var y = 0
            while (y + s <= height) {
                var x = 0
                while (x + s <= width) { out += Crop(x, y, s); x += step }
                y += step
            }
        }
        return out
    }

    fun pick(ids: LongArray, best: FloatArray, limit: Int = 8): List<Pair<Long, Float>> {
        val top = best.maxOrNull() ?: return emptyList()
        val cut = maxOf(FLOOR, top - MARGIN)
        return best.indices.filter { best[it] >= cut }.sortedByDescending { best[it] }.take(limit).map { ids[it] to best[it] }
    }
}
