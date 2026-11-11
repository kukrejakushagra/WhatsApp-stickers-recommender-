package com.kukreja.stickersearch.index

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.kukreja.stickersearch.data.StickerDatabase
import com.kukreja.stickersearch.diagnostics.EventLog
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * "Export for PC" as a foreground job, so it finishes even if the app is closed or in the
 * background (it takes minutes for thousands of stickers). Progress shows in a notification.
 * If it fails or is stopped, the half-made file the user picked is deleted rather than left broken.
 */
class PcExportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val out = Uri.parse(inputData.getString(KEY_URI) ?: return Result.failure(workDataOf(KEY_ERROR to "No file to write to.")))
        runCatching { setForeground(IndexNotification.info(ctx, 0, 0, TITLE, IndexNotification.TARGET_PC)) }
        val events = EventLog(File(ctx.filesDir, "events.jsonl"))
        return try {
            val r = PcBridge.export(ctx, StickerDatabase.get(ctx), out) { done, total ->
                setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to total))
                IndexNotification.update(ctx, done, total, TITLE, IndexNotification.TARGET_PC)
            }
            events.add("pc_export", mapOf("written" to r.written, "total" to r.total, "skipped" to r.skipped.toString()))
            Result.success(workDataOf(
                KEY_WRITTEN to r.written, KEY_TOTAL to r.total,
                KEY_SKIPPED to r.skipped.entries.joinToString("; ") { "${it.value} ${it.key}" },
            ))
        } catch (e: CancellationException) {
            discard(ctx, out)
            throw e
        } catch (e: Throwable) {
            events.add("pc_export", mapOf("error" to "${e.javaClass.simpleName}: ${e.message}"))
            discard(ctx, out)
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: e.javaClass.simpleName)))
        } finally {
            runCatching { ctx.contentResolver.releasePersistableUriPermission(out, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        }
    }

    /** Removes the file the user picked, so a broken export never sits there looking real. */
    private fun discard(ctx: Context, out: Uri) {
        runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, out) }
        File(ctx.cacheDir, "pc-export.zip").delete()
    }

    override suspend fun getForegroundInfo() = IndexNotification.info(applicationContext, 0, 0, TITLE, IndexNotification.TARGET_PC)

    companion object {
        const val UNIQUE = "pc-export"
        const val TITLE = "Exporting for PC"
        const val KEY_URI = "uri"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_WRITTEN = "written"
        const val KEY_SKIPPED = "skipped"
        const val KEY_ERROR = "error"

        fun enqueue(ctx: Context, out: Uri) {
            val req = OneTimeWorkRequestBuilder<PcExportWorker>().setInputData(workDataOf(KEY_URI to out.toString())).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.KEEP, req)
        }
    }
}
