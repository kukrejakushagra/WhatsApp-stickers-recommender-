package app.stickersearch.search

import app.stickersearch.ml.ReplyPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageAssistLogicTest {

    @Test fun cropsCoverAScreenshotAndStayInside() {
        val w = 1080; val h = 2400
        val crops = ImageMatch.crops(w, h)
        assertTrue("enough crops to find a sticker anywhere: ${crops.size}", crops.size in 40..120)
        assertTrue(crops.all { it.x >= 0 && it.y >= 0 && it.x + it.size <= w && it.y + it.size <= h })
        // A 300 px sticker at (650, 1500) is fully inside some crop of similar size.
        assertTrue(crops.any { it.x <= 650 && it.y <= 1500 && it.x + it.size >= 950 && it.y + it.size >= 1800 && it.size <= 700 })
    }

    @Test fun pickKeepsTheMatchAndNearTiesOnly() {
        val ids = longArrayOf(1, 2, 3, 4)
        assertEquals(listOf(2L, 4L), ImageMatch.pick(ids, floatArrayOf(0.60f, 0.97f, 0.80f, 0.95f)).map { it.first })
        assertTrue("nothing close enough", ImageMatch.pick(ids, floatArrayOf(0.5f, 0.7f, 0.86f, 0.3f)).isEmpty())
    }

    @Test fun replyPlanParsesLeniently() {
        val p = ReplyPlan.parse("""Here you go: {"reading":"They're teasing you for being late; reply playfully.",
            "queries":["playful eye roll","laughing it off"], "avoid":"angry, sad", "seen":[]}""")!!
        assertEquals(2, p.queries.size)
        assertEquals(listOf("angry", "sad"), p.avoid)
        assertTrue(p.reading.startsWith("They're teasing"))
        assertNull(ReplyPlan.parse("no json"))
        assertNull(ReplyPlan.parse("""{"queries":[],"reading":""}"""))
    }
}
