package app.stickersearch.index

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.ForegroundInfo
import app.stickersearch.R

/** The "Indexing stickers" progress notification for long scans (foreground media processing). */
object IndexNotification {
    private const val CHANNEL = "indexing"
    private const val ID = 4201

    private fun channel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Indexing", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress while the app indexes your stickers"
                },
            )
        }
    }

    /** Where a tap on the notification takes you (Settings, at the matching panel). */
    const val TARGET_STATUS = "status"
    const val TARGET_PC = "pc"
    const val EXTRA_OPEN = "open"

    private fun openApp(ctx: Context, target: String): android.app.PendingIntent {
        val intent = android.content.Intent(ctx, app.stickersearch.ui.MainActivity::class.java)
            .putExtra(EXTRA_OPEN, target)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return android.app.PendingIntent.getActivity(
            ctx, if (target == TARGET_PC) 11 else 10, intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun build(ctx: Context, done: Int, total: Int, title: String, target: String): Notification {
        channel(ctx)
        return Notification.Builder(ctx, CHANNEL)
            .setContentIntent(openApp(ctx, target))
            .setSmallIcon(R.drawable.ic_kds_search)
            .setContentTitle(title)
            .setContentText(if (total > 0) "%,d of %,d".format(done, total) else "Listing files")
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply {
                // While describing, the notification can pause it without opening the app.
                if (title == AiWorker.DESCRIBE_PHASE) {
                    val pi = android.app.PendingIntent.getBroadcast(
                        ctx, 1, android.content.Intent(ctx, PauseReceiver::class.java),
                        android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                    addAction(Notification.Action.Builder(null, "Pause describing", pi).build())
                }
            }
            .build()
    }

    /**
     * Long runs keep going in the background as a foreground task: "media processing" on
     * Android 15+, "data sync" on 10-14 (media processing doesn't exist there), untyped on 9.
     */
    fun info(ctx: Context, done: Int, total: Int, title: String = "Indexing stickers", target: String = TARGET_STATUS): ForegroundInfo {
        val n = build(ctx, done, total, title, target)
        return when {
            android.os.Build.VERSION.SDK_INT >= 35 -> ForegroundInfo(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            android.os.Build.VERSION.SDK_INT >= 29 -> ForegroundInfo(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else -> ForegroundInfo(ID, n)
        }
    }

    fun update(ctx: Context, done: Int, total: Int, title: String = "Indexing stickers", target: String = TARGET_STATUS) {
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(ID, build(ctx, done, total, title, target)) }
    }
}

/** The notification's "Pause describing" button. */
class PauseReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: android.content.Intent) = AiWorker.pause(context.applicationContext)
}
