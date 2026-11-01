package com.kukreja.stickersearch.media

import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * Reads WhatsApp's ".was" animated stickers without assuming their exact layout.
 * Handles a zip (central directory first, so STORED entries with data descriptors work),
 * gzip-wrapped JSON (like Telegram's .tgs) and raw Lottie JSON. Pure JVM; unit-tested.
 */
object WasArchive {

    enum class Container { ZIP, GZIP, JSON, UNKNOWN }

    data class Entry(val name: String, val size: Long)

    data class Content(
        val container: Container,
        val entries: List<Entry>,
        /** Lottie JSON candidates, best first. */
        val jsonCandidates: List<Pair<String, String>>,
        /** Image assets by file name (without folders) and by full path. */
        val images: Map<String, ByteArray>,
        val readError: String? = null,
    ) {
        fun describe(): String = buildString {
            append(container.name.lowercase())
            if (entries.isNotEmpty()) append(" [").append(entries.take(12).joinToString { "${it.name} ${it.size} B" }).append(']')
            readError?.let { append("; read error: ").append(it) }
        }
    }

    private val IMAGE_EXT = setOf("png", "webp", "jpg", "jpeg")
    private const val MAX_ENTRY = 8L * 1024 * 1024

    fun open(bytes: ByteArray, tmpDir: File? = null): Content = when {
        bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() -> openZip(bytes, tmpDir)
        bytes.size >= 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte() -> runCatching {
            val json = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }.toString(Charsets.UTF_8)
            Content(Container.GZIP, listOf(Entry("(gzip)", json.length.toLong())), listOf("(gzip)" to json), emptyMap())
        }.getOrElse { Content(Container.GZIP, emptyList(), emptyList(), emptyMap(), it.message) }
        looksLikeJson(bytes) -> Content(Container.JSON, emptyList(), listOf("(raw)" to bytes.toString(Charsets.UTF_8)), emptyMap())
        else -> Content(Container.UNKNOWN, emptyList(), emptyList(), emptyMap(), "not zip, gzip or JSON")
    }

    /** Picks the Lottie animation among entry names: exact path first, then real `.json` files, largest first. */
    fun rankJson(entries: List<Entry>): List<String> {
        val json = entries.filter { e ->
            val n = e.name.lowercase()
            n.endsWith(".json") && !n.substringAfterLast('/').startsWith(".") && "__macosx" !in n
        }
        fun rank(e: Entry): Int {
            val n = e.name.lowercase()
            return when {
                n == "animation/animation.json" -> 0
                n.substringAfterLast('/') == "animation.json" -> 1
                n.substringAfterLast('/') == "manifest.json" -> 9
                "secondary" in n || "metadata" in n -> 5
                else -> 2
            }
        }
        return json.sortedWith(compareBy<Entry>({ rank(it) }, { -it.size })).map { it.name }
    }

    private fun openZip(bytes: ByteArray, tmpDir: File?): Content {
        val data = LinkedHashMap<String, ByteArray>()
        var error: String? = null
        // 1) Central directory: robust to data descriptors and odd local headers.
        val tmp = runCatching { File.createTempFile("was", ".zip", tmpDir) }.getOrNull()
        if (tmp != null) {
            try {
                tmp.writeBytes(bytes)
                ZipFile(tmp).use { zf ->
                    for (e in zf.entries()) {
                        if (e.isDirectory || e.size > MAX_ENTRY) continue
                        data[e.name] = zf.getInputStream(e).use { it.readBytes() }
                    }
                }
            } catch (e: Exception) {
                error = "ZipFile: ${e.javaClass.simpleName}: ${e.message}"
            } finally {
                tmp.delete()
            }
        }
        // 2) Streaming fallback.
        if (data.isEmpty()) {
            runCatching {
                ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
                    while (true) {
                        val e = zin.nextEntry ?: break
                        if (!e.isDirectory) data[e.name] = zin.readBytes()
                    }
                }
            }.onFailure { error = listOfNotNull(error, "ZipInputStream: ${it.javaClass.simpleName}: ${it.message}").joinToString("; ") }
        }
        val entries = data.map { (k, v) -> Entry(k, v.size.toLong()) }
        val candidates = rankJson(entries).map { it to data.getValue(it).toString(Charsets.UTF_8) }
        val images = buildMap {
            data.forEach { (name, v) ->
                if (name.substringAfterLast('.', "").lowercase() in IMAGE_EXT) {
                    put(name, v)
                    put(name.substringAfterLast('/'), v)
                }
            }
        }
        return Content(Container.ZIP, entries, candidates, images, if (data.isEmpty()) error ?: "empty archive" else null)
    }

    private fun looksLikeJson(b: ByteArray): Boolean {
        var i = 0
        while (i < b.size && i < 16 && (b[i] == ' '.code.toByte() || b[i] == '\n'.code.toByte() || b[i] == '\r'.code.toByte() ||
                b[i] == '\t'.code.toByte() || b[i] == 0xEF.toByte() || b[i] == 0xBB.toByte() || b[i] == 0xBF.toByte())) i++
        return i < b.size && b[i] == '{'.code.toByte()
    }
}
