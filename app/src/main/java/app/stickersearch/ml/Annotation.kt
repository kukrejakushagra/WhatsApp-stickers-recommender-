package app.stickersearch.ml

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** What the app knows about a sticker's meaning, from the model or from the user. */
data class Annotation(
    val description: String = "",
    val text: String = "",
    val emotions: List<String> = emptyList(),
    val intents: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    /** Things people would type: English, Hinglish and Hindi together. */
    val phrases: List<String> = emptyList(),
    val emoji: String = "",
    val confidence: Float = 0f,
) {
    val isEmpty get() = description.isBlank() && tags.isEmpty() && phrases.isEmpty()

    /** The text a sticker is searched by (embedded with the "passage: " prefix, and indexed for keywords). */
    fun searchText(): String = buildString {
        append(description.trim())
        if (text.isNotBlank()) append(" Text: ").append(text.trim()).append('.')
        if (emotions.isNotEmpty()) append(" Feels: ").append(emotions.joinToString(", ") { it.replace('_', ' ') }).append('.')
        if (intents.isNotEmpty()) append(" Use: ").append(intents.joinToString(", ") { it.replace('_', ' ') }).append('.')
        if (tags.isNotEmpty()) append(" Tags: ").append(tags.joinToString(", ")).append('.')
        if (phrases.isNotEmpty()) append(' ').append(phrases.joinToString(". "))
    }.trim()

    /** User corrections win field by field; an empty correction field keeps the model's value. */
    fun overriddenBy(c: Correction?): Annotation = if (c == null) this else copy(
        description = c.description?.takeIf { it.isNotBlank() } ?: description,
        emotions = c.emotions ?: emotions,
        intents = c.intents ?: intents,
        tags = c.tags ?: tags,
    )

    companion object {
        /** Vocabularies the prompt asks the model to choose from (free values are kept too). */
        val EMOTIONS = listOf(
            "happiness", "sadness", "anger", "frustration", "sarcasm", "disbelief", "surprise", "excitement",
            "confusion", "embarrassment", "disappointment", "amusement", "love", "fear", "boredom", "pride",
            "gratitude", "sympathy", "smugness", "awkwardness",
        )
        val INTENTS = listOf(
            "responding_to_joke", "mocking_friend", "expressing_disbelief", "celebrating", "expressing_frustration_playfully",
            "agreeing_sarcastically", "showing_disappointment", "ending_conversation", "greeting", "saying_thanks",
            "apologising", "saying_i_told_you_so", "encouraging", "refusing", "agreeing", "waiting", "expressing_love",
            "teasing", "reacting_to_mistake", "showing_surprise", "saying_goodnight", "not_impressed",
        )

        /**
         * Parses the model's reply leniently: finds the outermost JSON object even inside markdown
         * fences or chatter, and accepts strings where lists were asked for.
         */
        fun parse(reply: String): Annotation? {
            val start = reply.indexOf('{')
            val end = reply.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            val o = try { JSONObject(reply.substring(start, end + 1)) } catch (e: JSONException) { return null }
            fun list(key: String, limit: Int): List<String> {
                val v = o.opt(key) ?: return emptyList()
                val raw = when (v) {
                    is JSONArray -> (0 until v.length()).mapNotNull { v.opt(it)?.toString() }
                    is String -> v.split(',', ';')
                    else -> emptyList()
                }
                return raw.map { it.trim().trim('"') }.filter { it.isNotEmpty() }.distinct().take(limit)
            }
            fun label(s: String) = s.lowercase().replace(Regex("[^a-z_ ]"), "").trim().replace(' ', '_')
            val a = Annotation(
                description = o.optString("description").trim(),
                text = o.optString("text").trim().let { if (it.equals("none", true) || it == "\"\"") "" else it },
                emotions = list("emotions", 4).map(::label).filter { it.isNotEmpty() },
                intents = list("intents", 4).map(::label).filter { it.isNotEmpty() },
                // PC replies also list what's visible and the setting it implies: kept as tags for search.
                tags = (list("tags", 16) + list("setting", 5) + list("visible", 10)).map { it.lowercase() }.distinct().take(24),
                phrases = (list("phrases", 8) + list("hinglish", 5) + list("hindi", 3)).distinct(),
                emoji = o.optString("emoji").trim().take(8),
                confidence = o.optDouble("confidence", 0.5).toFloat().coerceIn(0f, 1f),
            )
            return a.takeUnless { it.isEmpty }
        }

        fun fromJson(s: String?): Annotation? = s?.let { runCatching { fromStored(JSONObject(it)) }.getOrNull() }

        private fun fromStored(o: JSONObject) = Annotation(
            description = o.optString("description"), text = o.optString("text"),
            emotions = o.optJSONArray("emotions").strings(), intents = o.optJSONArray("intents").strings(),
            tags = o.optJSONArray("tags").strings(), phrases = o.optJSONArray("phrases").strings(),
            emoji = o.optString("emoji"), confidence = o.optDouble("confidence", 0.0).toFloat(),
        )

        private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }
    }

    fun toJson(): String = JSONObject()
        .put("description", description).put("text", text)
        .put("emotions", JSONArray(emotions)).put("intents", JSONArray(intents))
        .put("tags", JSONArray(tags)).put("phrases", JSONArray(phrases))
        .put("emoji", emoji).put("confidence", confidence.toDouble())
        .toString()
}

/** A user's edits; null fields mean "keep the model's value". */
data class Correction(
    val description: String? = null,
    val emotions: List<String>? = null,
    val intents: List<String>? = null,
    val tags: List<String>? = null,
)
