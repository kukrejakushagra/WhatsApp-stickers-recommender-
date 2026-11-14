package app.stickersearch.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When results and details sit side by side (instead of a bottom sheet). */
class AdaptiveTest {
    @Test fun phones() {
        assertFalse(Adaptive.twoPane(411, landscape = false))   // portrait: bottom sheet
        assertTrue(Adaptive.twoPane(891, landscape = true))     // landscape: side by side
        assertTrue(Adaptive.twoPane(700, landscape = true))     // small phone in landscape
    }

    @Test fun sevenInchTablets() {
        assertFalse(Adaptive.twoPane(600, landscape = false))
        assertTrue(Adaptive.twoPane(960, landscape = true))
    }

    @Test fun tenInchTablets() {
        assertFalse(Adaptive.twoPane(800, landscape = false))
        assertTrue(Adaptive.twoPane(1280, landscape = true))
    }

    @Test fun shortScreensAreLandscapePhones() {
        assertTrue(Adaptive.short(411))
        assertFalse(Adaptive.short(800))
    }
}
