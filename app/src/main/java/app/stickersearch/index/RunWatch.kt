package app.stickersearch.index

import android.app.ActivityManager
import android.content.Context

/**
 * Notices when the phone stops a describing run. Phone makers' own background controls (Honor's
 * App launch, Xiaomi's Autostart...) can't be read, but their effect can: the run starts, writes a
 * heartbeat as it goes, and records how it ended. A run that never recorded its end died mid-way;
 * the next time the app opens, Android's exit record (Android 11+) says why.
 */
object RunWatch {
    enum class Cause { PHONE, MEMORY, CRASH, UPDATE, UNKNOWN }

    data class Stop(val at: Long, val minutes: Int, val described: Int, val cause: Cause, val detail: String)

    private const val PREFS = "run_watch"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** ApplicationExitInfo.REASON_* (as numbers, so the mapping is testable on the JVM). */
    fun classify(reason: Int): Cause = when (reason) {
        4, 5, 6, 7 -> Cause.CRASH            // crash, native crash, not responding, failed to start
        3 -> Cause.MEMORY                    // low memory
        15, 16 -> Cause.UPDATE               // package changed / updated
        1 -> Cause.UNKNOWN                   // exited itself
        else -> Cause.PHONE                  // signalled, excessive use, force-stop, frozen, other, unknown
    }

    fun start(ctx: Context) {
        val now = System.currentTimeMillis()
        prefs(ctx).edit().putBoolean("open", true).putLong("start", now).putLong("beat", now).putInt("described", 0).commit()
    }

    fun beat(ctx: Context, described: Int) {
        prefs(ctx).edit().putLong("beat", System.currentTimeMillis()).putInt("described", described).apply()
    }

    /**
     * The run ended on its own terms. [stoppedBy] is null for a normal end (finished, paused,
     * unplugged); otherwise Android stopped the job (e.g. background restriction) and it's recorded.
     */
    fun end(ctx: Context, stoppedBy: String? = null) {
        val p = prefs(ctx)
        val e = p.edit().putBoolean("open", false)
        if (stoppedBy == null) clearStop(e)
        else putStop(e, p.getLong("beat", 0), p.getLong("start", 0), p.getInt("described", 0), Cause.PHONE, stoppedBy)
        e.commit()
    }

    /** Called when the app opens: if a run never recorded its end, works out why and keeps it. */
    fun takeUnfinished(ctx: Context): Stop? {
        val p = prefs(ctx)
        if (!p.getBoolean("open", false)) return null
        val beat = p.getLong("beat", 0)
        val (cause, detail) = exitCause(ctx, beat)
        val e = p.edit().putBoolean("open", false)
        if (cause == Cause.CRASH || cause == Cause.UPDATE) clearStop(e)  // not the phone's doing
        else putStop(e, beat, p.getLong("start", 0), p.getInt("described", 0), cause, detail)
        e.commit()
        return lastStop(ctx)
    }

    fun lastStop(ctx: Context): Stop? {
        val p = prefs(ctx)
        if (!p.contains("stop_at")) return null
        return Stop(p.getLong("stop_at", 0), p.getInt("stop_min", 0), p.getInt("stop_described", 0),
            runCatching { Cause.valueOf(p.getString("stop_cause", "UNKNOWN")!!) }.getOrDefault(Cause.UNKNOWN), p.getString("stop_detail", "") ?: "")
    }

    fun dismiss(ctx: Context) = prefs(ctx).edit().also { clearStop(it) }.apply()

    private fun putStop(e: android.content.SharedPreferences.Editor, beat: Long, start: Long, described: Int, cause: Cause, detail: String) {
        e.putLong("stop_at", beat).putInt("stop_min", (((beat - start).coerceAtLeast(0)) / 60_000).toInt())
            .putInt("stop_described", described).putString("stop_cause", cause.name).putString("stop_detail", detail)
    }

    private fun clearStop(e: android.content.SharedPreferences.Editor) {
        e.remove("stop_at").remove("stop_min").remove("stop_described").remove("stop_cause").remove("stop_detail")
    }

    private fun exitCause(ctx: Context, since: Long): Pair<Cause, String> {
        if (android.os.Build.VERSION.SDK_INT < 30) return Cause.UNKNOWN to "the app was stopped"
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return Cause.UNKNOWN to "the app was stopped"
        val info = runCatching { am.getHistoricalProcessExitReasons(ctx.packageName, 0, 5) }.getOrNull()
            ?.firstOrNull { it.timestamp >= since - 60_000 } ?: return Cause.UNKNOWN to "the app was stopped"
        val cause = classify(info.reason)
        val detail = when (cause) {
            Cause.MEMORY -> "Android closed the app to free memory"
            Cause.PHONE -> "the phone stopped the app in the background"
            Cause.CRASH -> "the app crashed"
            Cause.UPDATE -> "the app was updated"
            Cause.UNKNOWN -> "the app was stopped"
        }
        return cause to detail
    }
}
