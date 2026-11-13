package app.stickersearch.search

import kotlin.math.ln

/**
 * Hybrid ranking over a few thousand stickers, entirely in memory:
 * meaning (text vectors), looks (image–text vectors) and keywords, fused with reciprocal rank
 * fusion so no score scale has to be tuned. Pure; unit-tested.
 */
object Ranker {

    data class Doc(
        val id: Long,
        /** Lowercased words of the effective annotation (description, text, emotions, intents, tags, phrases). */
        val words: Set<String>,
        /** Labels the user would not want when they say "not X" (emotions, intents, tags). */
        val labels: Set<String>,
        val lastUsedAt: Long?,
    )

    data class Hit(val id: Long, val score: Double, val matched: List<String>)

    const val RRF_K = 60.0

    /** Keyword score: idf-weighted matches, a word counts if a document word starts with it (e.g. "sarcas"). */
    fun keywordScores(terms: List<String>, docs: List<Doc>): Map<Long, Pair<Double, List<String>>> {
        if (terms.isEmpty() || docs.isEmpty()) return emptyMap()
        val n = docs.size.toDouble()
        val df = terms.associateWith { t -> docs.count { d -> d.words.any { it.startsWith(t) } }.coerceAtLeast(1) }
        val out = HashMap<Long, Pair<Double, List<String>>>()
        for (d in docs) {
            val matched = terms.filter { t -> d.words.any { it.startsWith(t) } }
            if (matched.isEmpty()) continue
            out[d.id] = matched.sumOf { ln(1 + n / df.getValue(it)) } to matched
        }
        return out
    }

    /**
     * Fuses ranked lists (best first) with weights. A document that matches what the user said to
     * avoid is pushed down, not removed, so a poorly labelled sticker can still be found.
     */
    fun fuse(
        lists: List<Pair<Double, List<Long>>>,
        docs: Map<Long, Doc>,
        avoid: List<String>,
        matched: Map<Long, List<String>>,
        now: Long,
        limit: Int = 60,
    ): List<Hit> {
        val score = HashMap<Long, Double>()
        for ((weight, ranked) in lists) {
            ranked.forEachIndexed { rank, id -> score[id] = (score[id] ?: 0.0) + weight / (RRF_K + rank + 1) }
        }
        val dayMs = 86_400_000.0
        return score.entries.map { (id, s) ->
            val d = docs[id]
            var v = s
            if (d != null && avoid.isNotEmpty() && avoid.any { a -> d.labels.any { it.startsWith(a) } }) v *= 0.35
            // A light nudge for stickers used recently: ties go to what you actually send.
            d?.lastUsedAt?.let { used -> v += 0.002 / (1 + (now - used) / dayMs) }
            Hit(id, v, matched[id].orEmpty())
        }.sortedByDescending { it.score }.take(limit)
    }

    /** Indices of the [k] largest values, best first. */
    fun topK(scores: FloatArray, k: Int, minScore: Float = Float.NEGATIVE_INFINITY): List<Int> =
        scores.indices.filter { scores[it] > minScore }.sortedByDescending { scores[it] }.take(k)
}
