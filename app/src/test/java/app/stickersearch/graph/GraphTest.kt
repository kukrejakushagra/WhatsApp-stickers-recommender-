package app.stickersearch.graph

import app.stickersearch.data.EdgeType
import app.stickersearch.data.StickerEdgeEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class GraphTest {

    /** Unit vector mostly along axis [i], with a little of axis [j]: near-identical for close (i, j). */
    private fun v(i: Int, j: Int = i, mix: Float = 0f, dim: Int = 16): FloatArray {
        val a = FloatArray(dim); a[i] = 1f; a[j] += mix
        val n = sqrt(a.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(dim) { a[it] / n }
    }

    private fun node(id: Long, image: FloatArray? = null, text: FloatArray? = null, first: FloatArray? = null,
                     last: FloatArray? = null, tags: Set<String> = emptySet(), path: String? = null, at: Long? = null) =
        GraphBuilder.Node(id, image, text, first, last, tags, at, GraphBuilder.fileSeq(path))

    @Test fun storyChainsClipsCutFromOneVideo() {
        // One video in three clips: frames 0→1, 1→2, 2→3 (axes stand for distinct frames).
        val scene = v(10, 11, 0.3f)
        val clips = listOf(
            node(1, scene, first = v(0), last = v(1), path = "STK-20260207-WA0007.webp"),
            node(2, scene, first = v(1), last = v(2), path = "STK-20260207-WA0008.webp"),
            node(3, scene, first = v(2), last = v(3), path = "STK-20260207-WA0009.webp"),
            node(4, v(12), first = v(5), last = v(6)), // unrelated
        )
        val story = GraphBuilder.story(clips)
        assertEquals(setOf(1L to 2L, 2L to 3L), story.map { it.a to it.b }.toSet())
        val g = StickerGraph(story.map { StickerEdgeEntity(it.a, it.b, it.type, it.weight) })
        assertEquals(listOf(1L, 2L, 3L), g.story(2))
        assertTrue(g.story(4).isEmpty())
    }

    @Test fun differentScenesDontFormAStoryEvenIfOneFrameMatches() {
        val story = GraphBuilder.story(listOf(
            node(1, v(10), first = v(0), last = v(1)),
            node(2, v(13), first = v(1), last = v(2)), // same transition frame, unrelated rest
        ))
        assertTrue(story.isEmpty())
    }

    @Test fun looksAlikeIgnoresIdenticalDuplicatesAndDistantOnes() {
        val nodes = listOf(node(1, v(0, 1, 0.3f)), node(2, v(0, 1, 0.6f)), node(3, v(0)), node(4, v(5)), node(5, v(0, 1, 0.3f)))
        val looks = GraphBuilder.looks(nodes).map { it.a to it.b }.toSet()
        assertTrue(1L to 2L in looks)
        assertTrue("an exact duplicate isn't a look-alike", (1L to 5L) !in looks)
        assertTrue(looks.none { it.first == 4L || it.second == 4L })
    }

    @Test fun topicsLinkSpecificSharedThemesNotGenericOnes() {
        val nodes = (1L..200L).map { id -> // "driving" on 4 of 200 is specific; on 4 of 40 it would not be
            val tags = buildSet {
                add("funny") // generic, everywhere
                if (id in 1L..3L) add("driving")
                if (id in 4L..5L) add("cycling")
                if (id == 6L) add("driving")
            }
            node(id, text = if (id == 6L) v(9) else v(0, 1, 0.1f * (id % 3)), tags = tags)
        }
        val topics = GraphBuilder.topics(nodes)
        val pairs = topics.map { it.a to it.b }.toSet()
        assertTrue(1L to 2L in pairs)
        assertTrue(4L to 5L in pairs)
        assertTrue("meanings too far apart", pairs.none { it.first == 6L || it.second == 6L })
        assertEquals("driving", topics.first { it.a == 1L && it.b == 2L }.label)
        assertTrue("generic tags never make topics", topics.none { it.label?.contains("funny") == true })
    }

    @Test fun expansionPrefersStoryThenLooksThenTopic() {
        val g = StickerGraph(listOf(
            StickerEdgeEntity(1, 2, EdgeType.STORY, 1f),
            StickerEdgeEntity(1, 3, EdgeType.LOOKS, 0.9f),
            StickerEdgeEntity(1, 4, EdgeType.TOPIC, 0.9f, "driving"),
        ))
        val ex = g.expand(listOf(1L), exclude = setOf(1L))
        assertEquals(listOf(2L, 3L, 4L), ex.map { it.first })
        assertEquals("driving", ex.last().second)
        val rel = g.related(1)
        assertEquals(listOf(1L, 2L), rel.story)
        assertEquals(listOf(3L), rel.looks)
        assertEquals("driving" to listOf(4L), rel.topics.single())
    }

    @Test fun fileSequenceParsing() {
        assertEquals(20260207L to 7, GraphBuilder.fileSeq("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Stickers/STK-20260207-WA0007.webp"))
        assertEquals(null, GraphBuilder.fileSeq("abc.webp"))
    }
}
