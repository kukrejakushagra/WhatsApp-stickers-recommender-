package app.stickersearch.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import app.stickersearch.diagnostics.EventLog
import app.stickersearch.media.FormatSniffer
import app.stickersearch.media.Hasher
import app.stickersearch.model.StickerFormat
import java.io.File

/** What the sharer needs to know about a sticker. */
data class SendTarget(
    val sha256: String,
    val format: StickerFormat,
    val isAnimated: Boolean,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val uri: String,
)

/**
 * Sending experiments (M5 preview). The app only opens WhatsApp with the file attached;
 * the user always picks the chat and taps Send themselves.
 */
class StickerSharer(private val context: Context, private val events: EventLog) {

    private val authority = "${context.packageName}.files"

    enum class Mode { WHATSAPP_DIRECT, SYSTEM_SHEET }

    /** Stages the file (call off the main thread) and returns the intent to start, or an error. */
    fun prepare(sticker: SendTarget, mode: Mode): Result<Intent> {
        val file = stageCopy(sticker) ?: return Result.failure(IllegalStateException("Could not read the sticker file"))
        val uri = FileProvider.getUriForFile(context, authority, file)
        val mime = mimeFor(sticker.format)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("sticker", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        events.add("send_test", mapOf(
            "mode" to mode.name, "mime" to mime, "format" to sticker.format.name,
            "animated" to sticker.isAnimated, "sha256" to sticker.sha256.take(12),
            "width" to sticker.width, "height" to sticker.height, "bytes" to sticker.sizeBytes,
        ))
        return Result.success(when (mode) {
            Mode.WHATSAPP_DIRECT -> send.setPackage(app.stickersearch.share.StickerPacks.target(context))
            Mode.SYSTEM_SHEET -> Intent.createChooser(send, "Send sticker")
        })
    }

    fun shareText(activity: Context, file: File, mime: String) {
        val uri = FileProvider.getUriForFile(context, authority, file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(intent, "Export diagnostics report"))
    }

    /** Copies the original bytes (animation intact) into cache so WhatsApp gets a stable FileProvider URI. */
    private fun stageCopy(sticker: SendTarget): File? {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val out = File(dir, "${sticker.sha256.take(16)}.${extFor(sticker.format)}")
        if (out.exists() && out.length() > 0) return out
        return runCatching {
            context.contentResolver.openInputStream(Uri.parse(sticker.uri))?.use { input ->
                out.outputStream().use { input.copyTo(it) }
            } ?: return null
            out
        }.getOrNull()
    }

    /** Handles ACTION_SEND / SEND_MULTIPLE into the app: records what WhatsApp hands over. */
    fun importShared(intent: Intent, importDir: File): Int {
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(app.stickersearch.compat.Compat.streamOf(intent))
            Intent.ACTION_SEND_MULTIPLE ->
                app.stickersearch.compat.Compat.streamsOf(intent)
            else -> emptyList()
        }
        importDir.mkdirs()
        var imported = 0
        for (uri in uris) {
            val name = displayName(uri)
            val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            val fmt = bytes?.let { FormatSniffer.sniff(it) }
            if (bytes != null && fmt != null) {
                val sha = Hasher.sha256(bytes)
                File(importDir, "$sha.${extFor(fmt.format)}").writeBytes(bytes)
                imported++
            }
            events.add("share_in", mapOf(
                "intentType" to intent.type, "resolvedMime" to context.contentResolver.getType(uri),
                "authority" to uri.authority, "displayName" to name, "bytes" to bytes?.size,
                "format" to fmt?.format?.name, "animated" to fmt?.isAnimated, "frames" to fmt?.frameCount,
                "width" to fmt?.width, "height" to fmt?.height, "readable" to (bytes != null),
            ))
        }
        if (uris.isEmpty()) events.add("share_in", mapOf("intentType" to intent.type, "note" to "no EXTRA_STREAM"))
        return imported
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()

    companion object {
        fun mimeFor(f: StickerFormat) = when (f) {
            StickerFormat.WEBP_STATIC, StickerFormat.WEBP_ANIMATED -> "image/webp"
            StickerFormat.GIF -> "image/gif"
            StickerFormat.PNG, StickerFormat.APNG -> "image/png"
            StickerFormat.JPEG -> "image/jpeg"
            else -> "application/octet-stream"
        }

        fun extFor(f: StickerFormat) = when (f) {
            StickerFormat.WEBP_STATIC, StickerFormat.WEBP_ANIMATED -> "webp"
            StickerFormat.GIF -> "gif"
            StickerFormat.PNG, StickerFormat.APNG -> "png"
            StickerFormat.JPEG -> "jpg"
            StickerFormat.LOTTIE_WAS -> "was"
            StickerFormat.LOTTIE_JSON -> "json"
            StickerFormat.UNKNOWN -> "bin"
        }
    }
}
