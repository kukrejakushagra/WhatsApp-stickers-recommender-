package app.stickersearch.index

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.stickersearch.diagnostics.EventLog
import app.stickersearch.ml.ModelKind
import app.stickersearch.ml.ModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Downloads the search models (multilingual-e5 and SigLIP2, about 500 MB, no sign-in needed) from
 * Hugging Face, the only thing the app ever uses the internet for. Resumes where it stopped (a
 * `.part` file plus an HTTP Range request), waits for Wi-Fi unless the user allowed mobile data,
 * and checks each file is the right model before using it. Gemma is never downloaded here.
 */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val store = ModelStore(ctx)
        val events = EventLog(File(ctx.filesDir, "events.jsonl"))
        val todo = store.autoDownload.filterNot { store.has(it) }
        if (todo.isEmpty()) return@withContext Result.success()
        val totalMb = todo.sumOf { it.approxMb }
        runCatching { setForeground(IndexNotification.downloadInfo(ctx, 0, totalMb, "Starting")) }
        var doneMbBefore = 0
        for (kind in todo) {
            val part = store.partFile(kind)
            try {
                fetch(kind.downloadUrl, part) { have, size ->
                    val mb = doneMbBefore + (have shr 20).toInt()
                    val text = "%s · %,d of %,d MB".format(kind.title, mb, totalMb)
                    setProgress(workDataOf(KEY_KIND to kind.name, KEY_DONE_MB to mb, KEY_TOTAL_MB to totalMb,
                        KEY_FILE_DONE to have, KEY_FILE_TOTAL to size))
                    IndexNotification.updateDownload(ctx, mb, totalMb, text)
                }
            } catch (e: IOException) {
                events.add("model_download", mapOf("model" to kind.name, "error" to "${e.javaClass.simpleName}: ${e.message}"))
                // Network trouble: try again later; the .part file keeps what arrived.
                return@withContext if (runAttemptCount < MAX_ATTEMPTS) Result.retry()
                else Result.failure(workDataOf(KEY_ERROR to (e.message ?: "network error")))
            }
            if (!store.installDownloaded(kind, part)) {
                events.add("model_download", mapOf("model" to kind.name, "error" to "not the expected model"))
                return@withContext Result.failure(workDataOf(KEY_ERROR to "${kind.title} didn't download correctly. Try again."))
            }
            events.add("model_download", mapOf("model" to kind.name, "bytes" to store.sizeBytes(kind)))
            doneMbBefore += kind.approxMb
        }
        AiWorker.enqueue(ctx, manual = false)
        Result.success()
    }

    override suspend fun getForegroundInfo() = IndexNotification.downloadInfo(applicationContext, 0, 0, "Starting")

    companion object {
        const val UNIQUE = "model-download"
        const val KEY_KIND = "kind"
        const val KEY_DONE_MB = "doneMb"
        const val KEY_TOTAL_MB = "totalMb"
        const val KEY_FILE_DONE = "fileDone"
        const val KEY_FILE_TOTAL = "fileTotal"
        const val KEY_ERROR = "error"
        private const val MAX_ATTEMPTS = 8

        /** [wifiOnly]: wait for an unmetered network (the default). */
        fun enqueue(ctx: Context, wifiOnly: Boolean) {
            val req = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            // REPLACE, so switching Wi-Fi only on or off takes effect at once (the .part file is kept).
            WorkManager.getInstance(ctx).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.REPLACE, req)
        }

        fun cancel(ctx: Context) = WorkManager.getInstance(ctx).cancelUniqueWork(UNIQUE)

        /**
         * Downloads [url] into [part], continuing from what's already there. Calls [progress] with
         * bytes so far and the full size (-1 if unknown). Returns when the file is complete.
         */
        suspend fun fetch(url: String, part: File, progress: suspend (Long, Long) -> Unit) {
            var have = if (part.exists()) part.length() else 0L
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "AISticker-Search-Android")
                if (have > 0) setRequestProperty("Range", "bytes=$have-")
            }
            try {
                val code = conn.responseCode
                val append = when (code) {
                    HttpURLConnection.HTTP_PARTIAL -> true
                    HttpURLConnection.HTTP_OK -> { have = 0; false }
                    416 -> return // Range not satisfiable: the file is already complete.
                    else -> throw IOException("HTTP $code from the model server")
                }
                val rest = conn.getHeaderFieldLong("Content-Length", -1L)
                val size = if (rest >= 0) have + rest else -1L
                RandomAccessFile(part, "rw").use { out ->
                    if (!append) out.setLength(0)
                    out.seek(have)
                    conn.inputStream.use { input ->
                        val buf = ByteArray(256 * 1024)
                        var lastReport = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            have += n
                            if (have - lastReport >= 4L shl 20) { lastReport = have; progress(have, size) }
                        }
                    }
                }
                if (size >= 0 && have < size) throw IOException("connection closed early ($have of $size bytes)")
                progress(have, size)
            } finally {
                conn.disconnect()
            }
        }
    }
}
