package app.stickersearch.ml

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context

/**
 * Gemma runs in native code: when it crashes, or Android kills the app for memory mid-reply, no
 * Kotlin code gets to record it. So a marker is written before every model call and cleared after.
 * A marker still there on the next start means the last call never finished. The sticker it was
 * describing then counts a failed attempt (so it can't crash the app forever), the GPU is given up
 * for the CPU, and repeated crashes pause describing until the user tries again.
 */
object DescribeGuard {
    private const val PREFS = "describe_guard"
    /** Crashes in a row, with no successful description in between, before describing pauses. */
    const val PAUSE_AFTER = 3

    data class Unfinished(val stickerId: Long, val backend: String, val at: Long, val why: String)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** [stickerId] is -1 for the reply assistant. commit(), not apply(): the process may die next. */
    fun begin(ctx: Context, stickerId: Long, backend: String?) {
        prefs(ctx).edit().putLong("id", stickerId).putString("backend", backend ?: "?").putLong("at", System.currentTimeMillis())
            .putBoolean("open", true).commit()
    }

    fun end(ctx: Context, ok: Boolean) {
        val e = prefs(ctx).edit().putBoolean("open", false)
        if (ok) e.putInt("streak", 0)
        e.commit()
    }

    /** The call that never finished, if any; also counts it towards [paused]. */
    fun takeUnfinished(ctx: Context): Unfinished? {
        val p = prefs(ctx)
        if (!p.getBoolean("open", false)) return null
        val at = p.getLong("at", 0)
        val u = Unfinished(p.getLong("id", -1), p.getString("backend", "?") ?: "?", at, exitReason(ctx, at))
        p.edit().putBoolean("open", false).putInt("streak", p.getInt("streak", 0) + 1)
            .putString("last", "${u.backend}: ${u.why}").putLong("last_at", at).commit()
        when {
            u.backend == "GPU" -> GemmaAnnotator.giveUpGpu(ctx)
            // A crash while loading (the GPU attempt has its own guard) or on the CPU: use less memory.
            else -> GemmaAnnotator.useLite(ctx)
        }
        return u
    }

    // ---- the same guard for the e5 text-vector step (ONNX, also native) ----

    fun beginEmbed(ctx: Context, stickerId: Long) {
        prefs(ctx).edit().putLong("embed_id", stickerId).putLong("embed_at", System.currentTimeMillis()).putBoolean("embed_open", true).commit()
    }

    fun endEmbed(ctx: Context) { prefs(ctx).edit().putBoolean("embed_open", false).commit() }

    /** A text-vector call that never finished: that sticker is skipped from now on. Returns its id. */
    fun takeUnfinishedEmbed(ctx: Context): Long? {
        val p = prefs(ctx)
        if (!p.getBoolean("embed_open", false)) return null
        val id = p.getLong("embed_id", -1)
        val at = p.getLong("embed_at", 0)
        p.edit().putBoolean("embed_open", false)
            .putStringSet("embed_skip", (p.getStringSet("embed_skip", emptySet()).orEmpty() + id.toString()))
            .putString("last", "text vectors (e5): ${exitReason(ctx, at)}").putLong("last_at", at).commit()
        return id
    }

    fun embedSkipped(ctx: Context): Set<Long> =
        prefs(ctx).getStringSet("embed_skip", emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()

    fun streak(ctx: Context) = prefs(ctx).getInt("streak", 0)
    fun paused(ctx: Context) = streak(ctx) >= PAUSE_AFTER
    /** "Start now" clears a pause: the user wants another go. */
    fun resume(ctx: Context) = prefs(ctx).edit().putInt("streak", 0).apply()
    fun lastCrash(ctx: Context): String? = prefs(ctx).getString("last", null)
    fun lastCrashAt(ctx: Context): Long = prefs(ctx).getLong("last_at", 0)

    /** Android's own record of why the app's process ended after [since]. */
    @android.annotation.SuppressLint("NewApi") // guarded by the SDK check at the top
    fun exitReason(ctx: Context, since: Long): String {
        // Android keeps exit reasons from Android 11; older phones just get the plain message.
        if (android.os.Build.VERSION.SDK_INT < 30) return "the app closed unexpectedly"
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return "the app closed unexpectedly"
        val info = runCatching { am.getHistoricalProcessExitReasons(ctx.packageName, 0, 5) }.getOrNull()
            ?.firstOrNull { it.timestamp >= since - 1_000 } ?: return "the app closed unexpectedly"
        val what = when (info.reason) {
            ApplicationExitInfo.REASON_LOW_MEMORY -> "Android closed the app to free memory"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "the model crashed (native)"
            ApplicationExitInfo.REASON_CRASH -> "the app crashed"
            ApplicationExitInfo.REASON_ANR -> "the app stopped responding"
            ApplicationExitInfo.REASON_SIGNALED -> "the system stopped the app (signal ${info.status})"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Android stopped the app for using too much"
            ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED -> "the app was closed or force-stopped"
            else -> "the app closed (reason ${info.reason})"
        }
        val mb = info.pss.takeIf { it > 0 }?.let { ", using %,d MB".format(it / 1024) } ?: ""
        val where = nativeLibs(info).takeIf { it.isNotEmpty() }?.let { " in ${it.take(3).joinToString(", ")}" } ?: ""
        return what + mb + where + (info.description?.takeIf { it.isNotBlank() && it != "crash" }?.let { " · ${it.take(120)}" } ?: "")
    }

    /** The last few process exits, for the diagnostics report. */
    @android.annotation.SuppressLint("NewApi") // guarded by the SDK check at the top
    fun recentExits(ctx: Context): List<String> {
        if (android.os.Build.VERSION.SDK_INT < 30) return emptyList()
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return emptyList()
        return runCatching { am.getHistoricalProcessExitReasons(ctx.packageName, 0, 5) }.getOrDefault(emptyList()).map {
            "${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(it.timestamp))} " +
                "reason=${it.reason} status=${it.status} pssMB=${it.pss / 1024} rssMB=${it.rss / 1024} ${it.description.orEmpty().take(120)}" +
                nativeLibs(it).takeIf { l -> l.isNotEmpty() }?.let { l -> " libs=${l.joinToString(",")}" }.orEmpty()
        }
    }

    /**
     * For a native crash Android keeps the tombstone (a protobuf). Its readable strings name the
     * native libraries in the crashing thread's backtrace, which says which engine crashed
     * (MediaPipe's LLM library, ONNX Runtime, ...). System libraries are left out.
     */
    @androidx.annotation.RequiresApi(30)
    private fun nativeLibs(info: ApplicationExitInfo): List<String> {
        if (android.os.Build.VERSION.SDK_INT < 31 || info.reason != ApplicationExitInfo.REASON_CRASH_NATIVE) return emptyList()
        val bytes = runCatching { info.traceInputStream?.use { it.readBytes() } }.getOrNull() ?: return emptyList()
        val text = String(bytes, Charsets.ISO_8859_1)
        return Regex("""[A-Za-z0-9_\-.]+\.so""").findAll(text).map { it.value }
            .filterNot { it.startsWith("libc.") || it.startsWith("libart") || it.startsWith("libbase") || it == "linker64.so" }
            .distinct().take(8).toList()
    }
}
