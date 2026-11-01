package com.kukreja.stickersearch.graph

import com.kukreja.stickersearch.data.EdgeType
import com.kukreja.stickersearch.data.StickerEdgeEntity

/**
 * The graph in memory, for retrieval: a sticker's story (ordered), look-alikes and topic neighbours,
 * and expansion of search results through their strongest links. Pure; unit-tested.
 */
class StickerGraph(edges: List<StickerEdgeEntity>) {

    data class Link(val other: Long, val type: EdgeType, val weight: Float, val label: String?)

    data class Related(
        /** The whole story this sticker belongs to, in order; empty if it isn't part of one. */
        val story: List<Long>,
        val looks: List<Long>,
        /** Theme label → stickers sharing it. */
        val topics: List<Pair<String, List<Long>>>,
    ) {
        val isEmpty get() = story.isEmpty() && looks.isEmpty() && topics.isEmpty()
    }

    private val next = HashMap<Long, Long>()
    private val prev = HashMap<Long, Long>()
    private val adj = HashMap<Long, MutableList<Link>>()

    init {
        for (e in edges) {
            if (e.type == EdgeType.STORY) { next[e.a] = e.b; prev[e.b] = e.a }
            adj.getOrPut(e.a) { mutableListOf() } += Link(e.b, e.type, e.weight, e.label)
            adj.getOrPut(e.b) { mutableListOf() } += Link(e.a, e.type, e.weight, e.label)
        }
    }

    val size get() = adj.size

    fun links(id: Long): List<Link> = adj[id].orEmpty()

    /** Follows STORY links back to the start, then forward; guards against cycles. */
    fun story(id: Long, max: Int = 12): List<Long> {
        if (next[id] == null && prev[id] == null) return emptyList()
        var start = id
        val seen = hashSetOf(id)
        while (true) { val p = prev[start] ?: break; if (!seen.add(p) || seen.size > max) break; start = p }
        val out = mutableListOf(start)
        val walked = hashSetOf(start)
        var cur = start
        while (out.size < max) { val n = next[cur] ?: break; if (!walked.add(n)) break; out += n; cur = n }
        return out
    }

    fun related(id: Long): Related {
        val story = story(id)
        val inStory = story.toSet()
        val l = links(id).filter { it.other !in inStory }
        val looks = l.filter { it.type == EdgeType.LOOKS }.sortedByDescending { it.weight }.map { it.other }.distinct().take(12)
        val topics = l.filter { it.type == EdgeType.TOPIC && it.other !in looks }
            .groupBy { it.label ?: "related" }
            .map { (label, ls) -> label to ls.sortedByDescending { it.weight }.map { it.other }.take(10) }
            .sortedByDescending { it.second.size }.take(3)
        return Related(story, looks, topics)
    }

    /**
     * Graph-expanded retrieval: neighbours of the top hits, scored by the hit's rank and the link's
     * kind and strength (a story continuation counts most). Returns ids with the reason for each.
     */
    fun expand(seeds: List<Long>, exclude: Set<Long>, limit: Int = 12): List<Pair<Long, String>> {
        val score = HashMap<Long, Float>()
        val why = HashMap<Long, String>()
        seeds.take(8).forEachIndexed { rank, s ->
            val seedWeight = 1f / (1 + rank)
            for (lk in links(s)) {
                if (lk.other in exclude) continue
                val w = seedWeight * lk.weight * when (lk.type) { EdgeType.STORY -> 1.0f; EdgeType.LOOKS -> 0.6f; EdgeType.TOPIC -> 0.5f }
                if (w > (score[lk.other] ?: 0f)) why[lk.other] = reason(lk)
                score[lk.other] = (score[lk.other] ?: 0f) + w
            }
        }
        return score.entries.sortedByDescending { it.value }.take(limit).map { it.key to why.getValue(it.key) }
    }

    private fun reason(l: Link) = when (l.type) {
        EdgeType.STORY -> "same story"
        EdgeType.LOOKS -> "looks alike"
        EdgeType.TOPIC -> l.label ?: "same topic"
    }
}
