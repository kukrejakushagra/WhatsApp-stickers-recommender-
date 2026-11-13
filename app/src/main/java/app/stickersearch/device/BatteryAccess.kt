package app.stickersearch.device

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri

/**
 * Whether Android lets the app run for hours in the background (what Android 12+ calls the app's
 * battery mode: Unrestricted, Optimised or Restricted), and how to change it by hand. Android has no
 * way for an app to switch itself to Unrestricted, so Settings shows the status and the steps.
 */
object BatteryAccess {

    enum class Level { UNRESTRICTED, OPTIMISED, RESTRICTED }

    data class State(
        /** Exempt from battery optimisation (Doze): true when the app is "Unrestricted" on Android 12+. */
        val dozeExempt: Boolean = false,
        /** The user (or the phone) restricted background work: "Restricted" on Android 12+. */
        val backgroundRestricted: Boolean = false,
        /** Android put the app in the "restricted" standby bucket (Android 11+). */
        val restrictedBucket: Boolean = false,
        val standbyBucket: Int = -1,
    )

    private const val STANDBY_BUCKET_RESTRICTED = 45

    fun read(ctx: Context): State {
        val dozeExempt = runCatching { ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName) }.getOrDefault(false)
        val restricted = runCatching { ctx.getSystemService(ActivityManager::class.java).isBackgroundRestricted }.getOrDefault(false)
        val bucket = runCatching { ctx.getSystemService(UsageStatsManager::class.java).appStandbyBucket }.getOrDefault(-1)
        return State(
            dozeExempt = dozeExempt, backgroundRestricted = restricted,
            restrictedBucket = android.os.Build.VERSION.SDK_INT >= 30 && bucket == STANDBY_BUCKET_RESTRICTED,
            standbyBucket = bucket,
        )
    }

    fun level(s: State): Level = when {
        s.backgroundRestricted || s.restrictedBucket -> Level.RESTRICTED
        s.dozeExempt -> Level.UNRESTRICTED
        else -> Level.OPTIMISED
    }

    /** The manual path to Unrestricted on this Android version, then the phone maker's extra switch. */
    fun steps(sdk: Int, manufacturer: String): String {
        val android = if (sdk >= 31) {
            "Tap Open app settings, then App battery usage (on some phones: Battery), and choose Unrestricted."
        } else {
            "Tap Open app settings, then Battery (or Advanced › Battery), and turn Background restriction off. " +
                "Then open Settings › Apps › Special app access › Battery optimisation, switch the list to All apps, " +
                "tap AI Sticker Search and choose Don't optimise."
        }
        return android + " " + DeviceCaps.batteryTip(manufacturer)
    }

    /** App info: the one screen every Android version and phone maker has; its battery page is one tap away. */
    fun appSettingsIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${ctx.packageName}".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Opens App info, or the main Settings screen on the rare phone without it. */
    fun openAppSettings(ctx: Context) {
        try {
            ctx.startActivity(appSettingsIntent(ctx))
        } catch (e: android.content.ActivityNotFoundException) {
            runCatching { ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}
