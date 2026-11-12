package com.kukreja.stickersearch.device

import android.app.ActivityManager
import android.content.Context

/**
 * What this phone can reasonably do. Gemma (the describing model) needs about 3 GB of memory on
 * top of Android and the app, so phones with less than ~6 GB, or Android's "low RAM" class, have
 * on-phone describing off by default. Search by look, scans and sending work everywhere.
 */
object DeviceCaps {
    private const val PREFS = "device"
    /** Total memory below this (in GB, as Android reports it) makes on-phone describing unlikely to succeed. */
    const val DESCRIBE_MIN_GB = 5.5

    fun memoryGb(ctx: Context): Double {
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return 0.0
        val info = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        return info.totalMem / (1024.0 * 1024 * 1024)
    }

    fun lowRam(ctx: Context) = ctx.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true

    /** Pure rule, tested on the JVM. */
    fun phoneCanDescribe(memoryGb: Double, lowRamDevice: Boolean) = !lowRamDevice && memoryGb >= DESCRIBE_MIN_GB

    fun phoneCanDescribe(ctx: Context) = phoneCanDescribe(memoryGb(ctx), lowRam(ctx))

    /** The user chose "Try anyway" on a phone with little memory. */
    fun describeAnyway(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("describe_anyway", false)

    fun setDescribeAnyway(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("describe_anyway", on).apply()

    /** Whether describing may run on this phone at all. */
    fun describingAllowed(ctx: Context) = phoneCanDescribe(ctx) || describeAnyway(ctx)

    /**
     * The step that keeps background work alive on this maker's phones (their battery managers
     * stop apps beyond what Android itself does). Pure, tested on the JVM.
     */
    fun batteryTip(manufacturer: String): String = when (manufacturer.lowercase().trim()) {
        "xiaomi", "redmi", "poco" -> "On Xiaomi, Redmi and POCO phones, also turn on Autostart for Sticker Search, and set its Battery saver to “No restrictions”."
        "samsung" -> "On Samsung phones, also add Sticker Search to “Never sleeping apps” (Settings › Battery › Background usage limits)."
        "oneplus", "oppo", "realme" -> "On OnePlus, Oppo and Realme phones, also allow Auto-launch and background activity for Sticker Search."
        "vivo", "iqoo" -> "On Vivo and iQOO phones, also allow high background power consumption for Sticker Search."
        "huawei", "honor" -> "On Huawei and Honor phones, also set Sticker Search to “Manage manually” in Settings › Battery › App launch, with all three switches on."
        "asus" -> "On Asus phones, also allow Auto-start for Sticker Search in Mobile Manager."
        else -> "If overnight runs still stop, check your phone's battery settings for an “auto-start” or “background activity” option for Sticker Search."
    }
}
