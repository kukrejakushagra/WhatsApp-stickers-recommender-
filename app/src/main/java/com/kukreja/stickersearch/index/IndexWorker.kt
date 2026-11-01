package com.kukreja.stickersearch.index

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.kukreja.stickersearch.data.ScanRunEntity
import com.kukreja.stickersearch.data.StickerDatabase
import com.kukreja.stickersearch.discovery.FileSystemSource
import com.kukreja.stickersearch.discovery.SafTreeSource
import com.kukreja.stickersearch.discovery.SharedInSource
import com.kukreja.stickersearch.model.SourceResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Incremental scan: list sources → plan → process only new/changed files → mark missing.
 * Every processed file is committed on its own, so if Android stops the job, the next run resumes
 * where it left off at the cost of re-listing.
 */
class IndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val db = StickerDatabase.get(ctx)
        val started = System.currentTimeMillis()
        val trigger = inputData.getString(KEY_TRIGGER) ?: "manual"
        if (inputData.getBoolean(KEY_RETRY_FAILED, false)) {
            db.files().resetFailedAttempts()
            db.stickers().resetProblemAttempts()
        }

        setProgress(workDataOf(KEY_PHASE to "Listing files", KEY_DONE to 0, KEY_TOTAL to 0))
        val tree = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TREE, null)?.let(Uri::parse)
        val saf = SafTreeSource(ctx, tree).scan()
        val results = buildList<SourceResult> {
            add(saf)
            // All-files access only stands in when the folder picker isn't granted; using both would
            // list every sticker twice under different URIs.
            if (!saf.probe.accessible && Environment.isExternalStorageManager()) add(FileSystemSource().scan())
            add(SharedInSource(File(ctx.filesDir, "imported")).scan())
        }
        val accessible = results.filter { it.probe.accessible }.map { it.probe.source.name }.toSet()
        if (results.none { it.probe.accessible && it.probe.source.name != "SHARE_IN" }) {
            val why = saf.probe.error ?: "No sticker folder is accessible"
            db.runs().insert(ScanRunEntity(startedAt = started, finishedAt = System.currentTimeMillis(), trigger = trigger,
                filesSeen = 0, filesProcessed = 0, newStickers = 0, missing = 0, failed = 0, error = why))
            return Result.failure(workDataOf(KEY_ERROR to why))
        }

        val listing = results.flatMap { it.files }
        val plan = IndexPlanner.plan(listing, db.files().snapshot(), accessible)
        val now = System.currentTimeMillis()
        plan.unchanged.chunked(500).forEach { db.files().touch(it, now) }
        plan.missing.chunked(500).forEach { db.files().markMissing(it, now) }

        val ingestor = StickerIngestor.create(ctx)
        val repairs = db.stickers().needsRepair(IndexPlanner.MAX_ATTEMPTS, limit = 2000)
        val total = plan.toProcess.size + repairs.size
        if (total > FOREGROUND_THRESHOLD) {
            // Long run: a foreground task with a visible notification, so Android doesn't stop it.
            runCatching { setForeground(IndexNotification.info(ctx, 0, total)) }
        }
        val done = AtomicInteger(0)
        val counts = IntArray(IngestOutcome.entries.size)
        val gate = Semaphore(PARALLELISM)
        coroutineScope {
            plan.toProcess.map { (file, knownId) ->
                async {
                    val outcome = gate.withPermit { ingestor.ingest(file, knownId, now) }
                    synchronized(counts) { counts[outcome.ordinal]++ }
                    progress(done.incrementAndGet(), total)
                }
            }.awaitAll()
            // Finish what earlier runs couldn't: interrupted stickers and Lottie stickers to re-render.
            repairs.map { s ->
                async {
                    gate.withPermit { ingestor.repair(s, db.stickers().liveUri(s.id), now) }
                    progress(done.incrementAndGet(), total)
                }
            }.awaitAll()
        }
        val missingStickers = db.stickers().markOrphansMissing(now)
        db.stickers().reviveReturned(now)
        db.runs().insert(
            ScanRunEntity(
                startedAt = started, finishedAt = System.currentTimeMillis(), trigger = trigger,
                filesSeen = listing.size, filesProcessed = total,
                newStickers = counts[IngestOutcome.NEW_STICKER.ordinal], missing = missingStickers,
                failed = counts[IngestOutcome.FAILED.ordinal],
            ),
        )
        return Result.success()
    }

    private suspend fun progress(d: Int, total: Int) {
        if (d % 10 != 0 && d != total) return
        setProgress(workDataOf(KEY_PHASE to "Indexing", KEY_DONE to d, KEY_TOTAL to total))
        if (total > FOREGROUND_THRESHOLD) IndexNotification.update(applicationContext, d, total)
    }

    override suspend fun getForegroundInfo() = IndexNotification.info(applicationContext, 0, 0)

    companion object {
        private const val FOREGROUND_THRESHOLD = 50
        const val UNIQUE = "sticker-index"
        const val PREFS = "m1"
        const val KEY_TREE = "saf_tree_uri"
        const val KEY_TRIGGER = "trigger"
        const val KEY_RETRY_FAILED = "retry_failed"
        const val KEY_PHASE = "phase"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"
        private const val PARALLELISM = 3

        /** Starts a scan now unless one is already running. */
        fun enqueue(context: Context, trigger: String, retryFailed: Boolean = false) {
            val req = OneTimeWorkRequestBuilder<IndexWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(workDataOf(KEY_TRIGGER to trigger, KEY_RETRY_FAILED to retryFailed))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.KEEP, req)
        }

        data class Progress(val running: Boolean, val phase: String?, val done: Int, val total: Int, val error: String?)

        fun observe(context: Context): Flow<Progress> =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(UNIQUE).map { infos ->
                val info = infos.lastOrNull()
                val running = info?.state == WorkInfo.State.RUNNING || info?.state == WorkInfo.State.ENQUEUED
                Progress(
                    running = running,
                    phase = info?.progress?.getString(KEY_PHASE),
                    done = info?.progress?.getInt(KEY_DONE, 0) ?: 0,
                    total = info?.progress?.getInt(KEY_TOTAL, 0) ?: 0,
                    error = if (info?.state == WorkInfo.State.FAILED) info.outputData.getString(KEY_ERROR) else null,
                )
            }
    }
}
