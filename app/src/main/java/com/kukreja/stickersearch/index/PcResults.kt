package com.kukreja.stickersearch.index

import org.json.JSONObject

/**
 * Reads the lines of results.jsonl written by describe.py. Pure Kotlin, so it's tested on the JVM.
 *
 * A file copied while the script was still writing can end in half a line: that last line is
 * dropped quietly (the next import picks it up), while a bad line anywhere else counts as unreadable.
 */
object PcResults {
    data class Line(val sha256: String, val model: String, val raw: String, val promptVersion: Int?)
    data class Parsed(val lines: List<Line>, val unreadable: Int, val cutOffTail: Boolean)

    fun parse(text: List<String>): Parsed {
        val nonBlank = text.filter { it.isNotBlank() }
        val out = ArrayList<Line>(nonBlank.size)
        var unreadable = 0
        var cutOff = false
        nonBlank.forEachIndexed { i, line ->
            val o = runCatching { JSONObject(line) }.getOrNull()
            val sha = o?.optString("sha256").orEmpty()
            val raw = o?.optJSONObject("annotation")?.toString() ?: o?.optString("raw").orEmpty()
            if (o == null || sha.isBlank() || raw.isBlank()) {
                if (i == nonBlank.lastIndex) cutOff = true else unreadable++
                return@forEachIndexed
            }
            out += Line(
                sha256 = sha,
                model = o.optString("model").ifBlank { "pc" }.take(60),
                raw = raw,
                promptVersion = if (o.has("prompt_version")) o.optInt("prompt_version") else null,
            )
        }
        return Parsed(out, unreadable, cutOff)
    }
}
