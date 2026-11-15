package app.stickersearch.index

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.ForegroundInfo
import app.stickersearch.R
import app.stickersearch.ml.ModelKind

/** The words on the download notifications. Pure, so it's unit-tested. */
object DownloadText {
    private const val MB = 1L shl 20

    /** "118 MB", or "9.4 MB" under 10 MB. */
    fun mb(bytes: Long): String {
        val m = bytes.toDouble() / MB
        return if (m < 10) "%.1f MB".format(java.util.Locale.ROOT, m) else "%,d MB".format(java.util.Locale.ROOT, Math.round(m))
    }

    fun downloading(title: String) = "Downloading $title"

    /** "57 of 95 MB · 60%", or "57 MB so far" when the full size isn't known yet. */
    fun progress(done: Long, total: Long): String =
        if (total > 0) "%,d of %,d MB · %d%%".format(java.util.Locale.ROOT, done / MB, total / MB, (done * 100 / total).coerceIn(0, 100))
        else "%,d MB so far".format(java.util.Locale.ROOT, done / MB)

    fun position(index: Int, count: Int) = "Search model ${index + 1} of $count"

    fun waiting(title: String, wifiOnly: Boolean) = "$title · waiting for " + if (wifiOnly) "Wi-Fi" else "a connection"

    /** Finished downloads, in the order they finished: title to bytes. */
    data class Finished(val title: String, val bytes: Long)

    fun finishedTitle(done: List<Finished>, allReady: Boolean): String {
        val sum = mb(done.sumOf { it.bytes })
        return when {
            allReady -> "Search is ready · ${done.size} ${if (done.size == 1) "model" else "models"}, $sum"
            done.size == 1 -> "${done[0].title} downloaded · $sum"
            else -> "${done.size} search models downloaded · $sum"
        }
    }

    fun finishedLine(f: Finished) = "${f.title} · ${mb(f.bytes)}"

    fun finishedText(done: List<Finished>, allReady: Boolean): String =
        if (allReady) "Your stickers are being indexed for search." else done.joinToString(", ") { finishedLine(it) }
}

/**
 * Model downloads in the notification shade, on their own "Model downloads" channel:
 * - while downloading: one notification per model with its name, MB and %, and Pause;
 * - waiting for Wi-Fi / a connection, or retrying after a dropped connection: a quiet notice per model;
 * - finished: one notification listing each model and its real size;
 * - failed: the reason, with Retry.
 * Tapping any of them opens Settings at the models panel.
 */
object DownloadNotification {
    private const val CHANNEL = "model_downloads"
    /** The foreground (progress) notification of each model: ID + slot. */
    const val PROGRESS_ID = 4210
    private const val FINISHED_ID = 4220
    /** Waiting, retrying and failed notices: ID + slot (separate, so WorkManager never removes them). */
    private const val STATUS_ID = 4230

    const val ACTION_PAUSE = "app.stickersearch.download.PAUSE"
    const val ACTION_RETRY = "app.stickersearch.download.RETRY"
    const val ACTION_CLEAR = "app.stickersearch.download.CLEAR"
    const val EXTRA_KIND = "kind"

    /** The three search models, in the order the app lists them ("model 1 of 3"). */
    private val SEARCH = listOf(ModelKind.E5, ModelKind.SIGLIP_VISION, ModelKind.SIGLIP_TEXT)

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    private fun channel(ctx: Context) {
        if (nm(ctx).getNotificationChannel(CHANNEL) == null) {
            nm(ctx).createNotificationChannel(
                NotificationChannel(CHANNEL, "Model downloads", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Which search model is downloading, its progress, and its size when it's done"
                },
            )
        }
    }

    private fun openModels(ctx: Context): PendingIntent {
        val intent = Intent(ctx, app.stickersearch.ui.MainActivity::class.java)
            .putExtra(IndexNotification.EXTRA_OPEN, IndexNotification.TARGET_MODELS)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(ctx, 12, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun action(ctx: Context, what: String, kind: ModelKind?, label: String): Notification.Action {
        val intent = Intent(ctx, DownloadActionReceiver::class.java).setAction(what).putExtra(EXTRA_KIND, kind?.name)
        val pi = PendingIntent.getBroadcast(ctx, what.hashCode() * 31 + (kind?.ordinal ?: 0), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Action.Builder(null, label, pi).build()
    }

    private fun base(ctx: Context): Notification.Builder {
        channel(ctx)
        return Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_kds_download)
            .setContentIntent(openModels(ctx))
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
    }

    private fun slotOf(kind: ModelKind) = SEARCH.indexOf(kind).coerceAtLeast(0)

    private fun progressNotification(ctx: Context, kind: ModelKind, done: Long, total: Long): Notification {
        val search = SEARCH
        return base(ctx)
            .setContentTitle(DownloadText.downloading(kind.title))
            .setContentText(DownloadText.progress(done, total))
            .setSubText(DownloadText.position(slotOf(kind), search.size))
            .setProgress(if (total > 0) 1000 else 0, if (total > 0) (done * 1000 / total).toInt() else 0, total <= 0)
            .setOngoing(true)
            .addAction(action(ctx, ACTION_PAUSE, kind, "Pause"))
            .build()
    }

    /** The worker's foreground notification (data sync). */
    fun foreground(ctx: Context, kind: ModelKind, done: Long, total: Long): ForegroundInfo {
        clearStatus(ctx, kind)
        val n = progressNotification(ctx, kind, done, total)
        val id = PROGRESS_ID + slotOf(kind)
        return if (android.os.Build.VERSION.SDK_INT >= 29) ForegroundInfo(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(id, n)
    }

    fun progress(ctx: Context, kind: ModelKind, done: Long, total: Long) {
        runCatching { nm(ctx).notify(PROGRESS_ID + slotOf(kind), progressNotification(ctx, kind, done, total)) }
    }

    private fun status(ctx: Context, kind: ModelKind, title: String, text: String?, vararg actions: Notification.Action) {
        runCatching {
            val b = base(ctx).setContentTitle(title).setAutoCancel(true)
            if (text != null) b.setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
            actions.forEach { b.addAction(it) }
            nm(ctx).notify(STATUS_ID + slotOf(kind), b.build())
        }
    }

    /** Queued but the network rule isn't met yet: says so, so a download never waits silently. */
    fun waiting(ctx: Context, kind: ModelKind, wifiOnly: Boolean) = status(ctx, kind,
        DownloadText.waiting(kind.title, wifiOnly),
        if (wifiOnly) "Starts by itself on Wi-Fi. To use mobile data, turn off Wi-Fi only in Settings." else "Starts by itself when you're online.",
        action(ctx, ACTION_PAUSE, kind, "Pause"))

    fun retrying(ctx: Context, kind: ModelKind) = status(ctx, kind,
        "${kind.title} · connection lost", "Trying again in a moment. What's downloaded so far is kept.",
        action(ctx, ACTION_PAUSE, kind, "Pause"))

    fun failed(ctx: Context, kind: ModelKind, reason: String) = status(ctx, kind,
        "Couldn't download ${kind.title}", reason, action(ctx, ACTION_RETRY, kind, "Retry"))

    fun clearStatus(ctx: Context, kind: ModelKind) = runCatching { nm(ctx).cancel(STATUS_ID + slotOf(kind)) }

    /** Pause: no progress and no notice left behind for this model. */
    fun cancel(ctx: Context, kind: ModelKind) {
        runCatching { nm(ctx).cancel(PROGRESS_ID + slotOf(kind)) }
        clearStatus(ctx, kind)
    }

    // ---- finished: one notification that collects every model done since it was last seen ----

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("model_downloads", Context.MODE_PRIVATE)

    private fun readFinished(ctx: Context): List<DownloadText.Finished> =
        prefs(ctx).getString("finished", "").orEmpty().split('\n').filter { it.isNotBlank() }.mapNotNull { line ->
            val (name, bytes) = line.split('\t').takeIf { it.size == 2 } ?: return@mapNotNull null
            val kind = runCatching { ModelKind.valueOf(name) }.getOrNull() ?: return@mapNotNull null
            DownloadText.Finished(kind.title, bytes.toLongOrNull() ?: 0L)
        }

    fun finished(ctx: Context, kind: ModelKind, bytes: Long, allReady: Boolean) {
        cancel(ctx, kind)
        val lines = prefs(ctx).getString("finished", "").orEmpty().split('\n')
            .filter { it.isNotBlank() && !it.startsWith(kind.name + "\t") } + "${kind.name}\t$bytes"
        prefs(ctx).edit().putString("finished", lines.joinToString("\n")).apply()
        val done = readFinished(ctx)
        runCatching {
            val style = Notification.InboxStyle()
            done.forEach { style.addLine(DownloadText.finishedLine(it)) }
            style.setSummaryText(if (allReady) "All search models ready" else null)
            val clear = Intent(ctx, DownloadActionReceiver::class.java).setAction(ACTION_CLEAR)
            val n = base(ctx)
                .setCategory(Notification.CATEGORY_STATUS)
                .setContentTitle(DownloadText.finishedTitle(done, allReady))
                .setContentText(DownloadText.finishedText(done, allReady))
                .setStyle(style)
                .setAutoCancel(true)
                .setDeleteIntent(PendingIntent.getBroadcast(ctx, 13, clear, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                .build()
            nm(ctx).notify(FINISHED_ID, n)
        }
    }

    /** The finished notification was seen (opened or swiped away): start a fresh list next time. */
    fun clearFinished(ctx: Context) = prefs(ctx).edit().remove("finished").apply()

    /** Whether the download could start right now under the Wi-Fi-only rule. */
    fun networkOk(ctx: Context, wifiOnly: Boolean): Boolean = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            (!wifiOnly || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
    }.getOrDefault(true)
}

/** Pause and Retry on the download notifications, and clearing the finished list. */
class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        if (intent.action == DownloadNotification.ACTION_CLEAR) { DownloadNotification.clearFinished(ctx); return }
        val kind = intent.getStringExtra(DownloadNotification.EXTRA_KIND)?.let { runCatching { ModelKind.valueOf(it) }.getOrNull() } ?: return
        when (intent.action) {
            DownloadNotification.ACTION_PAUSE -> ModelDownloadWorker.pause(ctx, kind)
            DownloadNotification.ACTION_RETRY -> ModelDownloadWorker.enqueue(ctx, kind, ModelDownloadWorker.wifiOnly(ctx))
        }
    }
}
