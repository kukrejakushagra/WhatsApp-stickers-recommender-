package com.kukreja.stickersearch.data

import com.kukreja.stickersearch.ml.Annotation
import com.kukreja.stickersearch.ml.Correction
import com.kukreja.stickersearch.ml.Vectors
import org.json.JSONArray

/**
 * Reads and writes annotations. The model's output lives on the sticker row and in
 * sticker_tags(source=AI); user edits live only in user_corrections and always win when read.
 * Re-annotating a sticker never touches its correction.
 */
class AnnotationRepository(private val db: StickerDatabase) {

    private val ai get() = db.ai()

    suspend fun saveModel(stickerId: Long, a: Annotation, model: String, promptVersion: Int) {
        val now = System.currentTimeMillis()
        ai.setAnnotation(
            stickerId, a.description, a.text, JSONArray(a.emotions).toString(), JSONArray(a.intents).toString(),
            JSONArray(a.phrases).toString(), a.confidence, model, promptVersion, now,
        )
        ai.replaceTags(stickerId, SOURCE_AI, a.tags)
        ai.replaceTags(stickerId, SOURCE_EMOJI, listOfNotNull(a.emoji.takeIf { it.isNotBlank() }))
    }

    /** The model's annotation for a sticker, or null if it hasn't been annotated yet. */
    suspend fun model(stickerId: Long): Annotation? {
        val s = db.stickers().byId(stickerId) ?: return null
        val tags = ai.tags(stickerId)
        return fromRow(s.description, s.ocrText, s.emotionalIntent, s.conversationalIntent, s.searchPhrases, s.confidence,
            tags.filter { it.source == SOURCE_AI }.map { it.tag }, tags.firstOrNull { it.source == SOURCE_EMOJI }?.tag)
    }

    suspend fun correction(stickerId: Long): Correction? = ai.correction(stickerId)?.toCorrection()

    /** What search and the UI use: the model's annotation with the user's edits on top. */
    suspend fun effective(stickerId: Long): Annotation? {
        val m = model(stickerId)
        val c = correction(stickerId)
        if (m == null && c == null) return null
        return (m ?: Annotation()).overriddenBy(c)
    }

    /** Saves the user's edits and clears the text vector so it's rebuilt from the corrected text. */
    suspend fun saveCorrection(stickerId: Long, c: Correction) {
        val now = System.currentTimeMillis()
        db.ai().upsertCorrection(
            UserCorrectionEntity(
                stickerId = stickerId, correctedDescription = c.description,
                correctedEmotions = c.emotions?.let { JSONArray(it).toString() },
                correctedIntents = c.intents?.let { JSONArray(it).toString() },
                correctedTags = c.tags?.let { JSONArray(it).toString() }, updatedAt = now,
            ),
        )
        c.tags?.let { ai.replaceTags(stickerId, SOURCE_USER, it) }
        ai.setTextEmbedding(stickerId, null, null, now)
    }

    suspend fun clearCorrection(stickerId: Long) {
        ai.deleteCorrection(stickerId)
        ai.deleteTags(stickerId, SOURCE_USER)
        ai.setTextEmbedding(stickerId, null, null, System.currentTimeMillis())
    }

    suspend fun saveTextEmbedding(stickerId: Long, v: FloatArray, model: String) =
        ai.setTextEmbedding(stickerId, Vectors.toBytes(v), model, System.currentTimeMillis())

    companion object {
        const val SOURCE_AI = "AI"
        const val SOURCE_USER = "USER"
        const val SOURCE_EMOJI = "EMOJI"

        fun jsonList(s: String?): List<String> = s?.let {
            runCatching { JSONArray(it).let { a -> (0 until a.length()).map { i -> a.getString(i) } } }.getOrNull()
        } ?: emptyList()

        fun fromRow(
            description: String?, ocr: String?, emotions: String?, intents: String?, phrases: String?,
            confidence: Float?, tags: List<String>, emoji: String?,
        ): Annotation? {
            if (description == null) return null
            return Annotation(description, ocr ?: "", jsonList(emotions), jsonList(intents), tags, jsonList(phrases),
                emoji ?: "", confidence ?: 0f)
        }

        fun UserCorrectionEntity.toCorrection() = Correction(
            description = correctedDescription,
            emotions = correctedEmotions?.let(::jsonList),
            intents = correctedIntents?.let(::jsonList),
            tags = correctedTags?.let(::jsonList),
        )
    }
}
