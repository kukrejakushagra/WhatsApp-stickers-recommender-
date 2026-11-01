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
import com.kukreja.stickersearch.data.ScanRunEntity
import com.kukreja.stickersearch.data.StickerDatabase
import com.kukreja.stickersearch.data.StickerFrameEntity
import com.kukreja.stickersearch.data.StickerRow
import com.kukreja.stickersearch.data.StickerStatus
import com.kukreja.stickersearch.diagnostics.EventLog
import com.kukreja.stickersearch.diagnostics.ReportBuilder
import com.kukreja.stickersearch.diagnostics.ScanEngine
import com.kukreja.stickersearch.diagnostics.ScanOutcome
import com.kukreja.stickersearch.discovery.FileSystemSource
import com.kukreja.stickersearch.discovery.SafTreeSource
import com.kukreja.stickersearch.discovery.SharedInSource
import com.kukreja.stickersearch.index.IndexWorker
import com.kukreja.stickersearch.media.Thumbnailer
import com.kukreja.stickersearch.media.WasArchive
import com.kukreja.stickersearch.model.StickerFormat
import com.kukreja.stickersearch.share.SendTarget
import com.kukreja.stickersearch.share.StickerSharer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
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

data class IndexState(
    val running: Boolean = false,
    val phase: String? = null,
    val done: Int = 0,
    val total: Int = 0,
    val error: String? = null,
)

data class UiState(
    val permissions: Permissions,
    val stickers: List<StickerRow> = emptyList(),
    val statusCounts: Map<StickerStatus, Int> = emptyMap(),
    val index: IndexState = IndexState(),
    val lastRun: ScanRunEntity? = null,
    val loaded: Boolean = false,
    /** Optional diagnostic scan (Report tab), separate from the index. */
    val diagnosticRunning: Boolean = false,
    val diagnostic: ScanOutcome? = null,
    val message: String? = null,
    val eventCount: Int = 0,
) {
    val indexedCount get() = statusCounts.filterKeys { it != StickerStatus.MISSING }.values.sum()
    val failedCount get() = statusCounts[StickerStatus.FAILED] ?: 0
    val reviewCount get() = statusCounts[StickerStatus.NEEDS_REVIEW] ?: 0
    val missingCount get() = statusCounts[StickerStatus.MISSING] ?: 0
}

/** Detail sheet data loaded on demand. */
data class StickerDetails(val frames: List<StickerFrameEntity>, val paths: List<String>)

fun StickerRow.formatEnum(): StickerFormat = runCatching { StickerFormat.valueOf(format) }.getOrDefault(StickerFormat.UNKNOWN)

fun StickerRow.sendTarget(): SendTarget? = primaryUri?.let {
    SendTarget(sha256, formatEnum(), isAnimated, width, height, sizeBytes, it)
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Context get() = getApplication()
    private val prefs = app.getSharedPreferences(IndexWorker.PREFS, Context.MODE_PRIVATE)
    private val events = EventLog(File(app.filesDir, "events.jsonl"))
    private val importDir = File(app.filesDir, "imported")
    private val db = StickerDatabase.get(app)
    private val diagnosticEngine = ScanEngine(app, Thumbnailer(File(app.cacheDir, "diag-thumbs")))
    val sharer = StickerSharer(app, events)

    private val _state = MutableStateFlow(UiState(readPermissions(), eventCount = events.all().length()))
    val state: StateFlow<UiState> = _state

    init {
        viewModelScope.launch { db.stickers().observeVisible().collect { list -> _state.update { it.copy(stickers = list, loaded = true) } } }
        viewModelScope.launch {
            db.stickers().observeStatusCounts().collect { c -> _state.update { it.copy(statusCounts = c.associate { x -> x.status to x.n }) } }
        }
        viewModelScope.launch { db.runs().observeLast().collect { r -> _state.update { it.copy(lastRun = r) } } }
        viewModelScope.launch {
            var wasRunning = false
            IndexWorker.observe(ctx).collect { p ->
                _state.update { it.copy(index = IndexState(p.running, p.phase, p.done, p.total, p.error)) }
                if (wasRunning && !p.running) {
                    val run = withContext(Dispatchers.IO) { db.runs().recent(1).firstOrNull() }
                    toast(
                        when {
                            p.error != null -> "Scan stopped: ${p.error}"
                            run == null -> "Scan finished"
                            run.newStickers == 0 && run.missing == 0 -> "Scan finished, nothing new"
                            else -> "Scan finished, %,d new · %,d missing".format(run.newStickers, run.missing)
                        },
                    )
                }
                wasRunning = p.running
            }
        }
    }

    fun refreshPermissions() = _state.update { it.copy(permissions = readPermissions(), eventCount = events.all().length()) }

    private fun readPermissions(): Permissions {
        val tree = prefs.getString(IndexWorker.KEY_TREE, null)?.let(Uri::parse)
        val held = tree != null && ctx.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }
        return Permissions(tree, held, Environment.isExternalStorageManager())
    }

    fun onTreePicked(uri: Uri?) {
        if (uri == null) return
        ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        prefs.edit().putString(IndexWorker.KEY_TREE, uri.toString()).apply()
        events.add("saf_tree_picked", mapOf("treeDocumentId" to android.provider.DocumentsContract.getTreeDocumentId(uri)))
        refreshPermissions()
    }

    /** Incremental index via WorkManager: survives leaving the screen; only new or changed files are processed. */
    fun scan(retryFailed: Boolean = false) {
        refreshPermissions()
        IndexWorker.enqueue(ctx, trigger = if (retryFailed) "retry" else "manual", retryFailed = retryFailed)
    }

    suspend fun details(id: Long): StickerDetails = withContext(Dispatchers.IO) {
        StickerDetails(db.frames().forSticker(id), db.files().pathsFor(id))
    }

    /** The original M1 diagnostic: walks every source in memory, for the exported report. */
    fun runDiagnostic() {
        if (_state.value.diagnosticRunning) return
        val p = readPermissions()
        _state.update { it.copy(diagnosticRunning = true) }
        viewModelScope.launch {
            val outcome = runCatching {
                diagnosticEngine.run(listOf(SafTreeSource(ctx, p.safTree), FileSystemSource(), SharedInSource(importDir))) {}
            }
            _state.update {
                it.copy(
                    diagnosticRunning = false, diagnostic = outcome.getOrNull() ?: it.diagnostic,
                    message = outcome.exceptionOrNull()?.let { e -> "Diagnostic stopped: ${e.message}" } ?: "Diagnostic finished",
                )
            }
        }
    }

    suspend fun reportJson(): String = withContext(Dispatchers.IO) {
        val root = ReportBuilder.build(ctx, _state.value.diagnostic, readPermissions().asMap(), events.all())
        root.put("index", indexStats())
        root.toString(2)
    }

    private suspend fun indexStats(): JSONObject {
        val o = JSONObject()
        o.put("stickersByStatus", JSONObject(db.stickers().statusCounts().associate { it.status.name to it.n } as Map<*, *>))
        o.put("filesByStatus", JSONObject(db.files().statusCounts().associate { it.status.name to it.n } as Map<*, *>))
        o.put("animated", db.stickers().animatedCount())
        o.put("keyframes", db.frames().count())
        o.put("lottieSamples", JSONArray().apply {
            db.stickers().lottieSamples(5).forEach {
                val bytes = db.stickers().liveUri(it.id)?.let { u ->
                    runCatching { ctx.contentResolver.openInputStream(Uri.parse(u))?.use { s -> s.readBytes() } }.getOrNull()
                }
                put(JSONObject().put("status", it.status.name).put("frames", it.frameCount).put("attempts", it.attempts)
                    .put("bytes", bytes?.size ?: JSONObject.NULL)
                    .put("layout", bytes?.let { b -> WasArchive.open(b, ctx.cacheDir).describe() } ?: JSONObject.NULL)
                    .put("error", it.statusError ?: JSONObject.NULL))
            }
        })
        o.put("stickerProblems", JSONArray().apply {
            db.stickers().problems(20).forEach {
                put(JSONObject().put("status", it.status.name).put("format", it.format).put("sha", it.sha256.take(12))
                    .put("error", it.statusError ?: JSONObject.NULL))
            }
        })
        o.put("fileFailures", JSONArray(db.files().failureSamples(20)))
        o.put("runs", JSONArray().apply {
            db.runs().recent(5).forEach { r ->
                put(JSONObject().put("trigger", r.trigger).put("seconds", (r.finishedAt - r.startedAt) / 1000)
                    .put("filesSeen", r.filesSeen).put("processed", r.filesProcessed).put("new", r.newStickers)
                    .put("missing", r.missing).put("failed", r.failed).put("error", r.error ?: JSONObject.NULL))
            }
        })
        return o
    }

    fun copyReport() {
        viewModelScope.launch {
            val json = reportJson()
            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Sticker report", json))
            toast("Report copied")
        }
    }

    fun shareReport(activity: Context) {
        viewModelScope.launch {
            val json = reportJson()
            val f = withContext(Dispatchers.IO) {
                File(ctx.cacheDir, "reports").apply { mkdirs() }.let { dir ->
                    File(dir, "sticker-report.json").apply { writeText(json) }
                }
            }
            sharer.shareText(activity, f, "application/json")
        }
    }

    fun send(activity: Context, sticker: StickerRow, mode: StickerSharer.Mode) {
        val target = sticker.sendTarget() ?: return toast("This sticker's file is missing. Run a scan.")
        viewModelScope.launch {
            val intent = withContext(Dispatchers.IO) { sharer.prepare(target, mode) }
            intent.onSuccess {
                try { activity.startActivity(it) } catch (e: ActivityNotFoundException) {
                    toast("WhatsApp didn't open. Check it is installed, then try again.")
                }
            }.onFailure { toast(it.message ?: "Couldn't prepare the sticker. Try again.") }
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
            db.clearAllTables()
            events.clear()
            listOf("imported", "thumbs", "frames").forEach { File(ctx.filesDir, it).deleteRecursively() }
            ctx.cacheDir.deleteRecursively()
            _state.update { it.copy(diagnostic = null, eventCount = 0, message = "Index deleted") }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private fun toast(msg: String) = _state.update { it.copy(message = msg) }
}
