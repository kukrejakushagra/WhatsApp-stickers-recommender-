package com.kukreja.stickersearch.index

import android.content.Context
import android.net.Uri
import com.kukreja.stickersearch.data.FileStatus
import com.kukreja.stickersearch.data.StickerDatabase
import com.kukreja.stickersearch.data.StickerEntity
import com.kukreja.stickersearch.data.StickerFileEntity
import com.kukreja.stickersearch.data.StickerFrameEntity
import com.kukreja.stickersearch.data.StickerStatus
import com.kukreja.stickersearch.media.FormatSniffer
import com.kukreja.stickersearch.media.Hasher
import com.kukreja.stickersearch.media.Thumbnailer
import com.kukreja.stickersearch.model.DiscoveredFile
import com.kukreja.stickersearch.model.StickerFormat
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
        val updated = try {
            when (format.format) {
                StickerFormat.LOTTIE_WAS -> {
                    val r = frames.lottie(sha, bytes)
                    if (r == null) {
                        sticker.copy(status = StickerStatus.NEEDS_REVIEW, statusError = "not a Lottie archive we can read")
                    } else {
                        saveFrames(stickerId, r)
                        sticker.copy(thumbnailPath = r.thumbnailPath, durationMs = r.durationMs,
                            frameCount = r.frames.size, status = StickerStatus.PENDING)
                    }
                }
                StickerFormat.WEBP_ANIMATED -> {
                    val thumb = thumbnailer.ensureThumbnail(sha, bytes)
                    val r = frames.webp(sha, bytes)
                    if (r != null) saveFrames(stickerId, r)
                    sticker.copy(
                        thumbnailPath = thumb, durationMs = r?.durationMs,
                        status = if (r == null) StickerStatus.NEEDS_REVIEW else StickerStatus.PENDING,
                        statusError = if (r == null) "no frame could be decoded" else null,
                    )
                }
                else -> sticker.copy(thumbnailPath = thumbnailer.ensureThumbnail(sha, bytes), status = StickerStatus.PENDING)
            }
        } catch (e: Throwable) {
            if (e is OutOfMemoryError) System.gc()
            sticker.copy(status = StickerStatus.FAILED, statusError = "${e.javaClass.simpleName}: ${e.message}", attempts = sticker.attempts + 1)
        }
        db.stickers().update(updated.copy(updatedAt = now))
        return IngestOutcome.NEW_STICKER
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
            FrameExtractor(context, File(context.filesDir, "frames")),
        )
    }
}
