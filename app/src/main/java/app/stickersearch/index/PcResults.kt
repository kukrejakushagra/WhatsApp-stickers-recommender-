package app.stickersearch.index

import app.stickersearch.ml.Annotation
import app.stickersearch.ml.Correction
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads the lines of results.jsonl (from describe.py) or of a descriptions file shared from another
 * phone. Pure Kotlin, so it's tested on the JVM.
 *
 * A file copied while the script was still writing can end in half a line: that last line is
 * dropped quietly (the next import picks it up), while a bad line anywhere else counts as unreadable.
 */
object PcResults {
    /** "pc": described on a computer. "shared": exported from another phone's library. */
    enum class Source { PC, SHARED }

    data class Line(
        val sha256: String,
        val model: String,
        /** The model's reply (PC) or the stored annotation as JSON (shared). */
        val raw: String,
        val promptVersion: Int?,
        val pcPrompt: Int?,
        val source: Source,
        /** The other phone's own edits, if any (shared files only). */
        val correction: Correction?,
    ) {
        /** How the description is labelled in the library, so later imports can tell what's newer. */
        val label: String get() = when (source) {
            Source.PC -> "pc:$model" + (pcPrompt?.takeIf { it > 0 }?.let { ":p$it" } ?: "")
            Source.SHARED -> "shared:$model"
        }

        /** The description itself; null when it can't be read. */
        fun annotation(): Annotation? = when (source) {
            Source.PC -> Annotation.parse(raw)
            Source.SHARED -> Annotation.fromJson(raw)?.takeUnless { it.description.isBlank() && it.tags.isEmpty() }
        }
    }

    data class Parsed(val lines: List<Line>, val unreadable: Int, val cutOffTail: Boolean)

    fun parse(text: List<String>): Parsed {
        val nonBlank = text.filter { it.isNotBlank() }
        val out = ArrayList<Line>(nonBlank.size)
        var unreadable = 0
        var cutOff = false
        nonBlank.forEachIndexed { i, line ->
            val parsed = runCatching { readLine(line) }.getOrNull()
            if (parsed == null) {
                if (i == nonBlank.lastIndex) cutOff = true else unreadable++
            } else {
                out += parsed
            }
        }
        return Parsed(out, unreadable, cutOff)
    }

    private fun readLine(line: String): Line? {
        val o = JSONObject(line)
        val sha = o.optString("sha256")
        val raw = o.optJSONObject("annotation")?.toString() ?: o.optString("raw")
        val correction = o.optJSONObject("correction")?.let(::correctionFrom)
        // A reply is always a JSON object; anything else (a number, plain words) isn't one.
        val hasReply = raw.contains('{')
        if (sha.isBlank() || (!hasReply && correction == null)) return null
        return Line(
            sha256 = sha,
            model = o.optString("model").ifBlank { "unknown" }.take(60),
            raw = if (hasReply) raw else "",
            promptVersion = if (o.has("prompt_version")) o.optInt("prompt_version") else null,
            pcPrompt = if (o.has("pc_prompt")) o.optInt("pc_prompt") else null,
            source = if (o.optString("source") == "shared") Source.SHARED else Source.PC,
            correction = correction,
        )
    }

    private fun correctionFrom(o: JSONObject): Correction? {
        fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }
        val c = Correction(
            description = o.optString("description").takeIf { it.isNotBlank() },
            emotions = list("emotions"), intents = list("intents"), tags = list("tags"),
        )
        return c.takeUnless { it.description == null && it.emotions == null && it.intents == null && it.tags == null }
    }

    /** One line of a shared descriptions file. */
    fun sharedLine(sha256: String, model: String, annotation: Annotation?, correction: Correction?): String {
        val o = JSONObject().put("sha256", sha256).put("source", "shared").put("model", model)
        annotation?.let { o.put("annotation", JSONObject(it.toJson())) }
        correction?.let { c ->
            o.put("correction", JSONObject().apply {
                c.description?.let { put("description", it) }
                c.emotions?.let { put("emotions", JSONArray(it)) }
                c.intents?.let { put("intents", JSONArray(it)) }
                c.tags?.let { put("tags", JSONArray(it)) }
            })
        }
        return o.toString()
    }
}
