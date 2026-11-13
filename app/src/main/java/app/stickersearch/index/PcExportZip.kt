package app.stickersearch.index

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
    private val executables = LinkedHashSet<String>()
    private var finished = false

    /** Adds a file. JPEGs are already compressed, so [store] skips deflating them (much faster). */
    fun put(name: String, bytes: ByteArray, store: Boolean = false, executable: Boolean = false) {
        require(names.add(name)) { "duplicate entry $name" }
        if (executable) executables += name
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
        if (executables.isNotEmpty()) markExecutable(file, executables)
        return names
    }

    /** Closes without finishing (an error path); the file is then incomplete and must be discarded. */
    override fun close() {
        if (!finished) runCatching { zip.close() }
    }

    companion object {
        const val MANIFEST = "manifest.json"

        /**
         * Marks [names] as Unix executables (rwxr-xr-x), so the Mac and Linux starters run on a
         * double-click straight after extracting. ZipOutputStream can't record permissions, so the
         * finished zip's central directory is patched in place: "version made by" becomes Unix and
         * the external attributes carry mode 0100755. Sizes don't change, so no offsets move.
         * Windows ignores these bits. Best effort: an unexpected layout is left untouched.
         */
        fun markExecutable(file: File, names: Set<String>) {
            java.io.RandomAccessFile(file, "rw").use { raf ->
                val len = raf.length()
                val tailLen = minOf(len, 65_557L).toInt()
                val tail = ByteArray(tailLen)
                raf.seek(len - tailLen)
                raf.readFully(tail)
                fun le16(b: ByteArray, i: Int) = (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8)
                fun le32(b: ByteArray, i: Int) = le16(b, i).toLong() or (le16(b, i + 2).toLong() shl 16)
                var eocd = -1
                for (i in tailLen - 22 downTo 0) if (le32(tail, i) == 0x06054b50L) { eocd = i; break }
                if (eocd < 0) return
                val cdSize = le32(tail, eocd + 12)
                val cdOffset = le32(tail, eocd + 16)
                if (cdOffset == 0xFFFFFFFFL || cdSize > Int.MAX_VALUE) return // Zip64: leave as is
                val cd = ByteArray(cdSize.toInt())
                raf.seek(cdOffset)
                raf.readFully(cd)
                var p = 0
                while (p + 46 <= cd.size && le32(cd, p) == 0x02014b50L) {
                    val nameLen = le16(cd, p + 28)
                    val extraLen = le16(cd, p + 30)
                    val commentLen = le16(cd, p + 32)
                    val name = String(cd, p + 46, nameLen, Charsets.UTF_8)
                    if (name in names) {
                        cd[p + 5] = 3 // version made by: Unix (high byte); the low byte (spec version) stays
                        val attr = 0x81EDL shl 16 // regular file, rwxr-xr-x
                        for (k in 0 until 4) cd[p + 38 + k] = ((attr shr (8 * k)) and 0xff).toByte()
                    }
                    p += 46 + nameLen + extraLen + commentLen
                }
                raf.seek(cdOffset)
                raf.write(cd)
            }
        }

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
