package app.stickersearch.index

import android.content.Context
import android.net.Uri
import app.stickersearch.data.FileStatus
import app.stickersearch.data.StickerDatabase
import app.stickersearch.data.StickerEntity
import app.stickersearch.data.StickerFileEntity
import app.stickersearch.data.StickerFrameEntity
import app.stickersearch.data.StickerStatus
import app.stickersearch.media.FormatSniffer
import app.stickersearch.media.Hasher
import app.stickersearch.media.Thumbnailer
import app.stickersearch.model.DiscoveredFile
import app.stickersearch.model.StickerFormat
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

enum class IngestOutcome { NEW_STICKER, ATTACHED, IGNORED, FAILED }

/**
 * Turns one new or changed file into database rows: hash → dedup → (new sticker only) sniff,
 * thumbnail, keyframes → status PENDING, the queue the M3 annotator reads.
 */
class StickerIngestor(
    private val context: Context,
    private val db: StickerDatabase,
    private val thumbnailer: Thumbnailer,
    private val frames: FrameExtractor,
) {
    /** Serialises "find sticker by hash or create it" so duplicates processed in parallel can't race. */
    private val createLock = Mutex()

    suspend fun ingest(f: DiscoveredFile, knownId: Long?, now: Long): IngestOutcome {
        val prev = knownId?.let { db.files().byId(it) }
        fun fileRow(stickerId: Long?, status: FileStatus, error: String? = null) = StickerFileEntity(
            id = knownId ?: 0, stickerId = stickerId, source = f.source.name, uri = f.uri, displayPath = f.displayPath,
            sizeBytes = f.sizeBytes, lastModified = f.lastModifiedMs, status = status, error = error,
            attempts = if (status == FileStatus.FAILED) (prev?.attempts ?: 0) + 1 else 0,
            firstSeenAt = prev?.firstSeenAt ?: now, lastSeenAt = now, missingSince = null,
        )

        if (f.sizeBytes > MAX_BYTES) {
            db.files().upsert(fileRow(null, FileStatus.IGNORED, "larger than ${MAX_BYTES / 1_048_576} MB"))
            return IngestOutcome.IGNORED
        }
        val bytes = try {
            context.contentResolver.openInputStream(Uri.parse(f.uri))?.use { it.readBytes() }
                ?: error("openInputStream returned null")
        } catch (e: Exception) {
            db.files().upsert(fileRow(null, FileStatus.FAILED, "read failed: ${e.javaClass.simpleName}: ${e.message}"))
            return IngestOutcome.FAILED
        }
        val sha = Hasher.sha256(bytes)
        val format = FormatSniffer.sniff(bytes)
        if (format.format == StickerFormat.UNKNOWN) {
            db.files().upsert(fileRow(null, FileStatus.IGNORED, "unrecognised format"))
            return IngestOutcome.IGNORED
        }

        val (stickerId, created) = createLock.withLock {
            db.stickers().bySha(sha)?.let { it.id to false } ?: (db.stickers().insert(
                StickerEntity(
                    sha256 = sha, format = format.format.name, isAnimated = format.isAnimated,
                    frameCount = format.frameCount, width = format.width, height = format.height,
                    sizeBytes = bytes.size.toLong(), status = StickerStatus.DISCOVERED, createdAt = now, updatedAt = now,
                ),
            ) to true)
        }
        db.files().upsert(fileRow(stickerId, FileStatus.OK))
        if (!created) return IngestOutcome.ATTACHED

        // New sticker: derive previews. Failures mark the sticker, never the whole scan.
        val sticker = db.stickers().byId(stickerId) ?: return IngestOutcome.FAILED
        db.stickers().update(derivePreviews(sticker, bytes, format.format).copy(updatedAt = now))
        return IngestOutcome.NEW_STICKER
    }

    /**
     * Re-derives previews for a sticker an earlier scan left unfinished (stopped mid-way) or
     * couldn't render (Lottie). Reads its first live file again; never touches user data.
     */
    suspend fun repair(sticker: StickerEntity, uri: String?, now: Long): Boolean {
        val bytes = uri?.let { u ->
            runCatching { context.contentResolver.openInputStream(Uri.parse(u))?.use { it.readBytes() } }.getOrNull()
        }
        val format = runCatching { StickerFormat.valueOf(sticker.format) }.getOrDefault(StickerFormat.UNKNOWN)
        val updated = if (bytes == null) {
            sticker.copy(status = StickerStatus.FAILED, statusError = "file couldn't be read again", attempts = sticker.attempts + 1)
        } else {
            derivePreviews(sticker, bytes, format).let { r ->
                if (r.status == StickerStatus.PENDING) r else r.copy(attempts = sticker.attempts + 1)
            }
        }
        db.stickers().update(updated.copy(updatedAt = now))
        return updated.status == StickerStatus.PENDING
    }

    private suspend fun derivePreviews(sticker: StickerEntity, bytes: ByteArray, format: StickerFormat): StickerEntity = try {
        val sha = sticker.sha256
        when (format) {
            StickerFormat.LOTTIE_WAS -> {
                val r = frames.lottie(sha, bytes)
                if (r.frames.isEmpty()) {
                    sticker.copy(status = StickerStatus.NEEDS_REVIEW, statusError = r.error ?: "no frames rendered")
                } else {
                    saveFrames(sticker.id, r)
                    sticker.copy(thumbnailPath = r.thumbnailPath, durationMs = r.durationMs,
                        frameCount = r.frames.size, status = StickerStatus.PENDING, statusError = null)
                }
            }
            StickerFormat.WEBP_ANIMATED -> {
                val r = frames.webp(sha, bytes)
                if (r != null && r.frames.isNotEmpty()) {
                    saveFrames(sticker.id, r)
                    sticker.copy(thumbnailPath = r.thumbnailPath, durationMs = r.durationMs,
                        status = StickerStatus.PENDING, statusError = null)
                } else {
                    // Keyframes failed: still show a still thumbnail from the platform decoder.
                    sticker.copy(
                        thumbnailPath = runCatching { thumbnailer.ensureThumbnail(sha, bytes) }.getOrNull(),
                        durationMs = r?.durationMs, status = StickerStatus.NEEDS_REVIEW,
                        statusError = r?.error ?: "not a readable animated WebP",
                    )
                }
            }
            else -> sticker.copy(thumbnailPath = thumbnailer.ensureThumbnail(sha, bytes), status = StickerStatus.PENDING, statusError = null)
        }
    } catch (e: Throwable) {
        if (e is OutOfMemoryError) System.gc()
        sticker.copy(status = StickerStatus.FAILED, statusError = "${e.javaClass.simpleName}: ${e.message}", attempts = sticker.attempts + 1)
    }

    private suspend fun saveFrames(stickerId: Long, r: FrameResult) {
        db.frames().replace(stickerId, r.frames.map {
            StickerFrameEntity(stickerId = stickerId, frameIndex = it.index, timestampMs = it.timestampMs,
                framePath = it.path, changeScore = it.changeScore)
        })
    }

    companion object {
        const val MAX_BYTES = 10L * 1024 * 1024

        fun create(context: Context): StickerIngestor = StickerIngestor(
            context, StickerDatabase.get(context),
            Thumbnailer(File(context.filesDir, "thumbs")),
            FrameExtractor(context, File(context.filesDir, "frames"), File(context.filesDir, "thumbs")),
        )
    }
}
