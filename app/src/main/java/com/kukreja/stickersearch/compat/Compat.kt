package com.kukreja.stickersearch.compat

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.IntentCompat

/** The few places where Android 9-16 differ, in one spot. */
object Compat {
    /** Lossy WebP: WEBP_LOSSY from Android 11; before that, the old WEBP format (lossy below quality 100). */
    @Suppress("DEPRECATION")
    val webpLossy: Bitmap.CompressFormat
        get() = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP

    /** Lossless WebP: WEBP_LOSSLESS from Android 11; before that, the old WEBP format at quality 100. */
    @Suppress("DEPRECATION")
    val webpLossless: Bitmap.CompressFormat
        get() = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP

    /**
     * Whether the app may read WhatsApp's folders by path: "All files access" from Android 11,
     * the storage permission on Android 9-10 (with legacy storage on 10).
     */
    fun canReadAllFiles(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ctx.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    fun streamOf(intent: Intent): Uri? = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)

    fun streamsOf(intent: Intent): List<Uri> =
        IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()

    /** The notification permission only exists (as a runtime permission) from Android 13. */
    fun needsNotificationPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
}
