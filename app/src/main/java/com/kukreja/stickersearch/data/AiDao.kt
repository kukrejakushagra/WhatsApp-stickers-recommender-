package com.kukreja.stickersearch.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Row for building the in-memory search index. */
data class SearchDoc(
    val id: Long,
    val description: String?,
    val ocrText: String?,
    val emotions: String?,
    val intents: String?,
    val phrases: String?,
    val embedding: ByteArray?,
    val imageEmbedding: ByteArray?,
    val lastUsedAt: Long?,
    val userFavourite: Boolean,
) {
    override fun equals(other: Any?) = other is SearchDoc && other.id == id
    override fun hashCode() = id.hashCode()
}

data class AiCounts(val total: Int, val visual: Int, val annotated: Int, val embedded: Int)

data class TagRow(val stickerId: Long, val tag: String, val source: String)

@Dao
interface AiDao {

    /** Stickers still missing a visual fingerprint, newest file first. */
    @Query(
        """
        SELECT s.* FROM stickers s
        WHERE s.image_embedding IS NULL AND s.status IN ('PENDING', 'INDEXED') AND s.thumbnail_path IS NOT NULL
        ORDER BY (SELECT MAX(f.last_modified) FROM sticker_files f WHERE f.sticker_id = s.id) DESC
        LIMIT :limit
        """,
    )
    suspend fun pendingVisual(limit: Int): List<StickerEntity>

    /** Stickers waiting for the language model, newest file first. */
    @Query(
        """
        SELECT s.* FROM stickers s
        WHERE s.status = 'PENDING' AND s.attempts < :maxAttempts
        ORDER BY (SELECT MAX(f.last_modified) FROM sticker_files f WHERE f.sticker_id = s.id) DESC
        LIMIT :limit
        """,
    )
    suspend fun pendingAnnotation(maxAttempts: Int, limit: Int): List<StickerEntity>

    /** Annotated stickers whose text vector is missing (e.g. the text model arrived later, or a correction). */
    @Query(
        """SELECT * FROM stickers WHERE embedding IS NULL AND status != 'MISSING'
           AND (description IS NOT NULL OR id IN (SELECT sticker_id FROM user_corrections)) LIMIT :limit""",
    )
    suspend fun pendingTextEmbedding(limit: Int): List<StickerEntity>

    @Query("SELECT COUNT(*) FROM stickers WHERE image_embedding IS NULL AND status IN ('PENDING', 'INDEXED') AND thumbnail_path IS NOT NULL")
    suspend fun countPendingVisual(): Int

    @Query("SELECT COUNT(*) FROM stickers WHERE status = 'PENDING' AND attempts < :maxAttempts")
    suspend fun countPendingAnnotation(maxAttempts: Int): Int

    @Query(
        """SELECT COUNT(*) FROM stickers WHERE embedding IS NULL AND status != 'MISSING'
           AND (description IS NOT NULL OR id IN (SELECT sticker_id FROM user_corrections))""",
    )
    suspend fun countPendingTextEmbedding(): Int

    @Query(
        """
        SELECT COUNT(*) AS total,
          COALESCE(SUM(CASE WHEN length(image_embedding) > 0 THEN 1 ELSE 0 END), 0) AS visual,
          COALESCE(SUM(CASE WHEN description IS NOT NULL THEN 1 ELSE 0 END), 0) AS annotated,
          COALESCE(SUM(CASE WHEN embedding IS NOT NULL THEN 1 ELSE 0 END), 0) AS embedded
        FROM stickers WHERE status != 'MISSING'
        """,
    )
    fun observeCounts(): Flow<AiCounts>

    @Query(
        """
        SELECT id, description, ocr_text AS ocrText, emotional_intent AS emotions, conversational_intent AS intents,
               search_phrases AS phrases, embedding, image_embedding AS imageEmbedding, last_used_at AS lastUsedAt,
               user_favourite AS userFavourite
        FROM stickers WHERE status != 'MISSING'
        """,
    )
    suspend fun searchDocs(): List<SearchDoc>

    /** Bumps whenever annotations, vectors or corrections change, so the search index reloads. */
    @Query("SELECT COALESCE(MAX(updated_at), 0) + COUNT(*) FROM stickers")
    fun observeVersion(): Flow<Long>

    @Query("SELECT COALESCE(MAX(updated_at), 0) + COUNT(*) FROM stickers")
    suspend fun observeVersionOnce(): Long

    @Query("SELECT COUNT(*) FROM sticker_tags")
    suspend fun tagCount(): Int

    @Query("UPDATE stickers SET image_embedding = :v, updated_at = :now WHERE id = :id")
    suspend fun setImageEmbedding(id: Long, v: ByteArray, now: Long)

    @Query("UPDATE stickers SET embedding = :v, embedding_model = :model, updated_at = :now WHERE id = :id")
    suspend fun setTextEmbedding(id: Long, v: ByteArray?, model: String?, now: Long)

    @Query(
        """
        UPDATE stickers SET description = :description, ocr_text = :ocr, emotional_intent = :emotions,
          conversational_intent = :intents, search_phrases = :phrases, confidence = :confidence,
          annotator_model = :model, prompt_version = :promptVersion, status = 'INDEXED', status_error = NULL,
          embedding = NULL, updated_at = :now
        WHERE id = :id
        """,
    )
    suspend fun setAnnotation(
        id: Long, description: String, ocr: String, emotions: String, intents: String, phrases: String,
        confidence: Float, model: String, promptVersion: Int, now: Long,
    )

    @Query("UPDATE stickers SET attempts = attempts + 1, status_error = :error, updated_at = :now WHERE id = :id")
    suspend fun annotationFailed(id: Long, error: String, now: Long)

    @Query("UPDATE stickers SET status = 'NEEDS_REVIEW' WHERE status = 'PENDING' AND attempts >= :maxAttempts")
    suspend fun parkUnreadable(maxAttempts: Int)

    @Query("UPDATE stickers SET last_used_at = :now WHERE id = :id")
    suspend fun markUsed(id: Long, now: Long)

    @Query("SELECT id FROM stickers WHERE last_used_at IS NOT NULL AND status != 'MISSING' ORDER BY last_used_at DESC LIMIT :limit")
    fun observeRecentlyUsed(limit: Int): Flow<List<Long>>

    // ---- tags ----

    @Query("SELECT sticker_id AS stickerId, tag, source FROM sticker_tags WHERE sticker_id = :id")
    suspend fun tags(id: Long): List<TagRow>

    @Query("SELECT sticker_id AS stickerId, tag, source FROM sticker_tags")
    suspend fun allTags(): List<TagRow>

    @Query("DELETE FROM sticker_tags WHERE sticker_id = :id AND source = :source")
    suspend fun deleteTags(id: Long, source: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTags(tags: List<StickerTagEntity>)

    @Transaction
    suspend fun replaceTags(id: Long, source: String, tags: List<String>) {
        deleteTags(id, source)
        insertTags(tags.distinct().map { StickerTagEntity(stickerId = id, tag = it, source = source) })
    }

    // ---- corrections ----

    @Query("SELECT * FROM user_corrections WHERE sticker_id = :id")
    suspend fun correction(id: Long): UserCorrectionEntity?

    @Query("SELECT * FROM user_corrections")
    suspend fun allCorrections(): List<UserCorrectionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCorrection(c: UserCorrectionEntity)

    @Query("DELETE FROM user_corrections WHERE sticker_id = :id")
    suspend fun deleteCorrection(id: Long)
}
