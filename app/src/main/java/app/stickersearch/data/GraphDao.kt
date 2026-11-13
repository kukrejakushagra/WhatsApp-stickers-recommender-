package app.stickersearch.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** What the graph builder needs to know about each sticker. */
data class GraphNodeRow(
    val id: Long,
    val isAnimated: Boolean,
    val imageEmbedding: ByteArray?,
    val embedding: ByteArray?,
    val newest: Long?,
    val path: String?,
) {
    override fun equals(other: Any?) = other is GraphNodeRow && other.id == id
    override fun hashCode() = id.hashCode()
}

data class EdgeCount(val type: EdgeType, val n: Int)

@Dao
interface GraphDao {
    @Query(
        """
        SELECT s.id, s.is_animated AS isAnimated, s.image_embedding AS imageEmbedding, s.embedding,
          (SELECT MAX(f.last_modified) FROM sticker_files f WHERE f.sticker_id = s.id) AS newest,
          (SELECT f.display_path FROM sticker_files f WHERE f.sticker_id = s.id AND f.missing_since IS NULL LIMIT 1) AS path
        FROM stickers s WHERE s.status != 'MISSING'
        """,
    )
    suspend fun nodes(): List<GraphNodeRow>

    @Query("SELECT * FROM frame_vectors")
    suspend fun frameVectors(): List<FrameVectorEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putFrameVectors(v: List<FrameVectorEntity>)

    @Query("SELECT COUNT(*) FROM frame_vectors")
    suspend fun frameVectorCount(): Int

    /** Animated stickers that have keyframes but no first/last vectors yet. */
    @Query(
        """
        SELECT s.* FROM stickers s
        WHERE s.is_animated = 1 AND s.status != 'MISSING'
          AND EXISTS (SELECT 1 FROM sticker_frames fr WHERE fr.sticker_id = s.id)
          AND NOT EXISTS (SELECT 1 FROM frame_vectors v WHERE v.sticker_id = s.id)
        LIMIT :limit
        """,
    )
    suspend fun needingFrameVectors(limit: Int): List<StickerEntity>

    @Query("SELECT * FROM sticker_edges")
    suspend fun edges(): List<StickerEdgeEntity>

    @Query("SELECT * FROM sticker_edges WHERE a = :id OR b = :id")
    suspend fun edgesOf(id: Long): List<StickerEdgeEntity>

    @Query("SELECT type, COUNT(*) AS n FROM sticker_edges GROUP BY type")
    fun observeCounts(): Flow<List<EdgeCount>>

    @Query("SELECT type, COUNT(*) AS n FROM sticker_edges GROUP BY type")
    suspend fun counts(): List<EdgeCount>

    @Query("SELECT * FROM sticker_edges ORDER BY weight DESC LIMIT :n")
    suspend fun strongest(n: Int): List<StickerEdgeEntity>

    @Query("DELETE FROM sticker_edges")
    suspend fun clearEdges()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEdges(e: List<StickerEdgeEntity>)

    @Transaction
    suspend fun replaceAll(e: List<StickerEdgeEntity>) {
        clearEdges()
        e.chunked(500).forEach { insertEdges(it) }
    }
}
