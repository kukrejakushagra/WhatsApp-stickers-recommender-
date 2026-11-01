package com.kukreja.stickersearch.diagnostics

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the exportable diagnostics JSON. It contains counts, formats, folder names and file
 * names (which are content hashes) only: no image data and nothing from chats.
 */
object ReportBuilder {

    fun build(
        context: Context,
        outcome: ScanOutcome?,
        permissions: Map<String, Boolean>,
        events: JSONArray,
    ): JSONObject {
        val root = JSONObject()
        root.put("reportVersion", 1)
        root.put("app", appVersion(context))
        root.put("device", JSONObject()
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("sdkInt", Build.VERSION.SDK_INT)
            .put("release", Build.VERSION.RELEASE)
            .put("display", Build.DISPLAY))
        root.put("whatsapp", JSONObject().apply {
            for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) put(pkg, packageVersion(context, pkg))
        })
        root.put("permissions", JSONObject(permissions as Map<*, *>))
        root.put("events", events)
        if (outcome == null) {
            root.put("scan", JSONObject.NULL)
            return root
        }
        val s = outcome.summary
        root.put("scan", JSONObject().apply {
            put("startedAtMs", outcome.startedAtMs)
            put("durationMs", outcome.durationMs)
            put("filesSeen", outcome.processed.size)
            put("uniqueStickers", s.uniqueCount)
            put("uniqueAnimated", s.animatedCount)
            put("animatedDecodeFailures", s.animatedDecodeFailures)
            put("lottieWas", s.lottieCount)
            put("waEntries", JSONArray().apply {
                outcome.processed.filter { it.zipEntries != null }.distinctBy { it.sha256 }.take(3).forEach {
                    put(JSONObject().put("file", it.file.name).put("entries", JSONArray(it.zipEntries)))
                }
            })
            put("formats", JSONObject(s.formatBreakdown.mapKeys { it.key.name } as Map<*, *>))
            put("exclusiveToSource", JSONObject(s.exclusiveToSource.mapKeys { it.key.name } as Map<*, *>))
            put("sources", JSONArray().apply {
                for (r in outcome.sources) {
                    val sum = s.perSource[r.probe.source]
                    put(JSONObject().apply {
                        put("source", r.probe.source.name)
                        put("accessible", r.probe.accessible)
                        put("error", r.probe.error ?: JSONObject.NULL)
                        put("roots", JSONArray(r.probe.rootsScanned))
                        put("notes", JSONArray(r.probe.notes))
                        if (sum != null) {
                            put("files", sum.files)
                            put("uniqueHashes", sum.uniqueHashes)
                            put("duplicateFiles", sum.duplicateFiles)
                            put("totalBytes", sum.totalBytes)
                            put("animated", sum.animated)
                            put("failures", sum.failures)
                            put("oldestMs", sum.oldestMs ?: JSONObject.NULL)
                            put("newestMs", sum.newestMs ?: JSONObject.NULL)
                            put("formats", JSONObject(sum.formats.mapKeys { it.key.name } as Map<*, *>))
                        }
                        // Top directories by file count: reveals sticker folders we did not expect.
                        put("topDirectories", JSONArray().apply {
                            r.inventory.entries.sortedByDescending { it.value.fileCount }.take(40).forEach { (dir, st) ->
                                put(JSONObject().put("dir", dir).put("files", st.fileCount)
                                    .put("bytes", st.totalBytes).put("nomedia", st.hasNoMedia))
                            }
                        })
                    })
                }
            })
            put("dimensions", JSONObject(s.unique.groupingBy { "${it.format.width}x${it.format.height}" }
                .eachCount().entries.sortedByDescending { it.value }.take(10)
                .associate { it.key to it.value } as Map<*, *>))
            put("failureSamples", JSONArray().apply {
                s.failures.take(25).forEach { f ->
                    put(JSONObject().put("source", f.file.source.name).put("path", f.file.displayPath)
                        .put("format", f.format?.format?.name ?: JSONObject.NULL).put("error", f.error))
                }
            })
        })
        return root
    }

    private fun appVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "?"

    private fun packageVersion(context: Context, pkg: String): Any = try {
        val info = context.packageManager.getPackageInfo(pkg, 0)
        JSONObject().put("versionName", info.versionName).put("versionCode", info.longVersionCode)
    } catch (e: PackageManager.NameNotFoundException) {
        JSONObject.NULL
    }
}
