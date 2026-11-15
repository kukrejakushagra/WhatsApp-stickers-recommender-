package app.stickersearch.index

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadTextTest {
    private val mb = 1L shl 20

    @Test fun progressShowsMbAndPercent() {
        assertEquals("57 of 95 MB · 60%", DownloadText.progress(57 * mb, 95 * mb))
        assertEquals("0 of 283 MB · 0%", DownloadText.progress(0, 283 * mb))
    }

    @Test fun unknownTotal() = assertEquals("12 MB so far", DownloadText.progress(12 * mb, -1))

    @Test fun sizes() {
        assertEquals("118 MB", DownloadText.mb(118 * mb))
        assertEquals("9.4 MB", DownloadText.mb((9.4 * mb).toLong()))
        assertEquals("1,024 MB", DownloadText.mb(1024 * mb))
    }

    @Test fun titlesNameTheModel() {
        assertEquals("Downloading Meaning search", DownloadText.downloading("Meaning search"))
        assertEquals("Search model 2 of 3", DownloadText.position(1, 3))
        assertEquals("Meaning search · waiting for Wi-Fi", DownloadText.waiting("Meaning search", true))
        assertEquals("Meaning search · waiting for a connection", DownloadText.waiting("Meaning search", false))
    }

    @Test fun finishedSummaries() {
        val a = DownloadText.Finished("Meaning search", 118 * mb)
        val b = DownloadText.Finished("Visual search, images", 95 * mb)
        val c = DownloadText.Finished("Visual search, text", 283 * mb)
        assertEquals("Meaning search downloaded · 118 MB", DownloadText.finishedTitle(listOf(a), false))
        assertEquals("2 search models downloaded · 213 MB", DownloadText.finishedTitle(listOf(a, b), false))
        assertEquals("Search is ready · 3 models, 496 MB", DownloadText.finishedTitle(listOf(a, b, c), true))
        assertEquals("Meaning search · 118 MB, Visual search, images · 95 MB", DownloadText.finishedText(listOf(a, b), false))
        assertEquals("Your stickers are being indexed for search.", DownloadText.finishedText(listOf(a, b, c), true))
    }
}
