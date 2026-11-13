package app.stickersearch.graph

import app.stickersearch.data.EdgeType
import kotlin.math.abs
import kotlin.math.ln

/**
 * Builds the sticker graph from vectors and tags. Pure; unit-tested.
 *  - STORY: sticker B continues sticker A when A's last keyframe and B's first keyframe are near-identical
 *    images (clips cut from one video). Each sticker keeps at most one best successor and predecessor.
 *    Being received together, or WhatsApp file names in sequence (STK-…-WA0007 → WA0008), adds weight.
 *  - LOOKS: nearest neighbours in image space (same character or artwork).
 *  - TOPIC: a shared, specific tag (e.g. "driving") between stickers whose meanings are also close.
 */
object GraphBuilder {

    data class Node(
        val id: Long,
        val image: FloatArray?,
        val text: FloatArray?,
        val first: FloatArray?,
        val last: FloatArray?,
        val tags: Set<String>,
        val receivedAt: Long?,
        /** Parsed from WhatsApp names like STK-20260207-WA0007: (20260207, 7). */
        val fileSeq: Pair<Long, Int>?,
    )

    data class Edge(val a: Long, val b: Long, val type: EdgeType, val weight: Float, val label: String? = null)

    const val STORY_MIN = 0.90f
    const val STORY_SCENE_MIN = 0.70f
    const val LOOKS_MIN = 0.86f
    const val LOOKS_K = 6
    const val TOPIC_TEXT_MIN = 0.82f
    const val TOPIC_PER_NODE = 8

    /** Tags too broad to say two stickers share a theme. */
    val GENERIC = setOf(
        "sticker", "stickers", "cartoon", "funny", "face", "faces", "character", "text", "meme", "emoji", "man", "woman",
        "person", "people", "boy", "girl", "animal", "cute", "reaction", "happy", "sad", "angry", "laugh", "laughing",
        "smile", "smiling", "expression", "emotion", "animated", "animation", "gif", "white", "black", "background",
        "illustration", "drawing", "comic", "image", "picture", "photo", "lol", "love", "fun", "humor", "humour", "joke",
    )

    private val FILE_SEQ = Regex("""STK-(\d{8})-WA(\d+)""", RegexOption.IGNORE_CASE)

    fun fileSeq(path: String?): Pair<Long, Int>? =
        path?.let { FILE_SEQ.find(it) }?.let { it.groupValues[1].toLong() to it.groupValues[2].toInt() }

    fun build(nodes: List<Node>): List<Edge> {
        val story = story(nodes)
        val storyPairs = story.map { key(it.a, it.b) }.toHashSet()
        val looks = looks(nodes).filter { key(it.a, it.b) !in storyPairs }
        val looksPairs = looks.map { key(it.a, it.b) }.toHashSet()
        val topics = topics(nodes).filter { key(it.a, it.b) !in storyPairs && key(it.a, it.b) !in looksPairs }
        return story + looks + topics
    }

    private fun key(a: Long, b: Long) = if (a < b) a to b else b to a

    fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    fun story(nodes: List<Node>): List<Edge> {
        val cands = nodes.filter { it.first != null && it.last != null && it.first.size == it.last.size }
        if (cands.size < 2) return emptyList()
        // Best successor of each sticker, and best predecessor of each, so chains don't fan out.
        val bestNext = HashMap<Long, Pair<Long, Float>>()
        val bestPrev = HashMap<Long, Pair<Long, Float>>()
        for (a in cands) for (b in cands) {
            if (a.id == b.id) continue
            var s = dot(a.last!!, b.first!!)
            if (s < STORY_MIN - 0.04f) continue
            // The rest of the two clips should share the scene, not just one transition frame.
            if (a.image != null && b.image != null && a.image.size == b.image.size && dot(a.image, b.image) < STORY_SCENE_MIN) continue
            // Reversed direction fits better: let that pair be found the other way round.
            if (dot(b.last!!, a.first!!) > s) continue
            s += bonus(a, b)
            if (s < STORY_MIN) continue
            if (s > (bestNext[a.id]?.second ?: 0f)) bestNext[a.id] = b.id to s
            if (s > (bestPrev[b.id]?.second ?: 0f)) bestPrev[b.id] = a.id to s
        }
        return bestNext.mapNotNull { (a, next) ->
            val (b, s) = next
            if (bestPrev[b]?.first == a) Edge(a, b, EdgeType.STORY, s.coerceAtMost(1.2f)) else null
        }
    }

    /** Received within minutes of each other, or consecutive WhatsApp file numbers on the same day. */
    private fun bonus(a: Node, b: Node): Float {
        var x = 0f
        if (a.receivedAt != null && b.receivedAt != null && abs(a.receivedAt - b.receivedAt) < 10 * 60_000) x += 0.02f
        val fa = a.fileSeq; val fb = b.fileSeq
        if (fa != null && fb != null && fa.first == fb.first && fb.second - fa.second in 1..2) x += 0.03f
        return x
    }

    fun looks(nodes: List<Node>): List<Edge> {
        val withImg = nodes.filter { it.image != null && it.image.isNotEmpty() }
        val out = HashMap<Pair<Long, Long>, Edge>()
        for (a in withImg) {
            val near = withImg.asSequence().filter { it.id != a.id && it.image!!.size == a.image!!.size }
                .map { it to dot(a.image!!, it.image!!) }
                .filter { it.second >= LOOKS_MIN && it.second < 0.995f } // ~1.0 is the same image twice
                .sortedByDescending { it.second }.take(LOOKS_K).toList()
            near.forEach { (b, s) ->
                val k = key(a.id, b.id)
                if (out[k] == null || out.getValue(k).weight < s) out[k] = Edge(k.first, k.second, EdgeType.LOOKS, s)
            }
        }
        return out.values.toList()
    }

    fun topics(nodes: List<Node>): List<Edge> {
        val n = nodes.size
        if (n < 3) return emptyList()
        val df = HashMap<String, Int>()
        nodes.forEach { nd -> nd.tags.forEach { t -> df[t] = (df[t] ?: 0) + 1 } }
        // Specific themes: shared by at least two stickers, by at most ~3% of the library, not generic.
        val maxDf = maxOf(3, (n * 0.03).toInt())
        val themes = df.filter { (t, c) -> c in 2..maxDf && t !in GENERIC && t.length >= 3 }.keys
        val byTag = HashMap<String, MutableList<Node>>()
        nodes.forEach { nd -> nd.tags.filter { it in themes }.forEach { t -> byTag.getOrPut(t) { mutableListOf() } += nd } }
        val scored = HashMap<Pair<Long, Long>, Pair<Float, MutableSet<String>>>()
        for ((tag, group) in byTag) {
            val idf = ln(n.toFloat() / df.getValue(tag)).toFloat()
            for (i in group.indices) for (j in i + 1 until group.size) {
                val a = group[i]; val b = group[j]
                val sim = if (a.text != null && b.text != null && a.text.size == b.text.size) dot(a.text, b.text) else null
                if (sim != null && sim < TOPIC_TEXT_MIN) continue
                val k = key(a.id, b.id)
                val cur = scored.getOrPut(k) { 0f to mutableSetOf() }
                cur.second += tag
                scored[k] = (cur.first + idf * (sim ?: 0.85f)) to cur.second
            }
        }
        // Keep each sticker's strongest few topic links.
        val perNode = HashMap<Long, MutableList<Pair<Pair<Long, Long>, Float>>>()
        scored.forEach { (k, v) ->
            perNode.getOrPut(k.first) { mutableListOf() } += k to v.first
            perNode.getOrPut(k.second) { mutableListOf() } += k to v.first
        }
        val keep = HashSet<Pair<Long, Long>>()
        perNode.values.forEach { l -> l.sortedByDescending { it.second }.take(TOPIC_PER_NODE).forEach { keep += it.first } }
        val maxScore = scored.values.maxOfOrNull { it.first } ?: 1f
        return keep.map { k ->
            val (score, tags) = scored.getValue(k)
            Edge(k.first, k.second, EdgeType.TOPIC, (score / maxScore).coerceIn(0.05f, 1f), tags.sorted().take(3).joinToString(", "))
        }
    }
}
