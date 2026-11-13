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
 * Downloads one search model (multilingual-e5 or one half of SigLIP2; no sign-in needed) from
 * Hugging Face, the only thing the app ever uses the internet for. Each model is its own job, so it
 * can be started, paused and resumed on its own; it only starts when the user taps Download. Resumes
 * where it stopped (a `.part` file plus an HTTP Range request), waits for Wi-Fi unless the user
 * allowed mobile data, and checks the file is the right model before using it. Gemma is never
 * downloaded here.
 */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val kind get() = inputData.getString(KEY_KIND)?.let { runCatching { ModelKind.valueOf(it) }.getOrNull() }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val kind = kind ?: return@withContext Result.failure(workDataOf(KEY_ERROR to "Unknown model."))
        val store = ModelStore(ctx)
        val events = EventLog(File(ctx.filesDir, "events.jsonl"))
        if (store.has(kind)) return@withContext Result.success()
        runCatching { setForeground(IndexNotification.downloadInfo(ctx, kind.ordinal, 0, kind.approxMb, "${kind.title} · starting")) }
        val part = store.partFile(kind)
        try {
            fetch(kind.downloadUrl, part) { have, size ->
                if (size > 0) savedTotal(ctx, kind, size)
                val total = if (size > 0) size else kind.approxMb.toLong() shl 20
                setProgress(workDataOf(KEY_DONE to have, KEY_TOTAL to total))
                IndexNotification.updateDownload(ctx, kind.ordinal, (have shr 20).toInt(), (total shr 20).toInt(),
                    "%s · %,d of %,d MB".format(kind.title, have shr 20, total shr 20))
            }
        } catch (e: IOException) {
            events.add("model_download", mapOf("model" to kind.name, "error" to "${e.javaClass.simpleName}: ${e.message}"))
            // Network trouble: try again later; the .part file keeps what arrived.
            return@withContext if (runAttemptCount < MAX_ATTEMPTS) Result.retry()
            else Result.failure(workDataOf(KEY_ERROR to "The download kept failing (${e.message ?: "network error"})."))
        }
        if (!store.installDownloaded(kind, part)) {
            events.add("model_download", mapOf("model" to kind.name, "error" to "not the expected model"))
            return@withContext Result.failure(workDataOf(KEY_ERROR to "${kind.title} didn't download correctly."))
        }
        events.add("model_download", mapOf("model" to kind.name, "bytes" to store.sizeBytes(kind)))
        if (store.autoDownload.all { store.has(it) }) AiWorker.enqueue(ctx, manual = false)
        Result.success()
    }

    override suspend fun getForegroundInfo() =
        IndexNotification.downloadInfo(applicationContext, kind?.ordinal ?: 0, 0, 0, "${kind?.title ?: "Model"} · starting")

    companion object {
        const val KEY_KIND = "kind"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"
        private const val MAX_ATTEMPTS = 8

        fun unique(kind: ModelKind) = "model-download-${kind.name}"

        /** Download, Resume and Retry: starts (or restarts) this model's job; what already arrived is kept. */
        fun enqueue(ctx: Context, kind: ModelKind, wifiOnly: Boolean) {
            val req = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(workDataOf(KEY_KIND to kind.name))
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(unique(kind), ExistingWorkPolicy.REPLACE, req)
        }

        /** Pause: stops the job; the .part file stays for Resume. */
        fun pause(ctx: Context, kind: ModelKind) {
            WorkManager.getInstance(ctx).cancelUniqueWork(unique(kind))
            IndexNotification.cancelDownload(ctx, kind.ordinal)
        }

        private fun prefs(ctx: Context) = ctx.getSharedPreferences("model_downloads", Context.MODE_PRIVATE)
        fun savedTotal(ctx: Context, kind: ModelKind, bytes: Long) = prefs(ctx).edit().putLong(kind.name, bytes).apply()
        /** The full size seen on an earlier attempt, or the approximate size before the first. */
        fun total(ctx: Context, kind: ModelKind) = prefs(ctx).getLong(kind.name, kind.approxMb.toLong() shl 20)

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
