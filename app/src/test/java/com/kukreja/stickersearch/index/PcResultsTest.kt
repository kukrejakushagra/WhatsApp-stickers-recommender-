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
}
