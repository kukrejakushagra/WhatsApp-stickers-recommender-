package app.stickersearch.share

import org.junit.Assert.assertEquals
import org.junit.Test

class EmojiTest {
    @Test
    fun `real emoji are kept, anything else becomes the default`() {
        assertEquals("😂", StickerPacks.safeEmoji("😂"))
        assertEquals("😂", StickerPacks.safeEmoji(" 😂🤣 "))      // only the first one
        assertEquals("❤️", StickerPacks.safeEmoji("❤️"))          // with its variation selector
        assertEquals("🙂", StickerPacks.safeEmoji(""))
        assertEquals("🙂", StickerPacks.safeEmoji("laughing"))
        assertEquals("🙂", StickerPacks.safeEmoji("\uD83D"))       // half an emoji, cut off
    }
}
