package com.kukreja.stickersearch.index

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.kukreja.stickersearch.data.AnnotationRepository
import com.kukreja.stickersearch.data.StickerDatabase
import com.kukreja.stickersearch.data.StickerStatus
import androidx.room.withTransaction
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
    suspend fun export(
        ctx: Context, db: StickerDatabase, out: Uri, all: Boolean = false, onProgress: suspend (Int, Int) -> Unit,
    ): ExportResult {
        // "all": the PC redoes stickers the phone already described too (its descriptions win on import).
        val todo = if (all) db.ai().allForPc() else db.ai().undescribed()
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
                // Extract All, then double-click: no paths to type, nothing to move.
                ctx.assets.open("pc/Start on Windows.bat").use { zip.put("Start on Windows.bat", it.readBytes()) }
                ctx.assets.open("pc/Start on Mac.command").use { zip.put("Start on Mac.command", it.readBytes(), executable = true) }
                ctx.assets.open("pc/start-linux.sh").use { zip.put("start-linux.sh", it.readBytes(), executable = true) }
                todo.forEachIndexed { i, s ->
                    if (i % 10 == 0) onProgress(i, todo.size)
                    // Contact sheets vary in size: keep checking there's room, and stop cleanly if not.
                    if (i % 100 == 0 && ctx.cacheDir.usableSpace < 30L * 1024 * 1024) {
                        error("The phone's storage is full. Free some space and export again.")
                    }
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
                        .put("frames", if (motion) minOf(frames.size, 4) else 1)
                        .put("duration_ms", s.durationMs ?: JSONObject.NULL)
                        .put("prompt", GemmaAnnotator.prompt(motion, s.durationMs)))
                }
                zip.finish(JSONObject()
                    .put("format", FORMAT)
                    .put("app", runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "")
                    .put("prompt_version", GemmaAnnotator.PROMPT_VERSION)
                    // describe.py builds its own, more detailed prompt from these.
                    .put("emotions_vocab", JSONArray(com.kukreja.stickersearch.ml.Annotation.EMOTIONS))
                    .put("intents_vocab", JSONArray(com.kukreja.stickersearch.ml.Annotation.INTENTS))
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

    data class ImportResult(
        val saved: Int, val replacedPhone: Int, val alreadyImported: Int, val alreadyDescribed: Int,
        val editsAdded: Int, val unreadable: Int, val notHere: Int, val cutOffTail: Boolean,
    )

    /**
     * Reads results.jsonl from describe.py, or a descriptions file shared from another phone.
     *
     * - PC descriptions replace the phone's own (a bigger model usually does better). A line with
     *   the same model and prompt as what's stored is skipped, so importing the same file twice is harmless.
     * - Shared descriptions only fill stickers that aren't described yet; a shared edit is applied
     *   only where this phone has no edit of its own. The user's edits always stay on top.
     * - Stickers that aren't on this phone (deleted, or only on the other device) are skipped.
     * - Each line is handled on its own: a bad one is counted and skipped, never stopping the rest.
     * Saved in batches inside one transaction each, so the app refreshes once per batch.
     */
    suspend fun import(
        ctx: Context, db: StickerDatabase, repo: AnnotationRepository, src: Uri, onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): ImportResult {
        val text = (ctx.contentResolver.openInputStream(src) ?: error("Couldn't open that file.")).bufferedReader().use { it.readLines() }
        val parsed = PcResults.parse(text)
        var saved = 0; var replaced = 0; var already = 0; var described = 0; var edits = 0; var notHere = 0
        var unreadable = parsed.unreadable
        parsed.lines.chunked(200).forEachIndexed { batch, lines ->
            onProgress(batch * 200, parsed.lines.size)
            db.withTransaction {
                for (l in lines) {
                    val ok = runCatching {
                        val s = db.stickers().bySha(l.sha256)
                        if (s == null || s.status == StickerStatus.MISSING) { notHere++; return@runCatching }
                        // The other phone's own edits: only where this phone has none.
                        if (l.correction != null && repo.correction(s.id) == null) { repo.saveCorrection(s.id, l.correction); edits++ }
                        if (l.raw.isBlank()) return@runCatching
                        val isDescribed = s.status == StickerStatus.INDEXED
                        when {
                            isDescribed && s.annotatorModel == l.label -> { already++; return@runCatching }
                            l.source == PcResults.Source.SHARED && isDescribed -> { described++; return@runCatching }
                        }
                        val a = l.annotation()
                        if (a == null) { unreadable++; return@runCatching }
                        if (isDescribed && s.annotatorModel?.startsWith("pc:") != true) replaced++
                        repo.saveModel(s.id, a, l.label, l.promptVersion ?: GemmaAnnotator.PROMPT_VERSION)
                        saved++
                    }.isSuccess
                    if (!ok) unreadable++
                }
            }
        }
        onProgress(parsed.lines.size, parsed.lines.size)
        return ImportResult(saved, replaced, already, described, edits, unreadable, notHere, parsed.cutOffTail)
    }

    /**
     * Writes every description on this phone (and the user's edits) as a .jsonl file another phone
     * can import, matched by each sticker's SHA-256. Returns how many stickers went in.
     */
    suspend fun exportDescriptions(ctx: Context, db: StickerDatabase, repo: AnnotationRepository, out: Uri): Int {
        var n = 0
        val stream = ctx.contentResolver.openOutputStream(out, "wt") ?: error("Couldn't write to that file.")
        stream.bufferedWriter().use { w ->
            for (s in db.ai().describedOrEdited()) {
                val a = if (s.status == StickerStatus.INDEXED) repo.model(s.id) else null
                val c = repo.correction(s.id)
                if (a == null && c == null) continue
                w.write(PcResults.sharedLine(s.sha256, s.annotatorModel ?: GemmaAnnotator.MODEL_NAME, a, c))
                w.write("\n")
                n++
            }
        }
        return n
    }
}
