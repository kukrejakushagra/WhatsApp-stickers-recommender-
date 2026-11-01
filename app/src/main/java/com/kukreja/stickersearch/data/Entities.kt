package com.kukreja.stickersearch.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

/** Processing status of a unique sticker. */
enum class StickerStatus { DISCOVERED, PENDING, PROCESSING, INDEXED, FAILED, NEEDS_REVIEW, MISSING }

/** Status of one file on disk (a sticker can have several). */
enum class FileStatus { OK, FAILED, IGNORED }

/** One row per unique sticker (content hash). AI columns stay null until M3. */
@Entity(tableName = "stickers", indices = [Index(value = ["sha256"], unique = true), Index("status")])
data class StickerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sha256: String,
    val format: String,
    @ColumnInfo(name = "is_animated") val isAnimated: Boolean,
    @ColumnInfo(name = "frame_count") val frameCount: Int,
    val width: Int,
    val height: Int,
    @ColumnInfo(name = "duration_ms") val durationMs: Long? = null,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "thumbnail_path") val thumbnailPath: String? = null,
    val status: StickerStatus,
    @ColumnInfo(name = "status_error") val statusError: String? = null,
    val attempts: Int = 0,
    // ---- AI annotation (M3); user corrections live in user_corrections and always win ----
    val description: String? = null,
    @ColumnInfo(name = "emotional_intent") val emotionalIntent: String? = null,
    @ColumnInfo(name = "conversational_intent") val conversationalIntent: String? = null,
    @ColumnInfo(name = "search_phrases") val searchPhrases: String? = null,
    @ColumnInfo(name = "ocr_text") val ocrText: String? = null,
    val confidence: Float? = null,
    @ColumnInfo(name = "annotator_model") val annotatorModel: String? = null,
    @ColumnInfo(name = "prompt_version") val promptVersion: Int? = null,
    val embedding: ByteArray? = null,
    @ColumnInfo(name = "embedding_model") val embeddingModel: String? = null,
    @ColumnInfo(name = "image_embedding") val imageEmbedding: ByteArray? = null,
    @ColumnInfo(name = "user_favourite") val userFavourite: Boolean = false,
    @ColumnInfo(name = "last_used_at") val lastUsedAt: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    // ByteArray fields: identity equality is fine for an entity.
    override fun equals(other: Any?) = other is StickerEntity && other.id == id && other.updatedAt == updatedAt && other.status == status
    override fun hashCode() = id.hashCode()
}

/** Every place a sticker was seen. A failed or ignored file has no sticker yet. */
@Entity(
    tableName = "sticker_files",
    foreignKeys = [ForeignKey(entity = StickerEntity::class, parentColumns = ["id"], childColumns = ["sticker_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["uri"], unique = true), Index("sticker_id")],
)
data class StickerFileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "sticker_id") val stickerId: Long?,
    val source: String,
    val uri: String,
    @ColumnInfo(name = "display_path") val displayPath: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "last_modified") val lastModified: Long,
    val status: FileStatus,
    val error: String? = null,
    val attempts: Int = 0,
    @ColumnInfo(name = "first_seen_at") val firstSeenAt: Long,
    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long,
    @ColumnInfo(name = "missing_since") val missingSince: Long? = null,
)

@Entity(
    tableName = "sticker_frames",
    foreignKeys = [ForeignKey(entity = StickerEntity::class, parentColumns = ["id"], childColumns = ["sticker_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sticker_id")],
)
data class StickerFrameEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "sticker_id") val stickerId: Long,
    @ColumnInfo(name = "frame_index") val frameIndex: Int,
    @ColumnInfo(name = "timestamp_ms") val timestampMs: Long,
    @ColumnInfo(name = "frame_path") val framePath: String,
    @ColumnInfo(name = "change_score") val changeScore: Float,
)

/** Tags from the AI (M3) or the user. */
@Entity(
    tableName = "sticker_tags",
    foreignKeys = [ForeignKey(entity = StickerEntity::class, parentColumns = ["id"], childColumns = ["sticker_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["sticker_id", "tag", "source"], unique = true)],
)
data class StickerTagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "sticker_id") val stickerId: Long,
    val tag: String,
    val source: String,
    val confidence: Float? = null,
)

/** User corrections always win over AI annotations and are never touched by re-indexing. */
@Entity(
    tableName = "user_corrections",
    foreignKeys = [ForeignKey(entity = StickerEntity::class, parentColumns = ["id"], childColumns = ["sticker_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["sticker_id"], unique = true)],
)
data class UserCorrectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "sticker_id") val stickerId: Long,
    @ColumnInfo(name = "corrected_description") val correctedDescription: String? = null,
    @ColumnInfo(name = "corrected_emotions") val correctedEmotions: String? = null,
    @ColumnInfo(name = "corrected_intents") val correctedIntents: String? = null,
    @ColumnInfo(name = "corrected_tags") val correctedTags: String? = null,
    val hidden: Boolean = false,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "scan_runs")
data class ScanRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "finished_at") val finishedAt: Long,
    val trigger: String,
    @ColumnInfo(name = "files_seen") val filesSeen: Int,
    @ColumnInfo(name = "files_processed") val filesProcessed: Int,
    @ColumnInfo(name = "new_stickers") val newStickers: Int,
    val missing: Int,
    val failed: Int,
    val error: String? = null,
)

/** Keyword index over the effective (correction-overridden) text; filled from M3. rowid = sticker id. */
@Fts4
@Entity(tableName = "sticker_fts")
data class StickerFtsEntity(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Long,
    val description: String,
    val tags: String,
    val phrases: String,
    @ColumnInfo(name = "ocr_text") val ocrText: String,
)
