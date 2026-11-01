package com.kukreja.stickersearch.index

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.BatteryManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.kukreja.stickersearch.data.AnnotationRepository
import com.kukreja.stickersearch.data.StickerDatabase
import com.kukreja.stickersearch.data.StickerEntity
import com.kukreja.stickersearch.ml.GemmaAnnotator
import com.kukreja.stickersearch.ml.ImageEmbedder
import com.kukreja.stickersearch.ml.ModelKind
import com.kukreja.stickersearch.ml.ModelStore
import com.kukreja.stickersearch.ml.TextEmbedder
import com.kukreja.stickersearch.ml.Vectors
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * The AI passes, in order, each resumable sticker by sticker:
 *  1. visual fingerprints (SigLIP2 image) for every sticker — quick, makes everything searchable;
 *  2. text vectors (e5) for annotated stickers that lack one (new annotations, corrections);
 *  3. annotations (Gemma 3n), newest first — slow, so only while charging unless started by hand.
 * Steps whose model isn't imported are skipped.
 */
class AiWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val db = StickerDatabase.get(context)
    private val repo = AnnotationRepository(db)
    private val models = ModelStore(context)
    private var done = 0
    private var total = 0
    private var phase = ""

    override suspend fun doWork(): Result {
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        val ctx = applicationContext
        runCatching { setForeground(IndexNotification.info(ctx, 0, 0, "Preparing search")) }
        status(ctx).edit().putLong(KEY_LAST_RUN, System.currentTimeMillis()).apply()

        if (models.has(ModelKind.SIGLIP_VISION)) visualPass()
        if (isStopped) return Result.success()
        if (models.has(ModelKind.E5)) textPass()
        if (isStopped) return Result.success()

        if (models.has(ModelKind.GEMMA)) {
            if (manual || isCharging(ctx)) {
                annotatePass()
            } else {
                // Continue automatically the next time the phone is plugged in.
                enqueue(ctx, manual = false, whenCharging = true)
            }
        }
        return Result.success()
    }

    private suspend fun visualPass() {
        val embedder = runCatching { ImageEmbedder(models.file(ModelKind.SIGLIP_VISION)) }.getOrNull() ?: return
        embedder.use { e ->
            phase = "Visual search"
            done = 0
            total = db.ai().countPendingVisual()
            while (!isStopped) {
                val batch = db.ai().pendingVisual(64)
                if (batch.isEmpty()) break
                for (s in batch) {
                    if (isStopped) return
                    val frames = imagesFor(s, preferOriginal = false)
                    val v = if (frames.isEmpty()) null else runCatching { Vectors.mean(frames.map(e::embed)) }.getOrNull()
                    frames.forEach { it.recycle() }
                    // A sticker that can't be embedded gets a zero-length vector so it isn't retried forever.
                    db.ai().setImageEmbedding(s.id, v?.let(Vectors::toBytes) ?: ByteArray(0), System.currentTimeMillis())
                    tick()
                }
            }
        }
    }

    private suspend fun textPass() {
        val embedder = runCatching { TextEmbedder(applicationContext, models.file(ModelKind.E5)) }.getOrNull() ?: return
        embedder.use { e ->
            phase = "Meaning search"
            done = 0
            total = db.ai().countPendingTextEmbedding()
            while (!isStopped) {
                val batch = db.ai().pendingTextEmbedding(128)
                if (batch.isEmpty()) break
                for (s in batch) {
                    val a = repo.effective(s.id) ?: continue
                    repo.saveTextEmbedding(s.id, e.passage(a.searchText()), E5_NAME)
                    tick()
                }
            }
        }
    }

    private suspend fun annotatePass() {
        val gemma = runCatching { GemmaAnnotator.open(applicationContext, models.file(ModelKind.GEMMA)) }
            .getOrElse {
                status(applicationContext).edit().putString(KEY_GEMMA_ERROR, "${it.javaClass.simpleName}: ${it.message}").apply()
                return
            }
        status(applicationContext).edit().remove(KEY_GEMMA_ERROR).putString(KEY_GEMMA_BACKEND, gemma.backend).apply()
        val text = if (models.has(ModelKind.E5)) runCatching { TextEmbedder(applicationContext, models.file(ModelKind.E5)) }.getOrNull() else null
        try {
            phase = "Understanding stickers"
            done = 0
            total = db.ai().countPendingAnnotation(MAX_ATTEMPTS)
            while (!isStopped) {
                val batch = db.ai().pendingAnnotation(MAX_ATTEMPTS, 16)
                if (batch.isEmpty()) break
                for (s in batch) {
                    if (isStopped) return
                    val frames = imagesFor(s, preferOriginal = true)
                    if (frames.isEmpty()) {
                        db.ai().annotationFailed(s.id, "no image to show the model", System.currentTimeMillis())
                        continue
                    }
                    val r = runCatching { gemma.annotate(frames, s.isAnimated, s.durationMs) }
                    frames.forEach { it.recycle() }
                    val a = r.getOrNull()?.annotation
                    if (a == null) {
                        val why = r.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }
                            ?: "model reply wasn't readable: ${r.getOrNull()?.raw?.take(160)}"
                        db.ai().annotationFailed(s.id, why, System.currentTimeMillis())
                    } else {
                        repo.saveModel(s.id, a, GemmaAnnotator.MODEL_NAME, GemmaAnnotator.PROMPT_VERSION)
                        text?.let { t -> repo.saveTextEmbedding(s.id, t.passage((repo.effective(s.id) ?: a).searchText()), E5_NAME) }
                    }
                    tick()
                    status(applicationContext).edit().putLong(KEY_LAST_ANNOTATION_MS, r.getOrNull()?.millis ?: 0L).apply()
                }
                db.ai().parkUnreadable(MAX_ATTEMPTS)
            }
        } finally {
            gemma.close()
            text?.close()
        }
    }

    private suspend fun tick() {
        done++
        if (done % 4 == 0) {
            setProgress(workDataOf(KEY_PHASE to phase, KEY_DONE to done, KEY_TOTAL to total))
            IndexNotification.update(applicationContext, done, total, phase)
        }
    }

    /** Keyframes for animated stickers (motion), the full-size original or thumbnail for static ones. */
    private suspend fun imagesFor(s: StickerEntity, preferOriginal: Boolean): List<Bitmap> {
        if (s.isAnimated) {
            val frames = db.frames().forSticker(s.id).mapNotNull { BitmapFactory.decodeFile(it.framePath) }
            if (frames.isNotEmpty()) return frames
        }
        if (preferOriginal && !s.isAnimated) {
            db.stickers().liveUri(s.id)?.let { uri ->
                runCatching {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(applicationContext.contentResolver, Uri.parse(uri))) { d, _, _ ->
                        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        d.setTargetSize(512, 512)
                    }
                }.getOrNull()?.let { return listOf(it) }
            }
        }
        return listOfNotNull(s.thumbnailPath?.takeIf { File(it).exists() }?.let(BitmapFactory::decodeFile))
    }

    override suspend fun getForegroundInfo() = IndexNotification.info(applicationContext, done, total, phase.ifEmpty { "Preparing search" })

    companion object {
        const val UNIQUE = "sticker-ai"
        const val UNIQUE_CHARGING = "sticker-ai-charging"
        const val KEY_MANUAL = "manual"
        const val KEY_PHASE = "phase"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val MAX_ATTEMPTS = 3
        const val E5_NAME = "multilingual-e5-small-int8"

        fun isCharging(ctx: Context): Boolean {
            val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
            val s = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            return s == BatteryManager.BATTERY_STATUS_CHARGING || s == BatteryManager.BATTERY_STATUS_FULL
        }

        /** [manual]: the user asked, so annotation runs even on battery. */
        fun enqueue(ctx: Context, manual: Boolean, whenCharging: Boolean = false) {
            val b = OneTimeWorkRequestBuilder<AiWorker>().setInputData(workDataOf(KEY_MANUAL to manual))
            if (whenCharging) b.setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build())
            val name = if (whenCharging) UNIQUE_CHARGING else UNIQUE
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                name, if (manual) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, b.build(),
            )
        }

        const val KEY_GEMMA_ERROR = "gemma_error"
        const val KEY_GEMMA_BACKEND = "gemma_backend"
        const val KEY_LAST_ANNOTATION_MS = "last_annotation_ms"
        const val KEY_LAST_RUN = "last_run"

        fun status(ctx: Context) = ctx.getSharedPreferences("ai_status", Context.MODE_PRIVATE)

        data class Progress(val running: Boolean, val phase: String?, val done: Int, val total: Int, val waitingForCharge: Boolean)

        fun observe(ctx: Context): Flow<Progress> {
            val wm = WorkManager.getInstance(ctx)
            return kotlinx.coroutines.flow.combine(
                wm.getWorkInfosForUniqueWorkFlow(UNIQUE),
                wm.getWorkInfosForUniqueWorkFlow(UNIQUE_CHARGING),
            ) { a, b ->
                val running = (a + b).firstOrNull { it.state == WorkInfo.State.RUNNING }
                Progress(
                    running = running != null,
                    phase = running?.progress?.getString(KEY_PHASE),
                    done = running?.progress?.getInt(KEY_DONE, 0) ?: 0,
                    total = running?.progress?.getInt(KEY_TOTAL, 0) ?: 0,
                    waitingForCharge = running == null && b.any { it.state == WorkInfo.State.ENQUEUED },
                )
            }
        }
    }
}
