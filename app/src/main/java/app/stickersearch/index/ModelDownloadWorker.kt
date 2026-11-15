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
        runCatching { setForeground(DownloadNotification.foreground(ctx, kind, 0, total(ctx, kind))) }
        val part = store.partFile(kind)
        try {
            fetch(kind.downloadUrl, part) { have, size ->
                if (size > 0) savedTotal(ctx, kind, size)
                val total = if (size > 0) size else kind.approxMb.toLong() shl 20
                setProgress(workDataOf(KEY_DONE to have, KEY_TOTAL to total))
                DownloadNotification.progress(ctx, kind, have, total)
            }
        } catch (e: IOException) {
            events.add("model_download", mapOf("model" to kind.name, "error" to "${e.javaClass.simpleName}: ${e.message}"))
            // Network trouble: try again later; the .part file keeps what arrived.
            return@withContext if (runAttemptCount < MAX_ATTEMPTS) {
                DownloadNotification.retrying(ctx, kind)
                Result.retry()
            } else {
                val why = "The download kept failing (${e.message ?: "network error"})."
                DownloadNotification.failed(ctx, kind, why)
                Result.failure(workDataOf(KEY_ERROR to why))
            }
        }
        if (!store.installDownloaded(kind, part)) {
            events.add("model_download", mapOf("model" to kind.name, "error" to "not the expected model"))
            DownloadNotification.failed(ctx, kind, "The file that arrived wasn't the right model. Retry downloads it again.")
            return@withContext Result.failure(workDataOf(KEY_ERROR to "${kind.title} didn't download correctly."))
        }
        events.add("model_download", mapOf("model" to kind.name, "bytes" to store.sizeBytes(kind)))
        val allReady = store.autoDownload.all { store.has(it) }
        DownloadNotification.finished(ctx, kind, store.sizeBytes(kind), allReady)
        if (allReady) AiWorker.enqueue(ctx, manual = false)
        Result.success()
    }

    override suspend fun getForegroundInfo() =
        DownloadNotification.foreground(applicationContext, kind ?: ModelKind.E5, 0, 0)

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
            prefs(ctx).edit().putBoolean(WIFI_ONLY, wifiOnly).apply()
            // Can't start yet (no Wi-Fi, or offline): say so in the shade instead of waiting silently.
            if (!DownloadNotification.networkOk(ctx, wifiOnly)) DownloadNotification.waiting(ctx, kind, wifiOnly)
            else DownloadNotification.clearStatus(ctx, kind)
        }

        private const val WIFI_ONLY = "wifi_only"
        /** The Wi-Fi-only rule of the last Download tap, for Retry on the notification. */
        fun wifiOnly(ctx: Context) = prefs(ctx).getBoolean(WIFI_ONLY, true)

        /** Pause: stops the job; the .part file stays for Resume. */
        fun pause(ctx: Context, kind: ModelKind) {
            WorkManager.getInstance(ctx).cancelUniqueWork(unique(kind))
            DownloadNotification.cancel(ctx, kind)
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
