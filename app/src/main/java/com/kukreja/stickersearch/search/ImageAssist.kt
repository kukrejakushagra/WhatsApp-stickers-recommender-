package com.kukreja.stickersearch.search

import android.content.Context
import android.graphics.Bitmap
import com.kukreja.stickersearch.data.StickerDatabase
import com.kukreja.stickersearch.media.Hasher
import com.kukreja.stickersearch.ml.GemmaHolder
import com.kukreja.stickersearch.ml.ImageEmbedder
import com.kukreja.stickersearch.ml.ModelKind
import com.kukreja.stickersearch.ml.ModelStore
import com.kukreja.stickersearch.ml.ReplyPlan

/**
 * "Help me reply": from chat screenshots or images, plus optional text, it
 *  1. finds library stickers that appear in the images (exact file, then visual match of crops), and
 *  2. asks Gemma what's going on and which reaction fits, then searches for those reactions.
 * Images stay in memory; nothing is saved or sent anywhere.
 */
class ImageAssist(private val context: Context, private val engine: SearchEngine) {

    data class Result(
        val plan: ReplyPlan?,
        val suggestions: List<Ranker.Hit>,
        val found: List<Pair<Long, Float>>,
        val millis: Long,
        val note: String?,
    )

    private val models = ModelStore(context)
    private val db = StickerDatabase.get(context)

    suspend fun run(images: List<Pair<Bitmap, ByteArray>>, text: String, onPhase: (String) -> Unit): Result {
        val started = System.currentTimeMillis()
        val notes = mutableListOf<String>()

        // 1) Stickers in the images: an attached file that *is* a library sticker, then crops.
        onPhase("Looking for your stickers in the images")
        val found = LinkedHashMap<Long, Float>()
        images.forEach { (_, bytes) -> db.stickers().bySha(Hasher.sha256(bytes))?.let { found[it.id] = 1f } }
        if (images.isNotEmpty() && models.has(ModelKind.SIGLIP_VISION)) {
            ImageEmbedder(models.file(ModelKind.SIGLIP_VISION)).use { e ->
                images.forEach { (bmp, _) ->
                    val vecs = mutableListOf(e.embed(bmp))
                    ImageMatch.crops(bmp.width, bmp.height).forEach { c ->
                        val crop = Bitmap.createBitmap(bmp, c.x, c.y, c.size, c.size)
                        vecs += e.embed(crop)
                        crop.recycle()
                    }
                    engine.matchCrops(vecs).forEach { (id, sim) -> if (sim > (found[id] ?: 0f)) found[id] = sim }
                }
            }
        } else if (images.isNotEmpty()) {
            notes += "Import the SigLIP2 image model to find stickers inside images."
        }

        // 2) What to reply: Gemma reads the conversation; without it, the typed text is the query.
        var plan: ReplyPlan? = null
        if (models.has(ModelKind.GEMMA) && (images.isNotEmpty() || text.isNotBlank())) {
            onPhase("Reading the conversation")
            val r = runCatching { GemmaHolder.use(context) { g -> g.planReply(images.map { it.first }, text) } }
            plan = r.getOrNull()?.first
            if (plan == null) {
                notes += r.exceptionOrNull()?.let { "Gemma couldn't read it: ${it.message}" }
                    ?: "Gemma's answer wasn't readable, so only your text was searched."
            }
        } else if (images.isNotEmpty()) {
            notes += "Import Gemma to get reply suggestions from screenshots."
        }
        onPhase("Finding stickers")
        val queries = buildList {
            plan?.queries?.let(::addAll)
            if (text.isNotBlank()) add(text)
        }
        val suggestions = if (queries.isEmpty()) emptyList() else engine.searchMany(queries, plan?.avoid.orEmpty())
        val foundList = found.entries.sortedByDescending { it.value }.map { it.key to it.value }
        return Result(plan, suggestions.filter { h -> found[h.id] == null }, foundList, System.currentTimeMillis() - started,
            notes.joinToString(" ").ifBlank { null })
    }
}
