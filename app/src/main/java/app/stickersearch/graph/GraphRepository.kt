package app.stickersearch.graph

import android.content.Context
import app.stickersearch.data.AnnotationRepository
import app.stickersearch.data.AnnotationRepository.Companion.toCorrection
import app.stickersearch.data.StickerDatabase
import app.stickersearch.data.StickerEdgeEntity
import app.stickersearch.ml.Vectors

/** Rebuilds the stored sticker graph when its inputs changed (vectors, tags, corrections). */
class GraphRepository(context: Context) {
    private val db = StickerDatabase.get(context)
    private val prefs = context.getSharedPreferences("graph", Context.MODE_PRIVATE)

    data class Stats(val story: Int, val looks: Int, val topic: Int, val millis: Long)

    /** A cheap fingerprint of everything the graph depends on. */
    private suspend fun inputVersion(): String {
        val c = db.ai().observeVersionOnce()
        val fv = db.graph().frameVectorCount()
        val tags = db.ai().tagCount()
        return "$c/$fv/$tags"
    }

    suspend fun rebuildIfStale(force: Boolean = false): Stats? {
        val v = inputVersion()
        if (!force && prefs.getString("built_for", null) == v) return null
        val started = System.currentTimeMillis()
        val rows = db.graph().nodes()
        val frames = db.graph().frameVectors().groupBy { it.stickerId }
        val tags = db.ai().allTags().filter { it.source != AnnotationRepository.SOURCE_EMOJI }.groupBy { it.stickerId }
        val corrections = db.ai().allCorrections().associate { it.stickerId to it.toCorrection() }
        val nodes = rows.map { r ->
            val f = frames[r.id].orEmpty()
            val img = r.imageEmbedding?.takeIf { it.isNotEmpty() }?.let(Vectors::fromBytes)
            val tagSet = (corrections[r.id]?.tags ?: tags[r.id].orEmpty().map { it.tag }).map { it.lowercase().trim() }.toSet()
            GraphBuilder.Node(
                id = r.id, image = img,
                text = r.embedding?.takeIf { it.isNotEmpty() }?.let(Vectors::fromBytes),
                first = f.firstOrNull { it.which == "first" }?.vector?.let(Vectors::fromBytes),
                last = f.firstOrNull { it.which == "last" }?.vector?.let(Vectors::fromBytes),
                tags = tagSet, receivedAt = r.newest, fileSeq = GraphBuilder.fileSeq(r.path),
            )
        }
        val edges = GraphBuilder.build(nodes)
        db.graph().replaceAll(edges.map { StickerEdgeEntity(it.a, it.b, it.type, it.weight, it.label) })
        prefs.edit().putString("built_for", v).putLong("built_at", System.currentTimeMillis()).apply()
        return Stats(
            edges.count { it.type == app.stickersearch.data.EdgeType.STORY },
            edges.count { it.type == app.stickersearch.data.EdgeType.LOOKS },
            edges.count { it.type == app.stickersearch.data.EdgeType.TOPIC },
            System.currentTimeMillis() - started,
        ).also { prefs.edit().putLong("build_ms", it.millis).apply() }
    }

    suspend fun load(): StickerGraph = StickerGraph(db.graph().edges())
}
