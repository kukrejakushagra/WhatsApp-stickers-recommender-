package com.kukreja.stickersearch.index

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Writes the "Describe on a PC" zip to a local file. Pure Kotlin, so it's tested on the JVM.
 *
 * A zip's table of contents (central directory) is written only by [finish]; a zip that was cut
 * off before that looks empty to Windows. So the export always writes here first, checks the
 * result with [verify], and only then copies it to where the user asked.
 */
class PcExportZip(private val file: File) : Closeable {
    private val zip = ZipOutputStream(BufferedOutputStream(FileOutputStream(file), 1 shl 16)).apply {
        setLevel(Deflater.BEST_SPEED)
    }
    private val names = LinkedHashSet<String>()
    private var finished = false

    /** Adds a file. JPEGs are already compressed, so [store] skips deflating them (much faster). */
    fun put(name: String, bytes: ByteArray, store: Boolean = false) {
        require(names.add(name)) { "duplicate entry $name" }
        val e = ZipEntry(name)
        if (store) {
            val crc = CRC32().apply { update(bytes) }
            e.method = ZipEntry.STORED
            e.size = bytes.size.toLong()
            e.compressedSize = bytes.size.toLong()
            e.crc = crc.value
        }
        zip.putNextEntry(e)
        zip.write(bytes)
        zip.closeEntry()
    }

    /** Writes the manifest last, then the table of contents. Returns every entry name. */
    fun finish(manifest: ByteArray): Set<String> {
        put(MANIFEST, manifest)
        zip.close()
        finished = true
        return names
    }

    /** Closes without finishing (an error path); the file is then incomplete and must be discarded. */
    override fun close() {
        if (!finished) runCatching { zip.close() }
    }

    companion object {
        const val MANIFEST = "manifest.json"

        /**
         * Reopens [file] as a zip and checks every [expected] entry is listed and the manifest reads
         * back intact. Null when it's good, otherwise what's wrong.
         */
        fun verify(file: File, expected: Collection<String>): String? = try {
            ZipFile(file).use { z ->
                val missing = expected.filter { z.getEntry(it) == null }
                when {
                    missing.isNotEmpty() -> "${missing.size} entries missing (first: ${missing.first()})"
                    z.getEntry(MANIFEST) == null -> "manifest missing"
                    // Reading it in full checks its CRC.
                    else -> z.getInputStream(z.getEntry(MANIFEST)).use { it.readBytes() }.let { null }
                }
            }
        } catch (e: Exception) {
            "not a readable zip (${e.javaClass.simpleName}: ${e.message})"
        }
    }
}
