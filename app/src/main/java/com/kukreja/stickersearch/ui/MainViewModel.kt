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

/** App colours: follow the phone, or always light / dark. Dark is the default. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

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
    /** Why the app last closed inside Gemma, if it did. */
    val lastCrash: String? = null,
    val lastCrashAt: Long = 0,
    /** Describing stopped after repeated crashes; Start now tries again. */
    val describePaused: Boolean = false,
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
    /** Graph neighbours of the top results, with why each is related. */
    val related: List<Pair<StickerRow, String>> = emptyList(),
)

/** "Help me reply" with images: attachments, progress and results. */
data class AssistState(
    val attachments: List<Uri> = emptyList(),
    val running: Boolean = false,
    val phase: String? = null,
    val reading: String? = null,
    val queries: List<String> = emptyList(),
    val suggestions: List<StickerRow> = emptyList(),
    val found: List<StickerRow> = emptyList(),
    val note: String? = null,
    val millis: Long = 0,
    val done: Boolean = false,
)

data class PackInfo(val pack: StickerPacks.Pack, val count: Int, val added: Boolean, val stickers: List<String> = emptyList())

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
    val assist: AssistState = AssistState(),
    /** One-shot: images arrived from another app, so Search should come to the front. */
    val openSearch: Boolean = false,
    val graph: Map<com.kukreja.stickersearch.data.EdgeType, Int> = emptyMap(),
    /** Every pack served to WhatsApp, with its size and whether WhatsApp has added it. */
    val packs: List<PackInfo> = emptyList(),
    /** Search results being picked for a new pack. */
    val selecting: Boolean = false,
    val selected: Set<Long> = emptySet(),
    val batteryUnrestricted: Boolean = false,
    val storage: Storage = Storage(),
    /** Optional diagnostic scan (Settings), separate from the index. */
    val diagnosticRunning: Boolean = false,
    val diagnostic: ScanOutcome? = null,
    val message: String? = null,
    val eventCount: Int = 0,
    val themeMode: ThemeMode = ThemeMode.DARK,
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
    /** The story this sticker belongs to, in order (includes itself). */
    val story: List<StickerRow> = emptyList(),
    val looks: List<StickerRow> = emptyList(),
    val topics: List<Pair<String, List<StickerRow>>> = emptyList(),
)

fun StickerRow.isLottieRow() = format == StickerFormat.LOTTIE_WAS.name

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
    private val assistant = com.kukreja.stickersearch.search.ImageAssist(app, engine)
    private val diagnosticEngine = ScanEngine(app, Thumbnailer(File(app.cacheDir, "diag-thumbs")))
    val sharer = StickerSharer(app, events)

    private val uiPrefs = app.getSharedPreferences("ui", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(UiState(readPermissions(), eventCount = events.all().length(), themeMode = savedThemeMode()))
    val state: StateFlow<UiState> = _state
    private var searchJob: Job? = null
    private val graphRepo = com.kukreja.stickersearch.graph.GraphRepository(app)
    @Volatile private var graph = com.kukreja.stickersearch.graph.StickerGraph(emptyList())

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
        // If the app died inside Gemma (describing or suggesting replies), record it now.
        viewModelScope.launch(Dispatchers.IO) {
            com.kukreja.stickersearch.ml.DescribeGuard.takeUnfinishedEmbed(ctx)?.let { id ->
                events.add("e5_crash", mapOf("sticker" to id))
            }
            com.kukreja.stickersearch.ml.DescribeGuard.takeUnfinished(ctx)?.let { u ->
                if (u.stickerId > 0) {
                    db.ai().annotationFailed(u.stickerId, "The app closed while describing this on the ${u.backend}: ${u.why}", System.currentTimeMillis())
                    db.ai().parkUnreadable(AiWorker.MAX_ATTEMPTS)
                }
                events.add("gemma_crash", mapOf("sticker" to u.stickerId, "backend" to u.backend, "why" to u.why))
                if (u.stickerId == -1L) toast("Suggesting replies closed the app (${u.why}). Gemma now uses safer settings; try again.")
            }
        }
        // The graph reloads whenever the AI worker rebuilds it.
        viewModelScope.launch(Dispatchers.IO) {
            db.graph().observeCounts().collect { c ->
                graph = graphRepo.load()
                _state.update { it.copy(graph = c.associate { x -> x.type to x.n }) }
            }
        }
        viewModelScope.launch {
            AiWorker.observe(ctx).collect { p ->
                val st = AiWorker.status(ctx)
                _state.update {
                    it.copy(ai = it.ai.copy(
                        running = p.running, phase = p.phase, done = p.done, total = p.total, waitingForCharge = p.waitingForCharge,
                        gemmaError = st.getString(AiWorker.KEY_GEMMA_ERROR, null),
                        gemmaBackend = st.getString(AiWorker.KEY_GEMMA_BACKEND, null),
                        lastAnnotationMs = st.getLong(AiWorker.KEY_LAST_ANNOTATION_MS, 0),
                        lastCrash = com.kukreja.stickersearch.ml.DescribeGuard.lastCrash(ctx),
                        lastCrashAt = com.kukreja.stickersearch.ml.DescribeGuard.lastCrashAt(ctx),
                        describePaused = com.kukreja.stickersearch.ml.DescribeGuard.paused(ctx),
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
        viewModelScope.launch(Dispatchers.IO) { refreshPacks(); _state.update { it.copy(storage = storage()) } }
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
        ).let { d ->
            val rel = graph.related(id)
            val byId = _state.value.stickers.associateBy { it.id }
            d.copy(
                story = rel.story.mapNotNull { byId[it] },
                looks = rel.looks.mapNotNull { byId[it] },
                topics = rel.topics.map { (label, ids) -> label to ids.mapNotNull { byId[it] } }.filter { it.second.isNotEmpty() },
            )
        }
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
    fun setThemeMode(mode: ThemeMode) {
        uiPrefs.edit().putString("theme", mode.name).apply()
        _state.update { it.copy(themeMode = mode) }
    }

    private fun savedThemeMode() =
        runCatching { ThemeMode.valueOf(uiPrefs.getString("theme", null) ?: ThemeMode.DARK.name) }.getOrDefault(ThemeMode.DARK)

    fun runAiNow() {
        com.kukreja.stickersearch.ml.DescribeGuard.resume(ctx)
        AiWorker.enqueue(ctx, manual = true)
    }

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
        // Graph-expanded retrieval: stickers linked to the best results (same story, look, or theme).
        val related = graph.expand(r.hits.map { it.id }, r.hits.map { it.id }.toSet()).mapNotNull { (id, why) -> byId[id]?.let { it to why } }
        _state.update {
            it.copy(search = it.search.copy(
                results = r.hits.mapNotNull { h -> byId[h.id] }, matched = r.hits.associate { h -> h.id to h.matched },
                related = related,
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

    // ---------------- help me reply (images) ----------------

    fun attach(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _state.update { s ->
            val list = (s.assist.attachments + uris).distinct().take(com.kukreja.stickersearch.ml.GemmaAnnotator.MAX_IMAGES)
            s.copy(assist = s.assist.copy(attachments = list, done = false))
        }
    }

    private fun deleteIfOurs(uri: Uri) {
        if (uri.scheme == "file") uri.path?.let { p -> if (p.startsWith(ctx.cacheDir.absolutePath)) File(p).delete() }
    }

    fun removeAttachment(uri: Uri) = _state.update { s ->
        deleteIfOurs(uri)
        val left = s.assist.attachments - uri
        s.copy(assist = if (left.isEmpty()) AssistState() else s.assist.copy(attachments = left, done = false))
    }

    fun clearAssist() = _state.update { s -> s.assist.attachments.forEach(::deleteIfOurs); s.copy(assist = AssistState()) }

    fun consumeOpenSearch() = _state.update { it.copy(openSearch = false) }

    /** Reads the attached images (and the typed text) and suggests reply stickers. */
    fun runAssist() {
        val st = _state.value
        if (st.assist.running) return
        val text = st.search.query
        val uris = st.assist.attachments
        if (uris.isEmpty() && text.isBlank()) return
        _state.update { it.copy(assist = it.assist.copy(running = true, phase = "Opening images", done = false, note = null)) }
        viewModelScope.launch(Dispatchers.Default) {
            val images = uris.mapNotNull { u ->
                runCatching {
                    val bytes = ctx.contentResolver.openInputStream(u)?.use { it.readBytes() } ?: return@runCatching null
                    val bmp = android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))) { d, info, _ ->
                        d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                        val w = info.size.width; val h = info.size.height
                        // Screenshots are read at up to 1080 px wide: enough for text, bounded memory.
                        if (w > 1080) d.setTargetSize(1080, (h * 1080f / w).toInt().coerceAtLeast(1))
                    }
                    bmp to bytes
                }.getOrNull()
            }
            val r = runCatching {
                assistant.run(images, text) { phase -> _state.update { it.copy(assist = it.assist.copy(phase = phase)) } }
            }
            images.forEach { it.first.recycle() }
            val byId = _state.value.stickers.associateBy { it.id }
            _state.update {
                val res = r.getOrNull()
                it.copy(assist = it.assist.copy(
                    running = false, phase = null, done = true,
                    reading = res?.plan?.reading?.takeIf { s -> s.isNotBlank() },
                    queries = res?.plan?.queries.orEmpty(),
                    suggestions = res?.suggestions.orEmpty().mapNotNull { h -> byId[h.id] },
                    found = res?.found.orEmpty().mapNotNull { (id, _) -> byId[id] },
                    millis = res?.millis ?: 0,
                    note = r.exceptionOrNull()?.let { e -> "Couldn't finish: ${e.message}. Try again." } ?: res?.note
                        ?: if (images.size < uris.size) "${uris.size - images.size} image(s) couldn't be opened." else null,
                ))
            }
        }
    }

    // ---------------- custom packs ----------------

    fun startSelecting(preselect: Long? = null) =
        _state.update { it.copy(selecting = true, selected = listOfNotNull(preselect).toSet()) }

    fun stopSelecting() = _state.update { it.copy(selecting = false, selected = emptySet()) }

    fun toggleSelected(id: Long) = _state.update { s ->
        val sel = if (id in s.selected) s.selected - id else s.selected + id
        if (sel.size > StickerPacks.MAX) {
            toast("A pack holds up to ${StickerPacks.MAX} stickers. Make another pack for more.")
            s
        } else s.copy(selected = sel)
    }

    /** Selects the top results, up to WhatsApp's 30-per-pack limit. */
    fun selectTop(ids: List<Long>) = _state.update { s ->
        s.copy(selecting = true, selected = (s.selected + ids).toList().take(StickerPacks.MAX).toSet())
    }

    private suspend fun packItems(rows: List<StickerRow>): List<Triple<String, ByteArray, String>> {
        val out = mutableListOf<Triple<String, ByteArray, String>>()
        for (r in rows) {
            val bytes = r.primaryUri?.let { u -> runCatching { ctx.contentResolver.openInputStream(Uri.parse(u))?.use { it.readBytes() } }.getOrNull() }
            if (bytes != null) out += Triple(r.sha256, bytes, repo.effective(r.id)?.emoji.orEmpty())
        }
        return out
    }

    /**
     * Makes WhatsApp-ready packs from the selected stickers (split into static and animated, as WhatsApp
     * requires) and opens WhatsApp's "Add" screen for each in turn.
     */
    fun createPacksFromSelection(name: String, askToAdd: (Intent) -> Unit) {
        val st = _state.value
        val byId = st.stickers.associateBy { it.id }
        val rows = st.selected.mapNotNull { byId[it] }
        if (rows.isEmpty()) return toast("Select some stickers first.")
        viewModelScope.launch(Dispatchers.IO) {
            val plan = com.kukreja.stickersearch.share.PackPlanner.plan(name,
                rows.map { com.kukreja.stickersearch.share.PackPlanner.Item(it.id, it.isAnimated, it.isLottieRow()) })
            val made = mutableListOf<StickerPacks.Pack>()
            val notes = plan.notes.toMutableList()
            for (pl in plan.packs) {
                val pack = StickerPacks.createPack(ctx, pl.title, pl.animated)
                val r = StickerPacks.addMany(ctx, pack, packItems(pl.ids.mapNotNull { byId[it] }))
                if (r.skipped.isNotEmpty()) notes += "${r.skipped.size} couldn't be added: ${r.skipped.first()}"
                if (StickerPacks.entries(ctx, pack).size >= StickerPacks.MIN) made += pack
                else { notes += "“${pack.title}” ended up with fewer than ${StickerPacks.MIN} usable stickers, so it wasn't created."; StickerPacks.deletePack(ctx, pack) }
            }
            refreshPacks()
            withContext(Dispatchers.Main) {
                stopSelecting()
                if (made.isEmpty()) return@withContext toast(notes.joinToString(" ").ifBlank { "No pack could be made from these stickers." })
                addQueue.clear(); addQueue.addAll(made.drop(1)); queuedAsk = askToAdd
                addPackToWhatsApp(made.first(), askToAdd)
                toast((listOf(if (made.size == 1) "Pack “${made[0].title}” made. Tap Add in WhatsApp."
                    else "${made.size} packs made. WhatsApp asks about each one.") + notes).joinToString(" "))
            }
        }
    }

    /** Adds one sticker to a pack of the user's (or to a new one named [newName]). */
    fun addToPack(row: StickerRow, pack: StickerPacks.Pack?, newName: String?) {
        if (row.isLottieRow()) return toast("WhatsApp packs only take WebP, so Lottie stickers can't be added.")
        viewModelScope.launch(Dispatchers.IO) {
            val target = pack ?: StickerPacks.createPack(ctx, newName ?: "My stickers", row.isAnimated)
            if (target.animated != row.isAnimated) {
                return@launch toast("“${target.title}” is for ${if (target.animated) "animated" else "static"} stickers; WhatsApp can't mix the two.")
            }
            val r = StickerPacks.addMany(ctx, target, packItems(listOf(row)))
            refreshPacks()
            val n = StickerPacks.entries(ctx, target).size
            toast(when {
                r.full > 0 -> "“${target.title}” is full (${StickerPacks.MAX} stickers)."
                r.skipped.isNotEmpty() -> r.skipped.first()
                r.added == 0 -> "Already in “${target.title}”."
                n < StickerPacks.MIN -> "Added to “${target.title}” ($n). Add ${StickerPacks.MIN - n} more before it can go into WhatsApp."
                else -> "Added to “${target.title}” ($n stickers)."
            })
        }
    }

    fun removeFromPack(pack: StickerPacks.Pack, sha: String) {
        viewModelScope.launch(Dispatchers.IO) { StickerPacks.remove(ctx, pack, sha); refreshPacks() }
    }

    fun deletePack(pack: StickerPacks.Pack) {
        viewModelScope.launch(Dispatchers.IO) {
            StickerPacks.deletePack(ctx, pack); refreshPacks()
            toast("“${pack.title}” deleted. If it was in WhatsApp, it disappears there too.")
        }
    }

    fun renamePack(pack: StickerPacks.Pack, title: String) {
        viewModelScope.launch(Dispatchers.IO) { StickerPacks.renamePack(ctx, pack, title); refreshPacks() }
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
    /**
     * [askToAdd] must launch WhatsApp's ENABLE_STICKER_PACK intent *for a result*: WhatsApp only shows its
     * "Add sticker pack" screen to a caller it can identify, and reports validation errors in the result.
     */
    fun sendViaWhatsApp(activity: Context, s: StickerRow, askToAdd: (Intent) -> Unit) {
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
                is StickerPacks.Prepared.NotEligible -> {
                    events.add("pack_send", mapOf("step" to "not_eligible", "reason" to result.reason, "format" to s.format))
                    toast(result.reason)
                }
                is StickerPacks.Prepared.Ok -> {
                    val added = withContext(Dispatchers.IO) { StickerPacks.isAdded(ctx, result.pack) }
                    val size = StickerPacks.entries(ctx, result.pack).size
                    val wl = withContext(Dispatchers.IO) { StickerPacks.whitelisted(ctx, result.pack) }
                    events.add("pack_send", mapOf("step" to "prepared", "pack" to result.pack.id, "added" to added, "whitelist" to wl, "stickers" to size))
                    if (size < StickerPacks.MIN) {
                        toast("WhatsApp needs at least ${StickerPacks.MIN} stickers in a pack, and only $size could be prepared. " +
                            "Try sending another ${if (result.pack.animated) "animated" else "static"} sticker.")
                    } else if (!added) {
                        // First time only: WhatsApp asks once, then we go straight to WhatsApp.
                        pendingPack = result.pack
                        openAfterAdd = true
                        try {
                            askToAdd(StickerPacks.addToWhatsAppIntent(ctx, result.pack))
                            toast("One-time step: tap Add in WhatsApp. Next time it opens WhatsApp straight away.")
                        } catch (e: ActivityNotFoundException) {
                            pendingPack = null; openAfterAdd = false
                            events.add("pack_send", mapOf("step" to "no_whatsapp", "error" to e.message))
                            toast("WhatsApp didn't open. Check it is installed, then try again.")
                        }
                    } else if (StickerPacks.openWhatsApp(activity)) {
                        toast(readyHint(result.pack))
                    } else {
                        toast("WhatsApp didn't open. Check it is installed, then try again.")
                    }
                    refreshPermissions()
                }
            }
        }
    }

    private var pendingPack: StickerPacks.Pack? = null
    /** Set by Send in WhatsApp: once WhatsApp has added the pack, open it so the sticker can be sent. */
    private var openAfterAdd = false

    private fun readyHint(pack: StickerPacks.Pack) =
        "In the chat, tap the sticker icon, then the ${pack.title} tray: your sticker is first."

    /**
     * One-time setup from Settings: fills both built-in packs and asks WhatsApp to add whichever
     * isn't there yet, so Send in WhatsApp never stops at the Add screen later.
     */
    fun setUpQuickSend(askToAdd: (Intent) -> Unit) {
        viewModelScope.launch {
            val todo = withContext(Dispatchers.IO) {
                StickerPacks.Pack.builtIns.filter { p ->
                    if (StickerPacks.entries(ctx, p).size < StickerPacks.MIN) StickerPacks.fillIfShort(ctx, p, fillers(p, -1))
                    StickerPacks.entries(ctx, p).size >= StickerPacks.MIN && !StickerPacks.isAdded(ctx, p)
                }
            }
            refreshPacks()
            if (todo.isEmpty()) {
                return@launch toast(
                    if (StickerPacks.Pack.builtIns.all { StickerPacks.isAdded(ctx, it) }) "Both Sticker Search packs are already in WhatsApp."
                    else "There aren't enough stickers of each kind yet. Run a scan first.",
                )
            }
            addQueue.clear(); addQueue.addAll(todo.drop(1)); queuedAsk = askToAdd
            addPackToWhatsApp(todo.first(), askToAdd)
        }
    }

    /** For when WhatsApp lost the pack (removed there, or reinstalled): forget it and ask again. */
    fun readdPack(pack: StickerPacks.Pack, askToAdd: (Intent) -> Unit) {
        StickerPacks.markAdded(ctx, pack, false)
        refreshPacks()
        addPackToWhatsApp(pack, askToAdd)
    }
    /** Packs still to show WhatsApp's "Add" screen for, after the current one (bulk pack creation). */
    private val addQueue = ArrayDeque<StickerPacks.Pack>()
    private var queuedAsk: ((Intent) -> Unit)? = null

    private fun refreshPacks() {
        val list = StickerPacks.all(ctx).map { p ->
            val e = StickerPacks.entries(ctx, p)
            PackInfo(p, e.size, StickerPacks.isAdded(ctx, p), e.map { it.sha })
        }
        _state.update { it.copy(packs = list) }
    }

    /** Opens WhatsApp's "Add sticker pack" screen for [pack] (it must hold 3+ stickers). */
    fun addPackToWhatsApp(pack: StickerPacks.Pack, askToAdd: (Intent) -> Unit) {
        val n = StickerPacks.entries(ctx, pack).size
        if (n < StickerPacks.MIN) return toast("“${pack.title}” has $n stickers; WhatsApp needs at least ${StickerPacks.MIN}.")
        pendingPack = pack
        try { askToAdd(StickerPacks.addToWhatsAppIntent(ctx, pack)) } catch (e: ActivityNotFoundException) {
            pendingPack = null
            toast("WhatsApp didn't open. Check it is installed, then try again.")
        }
    }

    /** WhatsApp's answer to "add this pack": OK, or cancelled with a validation_error explaining why. */
    fun onAddPackResult(resultCode: Int, data: Intent?) {
        val pack = pendingPack
        pendingPack = null
        val openNow = openAfterAdd
        openAfterAdd = false
        // Bulk creation: show WhatsApp the next pack once it has answered about this one.
        addQueue.removeFirstOrNull()?.let { next -> queuedAsk?.let { ask -> addPackToWhatsApp(next, ask) } }
        if (addQueue.isEmpty() && pendingPack == null) queuedAsk = null
        val error = data?.getStringExtra("validation_error")
        events.add("pack_send", mapOf(
            "step" to "whatsapp_result", "pack" to pack?.id, "resultCode" to resultCode, "validationError" to error,
        ))
        viewModelScope.launch {
            val added = pack != null && withContext(Dispatchers.IO) {
                // WhatsApp's own "added" answer is remembered, so it isn't asked again on the next send.
                if (resultCode == android.app.Activity.RESULT_OK) StickerPacks.markAdded(ctx, pack, true)
                StickerPacks.isAdded(ctx, pack)
            }
            refreshPacks()
            if (added && openNow && pack != null && StickerPacks.openWhatsApp(ctx)) {
                toast(readyHint(pack))
                return@launch
            }
            toast(
                when {
                    (resultCode == android.app.Activity.RESULT_OK || added) && pack?.custom == true ->
                        "“${pack.title}” is in WhatsApp's sticker picker."
                    resultCode == android.app.Activity.RESULT_OK || added ->
                        "Pack added. In WhatsApp, open a chat and the sticker picker; your sticker is first in ${pack?.title ?: "Sticker Search"}."
                    error != null -> "WhatsApp didn't add the pack: $error"
                    else -> "WhatsApp closed without adding the pack. Tap Send in WhatsApp to try again."
                },
            )
            refreshPermissions()
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
            .put("gemmaLastCrash", com.kukreja.stickersearch.ml.DescribeGuard.lastCrash(ctx) ?: JSONObject.NULL)
            .put("gemmaCrashStreak", com.kukreja.stickersearch.ml.DescribeGuard.streak(ctx))
            .put("processExits", org.json.JSONArray(com.kukreja.stickersearch.ml.DescribeGuard.recentExits(ctx)))
            .put("lastAnnotationMs", st.getLong(AiWorker.KEY_LAST_ANNOTATION_MS, 0))
            .put("lastSearchMs", s.search.millis)
            .put("searchIndexSize", engine.size)
            .put("packs", JSONObject(StickerPacks.all(ctx).associate {
                it.id to "${StickerPacks.entries(ctx, it).size} stickers, added=${StickerPacks.isAdded(ctx, it)}"
            } as Map<*, *>))
            .put("batteryUnrestricted", batteryUnrestricted())
            .put("graph", JSONObject().apply {
                db.graph().counts().forEach { put(it.type.name, it.n) }
                put("buildMs", ctx.getSharedPreferences("graph", Context.MODE_PRIVATE).getLong("build_ms", 0))
                put("frameVectors", db.graph().frameVectorCount())
                put("strongest", JSONArray().apply {
                    db.graph().strongest(10).forEach { e -> put("${e.type} ${e.a}->${e.b} %.3f ${e.label ?: ""}".format(e.weight)) }
                })
            })
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

    /**
     * A screenshot (or any image) shared into the app becomes the attachment for the next search.
     * It's copied into the app's cache at once: the sharing app's read permission is temporary.
     */
    fun handleShareIntent(intent: Intent?) {
        if (intent == null || (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE)) return
        val streams = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
            else -> intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        }
        // Some screenshot tools only fill in ClipData.
        val clip = intent.clipData?.let { c -> (0 until c.itemCount).mapNotNull { c.getItemAt(it).uri } }.orEmpty()
        val uris = (streams + clip).distinct().take(com.kukreja.stickersearch.ml.GemmaAnnotator.MAX_IMAGES)
        events.add("share_in", mapOf("type" to intent.type, "count" to uris.size))
        if (uris.isEmpty()) return toast("Nothing to add: share an image or screenshot.")
        viewModelScope.launch(Dispatchers.IO) {
            val copies = uris.mapNotNull { u ->
                runCatching {
                    val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
                    val f = File(dir, "shot-${System.nanoTime()}.img")
                    ctx.contentResolver.openInputStream(u)?.use { input -> f.outputStream().use { input.copyTo(it) } } ?: return@runCatching null
                    Uri.fromFile(f)
                }.getOrNull()
            }
            if (copies.isEmpty()) return@launch toast("The shared image couldn't be opened. Try sharing it again.")
            // A fresh share starts a fresh question: it replaces earlier attachments and results.
            clearAssist()
            attach(copies)
            _state.update { it.copy(openSearch = true) }
            toast(if (copies.size == 1) "Screenshot added. Add a few words if you like, then tap Suggest stickers."
                else "${copies.size} images added. Add a few words if you like, then tap Suggest stickers.")
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private fun toast(msg: String) = _state.update { it.copy(message = msg) }

    override fun onCleared() {
        engine.close()
    }
}
