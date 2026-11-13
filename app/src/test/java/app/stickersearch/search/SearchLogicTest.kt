package app.stickersearch.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchLogicTest {

    @Test fun negationInEnglishAndHindiOrder() {
        val p = QueryParser.parse("I want something sarcastic but not offensive")
        assertTrue(p.terms.contains("sarcastic"))
        assertTrue(p.terms.contains("sarcasm")) // synonym bridge to the annotation vocabulary
        assertTrue(p.avoid.contains("offensive"))
        assertTrue("offensive must not be a positive term", "offensive" !in p.terms)
        assertTrue("models don't see the negated word", !p.expanded.contains("offensive"))

        val h = QueryParser.parse("gussa nahi, mazak wala")
        assertTrue(h.avoid.contains("gussa"))
        assertTrue(h.avoid.contains("angry"))
        assertTrue(h.terms.contains("mazak"))
        assertTrue(h.terms.contains("joke"))
    }

    @Test fun hinglishIsGlossedForTheModels() {
        val p = QueryParser.parse("kya bakwas hai yaar")
        assertTrue(p.expanded.contains("nonsense"))
        assertTrue(p.terms.containsAll(listOf("bakwas", "nonsense", "yaar", "friend")))
        assertTrue("stop words dropped", "hai" !in p.terms)
    }

    private fun doc(id: Long, text: String, labels: Set<String> = emptySet(), used: Long? = null) =
        Ranker.Doc(id, text.split(' ').toSet() + labels, labels, used)

    @Test fun keywordsPreferRareMatchesAndPrefixes() {
        val docs = listOf(
            doc(1, "cat sarcastic smirk"), doc(2, "cat happy"), doc(3, "dog happy"), doc(4, "cat sleeping"),
        )
        val kw = Ranker.keywordScores(listOf("sarcas", "cat"), docs)
        val best = kw.entries.maxByOrNull { it.value.first }!!.key
        assertEquals(1L, best)
        assertEquals(listOf("sarcas", "cat"), kw.getValue(1).second)
        assertTrue(3L !in kw)
    }

    @Test fun fusionCombinesListsAndPenalisesAvoided() {
        val docs = listOf(doc(1, "a", setOf("sarcasm")), doc(2, "b", setOf("sarcasm", "insulting")), doc(3, "c")).associateBy { it.id }
        // Both lists put 2 first; but the user said "not insulting".
        val hits = Ranker.fuse(listOf(1.0 to listOf(2L, 1L, 3L), 1.0 to listOf(2L, 3L, 1L)), docs, listOf("insult"), emptyMap(), 0L)
        assertEquals(1L, hits.first().id)
        assertEquals(3, hits.size)
    }

    @Test fun agreementAcrossSignalsBeatsOneStrongSignal() {
        val docs = (1L..3L).associateWith { doc(it, "x") }
        val hits = Ranker.fuse(listOf(1.0 to listOf(1L, 2L), 1.0 to listOf(3L, 2L), 1.0 to listOf(2L)), docs, emptyList(), emptyMap(), 0L)
        assertEquals(2L, hits.first().id)
    }

    @Test fun topK() {
        assertEquals(listOf(2, 0), Ranker.topK(floatArrayOf(0.5f, 0.1f, 0.9f), 2))
        assertEquals(listOf(2), Ranker.topK(floatArrayOf(0.5f, 0.1f, 0.9f), 5, minScore = 0.6f))
    }
}
