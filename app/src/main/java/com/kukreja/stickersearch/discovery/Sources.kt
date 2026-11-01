package com.kukreja.stickersearch.discovery

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.MediaStore
import com.kukreja.stickersearch.model.DirStats
import com.kukreja.stickersearch.model.DiscoveredFile
import com.kukreja.stickersearch.model.SourceId
import com.kukreja.stickersearch.model.SourceProbe
import com.kukreja.stickersearch.model.SourceResult
import java.io.File

/** One way of discovering sticker files. Every source reports what it could not see, too. */
interface StickerSource {
    val id: SourceId
    suspend fun scan(): SourceResult
}

/** Option A: plain java.io.File walk. Needs MANAGE_EXTERNAL_STORAGE ("All files access"). */
class FileSystemSource : StickerSource {
    override val id = SourceId.FILE_SYSTEM

    override suspend fun scan(): SourceResult {
        if (!Environment.isExternalStorageManager()) {
            return SourceResult(SourceProbe(id, false, "All files access not granted"), emptyList())
        }
        val ext = Environment.getExternalStorageDirectory()
        val files = mutableListOf<DiscoveredFile>()
        val inventory = mutableMapOf<String, DirStats>()
        val notes = mutableListOf<String>()
        val scanned = mutableListOf<String>()
        for (rel in PathRules.FILE_SYSTEM_ROOTS) {
            val root = File(ext, rel)
            if (!root.exists()) { notes += "$rel: does not exist"; continue }
            if (root.listFiles() == null) { notes += "$rel: exists but not listable"; continue }
            scanned += rel
            root.walkTopDown().onFail { f, e -> notes += "walk failed at ${f.path}: ${e.message}" }
                .filter { it.isFile }
                .forEach { f ->
                    val relPath = f.absolutePath.removePrefix(ext.absolutePath + "/")
                    PathRules.record(inventory, relPath, f.length())
                    if (PathRules.isStickerCandidate(relPath)) {
                        files += DiscoveredFile(
                            id, Uri.fromFile(f).toString(), relPath, f.name, f.length(), f.lastModified(),
                        )
                    }
                }
        }
        for (rel in PathRules.PRIVATE_PROBES) {
            val dir = File(ext, rel)
            val listing = dir.listFiles()
            notes += "$rel: exists=${dir.exists()} listable=${listing != null} entries=${listing?.size ?: 0}"
        }
        return SourceResult(SourceProbe(id, true, null, scanned, notes), files, inventory)
    }
}

/** Option B: Storage Access Framework tree the user picked once (persisted URI permission). */
class SafTreeSource(private val context: Context, private val treeUri: Uri?) : StickerSource {
    override val id = SourceId.SAF_TREE

    override suspend fun scan(): SourceResult {
        if (treeUri == null) return SourceResult(SourceProbe(id, false, "No folder picked yet"), emptyList())
        val held = context.contentResolver.persistedUriPermissions.any { it.uri == treeUri && it.isReadPermission }
        if (!held) {
            return SourceResult(SourceProbe(id, false, "Persisted permission missing or revoked for $treeUri"), emptyList())
        }
        val files = mutableListOf<DiscoveredFile>()
        val inventory = mutableMapOf<String, DirStats>()
        val notes = mutableListOf<String>()
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val stack = ArrayDeque(listOf(rootId))
        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED,
        )
        try {
            while (stack.isNotEmpty()) {
                val parentId = stack.removeLast()
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
                context.contentResolver.query(children, projection, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val docId = c.getString(0)
                        val name = c.getString(1) ?: ""
                        val mime = c.getString(2)
                        val size = if (c.isNull(3)) 0L else c.getLong(3)
                        val modified = if (c.isNull(4)) 0L else c.getLong(4)
                        if (mime == Document.MIME_TYPE_DIR) { stack.addLast(docId); continue }
                        val path = docIdToPath(docId)
                        PathRules.record(inventory, path, size)
                        // The user may pick the sticker folder itself, whose own name has "Sticker" in it.
                        if (PathRules.isStickerCandidate(path)) {
                            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                            files += DiscoveredFile(id, uri.toString(), path, name, size, modified, mime)
                        }
                    }
                } ?: notes.add("query returned null for $parentId")
            }
        } catch (e: Exception) {
            return SourceResult(SourceProbe(id, false, "${e.javaClass.simpleName}: ${e.message}", listOf(rootId), notes), files, inventory)
        }
        return SourceResult(SourceProbe(id, true, null, listOf(rootId), notes), files, inventory)
    }

    /** "primary:Android/media/..." -> "Android/media/..." */
    private fun docIdToPath(docId: String) = docId.substringAfter(':', docId)

    companion object {
        /** Initial location for the SAF picker, so the user does not have to navigate. */
        fun initialStickerFolderUri(): Uri = DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents", "primary:${PathRules.WHATSAPP_STICKER_RELATIVE}",
        )
    }
}

/** Option C: MediaStore. Expected to return little if WhatsApp marks the folder with .nomedia. */
class MediaStoreSource(private val context: Context, private val hasPermission: Boolean) : StickerSource {
    override val id = SourceId.MEDIA_STORE

    override suspend fun scan(): SourceResult {
        if (!hasPermission) return SourceResult(SourceProbe(id, false, "READ_MEDIA_IMAGES not granted"), emptyList())
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.RELATIVE_PATH,
            MediaStore.Files.FileColumns.DISPLAY_NAME, MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED, MediaStore.Files.FileColumns.MIME_TYPE,
        )
        val files = mutableListOf<DiscoveredFile>()
        val notes = mutableListOf<String>()
        try {
            val waTotal = context.contentResolver.query(
                collection, arrayOf(MediaStore.Files.FileColumns._ID),
                "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?", arrayOf("%com.whatsapp%"), null,
            )?.use { it.count } ?: -1
            notes += "MediaStore rows under any com.whatsapp path: $waTotal"
            context.contentResolver.query(
                collection, projection,
                "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?", arrayOf("%Sticker%"), null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val rowId = c.getLong(0)
                    val rel = c.getString(1) ?: ""
                    val name = c.getString(2) ?: ""
                    files += DiscoveredFile(
                        id, ContentUris.withAppendedId(collection, rowId).toString(),
                        rel + name, name, c.getLong(3), c.getLong(4) * 1000, c.getString(5),
                    )
                }
            }
        } catch (e: Exception) {
            return SourceResult(SourceProbe(id, false, "${e.javaClass.simpleName}: ${e.message}", notes = notes), files)
        }
        return SourceResult(SourceProbe(id, true, null, listOf("MediaStore external"), notes), files)
    }
}

/** Option D/E: files shared into the app via the Android share sheet, copied into app storage. */
class SharedInSource(private val dir: File) : StickerSource {
    override val id = SourceId.SHARE_IN

    override suspend fun scan(): SourceResult {
        val list = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") }.orEmpty()
        val files = list.map {
            DiscoveredFile(id, Uri.fromFile(it).toString(), "imported/${it.name}", it.name, it.length(), it.lastModified())
        }
        return SourceResult(SourceProbe(id, true, null, listOf("app imported/"), listOf("${files.size} shared-in files")), files)
    }
}
