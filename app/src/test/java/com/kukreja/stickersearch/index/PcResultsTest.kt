package com.kukreja.stickersearch.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PcResultsTest {
    private val good1 = """{"sha256": "a", "model": "gemma3:12b", "raw": "{\"description\": \"x\"}", "prompt_version": 1}"""
    private val good2 = """{"sha256": "b", "model": "gemma3:12b", "raw": "{\"description\": \"y\"}"}"""

    @Test
    fun `a half-written last line is dropped quietly, not counted as an error`() {
        val p = PcResults.parse(listOf(good1, good2, """{"sha256": "c", "mod"""))
        assertEquals(listOf("a", "b"), p.lines.map { it.sha256 })
        assertEquals(0, p.unreadable)
        assertTrue(p.cutOffTail)
    }

    @Test
    fun `a bad line in the middle counts as unreadable`() {
        val p = PcResults.parse(listOf(good1, "not json", "", good2))
        assertEquals(2, p.lines.size)
        assertEquals(1, p.unreadable)
        assertFalse(p.cutOffTail)
    }

    @Test
    fun `prompt version is kept when present`() {
        val p = PcResults.parse(listOf(good1, good2))
        assertEquals(1, p.lines[0].promptVersion)
        assertNull(p.lines[1].promptVersion)
        assertEquals("gemma3:12b", p.lines[0].model)
    }

    @Test
    fun `lines with odd values or missing fields are skipped, not fatal`() {
        val p = PcResults.parse(listOf(
            """{"sha256": "", "raw": "{}"}""",            // no sticker id
            """{"sha256": "x", "raw": ""}""",              // nothing to import
            """{"sha256": 42, "raw": 7, "pc_prompt": "two"}""",
            good1,
        ))
        assertEquals(listOf("a"), p.lines.map { it.sha256 })
        assertTrue(p.unreadable >= 2)
    }

    @Test
    fun `a PC line is labelled with its model and prompt version`() {
        val l = PcResults.parse(listOf("""{"sha256": "a", "model": "gemma3:12b", "raw": "{\"description\": \"x\"}", "pc_prompt": 2}""")).lines.single()
        assertEquals(PcResults.Source.PC, l.source)
        assertEquals("pc:gemma3:12b:p2", l.label)
    }

    @Test
    fun `a shared line round-trips its description and the user's edit`() {
        val a = com.kukreja.stickersearch.ml.Annotation(description = "Cat in a tie at a desk", tags = listOf("cat", "office"),
            phrases = listOf("monday mood", "office ka scene"), emoji = "😩")
        val c = com.kukreja.stickersearch.ml.Correction(tags = listOf("work", "boss"))
        val line = PcResults.sharedLine("abc", "gemma-3n-e2b-it-int4", a, c)
        val l = PcResults.parse(listOf(line)).lines.single()
        assertEquals(PcResults.Source.SHARED, l.source)
        assertEquals("shared:gemma-3n-e2b-it-int4", l.label)
        val back = l.annotation()!!
        assertEquals(a.description, back.description)
        assertEquals(a.tags, back.tags)
        assertEquals(a.phrases, back.phrases)
        assertEquals(listOf("work", "boss"), l.correction!!.tags)
    }
}
