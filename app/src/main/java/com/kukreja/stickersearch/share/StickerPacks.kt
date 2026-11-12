package com.kukreja.stickersearch.share

import android.content.ActivityNotFoundException
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.UriMatcher
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.kukreja.stickersearch.R
import com.kukreja.stickersearch.media.WebpAnim
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Sticker packs (two built-in, plus any the user makes) that WhatsApp reads through the documented third-party sticker-pack
 * ContentProvider contract (github.com/WhatsApp/stickers). Sending a sticker puts it first in the
 * matching pack; WhatsApp's own picker then shows it, and it arrives as a real sticker.
 * WhatsApp rules: 3–30 stickers per pack, 512×512 WebP, static ≤100 KB, animated ≤500 KB,
 * one kind per pack, 96×96 PNG tray icon.
 */
object StickerPacks {

    /** A pack served to WhatsApp: the two built-in "Sticker Search" packs, or one the user made. */
    data class Pack(val id: String, val title: String, val animated: Boolean, val custom: Boolean = false) {
        companion object {
            val STATIC = Pack("search_static", "Sticker Search", false)
            val ANIMATED = Pack("search_animated", "Sticker Search · animated", true)
            val builtIns = listOf(STATIC, ANIMATED)
        }
    }

    data class Entry(val sha: String, val emoji: String)

    const val MAX = 30
    const val MIN = 3
    const val TRAY = "tray.png"
    private const val PREFS = "sticker_packs"
    private const val CUSTOM = "custom_packs"

    fun authority(ctx: Context) = "${ctx.packageName}.stickercontentprovider"
    private fun dir(ctx: Context, pack: Pack) = File(ctx.filesDir, "packs/${pack.id}").apply { mkdirs() }
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Built-in packs first, then the user's packs, newest first. */
    fun all(ctx: Context): List<Pack> = Pack.builtIns + custom(ctx)

    fun custom(ctx: Context): List<Pack> = runCatching {
        val a = JSONArray(prefs(ctx).getString(CUSTOM, "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Pack(o.getString("id"), o.getString("title"), o.getBoolean("animated"), true) } }
    }.getOrDefault(emptyList())

    private fun saveCustom(ctx: Context, packs: List<Pack>) {
        prefs(ctx).edit().putString(CUSTOM, JSONArray(packs.map {
            JSONObject().put("id", it.id).put("title", it.title).put("animated", it.animated)
        }).toString()).apply()
    }

    /** WhatsApp limits pack names to 128 characters; ids to letters, digits, _ - . and spaces. */
    fun createPack(ctx: Context, title: String, animated: Boolean): Pack {
        val clean = title.trim().ifBlank { "My stickers" }.take(120)
        val pack = Pack("custom_${System.currentTimeMillis()}_${if (animated) "a" else "s"}", clean, animated, true)
        saveCustom(ctx, listOf(pack) + custom(ctx))
        ensureTray(ctx, pack)
        return pack
    }

    fun renamePack(ctx: Context, pack: Pack, title: String) {
        saveCustom(ctx, custom(ctx).map { if (it.id == pack.id) it.copy(title = title.trim().take(120).ifBlank { it.title }) else it })
        bump(ctx, pack)
    }

    fun deletePack(ctx: Context, pack: Pack) {
        if (!pack.custom) return
        saveCustom(ctx, custom(ctx).filter { it.id != pack.id })
        prefs(ctx).edit().remove(pack.id).remove("${pack.id}_v").remove("${pack.id}_added").remove("${pack.id}_added_w4b").apply()
        File(ctx.filesDir, "packs/${pack.id}").deleteRecursively()
        notifyWhatsApp(ctx)
    }

    fun entries(ctx: Context, pack: Pack): List<Entry> = runCatching {
        val a = JSONArray(prefs(ctx).getString(pack.id, "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Entry(o.getString("sha"), o.optString("emoji", "🙂")) } }
    }.getOrDefault(emptyList())

    fun version(ctx: Context, pack: Pack) = prefs(ctx).getInt("${pack.id}_v", 1)

    private fun saveEntries(ctx: Context, pack: Pack, all: List<Entry>) {
        // A listed sticker without its file makes WhatsApp reject the whole pack.
        val list = all.filter { File(dir(ctx, pack), "${it.sha}.webp").exists() }
        prefs(ctx).edit()
            .putString(pack.id, JSONArray(list.map { JSONObject().put("sha", it.sha).put("emoji", it.emoji) }).toString())
            .putInt("${pack.id}_v", version(ctx, pack) + 1)
            .apply()
        // Files no longer in the pack are removed.
        dir(ctx, pack).listFiles()?.forEach { f -> if (f.name != trayName(pack) && list.none { "${it.sha}.webp" == f.name }) f.delete() }
        ensureTray(ctx, pack)
        notifyWhatsApp(ctx)
    }

    private fun bump(ctx: Context, pack: Pack) {
        prefs(ctx).edit().putInt("${pack.id}_v", version(ctx, pack) + 1).apply()
        notifyWhatsApp(ctx)
    }

    private fun notifyWhatsApp(ctx: Context) =
        ctx.contentResolver.notifyChange(Uri.parse("content://${authority(ctx)}/metadata"), null)

    sealed interface Prepared {
        data class Ok(val pack: Pack) : Prepared
        data class NotEligible(val reason: String) : Prepared
    }

    /** Writes the pack-compliant WebP for [sha] into [pack]'s folder; null when it can't go into a pack. */
    private fun prepareFile(ctx: Context, pack: Pack, sha: String, bytes: ByteArray): String? {
        val out = File(dir(ctx, pack), "$sha.webp")
        if (out.exists()) return null
        if (pack.animated) {
            animatedProblem(bytes)?.let { return it }
            out.writeBytes(bytes)
        } else {
            out.writeBytes(staticWebp(bytes) ?: return "This sticker couldn't be converted for WhatsApp.")
        }
        return null
    }

    /**
     * Converts [bytes] to a pack-compliant WebP (static stickers are re-encoded onto a 512×512 canvas
     * until they fit 100 KB; animated ones must already comply) and puts it first in the built-in pack.
     */
    fun add(ctx: Context, sha: String, bytes: ByteArray, animated: Boolean, emoji: String): Prepared {
        val pack = if (animated) Pack.ANIMATED else Pack.STATIC
        prepareFile(ctx, pack, sha, bytes)?.let { return Prepared.NotEligible(it) }
        saveEntries(ctx, pack, (listOf(Entry(sha, safeEmoji(emoji))) + entries(ctx, pack).filter { it.sha != sha }).take(MAX))
        return Prepared.Ok(pack)
    }

    data class AddResult(val added: Int, val skipped: List<String>, val full: Int)

    /** Adds stickers to a user pack, keeping its order; stops at WhatsApp's 30-sticker limit. */
    fun addMany(ctx: Context, pack: Pack, items: List<Triple<String, ByteArray, String>>): AddResult {
        val list = entries(ctx, pack).toMutableList()
        val skipped = mutableListOf<String>()
        var added = 0
        var full = 0
        for ((sha, bytes, emoji) in items) {
            if (list.any { it.sha == sha }) continue
            if (list.size >= MAX) { full++; continue }
            val problem = prepareFile(ctx, pack, sha, bytes)
            if (problem != null) { skipped += problem; continue }
            list += Entry(sha, safeEmoji(emoji))
            added++
        }
        saveEntries(ctx, pack, list)
        return AddResult(added, skipped, full)
    }

    fun remove(ctx: Context, pack: Pack, sha: String) = saveEntries(ctx, pack, entries(ctx, pack).filter { it.sha != sha })

    /** WhatsApp needs at least 3 stickers in a pack; pad with these until the user has sent three. */
    fun fillIfShort(ctx: Context, pack: Pack, candidates: List<Triple<String, ByteArray, String>>) {
        val list = entries(ctx, pack).toMutableList()
        for ((sha, bytes, emoji) in candidates) {
            if (list.size >= MIN) break
            if (list.any { it.sha == sha }) continue
            if (prepareFile(ctx, pack, sha, bytes) == null) list += Entry(sha, safeEmoji(emoji))
        }
        saveEntries(ctx, pack, list)
    }

    fun animatedProblem(bytes: ByteArray): String? {
        val a = WebpAnim.parse(bytes) ?: return "Only animated WebP stickers can go into WhatsApp packs."
        if (a.canvasWidth != 512 || a.canvasHeight != 512) return "WhatsApp needs animated stickers at 512×512; this one is ${a.canvasWidth}×${a.canvasHeight}."
        if (bytes.size > 500 * 1024) return "WhatsApp allows animated pack stickers up to 500 KB; this one is ${bytes.size / 1024} KB."
        if (a.totalDurationMs > 10_000) return "WhatsApp allows animations up to 10 seconds."
        if (a.frames.any { it.durationMs in 1..7 }) return "WhatsApp needs each frame to last at least 8 ms."
        return null
    }

    private fun staticWebp(bytes: ByteArray): ByteArray? {
        val src = runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))) { d, _, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull() ?: return null
        val canvas = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val scale = minOf(512f / src.width, 512f / src.height)
        val w = (src.width * scale).toInt(); val h = (src.height * scale).toInt()
        Canvas(canvas).drawBitmap(src, null, Rect((512 - w) / 2, (512 - h) / 2, (512 + w) / 2, (512 + h) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
        src.recycle()
        try {
            for (q in listOf(100, 90, 80, 70, 60, 50, 40)) {
                val fmt = if (q == 100) com.kukreja.stickersearch.compat.Compat.webpLossless else com.kukreja.stickersearch.compat.Compat.webpLossy
                val o = ByteArrayOutputStream()
                canvas.compress(fmt, q, o)
                if (o.size() <= 100 * 1024) return o.toByteArray()
            }
            return null
        } finally {
            canvas.recycle()
        }
    }

    /**
     * The tray icon file. Animated packs get their own icon (the app icon with a play badge), so
     * the two packs are easy to tell apart in WhatsApp's sticker picker.
     */
    fun trayName(pack: Pack) = if (pack.animated) ANIM_TRAY else TRAY
    private const val ANIM_TRAY = "tray_anim.png"

    /** Draws the tray icon if it's missing; true when it had to be made. */
    private fun ensureTray(ctx: Context, pack: Pack): Boolean {
        val f = File(dir(ctx, pack), trayName(pack))
        if (f.exists()) return false
        val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        ctx.getDrawable(R.drawable.ic_launcher_bg)?.apply { setBounds(0, 0, 96, 96); draw(c) }
        ctx.getDrawable(R.drawable.ic_launcher_fg)?.apply { setBounds(-18, -18, 114, 114); draw(c) }
        if (pack.animated) {
            // Play badge, bottom-right: a dark disc with a white ring and a white triangle.
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.color = android.graphics.Color.WHITE
            c.drawCircle(70f, 70f, 25f, p)
            p.color = android.graphics.Color.rgb(11, 20, 20)
            c.drawCircle(70f, 70f, 22f, p)
            p.color = android.graphics.Color.WHITE
            val tri = android.graphics.Path().apply { moveTo(63f, 58f); lineTo(83f, 70f); lineTo(63f, 82f); close() }
            c.drawPath(tri, p)
        }
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return true
    }

    /**
     * Makes sure the tray icon WhatsApp is about to ask for exists. Packs added before the
     * animated icon existed get it now, with a version bump so WhatsApp refreshes the tray.
     */
    internal fun ensureTrayForServing(ctx: Context, pack: Pack) {
        if (ensureTray(ctx, pack) && entries(ctx, pack).isNotEmpty()) {
            prefs(ctx).edit().putInt("${pack.id}_v", version(ctx, pack) + 1).apply()
        }
    }

    fun ensureTrays(ctx: Context) = all(ctx).forEach { ensureTray(ctx, it) }

    // ---- which WhatsApp: regular, Business, or both installed ----

    const val WHATSAPP = "com.whatsapp"
    const val WHATSAPP_BUSINESS = "com.whatsapp.w4b"

    fun appName(pkg: String) = if (pkg == WHATSAPP_BUSINESS) "WhatsApp Business" else "WhatsApp"

    /** The WhatsApp apps installed on this phone, regular first. */
    fun installedApps(ctx: Context): List<String> =
        listOf(WHATSAPP, WHATSAPP_BUSINESS).filter { ctx.packageManager.getLaunchIntentForPackage(it) != null }

    /** Both are installed and the user hasn't said which one to send with yet. */
    fun needsAppChoice(ctx: Context) = shouldAsk(installedApps(ctx), prefs(ctx).getString("wa_target", null))

    fun setTarget(ctx: Context, pkg: String) = prefs(ctx).edit().putString("wa_target", pkg).apply()

    /** The app stickers are sent with: the chosen one, or the only one installed. */
    fun target(ctx: Context): String = pickTarget(installedApps(ctx), prefs(ctx).getString("wa_target", null))

    /** Pure rule: the saved choice if that app is installed, else the only (or first) one, else WhatsApp. */
    fun pickTarget(installed: List<String>, saved: String?): String =
        saved?.takeIf { it in installed } ?: installed.firstOrNull() ?: WHATSAPP

    /** Pure rule: ask only when both are installed and nothing has been chosen. */
    fun shouldAsk(installed: List<String>, saved: String?) = installed.size > 1 && saved == null

    /** Per-app bookkeeping: regular WhatsApp keeps the original keys, Business gets its own. */
    private fun key(pack: Pack, name: String, pkg: String) =
        if (pkg == WHATSAPP_BUSINESS) "${pack.id}_${name}_w4b" else "${pack.id}_$name"

    /**
     * WhatsApp's documented whitelist check: true or false, or null when WhatsApp didn't answer.
     * Some WhatsApp builds answer false (or nothing) even for an added pack, so this alone isn't trusted.
     */
    fun whitelisted(ctx: Context, pack: Pack, pkg: String = target(ctx)): Boolean? = runCatching {
        val uri = Uri.parse("content://$pkg.provider.sticker_whitelist_check/is_whitelisted")
            .buildUpon().appendQueryParameter("authority", authority(ctx)).appendQueryParameter("identifier", pack.id).build()
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) null else c.getInt(c.getColumnIndexOrThrow("result")) == 1
        }
    }.getOrNull()

    /**
     * True once WhatsApp has added this pack: its whitelist says so, or it answered "added" to our
     * request earlier. Remembering the answer stops the Add screen from coming back on every send.
     */
    fun isAdded(ctx: Context, pack: Pack, pkg: String = target(ctx)): Boolean {
        if (whitelisted(ctx, pack, pkg) == true) { markAdded(ctx, pack, true, pkg); return true }
        return prefs(ctx).getBoolean(key(pack, "added", pkg), false)
    }

    /** The pack version WhatsApp last read the sticker list of (0: never seen it read). */
    fun lastReadVersion(ctx: Context, pack: Pack, pkg: String = target(ctx)) = prefs(ctx).getInt(key(pack, "read_v", pkg), 0)

    /**
     * Called from the provider when WhatsApp reads a pack's sticker list. Logged once per version,
     * so the diagnostics report shows whether WhatsApp picks up changes by itself.
     */
    internal fun markRead(ctx: Context, pack: Pack, caller: String?) {
        // Which app is reading: regular WhatsApp or Business keep separate records.
        val pkg = if (caller == WHATSAPP_BUSINESS) WHATSAPP_BUSINESS else WHATSAPP
        val v = version(ctx, pack)
        if (lastReadVersion(ctx, pack, pkg) == v) return
        prefs(ctx).edit().putInt(key(pack, "read_v", pkg), v).putLong(key(pack, "read_at", pkg), System.currentTimeMillis()).apply()
        runCatching {
            com.kukreja.stickersearch.diagnostics.EventLog(File(ctx.filesDir, "events.jsonl"))
                .add("whatsapp_read", mapOf("pack" to pack.id, "app" to pkg, "version" to v, "stickers" to entries(ctx, pack).size))
        }
    }

    /**
     * WhatsApp rejects a whole pack if one sticker's emoji isn't a real emoji, so anything else
     * (words, several emoji, a cut-off character) becomes 🙂.
     */
    fun safeEmoji(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return "🙂"
        val it = java.text.BreakIterator.getCharacterInstance().apply { setText(t) }
        val first = t.substring(0, it.next().coerceAtLeast(1))
        val cp = first.codePointAt(0)
        val emojiLike = Character.getType(cp) == Character.OTHER_SYMBOL.toInt() || cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF
        return if (emojiLike) first else "🙂"
    }

    // ---- did WhatsApp pick up the sticker just sent? ----

    /** Called when WhatsApp is opened for a send: the version it should now show. */
    fun recordSent(ctx: Context, pack: Pack) {
        val pkg = target(ctx)
        prefs(ctx).edit().putInt(key(pack, "sent_v", pkg), version(ctx, pack)).putLong(key(pack, "sent_at", pkg), System.currentTimeMillis())
            .putBoolean(key(pack, "sent_checked", pkg), false).apply()
    }

    /**
     * Checked once, when the user comes back to the app 5 s to 10 min after a send: true if WhatsApp
     * read the pack's new sticker list, false if it didn't, null when there's nothing to check.
     */
    fun checkSent(ctx: Context, pack: Pack): Boolean? {
        val p = prefs(ctx)
        val pkg = target(ctx)
        val at = p.getLong(key(pack, "sent_at", pkg), 0)
        val age = System.currentTimeMillis() - at
        if (at == 0L || p.getBoolean(key(pack, "sent_checked", pkg), true) || age < 5_000) return null
        p.edit().putBoolean(key(pack, "sent_checked", pkg), true).apply()
        if (age > 10 * 60_000) return null
        return lastReadVersion(ctx, pack, pkg) >= p.getInt(key(pack, "sent_v", pkg), 0)
    }

    /** After repeated misses, every send goes through WhatsApp's pack screen, which makes it re-read the pack. */
    fun alwaysRefresh(ctx: Context) = prefs(ctx).getBoolean("always_refresh", false)
    fun setAlwaysRefresh(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("always_refresh", on).putInt("miss_count", 0).apply()

    /** Counts misses in a row; returns the new count (0 after a hit). */
    fun countMiss(ctx: Context, missed: Boolean): Int {
        val n = if (missed) prefs(ctx).getInt("miss_count", 0) + 1 else 0
        prefs(ctx).edit().putInt("miss_count", n).apply()
        return n
    }

    fun markAdded(ctx: Context, pack: Pack, added: Boolean, pkg: String = target(ctx)) =
        prefs(ctx).edit().putBoolean(key(pack, "added", pkg), added).apply()

    /** Asks WhatsApp to add the pack; WhatsApp shows its own confirmation. */
    fun addToWhatsAppIntent(ctx: Context, pack: Pack) = Intent("com.whatsapp.intent.action.ENABLE_STICKER_PACK").apply {
        putExtra("sticker_pack_id", pack.id)
        putExtra("sticker_pack_authority", authority(ctx))
        putExtra("sticker_pack_name", pack.title)
        setPackage(target(ctx))
    }

    fun openWhatsApp(ctx: Context): Boolean = try {
        ctx.packageManager.getLaunchIntentForPackage(target(ctx))?.let {
            // Without a reset flag this brings WhatsApp's existing task forward: you're back in the chat you left.
            ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
        } ?: false
    } catch (e: ActivityNotFoundException) { false }

    // ---- what the provider serves ----

    internal fun file(ctx: Context, packId: String, name: String): File? {
        val pack = pack(ctx, packId) ?: return null
        if (name.contains('/') || name.contains("..")) return null
        return File(dir(ctx, pack), name).takeIf { it.exists() }
    }

    fun pack(ctx: Context, id: String) = all(ctx).firstOrNull { it.id == id }

}

/** WhatsApp's third-party sticker-pack ContentProvider contract. Read-only; only WhatsApp may read it. */
class StickerContentProvider : ContentProvider() {

    private lateinit var matcher: UriMatcher

    override fun onCreate(): Boolean {
        val auth = StickerPacks.authority(context!!)
        matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(auth, "metadata", METADATA)
            addURI(auth, "metadata/*", METADATA_SINGLE)
            addURI(auth, "stickers/*", STICKERS)
            addURI(auth, "stickers_asset/*/*", ASSET)
        }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        val ctx = context ?: return null
        return when (matcher.match(uri)) {
            METADATA -> metadata(ctx, StickerPacks.all(ctx).filter { StickerPacks.entries(ctx, it).size >= StickerPacks.MIN })
            METADATA_SINGLE -> metadata(ctx, listOfNotNull(StickerPacks.pack(ctx, uri.lastPathSegment ?: "")))
            STICKERS -> {
                val pack = StickerPacks.pack(ctx, uri.lastPathSegment ?: "") ?: return null
                StickerPacks.markRead(ctx, pack, callingPackage)
                MatrixCursor(arrayOf("sticker_file_name", "sticker_emoji", "sticker_accessibility_text")).apply {
                    StickerPacks.entries(ctx, pack).forEach { addRow(arrayOf("${it.sha}.webp", StickerPacks.safeEmoji(it.emoji), "Sticker")) }
                    setNotificationUri(ctx.contentResolver, uri)
                }
            }
            else -> null
        }
    }

    private fun metadata(ctx: Context, packs: List<StickerPacks.Pack>) = MatrixCursor(
        arrayOf(
            "sticker_pack_identifier", "sticker_pack_name", "sticker_pack_publisher", "sticker_pack_icon",
            "android_play_store_link", "ios_app_download_link", "sticker_pack_publisher_email",
            "sticker_pack_publisher_website", "sticker_pack_privacy_policy_website", "sticker_pack_license_agreement_website",
            "image_data_version", "whatsapp_will_not_cache_stickers", "animated_sticker_pack",
        ),
    ).apply {
        packs.forEach { p ->
            StickerPacks.ensureTrayForServing(ctx, p)
            addRow(arrayOf<Any>(
                p.id, p.title, "Sticker Search", StickerPacks.trayName(p), "", "", "", "", "", "",
                StickerPacks.version(ctx, p).toString(), 1, if (p.animated) 1 else 0,
            ))
        }
        setNotificationUri(ctx.contentResolver, Uri.parse("content://${StickerPacks.authority(ctx)}/metadata"))
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        val ctx = context ?: return null
        if (matcher.match(uri) != ASSET) return null
        val seg = uri.pathSegments
        val f = StickerPacks.file(ctx, seg[seg.size - 2], seg.last()) ?: return null
        return AssetFileDescriptor(ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY), 0, f.length())
    }

    override fun getType(uri: Uri): String? {
        val auth = StickerPacks.authority(context!!)
        return when (matcher.match(uri)) {
            METADATA -> "vnd.android.cursor.dir/vnd.$auth.metadata"
            METADATA_SINGLE -> "vnd.android.cursor.item/vnd.$auth.metadata"
            STICKERS -> "vnd.android.cursor.dir/vnd.$auth.stickers"
            ASSET -> if (uri.lastPathSegment?.endsWith(".png") == true) "image/png" else "image/webp"
            else -> null
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int =
        throw UnsupportedOperationException()

    private companion object {
        const val METADATA = 1
        const val METADATA_SINGLE = 2
        const val STICKERS = 3
        const val ASSET = 4
    }
}
