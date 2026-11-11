package com.kukreja.stickersearch.index

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.kukreja.stickersearch.data.AnnotationRepository
import com.kukreja.stickersearch.data.StickerDatabase
import com.kukreja.stickersearch.data.StickerStatus
import com.kukreja.stickersearch.ml.Annotation
import com.kukreja.stickersearch.ml.GemmaAnnotator
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * "Describe on a PC": the slow Gemma step, done by a bigger model on the user's own computer.
 *
 * Export writes one zip to a place the user picks: the exact image and prompt the phone's Gemma
 * would get for each undescribed sticker, plus describe.py and a README. The user copies it to
 * their PC and runs the script against a local model (Ollama by default). It writes results.jsonl,
 * which Import reads back, matching stickers by SHA-256. Nothing goes online: the app has no
 * internet access, and the script refuses non-local model servers unless told otherwise.
 */
object PcBridge {
    const val FORMAT = 1
    const val RESULTS_NAME = "results.jsonl"

    data class ExportResult(val written: Int, val total: Int, val skipped: Map<String, Int>)

    /**
     * Writes the export zip to [out]. It's built in the app's cache first, verified, and only then
     * copied to [out], so the user never gets a half-written zip. Throws if anything goes wrong;
     * the caller deletes [out] then.
     */
    suspend fun export(ctx: Context, db: StickerDatabase, out: Uri, onProgress: suspend (Int, Int) -> Unit): ExportResult {
        val todo = db.ai().undescribed()
        // Rough room needed: about 60 KB per sticker in the temp copy, plus a margin.
        val need = todo.size * 60L * 1024 + 20L * 1024 * 1024
        if (ctx.cacheDir.usableSpace < need) {
            error("Not enough free space: about %,d MB is needed while exporting.".format(need / (1024 * 1024)))
        }
        val temp = File(ctx.cacheDir, "pc-export.zip").apply { delete() }
        val items = JSONArray()
        val skipped = linkedMapOf<String, Int>()
        val expected = mutableListOf<String>()
        try {
            PcExportZip(temp).use { zip ->
                ctx.assets.open("pc/describe.py").use { zip.put("describe.py", it.readBytes()) }
                ctx.assets.open("pc/README.txt").use { zip.put("README.txt", it.readBytes()) }
                todo.forEachIndexed { i, s ->
                    if (i % 10 == 0) onProgress(i, todo.size)
                    val frames = runCatching { StickerImages.load(ctx, db, s, preferOriginal = true) }.getOrElse { emptyList() }
                    if (frames.isEmpty()) {
                        val why = if (s.format == "LOTTIE_WAS") "Lottie with no readable frames" else "no picture could be read"
                        skipped[why] = (skipped[why] ?: 0) + 1
                        return@forEachIndexed
                    }
                    val motion = s.isAnimated && frames.size > 1
                    val image = if (frames.size > 1) GemmaAnnotator.contactSheet(frames) else GemmaAnnotator.onWhite(frames.first())
                    frames.forEach { it.recycle() }
                    val jpg = ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
                    image.recycle()
                    val name = "images/${s.sha256}.jpg"
                    zip.put(name, jpg, store = true)
                    expected += name
                    items.put(JSONObject()
                        .put("sha256", s.sha256)
                        .put("image", name)
                        .put("animated", motion)
                        .put("prompt", GemmaAnnotator.prompt(motion, s.durationMs)))
                }
                zip.finish(JSONObject()
                    .put("format", FORMAT)
                    .put("app", runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "")
                    .put("prompt_version", GemmaAnnotator.PROMPT_VERSION)
                    .put("created_ms", System.currentTimeMillis())
                    .put("items", items)
                    .toString().toByteArray())
            }
            onProgress(todo.size, todo.size)
            PcExportZip.verify(temp, expected)?.let { error("The export didn't check out: $it") }
            // Copy the finished, verified zip to where the user asked.
            val stream = ctx.contentResolver.openOutputStream(out, "wt") ?: error("Couldn't write to that file.")
            stream.use { o -> temp.inputStream().use { it.copyTo(o, 1 shl 16) } }
            return ExportResult(expected.size, todo.size, skipped)
        } finally {
            temp.delete()
        }
    }

    data class ImportResult(val saved: Int, val unreadable: Int, val unknown: Int, val alreadyDescribed: Int)

    /**
     * Reads results.jsonl: one {"sha256", "model", "raw"} per line, "raw" being the model's reply.
     * Stickers already described on the phone, or edited by the user, are left as they are.
     */
    suspend fun import(ctx: Context, db: StickerDatabase, repo: AnnotationRepository, src: Uri): ImportResult {
        var saved = 0; var unreadable = 0; var unknown = 0; var already = 0
        val input = ctx.contentResolver.openInputStream(src) ?: error("Couldn't open that file.")
        input.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                val o = runCatching { JSONObject(line) }.getOrNull()
                if (o == null) { unreadable++; continue }
                val sha = o.optString("sha256")
                val s = db.stickers().bySha(sha)
                if (s == null) { unknown++; continue }
                if (s.status == StickerStatus.INDEXED) { already++; continue }
                val raw = o.optJSONObject("annotation")?.toString() ?: o.optString("raw")
                val a = Annotation.parse(raw)
                if (a == null) { unreadable++; continue }
                val model = o.optString("model").ifBlank { "pc" }.take(60)
                repo.saveModel(s.id, a, "pc:$model", GemmaAnnotator.PROMPT_VERSION)
                saved++
            }
        }
        return ImportResult(saved, unreadable, unknown, already)
    }
}
