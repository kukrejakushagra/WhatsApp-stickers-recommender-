package com.kukreja.stickersearch.discovery

import com.kukreja.stickersearch.model.DirStats

/** Pure path heuristics shared by the filesystem and SAF sources. */
object PathRules {

    /** The folder the user picks: it holds both sticker folders found on device (Android 11+). */
    const val WHATSAPP_MEDIA_RELATIVE = "Android/media/com.whatsapp/WhatsApp/Media"

    /**
     * Where WhatsApp keeps its Media folder: under Android/media from Android 11 (scoped storage),
     * at the top of the phone's storage on Android 9-10 and older installs. Business mirrors it.
     */
    fun mediaFolder(business: Boolean, sdk: Int): String = when {
        business && sdk >= 30 -> "Android/media/com.whatsapp.w4b/WhatsApp Business/Media"
        business -> "WhatsApp Business/Media"
        sdk >= 30 -> WHATSAPP_MEDIA_RELATIVE
        else -> "WhatsApp/Media"
    }

    /** Path segments that lead to sticker folders without being one (navigation ancestors). */
    private val ANCESTORS = setOf("android", "media", "com.whatsapp", "com.whatsapp.w4b", "whatsapp", "whatsapp business")

    /** Roots probed under external storage by the all-files-access source. */
    val FILE_SYSTEM_ROOTS = listOf(
        "Android/media/com.whatsapp",
        "Android/media/com.whatsapp.w4b",
        "WhatsApp", // Android 9-10, and pre-2021 installs
        "WhatsApp Business",
    )

    /** Expected to be unreadable on Android 11+; probed only to record the result. */
    val PRIVATE_PROBES = listOf("Android/data/com.whatsapp")

    private val MEDIA_EXTENSIONS = setOf("webp", "gif", "png", "apng", "was", "json", "jpg", "jpeg")

    /**
     * A file is a sticker candidate when a directory on its path mentions "sticker", it is not under
     * WhatsApp's encrypted `Backups/` tree, and it has an image-like (or missing) extension.
     * Seen on device: "WhatsApp Stickers" and "WhatsApp Backup Excluded Stickers".
     */
    fun isStickerCandidate(path: String): Boolean {
        val segments = path.replace('\\', '/').split('/')
        val name = segments.lastOrNull() ?: return false
        if (name.startsWith(".")) return false
        val dirs = segments.dropLast(1)
        if (dirs.any { it.equals("Backups", ignoreCase = true) }) return false
        if (dirs.none { it.contains("sticker", ignoreCase = true) }) return false
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext.isEmpty() || ext in MEDIA_EXTENSIONS
    }

    /** Whether a directory walk should enter [dirPath]: sticker folders and the folders leading to them. */
    fun shouldDescend(dirPath: String): Boolean {
        val segments = dirPath.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        if (segments.any { it.equals("Backups", ignoreCase = true) }) return false
        if (segments.any { it.contains("sticker", ignoreCase = true) }) return true
        return segments.all { it.lowercase() in ANCESTORS }
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
