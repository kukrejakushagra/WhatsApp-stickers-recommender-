package app.stickersearch.device

import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Phone makers that add their own background controls on top of Android's battery setting
 * (Honor/Huawei "App launch", Xiaomi "Autostart", Samsung "sleeping apps"...). Apps can't read these
 * settings, so Settings explains the step, offers a button to the screen and a "Done" checkbox.
 */
enum class PhoneMaker(
    val brand: String,
    /** What the maker calls the setting, used in the row title and button. */
    val setting: String,
    val steps: String,
    /** Screens to try, in order; App info is the fallback. */
    val screens: List<Pair<String, String>>,
) {
    HONOR(
        "Honor", "App launch",
        "Open App launch (or App info › Power usage details › App launch), find AI Sticker Search and turn off " +
            "Manage automatically. In the window that opens, turn on Auto-launch, Secondary launch and Run in background, then tap OK.",
        listOf(
            "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        ),
    ),
    HUAWEI(
        "Huawei", "App launch",
        "Open App launch (or App info › Power usage details › App launch), find AI Sticker Search and turn off " +
            "Manage automatically. In the window that opens, turn on Auto-launch, Secondary launch and Run in background, then tap OK.",
        listOf(
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
        ),
    ),
    XIAOMI(
        "Xiaomi", "Autostart",
        "Turn on Autostart for AI Sticker Search. Then in App info › Battery saver, choose No restrictions.",
        listOf("com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity"),
    ),
    SAMSUNG(
        "Samsung", "Background usage limits",
        "In Settings › Battery › Background usage limits, make sure AI Sticker Search isn't in Sleeping apps or " +
            "Deep sleeping apps, and add it to Never sleeping apps.",
        listOf("com.samsung.android.lool" to "com.samsung.android.sm.battery.ui.BatteryActivity"),
    ),
    ONEPLUS(
        "OnePlus", "Auto-launch",
        "Allow Auto-launch for AI Sticker Search. Then in App info › Battery usage, allow background activity.",
        listOf("com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
    ),
    OPPO(
        "Oppo / Realme", "Auto launch",
        "Allow Auto launch for AI Sticker Search. Then in App info › Battery usage, allow background activity.",
        listOf("com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
    ),
    VIVO(
        "Vivo", "Background power",
        "Allow High background power consumption (sometimes called Background activity) for AI Sticker Search, and Autostart if it's listed.",
        listOf("com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
    ),
    ASUS(
        "Asus", "Auto-start",
        "In Mobile Manager › Auto-start manager, allow AI Sticker Search.",
        listOf("com.asus.mobilemanager" to "com.asus.mobilemanager.autostart.AutoStartActivity"),
    );

    /** Opens the maker's screen if the phone has it, otherwise App info. */
    fun open(ctx: Context) {
        for ((pkg, cls) in screens) {
            try {
                ctx.startActivity(Intent().setComponent(ComponentName(pkg, cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) {
                // ActivityNotFoundException or SecurityException: try the next one.
            }
        }
        BatteryAccess.openAppSettings(ctx)
    }

    companion object {
        fun of(manufacturer: String): PhoneMaker? = when (manufacturer.lowercase().trim()) {
            "honor", "hihonor" -> HONOR
            "huawei" -> HUAWEI
            "xiaomi", "redmi", "poco" -> XIAOMI
            "samsung" -> SAMSUNG
            "oneplus" -> ONEPLUS
            "oppo", "realme" -> OPPO
            "vivo", "iqoo" -> VIVO
            "asus" -> ASUS
            else -> null
        }
    }
}
