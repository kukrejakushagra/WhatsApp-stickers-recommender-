package app.stickersearch.media

import java.io.File

/**
 * A folder of cached files kept under a size cap: the least recently used ones go first.
 * "Used" is the file's modified time, touched on every read. Pure Kotlin, tested on the JVM.
 */
class SizeCappedDir(private val dir: File, private val capBytes: Long) {
    init { dir.mkdirs() }

    fun get(name: String): ByteArray? {
        val f = File(dir, name)
        if (!f.isFile) return null
        f.setLastModified(System.currentTimeMillis())
        return runCatching { f.readBytes() }.getOrNull()
    }

    fun put(name: String, bytes: ByteArray) {
        val f = File(dir, name)
        val tmp = File(dir, "$name.tmp")
        runCatching {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
        trim()
    }

    /** Removes the least recently used files until the folder is under the cap. */
    fun trim() {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= capBytes) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= capBytes) break
            total -= f.length()
            f.delete()
        }
    }

    fun size(): Long = dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    fun clear() { dir.listFiles()?.forEach { it.delete() } }
}
