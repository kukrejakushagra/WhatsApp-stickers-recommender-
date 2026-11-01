package com.kukreja.stickersearch.ui

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kukreja.stickersearch.data.AiCounts
import com.kukreja.stickersearch.data.AnnotationRepository
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
import com.kukreja.stickersearch.index.AiWorker
import com.kukreja.stickersearch.index.IndexWorker
import com.kukreja.stickersearch.index.Sync
import com.kukreja.stickersearch.media.Thumbnailer
import com.kukreja.stickersearch.media.WasArchive
import com.kukreja.stickersearch.ml.Annotation
import com.kukreja.stickersearch.ml.Correction
import com.kukreja.stickersearch.ml.ModelKind
import com.kukreja.stickersearch.ml.ModelStore
import com.kukreja.stickersearch.model.StickerFormat
import com.kukreja.stickersearch.search.SearchEngine
import com.kukreja.stickersearch.share.SendTarget
import com.kukreja.stickersearch.share.StickerPacks
import com.kukreja.stickersearch.share.StickerSharer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

data class AiState(
    val running: Boolean = false,
    val phase: String? = null,
    val done: Int = 0,
    val total: Int = 0,
    val waitingForCharge: Boolean = false,
    val counts: AiCounts = AiCounts(0, 0, 0, 0),
    val gemmaError: String? = null,
    val gemmaBackend: String? = null,
    val lastAnnotationMs: Long = 0,
)

data class ModelsState(
    val present: Map<ModelKind, Long> = emptyMap(),
    val importing: String? = null,
    /** The row whose Import button started the current import, if any. */
    val importingKind: ModelKind? = null,
) {
    val readyCount get() = ModelKind.entries.count { has(it) }
    fun has(k: ModelKind) = (present[k] ?: 0L) > 0L
    val searchReady get() = has(ModelKind.E5) || has(ModelKind.SIGLIP_TEXT)
}

data class SearchState(
    val query: String = "",
    val results: List<StickerRow> = emptyList(),
    val matched: Map<Long, List<String>> = emptyMap(),
    val searching: Boolean = false,
    val millis: Long = 0,
    val searched: Boolean = false,
    val recent: List<String> = emptyList(),
    val recentlyUsed: List<StickerRow> = emptyList(),
)

data class Storage(val models: Long = 0, val previews: Long = 0, val database: Long = 0)

data class UiState(
    val permissions: Permissions,
    val stickers: List<StickerRow> = emptyList(),
    val statusCounts: Map<StickerStatus, Int> = emptyMap(),
    val index: IndexState = IndexState(),
    val lastRun: ScanRunEntity? = null,
    val loaded: Boolean = false,
    val ai: AiState = AiState(),
    val models: ModelsState = ModelsState(),
    val search: SearchState = SearchState(),
    val packsAdded: Map<StickerPacks.Pack, Boolean> = emptyMap(),
    val packCounts: Map<StickerPacks.Pack, Int> = emptyMap(),
    val batteryUnrestricted: Boolean = false,
    val storage: Storage = Storage(),
    /** Optional diagnostic scan (Settings), separate from the index. */
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
data class StickerDetails(
    val frames: List<StickerFrameEntity>,
    val paths: List<String>,
    val annotation: Annotation? = null,
    val model: Annotation? = null,
    val corrected: Boolean = false,
    val confidence: Float = 0f,
)

fun StickerRow.formatEnum(): StickerFormat = runCatching { StickerFormat.valueOf(format) }.getOrDefault(StickerFormat.UNKNOWN)

fun StickerRow.sendTarget(): SendTarget? = primaryUri?.let {
    SendTarget(sha256, formatEnum(), isAnimated, width, height, sizeBytes, it)
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Context get() = getApplication()
    private val prefs = app.getSharedPreferences(IndexWorker.PREFS, Context.MODE_PRIVATE)
    private val searchPrefs = app.getSharedPreferences("search", Context.MODE_PRIVATE)
    private val events = EventLog(File(app.filesDir, "events.jsonl"))
    private val importDir = File(app.filesDir, "imported")
    private val db = StickerDatabase.get(app)
    private val repo = AnnotationRepository(db)
    private val models = ModelStore(app)
    private val engine = SearchEngine(app)
    private val diagnosticEngine = ScanEngine(app, Thumbnailer(File(app.cacheDir, "diag-thumbs")))
    val sharer = StickerSharer(app, events)

    private val _state = MutableStateFlow(UiState(readPermissions(), eventCount = events.all().length()))
    val state: StateFlow<UiState> = _state
    private var searchJob: Job? = null

    init {
        Sync.schedule(app)
        refreshModels()
        _state.update { it.copy(search = it.search.copy(recent = recentSearches())) }
        viewModelScope.launch {
            db.stickers().observeVisible().collect { list ->
                _state.update { s ->
                    val byId = list.associateBy { it.id }
                    s.copy(
                        stickers = list, loaded = true,
                        search = s.search.copy(results = s.search.results.mapNotNull { byId[it.id] }),
                    )
                }
            }
        }
        viewModelScope.launch {
            db.stickers().observeStatusCounts().collect { c -> _state.update { it.copy(statusCounts = c.associate { x -> x.status to x.n }) } }
        }
        viewModelScope.launch { db.runs().observeLast().collect { r -> _state.update { it.copy(lastRun = r) } } }
        viewModelScope.launch { db.ai().observeCounts().collect { c -> _state.update { it.copy(ai = it.ai.copy(counts = c)) } } }
        viewModelScope.launch {
            db.ai().observeRecentlyUsed(12).collect { ids ->
                _state.update { s ->
                    val byId = s.stickers.associateBy { it.id }
                    s.copy(search = s.search.copy(recentlyUsed = ids.mapNotNull { byId[it] }))
                }
            }
        }
        // Rebuild the search index when annotations or vectors change (debounced).
        viewModelScope.launch(Dispatchers.Default) {
            db.ai().observeVersion().collect { v ->
                delay(1500)
                engine.refresh(v)
            }
        }
        viewModelScope.launch(Dispatchers.Default) { engine.warmUp() }
        viewModelScope.launch {
            AiWorker.observe(ctx).collect { p ->
                val st = AiWorker.status(ctx)
                _state.update {
                    it.copy(ai = it.ai.copy(
                        running = p.running, phase = p.phase, done = p.done, total = p.total, waitingForCharge = p.waitingForCharge,
                        gemmaError = st.getString(AiWorker.KEY_GEMMA_ERROR, null),
                        gemmaBackend = st.getString(AiWorker.KEY_GEMMA_BACKEND, null),
                        lastAnnotationMs = st.getLong(AiWorker.KEY_LAST_ANNOTATION_MS, 0),
                    ))
                }
            }
        }
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

    // ---------------- permissions & scanning ----------------

    fun refreshPermissions() {
        _state.update { it.copy(permissions = readPermissions(), eventCount = events.all().length(), batteryUnrestricted = batteryUnrestricted()) }
        refreshModels()
        viewModelScope.launch(Dispatchers.IO) {
            val added = StickerPacks.Pack.entries.associateWith { StickerPacks.isAdded(ctx, it) }
            val counts = StickerPacks.Pack.entries.associateWith { StickerPacks.entries(ctx, it).size }
            _state.update { it.copy(packsAdded = added, packCounts = counts, storage = storage()) }
        }
    }

    private fun readPermissions(): Permissions {
        val tree = prefs.getString(IndexWorker.KEY_TREE, null)?.let(Uri::parse)
        val held = tree != null && ctx.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }
        return Permissions(tree, held, Environment.isExternalStorageManager())
    }

    private fun batteryUnrestricted() = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    fun onTreePicked(uri: Uri?) {
        if (uri == null) return
        ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        prefs.edit().putString(IndexWorker.KEY_TREE, uri.toString()).apply()
        events.add("saf_tree_picked", mapOf("treeDocumentId" to android.provider.DocumentsContract.getTreeDocumentId(uri)))
        refreshPermissions()
    }

    fun scan(retryFailed: Boolean = false) {
        refreshPermissions()
        IndexWorker.enqueue(ctx, trigger = if (retryFailed) "retry" else "manual", retryFailed = retryFailed)
    }

    suspend fun details(id: Long): StickerDetails = withContext(Dispatchers.IO) {
        val s = db.stickers().byId(id)
        StickerDetails(
            frames = db.frames().forSticker(id), paths = db.files().pathsFor(id),
            annotation = repo.effective(id), model = repo.model(id), corrected = db.ai().correction(id) != null,
            confidence = s?.confidence ?: 0f,
        )
    }

    // ---------------- models & AI ----------------

    private fun refreshModels() {
        _state.update { s -> s.copy(models = s.models.copy(present = ModelKind.entries.associateWith { models.sizeBytes(it) })) }
    }

    /** [expected]: the row the user imported from; the file is still identified by its contents. */
    fun importModels(uris: List<Uri>, expected: ModelKind? = null) {
        if (uris.isEmpty()) return
        _state.update { it.copy(models = it.models.copy(importing = "Starting", importingKind = expected)) }
        viewModelScope.launch(Dispatchers.IO) {
            val results = uris.map { uri ->
                models.import(uri) { copied, total ->
                    val label = if (total > 0) "Importing, %d%%".format(copied * 100 / total) else "Importing, %,d MB".format(copied shr 20)
                    _state.update { it.copy(models = it.models.copy(importing = label)) }
                }
            }
            _state.update { it.copy(models = it.models.copy(importing = null, importingKind = null)) }
            refreshModels()
            engine.close()
            engine.warmUp()
            val ok = results.mapNotNull { it.kind }
            val bad = results.filter { it.kind == null }
            val left = ModelKind.entries.count { !models.has(it) }
            val leftText = if (left == 0) "All 4 models are ready." else "$left of 4 still to import."
            toast(
                when {
                    expected != null && ok.size == 1 && ok[0] != expected ->
                        "That file is ${ok[0].title}, not ${expected.title}, so it was imported there. $leftText"
                    bad.isEmpty() -> (if (ok.size == 1) "Imported ${ok[0].title}. " else "Imported ${ok.size} models. ") + leftText
                    ok.isEmpty() -> "${bad.first().name}: ${bad.first().error}"
                    else -> "Imported ${ok.size}; ${bad.size} not recognised (${bad.first().name})"
                },
            )
            if (ok.isNotEmpty()) AiWorker.enqueue(ctx, manual = false)
        }
    }

    fun deleteModel(kind: ModelKind) {
        viewModelScope.launch(Dispatchers.IO) {
            if (kind == ModelKind.E5 || kind == ModelKind.SIGLIP_TEXT) engine.close()
            models.delete(kind)
            refreshModels()
            _state.update { it.copy(storage = storage()) }
            toast("${kind.title} removed")
        }
    }

    /** Runs all AI passes now, including annotation on battery (the user asked). */
    fun runAiNow() = AiWorker.enqueue(ctx, manual = true)

    // ---------------- search ----------------

    fun onQueryChange(q: String) {
        _state.update { it.copy(search = it.search.copy(query = q)) }
        searchJob?.cancel()
        if (q.isBlank()) {
            _state.update { it.copy(search = it.search.copy(results = emptyList(), searched = false, searching = false)) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(350)
            runSearch(q, remember = false)
        }
    }

    fun submitSearch() {
        val q = _state.value.search.query
        if (q.isBlank()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch { runSearch(q, remember = true) }
    }

    fun useRecent(q: String) {
        _state.update { it.copy(search = it.search.copy(query = q)) }
        submitSearch()
    }

    private suspend fun runSearch(q: String, remember: Boolean) {
        _state.update { it.copy(search = it.search.copy(searching = true)) }
        val r = withContext(Dispatchers.Default) { engine.search(q) }
        val byId = _state.value.stickers.associateBy { it.id }
        _state.update {
            it.copy(search = it.search.copy(
                results = r.hits.mapNotNull { h -> byId[h.id] }, matched = r.hits.associate { h -> h.id to h.matched },
                searching = false, millis = r.millis, searched = true,
            ))
        }
        if (remember) rememberSearch(q)
    }

    private fun recentSearches(): List<String> = runCatching {
        JSONArray(searchPrefs.getString("recent", "[]")).let { a -> (0 until a.length()).map { a.getString(it) } }
    }.getOrDefault(emptyList())

    private fun rememberSearch(q: String) {
        val list = (listOf(q.trim()) + recentSearches().filter { !it.equals(q.trim(), true) }).take(8)
        searchPrefs.edit().putString("recent", JSONArray(list).toString()).apply()
        _state.update { it.copy(search = it.search.copy(recent = list)) }
    }

    fun clearRecentSearches() {
        searchPrefs.edit().remove("recent").apply()
        _state.update { it.copy(search = it.search.copy(recent = emptyList())) }
    }

    // ---------------- corrections ----------------

    fun saveCorrection(id: Long, c: Correction) {
        viewModelScope.launch(Dispatchers.IO) {
            repo.saveCorrection(id, c)
            AiWorker.enqueue(ctx, manual = false) // rebuilds the text vector from the corrected text
            toast("Changes saved. They're kept when stickers are re-analysed.")
        }
    }

    fun resetCorrection(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repo.clearCorrection(id)
            AiWorker.enqueue(ctx, manual = false)
            toast("Back to the AI description")
        }
    }

    // ---------------- sending ----------------

    /** Puts the sticker first in the Sticker Search pack and opens WhatsApp; the user sends it there. */
    fun sendViaWhatsApp(activity: Context, s: StickerRow) {
        viewModelScope.launch {
            val uri = s.primaryUri ?: return@launch toast("This sticker's file is missing. Run a scan.")
            if (s.formatEnum() == StickerFormat.LOTTIE_WAS) {
                return@launch toast("WhatsApp only accepts WebP in third-party packs, so Lottie stickers can't be sent this way.")
            }
            val result = withContext(Dispatchers.IO) {
                val bytes = ctx.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytes() }
                    ?: return@withContext StickerPacks.Prepared.NotEligible("The sticker file couldn't be read.")
                val emoji = repo.effective(s.id)?.emoji.orEmpty()
                val r = StickerPacks.add(ctx, s.sha256, bytes, s.isAnimated, emoji)
                if (r is StickerPacks.Prepared.Ok) {
                    db.ai().markUsed(s.id, System.currentTimeMillis())
                    if (StickerPacks.entries(ctx, r.pack).size < StickerPacks.MIN) StickerPacks.fillIfShort(ctx, r.pack, fillers(r.pack, s.id))
                }
                r
            }
            when (result) {
                is StickerPacks.Prepared.NotEligible -> toast(result.reason)
                is StickerPacks.Prepared.Ok -> {
                    val added = withContext(Dispatchers.IO) { StickerPacks.isAdded(ctx, result.pack) }
                    if (!added) {
                        try {
                            activity.startActivity(StickerPacks.addToWhatsAppIntent(ctx, result.pack))
                            toast("Tap Add in WhatsApp. After that, the sticker is first in the ${result.pack.title} pack.")
                        } catch (e: ActivityNotFoundException) {
                            toast("WhatsApp didn't open. Check it is installed, then try again.")
                        }
                    } else if (StickerPacks.openWhatsApp(activity)) {
                        toast("Open a chat and the sticker picker; it's first in ${result.pack.title}.")
                    } else {
                        toast("WhatsApp didn't open. Check it is installed, then try again.")
                    }
                    refreshPermissions()
                }
            }
        }
    }

    /** Recently used (then newest) stickers of the same kind, to reach WhatsApp's 3-sticker minimum. */
    private suspend fun fillers(pack: StickerPacks.Pack, exclude: Long): List<Triple<String, ByteArray, String>> {
        val pool = _state.value.search.recentlyUsed + _state.value.stickers
        val out = mutableListOf<Triple<String, ByteArray, String>>()
        for (r in pool.filter { it.id != exclude && it.isAnimated == pack.animated && it.formatEnum() != StickerFormat.LOTTIE_WAS }
            .distinctBy { it.id }.take(12)) {
            val bytes = r.primaryUri?.let { u -> runCatching { ctx.contentResolver.openInputStream(Uri.parse(u))?.use { it.readBytes() } }.getOrNull() }
            if (bytes != null) out += Triple(r.sha256, bytes, repo.effective(r.id)?.emoji.orEmpty())
        }
        return out
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
            withContext(Dispatchers.IO) { db.ai().markUsed(sticker.id, System.currentTimeMillis()) }
        }
    }

    // ---------------- battery, storage, data ----------------

    fun batteryIntent(): Intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${ctx.packageName}".toUri())

    private fun dirSize(f: File): Long = if (!f.exists()) 0 else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun storage() = Storage(
        models = models.totalBytes(),
        previews = listOf("thumbs", "frames", "packs").sumOf { dirSize(File(ctx.filesDir, it)) },
        database = ctx.getDatabasePath("stickers.db").let { f -> f.length() + File(f.path + "-wal").length() },
    )

    fun clearAll() {
        viewModelScope.launch(Dispatchers.IO) {
            db.clearAllTables()
            events.clear()
            listOf("imported", "thumbs", "frames", "packs").forEach { File(ctx.filesDir, it).deleteRecursively() }
            ctx.getSharedPreferences("sticker_packs", Context.MODE_PRIVATE).edit().clear().apply()
            ctx.cacheDir.deleteRecursively()
            _state.update { it.copy(diagnostic = null, eventCount = 0, message = "Index deleted. Your models are kept.", storage = storage()) }
        }
    }

    // ---------------- diagnostics & report ----------------

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
        root.put("ai", aiStats())
        root.toString(2)
    }

    private suspend fun aiStats(): JSONObject {
        val s = _state.value
        val st = AiWorker.status(ctx)
        return JSONObject()
            .put("models", JSONObject(ModelKind.entries.associate { it.name to models.sizeBytes(it) } as Map<*, *>))
            .put("visual", s.ai.counts.visual).put("annotated", s.ai.counts.annotated).put("embedded", s.ai.counts.embedded)
            .put("total", s.ai.counts.total)
            .put("gemmaBackend", st.getString(AiWorker.KEY_GEMMA_BACKEND, null) ?: JSONObject.NULL)
            .put("gemmaError", st.getString(AiWorker.KEY_GEMMA_ERROR, null) ?: JSONObject.NULL)
            .put("lastAnnotationMs", st.getLong(AiWorker.KEY_LAST_ANNOTATION_MS, 0))
            .put("lastSearchMs", s.search.millis)
            .put("searchIndexSize", engine.size)
            .put("packs", JSONObject(StickerPacks.Pack.entries.associate {
                it.id to "${StickerPacks.entries(ctx, it).size} stickers, added=${StickerPacks.isAdded(ctx, it)}"
            } as Map<*, *>))
            .put("batteryUnrestricted", batteryUnrestricted())
    }

    private suspend fun indexStats(): JSONObject {
        val o = JSONObject()
        o.put("stickersByStatus", JSONObject(db.stickers().statusCounts().associate { it.status.name to it.n } as Map<*, *>))
        o.put("filesByStatus", JSONObject(db.files().statusCounts().associate { it.status.name to it.n } as Map<*, *>))
        o.put("animated", db.stickers().animatedCount())
        o.put("keyframes", db.frames().count())
        o.put("lottieSamples", JSONArray().apply {
            db.stickers().lottieSamples(3).forEach {
                val bytes = db.stickers().liveUri(it.id)?.let { u ->
                    runCatching { ctx.contentResolver.openInputStream(Uri.parse(u))?.use { s -> s.readBytes() } }.getOrNull()
                }
                put(JSONObject().put("status", it.status.name).put("frames", it.frameCount).put("attempts", it.attempts)
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
                File(ctx.cacheDir, "reports").apply { mkdirs() }.let { dir -> File(dir, "sticker-report.json").apply { writeText(json) } }
            }
            sharer.shareText(activity, f, "application/json")
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

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private fun toast(msg: String) = _state.update { it.copy(message = msg) }

    override fun onCleared() {
        engine.close()
    }
}
