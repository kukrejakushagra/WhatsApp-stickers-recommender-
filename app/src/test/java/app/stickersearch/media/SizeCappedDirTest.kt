package app.stickersearch.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SizeCappedDirTest {
    @Test
    fun `least recently used files go first when over the cap`() {
        val dir = Files.createTempDirectory("anim").toFile().apply { deleteOnExit() }
        val cache = SizeCappedDir(dir, capBytes = 3_000)
        cache.put("a", ByteArray(1_000) { 1 }); java.io.File(dir, "a").setLastModified(1_000)
        cache.put("b", ByteArray(1_000) { 2 }); java.io.File(dir, "b").setLastModified(2_000)
        cache.put("c", ByteArray(1_000) { 3 }); java.io.File(dir, "c").setLastModified(3_000)
        assertNotNull(cache.get("a"))          // using "a" makes it the most recent
        cache.put("d", ByteArray(1_000) { 4 })  // over the cap: "b" (least recent) goes
        assertNull(cache.get("b"))
        assertArrayEquals(ByteArray(1_000) { 1 }, cache.get("a"))
        assertNotNull(cache.get("d"))
        assertTrue(cache.size() <= 3_000)
    }

    @Test
    fun `missing files read as null and clear empties the folder`() {
        val dir = Files.createTempDirectory("anim").toFile().apply { deleteOnExit() }
        val cache = SizeCappedDir(dir, capBytes = 10_000)
        assertNull(cache.get("nope"))
        cache.put("x", ByteArray(10))
        cache.clear()
        assertNull(cache.get("x"))
        assertTrue(cache.size() == 0L)
    }
}
