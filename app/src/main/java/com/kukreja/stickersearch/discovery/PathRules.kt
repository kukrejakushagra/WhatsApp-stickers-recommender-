package com.kukreja.stickersearch.discovery

import com.kukreja.stickersearch.model.DirStats

/** Pure path heuristics shared by the filesystem and SAF sources. */
object PathRules {

    const val WHATSAPP_STICKER_RELATIVE = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Stickers"

    /** Roots probed under external storage by the all-files-access source. */
    val FILE_SYSTEM_ROOTS = listOf(
        "Android/media/com.whatsapp",
        "Android/media/com.whatsapp.w4b",
        "WhatsApp", // pre-2021 legacy location
    )

    /** Expected to be unreadable on Android 11+; probed only to record the result. */
    val PRIVATE_PROBES = listOf("Android/data/com.whatsapp")

    private val MEDIA_EXTENSIONS = setOf("webp", "gif", "png", "apng", "was", "json", "jpg", "jpeg")

    /**
     * A file is a sticker candidate when any directory on its path mentions "sticker" and it has
     * an image-like (or missing) extension. Everything else is only counted in the inventory.
     */
    fun isStickerCandidate(path: String): Boolean {
        val segments = path.replace('\\', '/').split('/')
        val name = segments.lastOrNull() ?: return false
        if (name.startsWith(".")) return false
        val dirs = segments.dropLast(1)
        if (dirs.none { it.contains("sticker", ignoreCase = true) }) return false
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext.isEmpty() || ext in MEDIA_EXTENSIONS
    }

    fun parentOf(path: String): String = path.replace('\\', '/').substringBeforeLast('/', "")

    /** Folds a file into a per-directory inventory. */
    fun record(inventory: MutableMap<String, DirStats>, path: String, size: Long) {
        val parent = parentOf(path)
        val stats = inventory.getOrPut(parent) { DirStats() }
        if (path.endsWith("/.nomedia")) stats.hasNoMedia = true else {
            stats.fileCount++
            stats.totalBytes += size
        }
    }
}
