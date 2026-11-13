package app.stickersearch.diagnostics

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Append-only log of manual experiments (share-in received, test sends) so they appear in the
 * exported report. Stored as JSON lines in app-private storage; never leaves the device unless
 * the user exports the report.
 */
class EventLog(private val file: File) {

    @Synchronized
    fun add(type: String, fields: Map<String, Any?>) {
        val o = JSONObject().put("type", type).put("atMs", System.currentTimeMillis())
        fields.forEach { (k, v) -> o.put(k, v ?: JSONObject.NULL) }
        file.appendText(o.toString() + "\n")
    }

    @Synchronized
    fun all(): JSONArray {
        val arr = JSONArray()
        if (!file.exists()) return arr
        file.readLines().filter { it.isNotBlank() }.forEach {
            runCatching { arr.put(JSONObject(it)) }
        }
        return arr
    }

    @Synchronized
    fun clear() { file.delete() }
}
