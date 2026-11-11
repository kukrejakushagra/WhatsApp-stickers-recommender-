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
import com.kukreja.stickersearch.data.FrameVectorEntity
import com.kukreja.stickersearch.data.StickerEntity
import com.kukreja.stickersearch.graph.GraphRepository
import com.kukreja.stickersearch.ml.GemmaAnnotator
import com.kukreja.stickersearch.ml.GemmaHolder
import com.kukreja.stickersearch.ml.DescribeGuard
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

        if (models.has(ModelKind.SIGLIP_VISION)) { visualPass(); frameVectorPass() }
        if (isStopped) return Result.success()
        if (models.has(ModelKind.E5)) textPass()
        if (isStopped) return Result.success()
        graphPass()
        if (isStopped) return Result.success()

        if (models.has(ModelKind.GEMMA) && !isPaused(ctx)) {
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
                    val each = if (frames.isEmpty()) emptyList() else runCatching { frames.map(e::embed) }.getOrDefault(emptyList())
                    val v = if (each.isEmpty()) null else Vectors.mean(each)
                    frames.forEach { it.recycle() }
                    if (s.isAnimated && each.size >= 2) saveFrameVectors(s.id, each.first(), each.last())
                    // A sticker that can't be embedded gets a zero-length vector so it isn't retried forever.
                    db.ai().setImageEmbedding(s.id, v?.let(Vectors::toBytes) ?: ByteArray(0), System.currentTimeMillis())
                    tick()
                }
            }
        }
    }

    private suspend fun saveFrameVectors(id: Long, first: FloatArray, last: FloatArray) {
        db.graph().putFrameVectors(listOf(
            FrameVectorEntity(id, "first", Vectors.toBytes(first)), FrameVectorEntity(id, "last", Vectors.toBytes(last)),
        ))
    }

    /** First/last keyframe vectors for animated stickers fingerprinted before the graph existed. */
    private suspend fun frameVectorPass() {
        val embedder = runCatching { ImageEmbedder(models.file(ModelKind.SIGLIP_VISION)) }.getOrNull() ?: return
        embedder.use { e ->
            phase = "Linking stories"
            while (!isStopped) {
                val batch = db.graph().needingFrameVectors(64)
                if (batch.isEmpty()) break
                for (s in batch) {
                    val frames = db.frames().forSticker(s.id)
                    val firstBmp = frames.firstOrNull()?.let { BitmapFactory.decodeFile(it.framePath) }
                    val lastBmp = frames.lastOrNull()?.takeIf { frames.size >= 2 }?.let { BitmapFactory.decodeFile(it.framePath) }
                    if (firstBmp != null && lastBmp != null) {
                        saveFrameVectors(s.id, e.embed(firstBmp), e.embed(lastBmp))
                    } else {
                        // Mark as done with identical vectors so it isn't retried; it can't link a story.
                        val one = (firstBmp ?: lastBmp)?.let(e::embed) ?: FloatArray(0)
                        saveFrameVectors(s.id, one, one)
                    }
                    firstBmp?.recycle(); lastBmp?.recycle()
                }
            }
        }
    }

    private suspend fun graphPass() {
        phase = "Connecting related stickers"
        setProgress(workDataOf(KEY_PHASE to phase, KEY_DONE to 0, KEY_TOTAL to 0))
        runCatching { GraphRepository(applicationContext).rebuildIfStale() }
    }

    private suspend fun textPass() {
        val embedder = runCatching { TextEmbedder(applicationContext, models.file(ModelKind.E5)) }.getOrNull() ?: return
        val ctx = applicationContext
        DescribeGuard.takeUnfinishedEmbed(ctx)
        val skip = DescribeGuard.embedSkipped(ctx)
        embedder.use { e ->
            phase = "Meaning search"
            done = 0
            total = db.ai().countPendingTextEmbedding()
            val tried = HashSet<Long>()
            while (!isStopped) {
                // Skipped stickers stay without a text vector (still found by looks and keywords), so page past them.
                val batch = db.ai().pendingTextEmbedding(128 + tried.size).filter { it.id !in skip && it.id !in tried }
                if (batch.isEmpty()) break
                for (s in batch) {
                    tried += s.id
                    val a = repo.effective(s.id) ?: continue
                    embedGuarded(e, s.id, a.searchText())
                    tick()
                }
            }
        }
    }

    /** One e5 call inside the crash guard, so a sticker that crashes it is skipped next time. */
    private suspend fun embedGuarded(e: TextEmbedder, id: Long, text: String) {
        DescribeGuard.beginEmbed(applicationContext, id)
        val v = runCatching { e.passage(text) }.getOrNull()
        DescribeGuard.endEmbed(applicationContext)
        if (v != null) repo.saveTextEmbedding(id, v, E5_NAME)
    }

    private suspend fun annotatePass() {
        val ctx = applicationContext
        // The last model call never finished: the process died inside Gemma (see DescribeGuard).
        DescribeGuard.takeUnfinished(ctx)?.let { u ->
            if (u.stickerId > 0) {
                db.ai().annotationFailed(u.stickerId, "The app closed while describing this on the ${u.backend}: ${u.why}", System.currentTimeMillis())
                db.ai().parkUnreadable(MAX_ATTEMPTS)
            }
        }
        if (DescribeGuard.paused(ctx)) return
        // Shared with the reply assistant, so the two never load separate 3 GB copies.
        DescribeGuard.begin(ctx, 0, "loading")
        val backend = runCatching { GemmaHolder.use(applicationContext) { it.backend } }
            .also { DescribeGuard.end(ctx, ok = false) }
            .getOrElse {
                status(applicationContext).edit().putString(KEY_GEMMA_ERROR, "${it.javaClass.simpleName}: ${it.message}").apply()
                return
            }
        status(applicationContext).edit().remove(KEY_GEMMA_ERROR).putString(KEY_GEMMA_BACKEND, backend).apply()
        val text = if (models.has(ModelKind.E5)) runCatching { TextEmbedder(applicationContext, models.file(ModelKind.E5)) }.getOrNull() else null
        try {
            phase = DESCRIBE_PHASE
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
                    val r = runCatching {
                        GemmaHolder.use(applicationContext) { g ->
                            DescribeGuard.begin(ctx, s.id, g.backend)
                            g.annotate(frames, s.isAnimated, s.durationMs)
                        }
                    }
                    DescribeGuard.end(ctx, ok = r.getOrNull()?.annotation != null)
                    frames.forEach { it.recycle() }
                    val a = r.getOrNull()?.annotation
                    if (a == null) {
                        val why = r.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }
                            ?: "model reply wasn't readable: ${r.getOrNull()?.raw?.take(160)}"
                        db.ai().annotationFailed(s.id, why, System.currentTimeMillis())
                    } else {
                        repo.saveModel(s.id, a, GemmaAnnotator.MODEL_NAME, GemmaAnnotator.PROMPT_VERSION)
                        text?.let { t -> if (s.id !in DescribeGuard.embedSkipped(ctx)) embedGuarded(t, s.id, (repo.effective(s.id) ?: a).searchText()) }
                    }
                    tick()
                    status(applicationContext).edit().putLong(KEY_LAST_ANNOTATION_MS, r.getOrNull()?.millis ?: 0L).apply()
                }
                db.ai().parkUnreadable(MAX_ATTEMPTS)
                // New descriptions bring new topics: refresh the graph every so often.
                if (done > 0 && done % 500 == 0) graphPass()
            }
            graphPass()
        } finally {
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

    private suspend fun imagesFor(s: StickerEntity, preferOriginal: Boolean): List<Bitmap> =
        StickerImages.load(applicationContext, db, s, preferOriginal)

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

        /**
         * After a PC import: vectors and graph for the new descriptions. Queued behind any run in
         * progress (which may have passed its text step already) instead of being dropped.
         */
        fun enqueueAfterImport(ctx: Context) {
            val b = OneTimeWorkRequestBuilder<AiWorker>().setInputData(workDataOf(KEY_MANUAL to false))
            WorkManager.getInstance(ctx).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.APPEND_OR_REPLACE, b.build())
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

        const val KEY_PAUSED = "describe_paused_by_user"
        const val DESCRIBE_PHASE = "Understanding stickers"

        fun isPaused(ctx: Context) = status(ctx).getBoolean(KEY_PAUSED, false)

        /**
         * The user paused describing: stop the run in progress (it finishes the sticker it's on) and
         * any run waiting for the charger, and don't start new ones until [resume].
         */
        fun pause(ctx: Context) {
            status(ctx).edit().putBoolean(KEY_PAUSED, true).commit()
            val wm = WorkManager.getInstance(ctx)
            wm.cancelUniqueWork(UNIQUE)
            wm.cancelUniqueWork(UNIQUE_CHARGING)
        }

        /** Carries on where it stopped: already described stickers are never redone. */
        fun resume(ctx: Context) {
            status(ctx).edit().putBoolean(KEY_PAUSED, false).commit()
            enqueue(ctx, manual = true)
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
