package app.stickersearch.ui

import app.stickersearch.ui.kds.TooltipPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Window 1080×2340 px, popup 600×200 px, 36 px margin, 18 px gap (12 dp / 6 dp at 3x). */
class TooltipPlacementTest {
    private fun place(l: Int, t: Int, r: Int, b: Int, w: Int = 1080, h: Int = 2340, pw: Int = 600, ph: Int = 200) =
        TooltipPlacement.place(l, t, r, b, w, h, pw, ph, margin = 36, gap = 18, safeTop = 72, safeBottom = 144, arrowHalf = 27)

    @Test fun belowAndCentredByDefault() {
        val p = place(400, 500, 680, 560)
        assertTrue(p.below)
        assertEquals(560 + 18, p.y)
        assertEquals(540 - 300, p.x)          // centred on the label
        assertEquals(300, p.arrowX)           // arrow at the label's centre
    }

    @Test fun flipsAboveNearTheBottom() {
        val p = place(400, 2050, 680, 2110)
        assertFalse(p.below)
        assertEquals(2050 - 18 - 200, p.y)
    }

    @Test fun clampedAtTheLeftEdge() {
        val p = place(12, 500, 120, 560)      // a label right at the left edge
        assertEquals(36, p.x)
        assertEquals("arrow as close to the label as the card allows", 27 + 18, p.arrowX)
    }

    @Test fun clampedAtTheRightEdge() {
        val p = place(960, 500, 1068, 560)    // a label right at the right edge
        assertEquals(1080 - 36 - 600, p.x)
        // The label's centre is past the card's corner radius, so the arrow stops just inside the card.
        assertEquals(600 - 27 - 18, p.arrowX)
    }

    @Test fun popupWiderThanTheWindowStaysAtTheMargin() {
        val p = place(400, 500, 680, 560, pw = 1200)
        assertEquals(36, p.x)
    }

    @Test fun neverAboveTheStatusBar() {
        val p = place(400, 120, 680, 180, h = 400, ph = 300)  // no room either way: stays below the status bar
        assertTrue(p.y >= 72)
    }
}
