package app.stickersearch.index

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.random.Random

/** The resumable model download, against a tiny local server that honours (or ignores) Range. */
class ModelDownloadTest {
    private val body = Random(7).nextBytes(3 * 1024 * 1024 + 123)
    private lateinit var server: ServerSocket
    @Volatile private var honourRange = true
    @Volatile private var cutAfter = -1
    private val ranges = java.util.Collections.synchronizedList(mutableListOf<String?>())
    private val dir = File(System.getProperty("java.io.tmpdir"), "dl-test-${System.nanoTime()}").apply { mkdirs() }

    /** A minimal HTTP/1.0 server (one request per connection) is all HttpURLConnection needs. */
    @Before fun start() {
        server = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val sock = runCatching { server.accept() }.getOrNull() ?: break
                sock.use { s -> serve(s) }
            }
        }
    }

    private fun serve(s: java.net.Socket) {
        val input = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        var range: String? = null
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(":").trim()
        }
        ranges += range
        val out = s.getOutputStream()
        val from = if (honourRange && range != null) range.removePrefix("bytes=").removeSuffix("-").toInt() else 0
        if (from >= body.size) {
            out.write("HTTP/1.0 416 Range Not Satisfiable\r\nContent-Length: 0\r\n\r\n".toByteArray()); out.flush(); return
        }
        val slice = body.copyOfRange(from, body.size)
        val status = if (from > 0) "206 Partial Content" else "200 OK"
        out.write("HTTP/1.0 $status\r\nContent-Length: ${slice.size}\r\n\r\n".toByteArray())
        if (cutAfter in 0 until slice.size) out.write(slice, 0, cutAfter) else out.write(slice)
        out.flush()
    }

    @After fun stop() { server.close(); dir.deleteRecursively() }

    private val url get() = "http://127.0.0.1:${server.localPort}/model"

    @Test fun downloadsWholeFile() = runBlocking {
        val part = File(dir, "a.part")
        var last = 0L to 0L
        ModelDownloadWorker.fetch(url, part) { have, size -> last = have to size }
        assertArrayEquals(body, part.readBytes())
        assertEquals(body.size.toLong() to body.size.toLong(), last)
    }

    @Test fun resumesAfterACutConnection() = runBlocking {
        val part = File(dir, "b.part")
        cutAfter = 1_000_000
        try {
            ModelDownloadWorker.fetch(url, part) { _, _ -> }
            fail("a cut connection must not look like a finished download")
        } catch (e: IOException) { /* expected */ }
        assertTrue(part.length() in 1..body.size.toLong() - 1)
        cutAfter = -1
        ModelDownloadWorker.fetch(url, part) { _, _ -> }
        assertArrayEquals(body, part.readBytes())
        // The second request asked only for what was missing.
        val resumedFrom = ranges.last()!!.removePrefix("bytes=").removeSuffix("-").toLong()
        assertTrue(resumedFrom in 1 until body.size.toLong())
    }

    @Test fun startsAgainWhenServerIgnoresRange() = runBlocking {
        val part = File(dir, "c.part")
        part.writeBytes(ByteArray(5000) { 1 }) // junk from an earlier, different attempt
        honourRange = false
        ModelDownloadWorker.fetch(url, part) { _, _ -> }
        assertArrayEquals(body, part.readBytes())
    }

    @Test fun alreadyCompleteFileIsKept() = runBlocking {
        val part = File(dir, "d.part")
        part.writeBytes(body)
        ModelDownloadWorker.fetch(url, part) { _, _ -> }
        assertArrayEquals(body, part.readBytes())
    }
}
