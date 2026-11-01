package com.kukreja.stickersearch.index

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Daily background sync: while charging and the battery isn't low, look for new, changed or
 * removed stickers; the scan then hands new stickers to the AI passes. WorkManager defers it
 * around Doze; nothing runs continuously.
 */
object Sync {
    private const val UNIQUE = "sticker-daily-sync"

    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<IndexWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build())
            .setInputData(workDataOf(IndexWorker.KEY_TRIGGER to "daily"))
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.KEEP, req)
    }
}
