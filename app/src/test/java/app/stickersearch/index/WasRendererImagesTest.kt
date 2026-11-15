package app.stickersearch.index

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pictures embedded in a Lottie sticker's JSON are decoded by the app itself (Lottie skips them without a Context). */
class WasRendererImagesTest {
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)
    private val b64 = java.util.Base64.getEncoder().encodeToString(png)

    @Test fun embeddedPicture() = assertArrayEquals(png, WasRenderer.dataUriBytes("data:image/png;base64,$b64"))

    @Test fun embeddedWithLineBreaks() =
        assertArrayEquals(png, WasRenderer.dataUriBytes("data:image/png;base64," + b64.chunked(4).joinToString("\n")))

    @Test fun fileNameIsNotEmbedded() {
        assertNull(WasRenderer.dataUriBytes("img_0.png"))
        assertNull(WasRenderer.dataUriBytes(null))
    }

    @Test fun brokenData() {
        assertNull(WasRenderer.dataUriBytes("data:image/png;base64,"))
        assertNull(WasRenderer.dataUriBytes("data:image/png,notbase64"))
        assertNull(WasRenderer.dataUriBytes("data:image/png;base64,@@@"))
    }
}
