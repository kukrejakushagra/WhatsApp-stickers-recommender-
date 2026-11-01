package com.kukreja.stickersearch.ui

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kukreja.stickersearch.diagnostics.EventLog
import com.kukreja.stickersearch.diagnostics.ReportBuilder
import com.kukreja.stickersearch.diagnostics.ScanEngine
import com.kukreja.stickersearch.diagnostics.ScanOutcome
import com.kukreja.stickersearch.diagnostics.ScanProgress
import com.kukreja.stickersearch.discovery.FileSystemSource
import com.kukreja.stickersearch.discovery.SafTreeSource
import com.kukreja.stickersearch.discovery.SharedInSource
import com.kukreja.stickersearch.media.Thumbnailer
import com.kukreja.stickersearch.model.UniqueSticker
import com.kukreja.stickersearch.share.StickerSharer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class Permissions(
    val safTree: Uri?,
    val safHeld: Boolean,
    val allFiles: Boolean,
) {
    val safTreeId: String? get() = safTree?.let { android.provider.DocumentsContract.getTreeDocumentId(it) }

    fun asMap() = mapOf(
        "safTreePicked" to (safTree != null), "safPermissionHeld" to safHeld,
        "manageExternalStorage" to allFiles,
    )
}

data class UiState(
    val permissions: Permissions,
    val scanning: Boolean = false,
    val progress: ScanProgress? = null,
    val outcome: ScanOutcome? = null,
    val message: String? = null,
    val eventCount: Int = 0,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Context get() = getApplication()
    private val prefs = app.getSharedPreferences("m1", Context.MODE_PRIVATE)
    private val events = EventLog(File(app.filesDir, "events.jsonl"))
    private val importDir = File(app.filesDir, "imported")
    private val thumbnailer = Thumbnailer(File(app.filesDir, "thumbs"))
    private val engine = ScanEngine(app, thumbnailer)
    val sharer = StickerSharer(app, events)

    private val _state = MutableStateFlow(UiState(readPermissions(), eventCount = events.all().length()))
    val state: StateFlow<UiState> = _state

    fun refreshPermissions() = _state.update { it.copy(permissions = readPermissions(), eventCount = events.all().length()) }

    private fun readPermissions(): Permissions {
        val tree = prefs.getString(KEY_TREE, null)?.let(Uri::parse)
        val held = tree != null && ctx.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }
        return Permissions(tree, held, Environment.isExternalStorageManager())
    }

    fun onTreePicked(uri: Uri?) {
        if (uri == null) return
        ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        prefs.edit().putString(KEY_TREE, uri.toString()).apply()
        events.add("saf_tree_picked", mapOf("treeDocumentId" to android.provider.DocumentsContract.getTreeDocumentId(uri)))
        refreshPermissions()
    }

    fun scan() {
        if (_state.value.scanning) return
        val p = readPermissions()
        _state.update { it.copy(permissions = p, scanning = true, message = null) }
        viewModelScope.launch {
            val sources = listOf(
                SafTreeSource(ctx, p.safTree),
                FileSystemSource(),
                SharedInSource(importDir),
            )
            val outcome = runCatching {
                engine.run(sources) { prog -> _state.update { s -> s.copy(progress = prog) } }
            }
            _state.update {
                it.copy(
                    scanning = false, progress = null,
                    outcome = outcome.getOrNull() ?: it.outcome,
                    message = outcome.exceptionOrNull()?.let { e -> "Scan stopped: ${e.message}. Run it again." }
                        ?: "Scan finished, %,d unique stickers".format(outcome.getOrNull()?.summary?.uniqueCount ?: 0),
                )
            }
        }
    }

    fun reportJson(): String =
        ReportBuilder.build(ctx, _state.value.outcome, readPermissions().asMap(), events.all()).toString(2)

    fun copyReport() {
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("Sticker diagnostics", reportJson()))
        toast("Report copied")
    }

    fun shareReport(activity: Context) {
        viewModelScope.launch {
            val f = withContext(Dispatchers.IO) {
                File(ctx.cacheDir, "reports").apply { mkdirs() }.let { dir ->
                    File(dir, "sticker-diagnostics.json").apply { writeText(reportJson()) }
                }
            }
            sharer.shareText(activity, f, "application/json")
        }
    }

    fun send(activity: Context, sticker: UniqueSticker, mode: StickerSharer.Mode) {
        viewModelScope.launch {
            val intent = withContext(Dispatchers.IO) { sharer.prepare(sticker, mode) }
            intent.onSuccess {
                try { activity.startActivity(it) } catch (e: ActivityNotFoundException) {
                    toast("WhatsApp didn't open. Check it is installed, then try again.")
                }
            }.onFailure { toast(it.message ?: "Send failed") }
            refreshPermissions()
        }
    }

    fun handleShareIntent(intent: Intent?) {
        if (intent == null || (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE)) return
        viewModelScope.launch {
            val n = withContext(Dispatchers.IO) { sharer.importShared(intent, importDir) }
            toast(if (n == 1) "Imported 1 sticker. Run a scan to include it." else "Imported $n stickers. Run a scan to include them.")
            refreshPermissions()
        }
    }

    fun clearAll() {
        viewModelScope.launch(Dispatchers.IO) {
            events.clear()
            importDir.deleteRecursively()
            File(ctx.filesDir, "thumbs").deleteRecursively()
            ctx.cacheDir.deleteRecursively()
            File(ctx.filesDir, "thumbs").mkdirs()
            _state.update { it.copy(outcome = null, eventCount = 0, message = "Scan data deleted") }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private fun toast(msg: String) = _state.update { it.copy(message = msg) }

    companion object {
        private const val KEY_TREE = "saf_tree_uri"
    }
}
