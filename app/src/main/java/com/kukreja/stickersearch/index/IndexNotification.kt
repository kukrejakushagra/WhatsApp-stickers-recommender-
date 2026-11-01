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

    private fun build(ctx: Context, done: Int, total: Int): Notification {
        channel(ctx)
        return Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_kds_search)
            .setContentTitle("Indexing stickers")
            .setContentText(if (total > 0) "%,d of %,d".format(done, total) else "Listing files")
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    fun info(ctx: Context, done: Int, total: Int) =
        ForegroundInfo(ID, build(ctx, done, total), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)

    fun update(ctx: Context, done: Int, total: Int) {
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(ID, build(ctx, done, total)) }
    }
}
