package app.stickersearch.ml

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Gemma's reading of a conversation: what's going on, and what to search for. */
data class ReplyPlan(
    val reading: String,
    val queries: List<String>,
    val avoid: List<String>,
    val seen: List<String>,
) {
    companion object {
        fun parse(reply: String): ReplyPlan? {
            val start = reply.indexOf('{')
            val end = reply.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            val o = try { JSONObject(reply.substring(start, end + 1)) } catch (e: JSONException) { return null }
            fun list(key: String, limit: Int): List<String> = when (val v = o.opt(key)) {
                is JSONArray -> (0 until v.length()).mapNotNull { v.opt(it)?.toString()?.trim() }
                is String -> v.split(',', ';').map { it.trim() }
                else -> emptyList()
            }.filter { it.isNotEmpty() && it != "null" }.distinct().take(limit)
            val plan = ReplyPlan(o.optString("reading").trim(), list("queries", 4), list("avoid", 3), list("seen", 4))
            return plan.takeIf { it.queries.isNotEmpty() || it.reading.isNotEmpty() }
        }
    }
}
