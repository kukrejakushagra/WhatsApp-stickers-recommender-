package app.stickersearch.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Grid row: a sticker plus one live file to open, and its newest file time. */
data class StickerRow(
    val id: Long,
    val sha256: String,
    val format: String,
    val isAnimated: Boolean,
    val frameCount: Int,
    val width: Int,
    val height: Int,
    val durationMs: Long?,
    val sizeBytes: Long,
    val thumbnailPath: String?,
    val status: StickerStatus,
    val statusError: String?,
    val primaryUri: String?,
    val newest: Long?,
    val fileCount: Int,
)

data class StatusCount(val status: StickerStatus, val n: Int)

data class FileStatusCount(val status: FileStatus, val n: Int)

@Dao
interface StickerDao {

    @Query("SELECT * FROM stickers WHERE sha256 = :sha LIMIT 1")
    suspend fun bySha(sha: String): StickerEntity?

    @Query("SELECT * FROM stickers WHERE id = :id")
    suspend fun byId(id: Long): StickerEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(s: StickerEntity): Long

    @Update
    suspend fun update(s: StickerEntity)

    @Query(
        """
        SELECT s.id, s.sha256, s.format, s.is_animated AS isAnimated, s.frame_count AS frameCount, s.width, s.height,
               s.duration_ms AS durationMs, s.size_bytes AS sizeBytes, s.thumbnail_path AS thumbnailPath, s.status,
               s.status_error AS statusError,
               (SELECT f.uri FROM sticker_files f WHERE f.sticker_id = s.id AND f.missing_since IS NULL LIMIT 1) AS primaryUri,
               (SELECT MAX(f.last_modified) FROM sticker_files f WHERE f.sticker_id = s.id) AS newest,
               (SELECT COUNT(*) FROM sticker_files f WHERE f.sticker_id = s.id AND f.missing_since IS NULL) AS fileCount
        FROM stickers s
        WHERE s.status != 'MISSING'
        ORDER BY newest DESC
        """,
    )
    fun observeVisible(): Flow<List<StickerRow>>

    @Query("SELECT status, COUNT(*) AS n FROM stickers GROUP BY status")
    fun observeStatusCounts(): Flow<List<StatusCount>>

    @Query("SELECT status, COUNT(*) AS n FROM stickers GROUP BY status")
    suspend fun statusCounts(): List<StatusCount>

    /** Stickers with no live file become MISSING; annotations and thumbnails are kept. */
    @Query(
        """
        UPDATE stickers SET status = 'MISSING', updated_at = :now
        WHERE status != 'MISSING' AND id NOT IN
          (SELECT sticker_id FROM sticker_files WHERE missing_since IS NULL AND sticker_id IS NOT NULL)
        """,
    )
    suspend fun markOrphansMissing(now: Long): Int

    /** A MISSING sticker whose file came back returns to PENDING (annotations, if any, are still there). */
    @Query(
        """
        UPDATE stickers SET status = 'PENDING', updated_at = :now
        WHERE status = 'MISSING' AND id IN
          (SELECT sticker_id FROM sticker_files WHERE missing_since IS NULL AND sticker_id IS NOT NULL)
        """,
    )
    suspend fun reviveReturned(now: Long): Int

    /** Stickers an earlier scan left unfinished, or Lottie stickers still worth another render attempt. */
    @Query(
        """
        SELECT * FROM stickers
        WHERE status IN ('DISCOVERED', 'PROCESSING')
           OR (format = 'LOTTIE_WAS' AND status IN ('NEEDS_REVIEW', 'FAILED') AND attempts < :maxAttempts)
        LIMIT :limit
        """,
    )
    suspend fun needsRepair(maxAttempts: Int, limit: Int): List<StickerEntity>

    @Query("SELECT uri FROM sticker_files WHERE sticker_id = :stickerId AND missing_since IS NULL LIMIT 1")
    suspend fun liveUri(stickerId: Long): String?

    @Query("UPDATE stickers SET attempts = 0 WHERE status IN ('FAILED', 'NEEDS_REVIEW')")
    suspend fun resetProblemAttempts()

    @Query("SELECT * FROM stickers WHERE status IN ('FAILED', 'NEEDS_REVIEW') LIMIT :limit")
    suspend fun problems(limit: Int): List<StickerEntity>

    @Query("SELECT * FROM stickers WHERE format = 'LOTTIE_WAS' LIMIT :limit")
    suspend fun lottieSamples(limit: Int): List<StickerEntity>

    @Query("SELECT COUNT(*) FROM stickers WHERE is_animated = 1 AND status != 'MISSING'")
    suspend fun animatedCount(): Int

    @Query("DELETE FROM stickers")
    suspend fun deleteAll()
}

/** Snapshot row used by the incremental planner. */
data class KnownFileRow(
    val id: Long,
    val source: String,
    val uri: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val stickerId: Long?,
    val status: FileStatus,
    val attempts: Int,
    val missingSince: Long?,
)

@Dao
interface FileDao {
    @Query(
        """SELECT id, source, uri, size_bytes AS sizeBytes, last_modified AS lastModified, sticker_id AS stickerId, status,
           attempts, missing_since AS missingSince FROM sticker_files""",
    )
    suspend fun snapshot(): List<KnownFileRow>

    @Query("SELECT * FROM sticker_files WHERE id = :id")
    suspend fun byId(id: Long): StickerFileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(f: StickerFileEntity): Long

    @Query("UPDATE sticker_files SET last_seen_at = :now, missing_since = NULL WHERE id IN (:ids)")
    suspend fun touch(ids: List<Long>, now: Long)

    @Query("UPDATE sticker_files SET missing_since = :now WHERE id IN (:ids) AND missing_since IS NULL")
    suspend fun markMissing(ids: List<Long>, now: Long)

    @Query("SELECT display_path FROM sticker_files WHERE sticker_id = :stickerId ORDER BY display_path")
    suspend fun pathsFor(stickerId: Long): List<String>

    @Query("SELECT status, COUNT(*) AS n FROM sticker_files WHERE missing_since IS NULL GROUP BY status")
    suspend fun statusCounts(): List<FileStatusCount>

    @Query("SELECT display_path || ': ' || COALESCE(error, '') FROM sticker_files WHERE status = 'FAILED' LIMIT :limit")
    suspend fun failureSamples(limit: Int): List<String>

    /** Lets "Retry failed" re-attempt files that hit the retry cap. */
    @Query("UPDATE sticker_files SET attempts = 0 WHERE status = 'FAILED'")
    suspend fun resetFailedAttempts()

    @Query("DELETE FROM sticker_files")
    suspend fun deleteAll()
}

@Dao
interface FrameDao {
    @Insert
    suspend fun insertAll(frames: List<StickerFrameEntity>)

    @Query("DELETE FROM sticker_frames WHERE sticker_id = :stickerId")
    suspend fun deleteFor(stickerId: Long)

    @Query("SELECT * FROM sticker_frames WHERE sticker_id = :stickerId ORDER BY frame_index")
    suspend fun forSticker(stickerId: Long): List<StickerFrameEntity>

    @Query("SELECT COUNT(*) FROM sticker_frames")
    suspend fun count(): Int

    @Transaction
    suspend fun replace(stickerId: Long, frames: List<StickerFrameEntity>) {
        deleteFor(stickerId)
        insertAll(frames)
    }
}

@Dao
interface ScanRunDao {
    @Insert
    suspend fun insert(r: ScanRunEntity): Long

    @Query("SELECT * FROM scan_runs ORDER BY id DESC LIMIT 1")
    fun observeLast(): Flow<ScanRunEntity?>

    @Query("SELECT * FROM scan_runs ORDER BY id DESC LIMIT :n")
    suspend fun recent(n: Int): List<ScanRunEntity>

    @Query("DELETE FROM scan_runs")
    suspend fun deleteAll()
}
