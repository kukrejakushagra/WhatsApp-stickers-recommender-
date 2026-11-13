package app.stickersearch.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyframeSelectorTest {

    private fun flat(v: Float) = FloatArray(1024) { v }

    /** A bright block whose position encodes a "pose". */
    private fun pose(p: Int) = FloatArray(1024) { i -> if ((i / 32) in (p * 4) until (p * 4 + 8)) 0.9f else 0.1f }

    @Test fun staticLookingAnimationKeepsFirstAndLast() {
        val sigs = List(20) { pose(2) }
        val c = KeyframeSelector.select(sigs)
        assertEquals(listOf(0, 19), c.map { it.index })
    }

    @Test fun skipsBlankLeadingFrames() {
        val sigs = listOf(flat(0.5f), flat(0.5f), pose(1), pose(3), pose(5))
        assertEquals(2, KeyframeSelector.select(sigs).first().index)
    }

    @Test fun picksChangePeaksWithinTheCap() {
        // Head-banging: the pose flips between two positions at frames 10, 20, 30, 40.
        val sigs = List(50) { i -> if ((i / 10) % 2 == 0) pose(1) else pose(5) }
        val c = KeyframeSelector.select(sigs, maxFrames = 6)
        val idx = c.map { it.index }
        assertTrue(idx.size in 3..6)
        assertTrue(idx.containsAll(listOf(0, 49)))
        assertTrue("peaks found: $idx", idx.count { it in listOf(10, 20, 30, 40) } >= 2)
        assertEquals(idx.sorted(), idx)
    }

    @Test fun singleFrame() {
        assertEquals(listOf(0), KeyframeSelector.select(listOf(pose(1))).map { it.index })
    }
}
