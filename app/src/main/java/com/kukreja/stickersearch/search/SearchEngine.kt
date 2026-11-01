package com.kukreja.stickersearch.search

import android.content.Context
import com.kukreja.stickersearch.data.AnnotationRepository
import com.kukreja.stickersearch.data.AnnotationRepository.Companion.toCorrection
import com.kukreja.stickersearch.data.StickerDatabase
import com.kukreja.stickersearch.ml.ImageTextEmbedder
import com.kukreja.stickersearch.ml.ModelKind
import com.kukreja.stickersearch.ml.ModelStore
import com.kukreja.stickersearch.ml.TextEmbedder
import com.kukreja.stickersearch.ml.Vectors
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The search index: all vectors and keywords in memory (about 25 MB for 5,000 stickers), rebuilt
 * when annotations change. Query models load on first use and stay loaded while the app runs.
 */
class SearchEngine(private val context: Context) {

    data class Result(val hits: List<Ranker.Hit>, val millis: Long, val usedMeaning: Boolean, val usedLooks: Boolean)

    private val db = StickerDatabase.get(context)
    private val models = ModelStore(context)
    private val lock = Mutex()

    private var version = -1L
    private var ids = LongArray(0)
    private var textDim = 0
    private var textVecs = FloatArray(0)
    private var hasText = BooleanArray(0)
    private var imgDim = 0
    private var imgVecs = FloatArray(0)
    private var hasImg = BooleanArray(0)
    private var docs: List<Ranker.Doc> = emptyList()
    private var docById: Map<Long, Ranker.Doc> = emptyMap()

    private var text: TextEmbedder? = null
    private var imageText: ImageTextEmbedder? = null

    val size get() = ids.size

    suspend fun refresh(newVersion: Long) = lock.withLock {
        if (newVersion == version) return@withLock
        val rows = db.ai().searchDocs()
        val tags = db.ai().allTags().groupBy { it.stickerId }
        val corrections = db.ai().allCorrections().associateBy { it.stickerId }
        val n = rows.size
        val tVecs = rows.map { r -> r.embedding?.takeIf { it.isNotEmpty() }?.let(Vectors::fromBytes) }
        val iVecs = rows.map { r -> r.imageEmbedding?.takeIf { it.isNotEmpty() }?.let(Vectors::fromBytes) }
        textDim = tVecs.firstNotNullOfOrNull { it }?.size ?: 0
        imgDim = iVecs.firstNotNullOfOrNull { it }?.size ?: 0
        ids = LongArray(n) { rows[it].id }
        textVecs = FloatArray(n * textDim); hasText = BooleanArray(n)
        imgVecs = FloatArray(n * imgDim); hasImg = BooleanArray(n)
        for (i in 0 until n) {
            tVecs[i]?.takeIf { it.size == textDim }?.let { it.copyInto(textVecs, i * textDim); hasText[i] = true }
            iVecs[i]?.takeIf { it.size == imgDim }?.let { it.copyInto(imgVecs, i * imgDim); hasImg[i] = true }
        }
        docs = rows.map { r ->
            val modelTags = tags[r.id].orEmpty().filter { it.source != AnnotationRepository.SOURCE_EMOJI }.map { it.tag }
            val correction = corrections[r.id]?.toCorrection()
            val base = AnnotationRepository.fromRow(r.description, r.ocrText, r.emotions, r.intents, r.phrases, null, modelTags, null)
                ?: correction?.let { com.kukreja.stickersearch.ml.Annotation() }
            val a = base?.overriddenBy(correction)
            val labels = a?.let { (it.emotions + it.intents + it.tags).map(String::lowercase).toSet() }.orEmpty()
            val words = a?.searchText()?.lowercase()?.let { s -> WORDS.findAll(s).map { it.value }.toSet() + labels }.orEmpty()
            Ranker.Doc(r.id, words, labels, r.lastUsedAt)
        }
        docById = docs.associateBy { it.id }
        version = newVersion
    }

    suspend fun warmUp() = lock.withLock { loadModels() }

    private fun loadModels() {
        if (text == null && models.has(ModelKind.E5)) text = runCatching { TextEmbedder(context, models.file(ModelKind.E5)) }.getOrNull()
        if (imageText == null && models.has(ModelKind.SIGLIP_TEXT)) {
            imageText = runCatching { ImageTextEmbedder(context, models.file(ModelKind.SIGLIP_TEXT)) }.getOrNull()
        }
    }

    suspend fun search(query: String): Result = lock.withLock {
        val started = System.currentTimeMillis()
        loadModels()
        val q = QueryParser.parse(query)
        val lists = mutableListOf<Pair<Double, List<Long>>>()

        val tq = if (textDim > 0) text?.query(q.expanded) else null
        if (tq != null && tq.size == textDim) {
            val s = FloatArray(ids.size) { i -> if (hasText[i]) Vectors.dot(tq, textVecs, i * textDim) else Float.NEGATIVE_INFINITY }
            lists += 1.0 to Ranker.topK(s, 200, 0.70f).map { ids[it] } // e5 cosines for related text sit above ~0.75
        }
        // SigLIP text towers read short captions best.
        val iq = if (imgDim > 0) imageText?.embed("a sticker of ${q.expanded}") else null
        if (iq != null && iq.size == imgDim) {
            val s = FloatArray(ids.size) { i -> if (hasImg[i]) Vectors.dot(iq, imgVecs, i * imgDim) else Float.NEGATIVE_INFINITY }
            lists += 0.8 to Ranker.topK(s, 200).map { ids[it] }
        }
        val kw = Ranker.keywordScores(q.terms, docs)
        if (kw.isNotEmpty()) lists += 0.9 to kw.entries.sortedByDescending { it.value.first }.take(200).map { it.key }

        val hits = Ranker.fuse(lists, docById, q.avoid, kw.mapValues { it.value.second }, System.currentTimeMillis())
        Result(hits, System.currentTimeMillis() - started, tq != null, iq != null)
    }

    fun close() {
        text?.close(); text = null
        imageText?.close(); imageText = null
    }

    companion object {
        private val WORDS = Regex("[\\p{L}\\p{M}\\p{N}_]+")
    }
}
