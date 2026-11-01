package com.kukreja.stickersearch.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PackPlannerTest {
    private fun item(id: Long, animated: Boolean = false, lottie: Boolean = false) = PackPlanner.Item(id, animated, lottie)

    @Test fun mixedSelectionSplitsIntoStaticAndAnimatedPacks() {
        val p = PackPlanner.plan("Driving", (1L..4L).map { item(it) } + (5L..8L).map { item(it, animated = true) })
        assertEquals(listOf("Driving" to false, "Driving · animated" to true), p.packs.map { it.title to it.animated })
        assertEquals(listOf(1L, 2L, 3L, 4L), p.packs[0].ids)
        assertTrue(p.notes.isEmpty())
    }

    @Test fun oneKindKeepsThePlainName() {
        val p = PackPlanner.plan("  ", (1L..3L).map { item(it, animated = true) })
        assertEquals("My stickers", p.packs.single().title)
        assertTrue(p.packs.single().animated)
    }

    @Test fun tooFewOfAKindAndLottieAreExplained() {
        val p = PackPlanner.plan("Cycling", (1L..5L).map { item(it) } + item(6, animated = true) + item(7, lottie = true))
        assertEquals(1, p.packs.size)
        assertEquals(2, p.notes.size)
        assertTrue(p.notes.any { "Lottie" in it })
        assertTrue(p.notes.any { "at least 3" in it })
    }

    @Test fun capsAtThirtyPerPack() {
        val p = PackPlanner.plan("Lots", (1L..35L).map { item(it) })
        assertEquals(30, p.packs.single().ids.size)
        assertTrue(p.notes.single().contains("5 static"))
    }
}
