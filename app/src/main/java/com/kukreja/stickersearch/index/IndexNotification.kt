package com.kukreja.stickersearch.index

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.ForegroundInfo
import com.kukreja.stickersearch.R

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

    private fun build(ctx: Context, done: Int, total: Int, title: String): Notification {
        channel(ctx)
        return Notification.Builder(ctx, CHANNEL)
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

    fun info(ctx: Context, done: Int, total: Int, title: String = "Indexing stickers") =
        ForegroundInfo(ID, build(ctx, done, total, title), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)

    fun update(ctx: Context, done: Int, total: Int, title: String = "Indexing stickers") {
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(ID, build(ctx, done, total, title)) }
    }
}

/** The notification's "Pause describing" button. */
class PauseReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: android.content.Intent) = AiWorker.pause(context.applicationContext)
}
