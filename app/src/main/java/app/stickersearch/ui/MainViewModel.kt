package app.stickersearch.ui

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
import app.stickersearch.data.AiCounts
import app.stickersearch.data.AnnotationRepository
import app.stickersearch.data.ScanRunEntity
import app.stickersearch.data.StickerDatabase
import app.stickersearch.data.StickerFrameEntity
import app.stickersearch.data.StickerRow
import app.stickersearch.data.StickerStatus
import app.stickersearch.diagnostics.EventLog
import app.stickersearch.diagnostics.ReportBuilder
import app.stickersearch.diagnostics.ScanEngine
import app.stickersearch.diagnostics.ScanOutcome
import app.stickersearch.discovery.FileSystemSource
import app.stickersearch.discovery.SafTreeSource
import app.stickersearch.discovery.SharedInSource
import app.stickersearch.index.AiWorker
import app.stickersearch.index.IndexWorker
import app.stickersearch.index.Sync
import app.stickersearch.media.Thumbnailer
import app.stickersearch.media.WasArchive
import app.stickersearch.ml.Annotation
import app.stickersearch.ml.Correction
import app.stickersearch.ml.ModelKind
import app.stickersearch.ml.ModelStore
import app.stickersearch.model.StickerFormat
import app.stickersearch.search.SearchEngine
import app.stickersearch.share.SendTarget
import app.stickersearch.share.StickerPacks
import app.stickersearch.share.StickerSharer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Permissions(
    val safTree: Uri?,
    val safHeld: Boolean,
    val allFiles: Boolean,
    /** WhatsApp Business's Media folder, picked separately when that application is installed. */
    val businessTree: Uri? = null,
    val businessHeld: Boolean = false,
    /** Installed WhatsApp apps (regular first) and the one stickers are sent with. */
    val whatsApps: List<String> = emptyList(),
    val sendWith: String = "com.whatsapp",
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
    /** Why the application last closed inside Gemma, if it did. */
    val lastCrash: String? = null,
    val lastCrashAt: Long = 0,
    /** Describing stopped after repeated crashes; Start now tries again. */
    val describePaused: Boolean = false,
    /** The user paused describing; Resume carries on. */
    val userPaused: Boolean = false,
)

data class ModelsState(
    val present: Map<ModelKind, Long> = emptyMap(),
    val importing: String? = null,
    /** The row whose Import button started the current import, if any. */
    val importingKind: ModelKind? = null,
    /** In-app download of the search models: running (or waiting for a network), with progress. */
    val downloading: Boolean = false,
    val downloadText: String? = null,
    val downloadFraction: Float? = null,
    /** Bytes so far for the model being downloaded right now. */
    val downloadKind: ModelKind? = null,
    val downloadError: String? = null,
    /** Downloads wait for Wi-Fi unless the user allowed mobile data. */
    val wifiOnly: Boolean = true,
) {
    /** The search models (everything except Gemma), which the app can download itself. */
    val searchModelsReady get() = has(ModelKind.E5) && has(ModelKind.SIGLIP_VISION) && has(ModelKind.SIGLIP_TEXT)
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
    /** One-shot: images arrived from another application, so Search should come to the front. */
    val openSearch: Boolean = false,
    val graph: Map<app.stickersearch.data.EdgeType, Int> = emptyMap(),
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
    /** Progress of a PC export or import, shown in Settings › AI. */
    val pcProgress: String? = null,
    /** The sticker being sent to WhatsApp right now; its send button shows a spinner. */
    val sendingId: Long? = null,
    /** WhatsApp didn't pick up the last sticker sent from this pack (shows a banner). */
    val sendMissed: StickerPacks.Pack? = null,
    /** Every send refreshes the pack in WhatsApp first. */
    val alwaysRefresh: Boolean = false,
    /** A tapped notification asked for Settings at this panel ("status" or "pc"). */
    val openSettingsAt: String? = null,
    /** Animated stickers play (twice) in the grid while on screen. */
    val animateGrid: Boolean = true,
    /** A send waiting for the user to pick WhatsApp or WhatsApp Business. */
    val chooseAppFor: StickerRow? = null,
    /** This phone's memory, and whether describing can run here (or the user said "Try anyway"). */
    val memoryGb: Double = 0.0,
    val phoneCanDescribe: Boolean = true,
    val describeAnyway: Boolean = false,
    /** First-run guided setup: finished, put off for now ("Later"), and the step to resume at. */
    val setupDone: Boolean = true,
    val setupDismissed: Boolean = false,
    val setupStep: Int = 0,
    /** Background battery status: Unrestricted, Optimised or Restricted. */
    val battery: app.stickersearch.device.BatteryAccess.State = app.stickersearch.device.BatteryAccess.State(),
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

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val ctx: Context get() = getApplication()
    private val prefs = application.getSharedPreferences(IndexWorker.PREFS, Context.MODE_PRIVATE)
    private val searchPrefs = application.getSharedPreferences("search", Context.MODE_PRIVATE)
    private val events = EventLog(File(application.filesDir, "events.jsonl"))
    private val importDir = File(application.filesDir, "imported")
    private val db = StickerDatabase.get(application)
    private val repo = AnnotationRepository(db)
    private val models = ModelStore(application)
    private val engine = SearchEngine(application)
    private val assistant = app.stickersearch.search.ImageAssist(application, engine)
    private val diagnosticEngine = ScanEngine(application, Thumbnailer(File(application.cacheDir, "diag-thumbs")))
    val sharer = StickerSharer(application, events)

    private val uiPrefs = application.getSharedPreferences("ui", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(UiState(readPermissions(), eventCount = events.all().length(), themeMode = savedThemeMode(),
        memoryGb = app.stickersearch.device.DeviceCaps.memoryGb(application),
        phoneCanDescribe = app.stickersearch.device.DeviceCaps.phoneCanDescribe(application),
        describeAnyway = app.stickersearch.device.DeviceCaps.describeAnyway(application), animateGrid = uiPrefs.getBoolean("animate_grid", true), alwaysRefresh = StickerPacks.alwaysRefresh(application)))
    val state: StateFlow<UiState> = _state
    private var searchJob: Job? = null
    private val graphRepo = app.stickersearch.graph.GraphRepository(application)
    @Volatile private var graph = app.stickersearch.graph.StickerGraph(emptyList())

    init {
        Sync.schedule(application)
        refreshModels()
        _state.update { it.copy(search = it.search.copy(recent = recentSearches())) }
        viewModelScope.launch {
            // Room re-runs this on every write to the stickers table (each description, each vector),
            // mostly with identical rows: only real changes reach the UI, so scrolling stays smooth.
            db.stickers().observeVisible().distinctUntilChanged().flowOn(Dispatchers.Default).collect { list ->
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
        observePcExport()
        observeModelDownload()
        loadSetup()
        // If the application died inside Gemma (describing or suggesting replies), record it now.
        viewModelScope.launch(Dispatchers.IO) {
            app.stickersearch.ml.DescribeGuard.takeUnfinishedEmbed(ctx)?.let { id ->
                events.add("e5_crash", mapOf("sticker" to id))
            }
            app.stickersearch.ml.DescribeGuard.takeUnfinished(ctx)?.let { u ->
                if (u.stickerId > 0) {
                    db.ai().annotationFailed(u.stickerId, "The application closed while describing this on the ${u.backend}: ${u.why}", System.currentTimeMillis())
                    db.ai().parkUnreadable(AiWorker.MAX_ATTEMPTS)
                }
                events.add("gemma_crash", mapOf("sticker" to u.stickerId, "backend" to u.backend, "why" to u.why))
                if (u.stickerId == -1L) toast("Suggesting replies closed the application (${u.why}). Gemma now uses safer settings; try again.")
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
                        lastCrash = app.stickersearch.ml.DescribeGuard.lastCrash(ctx),
                        lastCrashAt = app.stickersearch.ml.DescribeGuard.lastCrashAt(ctx),
                        describePaused = app.stickersearch.ml.DescribeGuard.paused(ctx),
                        userPaused = AiWorker.isPaused(ctx),
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
        _state.update { it.copy(permissions = readPermissions(), eventCount = events.all().length(), batteryUnrestricted = batteryUnrestricted(), battery = app.stickersearch.device.BatteryAccess.read(ctx)) }
        refreshModels()
        viewModelScope.launch(Dispatchers.IO) { refreshPacks(); _state.update { it.copy(storage = storage()) } }
    }

    private fun readPermissions(): Permissions {
        val tree = prefs.getString(IndexWorker.KEY_TREE, null)?.let(Uri::parse)
        val held = tree != null && ctx.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }
        val business = prefs.getString(IndexWorker.KEY_TREE_BUSINESS, null)?.let(Uri::parse)
        val businessHeld = business != null && ctx.contentResolver.persistedUriPermissions.any { it.uri == business && it.isReadPermission }
        return Permissions(tree, held, app.stickersearch.compat.Compat.canReadAllFiles(ctx), business, businessHeld,
            StickerPacks.installedApps(ctx), StickerPacks.target(ctx))
    }

    private fun batteryUnrestricted() = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    fun onTreePicked(uri: Uri?, business: Boolean = false) {
        if (uri == null) return
        ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        prefs.edit().putString(if (business) IndexWorker.KEY_TREE_BUSINESS else IndexWorker.KEY_TREE, uri.toString()).apply()
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

    // ---------------- model downloads ----------------

    /** Starts (or restarts) downloading the search models. The user agreed to it on the screen that calls this. */
    fun downloadModels() {
        _state.update { it.copy(models = it.models.copy(downloading = true, downloadError = null, downloadText = "Starting")) }
        app.stickersearch.index.ModelDownloadWorker.enqueue(ctx, _state.value.models.wifiOnly)
    }

    fun cancelModelDownload() {
        app.stickersearch.index.ModelDownloadWorker.cancel(ctx)
        _state.update { it.copy(models = it.models.copy(downloading = false, downloadText = null, downloadFraction = null, downloadKind = null)) }
    }

    fun setWifiOnly(on: Boolean) {
        uiPrefs.edit().putBoolean("wifi_only", on).apply()
        _state.update { it.copy(models = it.models.copy(wifiOnly = on)) }
        // A running download picks up the new rule straight away; nothing downloaded so far is lost.
        if (_state.value.models.downloading) app.stickersearch.index.ModelDownloadWorker.enqueue(ctx, on)
    }

    private fun observeModelDownload() {
        _state.update { it.copy(models = it.models.copy(wifiOnly = uiPrefs.getBoolean("wifi_only", true))) }
        val W = app.stickersearch.index.ModelDownloadWorker
        viewModelScope.launch {
            androidx.work.WorkManager.getInstance(ctx).getWorkInfosForUniqueWorkFlow(W.UNIQUE).collect { infos ->
                val w = infos.lastOrNull() ?: return@collect
                when (w.state) {
                    androidx.work.WorkInfo.State.RUNNING -> {
                        val d = w.progress.getInt(W.KEY_DONE_MB, 0); val t = w.progress.getInt(W.KEY_TOTAL_MB, 0)
                        val kind = w.progress.getString(W.KEY_KIND)?.let { runCatching { ModelKind.valueOf(it) }.getOrNull() }
                        _state.update { it.copy(models = it.models.copy(
                            downloading = true, downloadError = null, downloadKind = kind,
                            downloadText = if (t > 0) "%,d of %,d MB".format(d, t) else "Starting",
                            downloadFraction = if (t > 0) d.toFloat() / t else null,
                        )) }
                        refreshModels()
                    }
                    androidx.work.WorkInfo.State.ENQUEUED, androidx.work.WorkInfo.State.BLOCKED ->
                        _state.update { it.copy(models = it.models.copy(
                            downloading = true, downloadKind = null,
                            downloadText = if (it.models.wifiOnly) "Waiting for Wi-Fi" else "Waiting for a connection",
                        )) }
                    androidx.work.WorkInfo.State.SUCCEEDED -> {
                        _state.update { it.copy(models = it.models.copy(downloading = false, downloadText = null, downloadFraction = null, downloadKind = null)) }
                        refreshModels()
                        _state.update { it.copy(storage = storage()) }
                        withContext(Dispatchers.Default) { engine.close(); engine.warmUp() }
                        val id = w.id.toString()
                        if (uiPrefs.getString("download_seen", null) != id) {
                            uiPrefs.edit().putString("download_seen", id).apply()
                            toast("Search models are ready. Search by meaning and by look works now.")
                        }
                    }
                    androidx.work.WorkInfo.State.FAILED -> _state.update { it.copy(models = it.models.copy(
                        downloading = false, downloadText = null, downloadFraction = null, downloadKind = null,
                        downloadError = w.outputData.getString(W.KEY_ERROR) ?: "The download didn't finish.",
                    )) }
                    androidx.work.WorkInfo.State.CANCELLED -> _state.update { it.copy(models = it.models.copy(
                        downloading = false, downloadText = null, downloadFraction = null, downloadKind = null)) }
                }
            }
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
    fun setDescribeAnyway(on: Boolean) {
        app.stickersearch.device.DeviceCaps.setDescribeAnyway(ctx, on)
        _state.update { it.copy(describeAnyway = on) }
        if (on) toast("Describing will be tried on this phone. If the application keeps closing, it pauses itself.")
    }

    fun setAnimateGrid(on: Boolean) {
        uiPrefs.edit().putBoolean("animate_grid", on).apply()
        _state.update { it.copy(animateGrid = on) }
    }

    fun setThemeMode(mode: ThemeMode) {
        uiPrefs.edit().putString("theme", mode.name).apply()
        _state.update { it.copy(themeMode = mode) }
    }

    private fun savedThemeMode() =
        runCatching { ThemeMode.valueOf(uiPrefs.getString("theme", null) ?: ThemeMode.DARK.name) }.getOrDefault(ThemeMode.DARK)

    // ---------------- describe on a PC ----------------

    fun exportForPc(out: Uri) {
        if (_state.value.pcProgress != null) return
        // Keep write access after this screen goes away: the export runs as a background job.
        runCatching { ctx.contentResolver.takePersistableUriPermission(out, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        app.stickersearch.index.PcExportWorker.enqueue(ctx, out)
        _state.update { it.copy(pcProgress = "Preparing the export") }
        toast("Exporting in the background. You can leave the application; the notification shows progress.")
    }

    /** Follows the export job: progress into Settings, and one message when it ends. */
    private fun observePcExport() {
        val prefs = uiPrefs
        viewModelScope.launch {
            androidx.work.WorkManager.getInstance(ctx)
                .getWorkInfosForUniqueWorkFlow(app.stickersearch.index.PcExportWorker.UNIQUE)
                .collect { infos ->
                    val w = infos.lastOrNull() ?: return@collect
                    val W = app.stickersearch.index.PcExportWorker
                    when (w.state) {
                        androidx.work.WorkInfo.State.RUNNING, androidx.work.WorkInfo.State.ENQUEUED, androidx.work.WorkInfo.State.BLOCKED -> {
                            val d = w.progress.getInt(W.KEY_DONE, 0); val t = w.progress.getInt(W.KEY_TOTAL, 0)
                            _state.update { it.copy(pcProgress = if (t > 0) "Exporting %,d of %,d stickers".format(d, t) else "Preparing the export") }
                        }
                        else -> {
                            _state.update { it.copy(pcProgress = null) }
                            // Each finished export is announced once, even across application restarts.
                            val id = w.id.toString()
                            if (prefs.getString("pc_export_seen", null) == id) return@collect
                            prefs.edit().putString("pc_export_seen", id).apply()
                            val o = w.outputData
                            toast(when (w.state) {
                                androidx.work.WorkInfo.State.SUCCEEDED -> {
                                    val n = o.getInt(W.KEY_WRITTEN, 0); val t = o.getInt(W.KEY_TOTAL, 0)
                                    val skipped = o.getString(W.KEY_SKIPPED).orEmpty()
                                    if (t == 0) "Every sticker is already described; nothing to export."
                                    else {
                                        // The PC's descriptions will replace the phone's, so the phone stops spending hours on them.
                                        val pausedNow = !AiWorker.isPaused(ctx)
                                        if (pausedNow) { AiWorker.pause(ctx); _state.update { it.copy(ai = it.ai.copy(userPaused = true)) } }
                                        "Exported %,d of %,d stickers%s. Copy the file to your PC and follow README.txt inside it.%s"
                                            .format(n, t, if (skipped.isNotBlank()) " (skipped: $skipped)" else "",
                                                if (pausedNow) " Describing on the phone is paused meanwhile; Resume in Settings restarts it." else "")
                                    }
                                }
                                androidx.work.WorkInfo.State.CANCELLED -> "The export was stopped, and the unfinished file was removed."
                                else -> "The export didn't finish: ${o.getString(W.KEY_ERROR) ?: "unknown error"}. The unfinished file was removed; try again."
                            })
                        }
                    }
                }
        }
    }

    /** Writes this phone's descriptions (and edits) for another phone to import. */
    fun exportDescriptions(out: Uri) {
        if (_state.value.pcProgress != null) return
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(pcProgress = "Saving descriptions") }
            runCatching { app.stickersearch.index.PcBridge.exportDescriptions(ctx, db, repo, out) }
                .onSuccess { n ->
                    events.add("descriptions_export", mapOf("stickers" to n))
                    toast(if (n == 0) "Nothing is described yet, so there's nothing to share."
                        else "Saved %,d descriptions. On your other phone, use Import results and pick this file.".format(n))
                }
                .onFailure { e ->
                    runCatching { android.provider.DocumentsContract.deleteDocument(ctx.contentResolver, out) }
                    toast("Couldn't save the descriptions: ${e.message}")
                }
            _state.update { it.copy(pcProgress = null) }
        }
    }

    fun importPcResults(src: Uri) {
        if (_state.value.pcProgress != null) return
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(pcProgress = "Reading the results") }
            runCatching {
                app.stickersearch.index.PcBridge.import(ctx, db, repo, src) { d, t ->
                    _state.update { it.copy(pcProgress = "Importing %,d of %,d".format(d, t)) }
                }
            }.onSuccess { r ->
                events.add("pc_import", mapOf("saved" to r.saved, "replacedPhone" to r.replacedPhone, "already" to r.alreadyImported,
                    "alreadyDescribed" to r.alreadyDescribed, "edits" to r.editsAdded, "unreadable" to r.unreadable,
                    "notHere" to r.notHere, "cutOffTail" to r.cutOffTail))
                toast(buildString {
                    append(when {
                        r.saved > 0 -> "Imported %,d descriptions.".format(r.saved)
                        r.editsAdded > 0 -> "No new descriptions."
                        r.alreadyImported + r.alreadyDescribed > 0 -> "Nothing new: these stickers already have descriptions."
                        else -> "Nothing to import from that file."
                    })
                    val notes = buildList {
                        if (r.replacedPhone > 0) add("%,d replaced the phone's own".format(r.replacedPhone))
                        if (r.editsAdded > 0) add("%,d of your edits added".format(r.editsAdded))
                        if (r.alreadyImported > 0) add("%,d already imported".format(r.alreadyImported))
                        if (r.alreadyDescribed > 0) add("%,d already described here, kept".format(r.alreadyDescribed))
                        if (r.notHere > 0) add("%,d not on this phone (deleted or from another device), skipped".format(r.notHere))
                        if (r.unreadable > 0) add("%,d couldn't be read".format(r.unreadable))
                    }
                    if (notes.isNotEmpty()) append(" (").append(notes.joinToString(", ")).append(")")
                    if (r.saved + r.editsAdded > 0) append(" Search vectors and related stickers update in the background.")
                })
                // Text vectors (cleared for every new description or edit) and the graph, after any run in progress.
                if (r.saved + r.editsAdded > 0) AiWorker.enqueueAfterImport(ctx)
            }.onFailure { toast("Import failed: ${it.message}") }
            _state.update { it.copy(pcProgress = null) }
        }
    }

    fun runAiNow() {
        app.stickersearch.ml.DescribeGuard.resume(ctx)
        AiWorker.resume(ctx)
        _state.update { it.copy(ai = it.ai.copy(userPaused = false, describePaused = false)) }
    }

    fun pauseDescribing() {
        AiWorker.pause(ctx)
        _state.update { it.copy(ai = it.ai.copy(userPaused = true)) }
        toast("Describing paused. Already described stickers are kept; Resume carries on from here.")
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
            val list = (s.assist.attachments + uris).distinct().take(app.stickersearch.ml.GemmaAnnotator.MAX_IMAGES)
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
            val plan = app.stickersearch.share.PackPlanner.plan(name,
                rows.map { app.stickersearch.share.PackPlanner.Item(it.id, it.isAnimated, it.isLottieRow()) })
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

    /** Puts the sticker first in the AI Sticker Search pack and opens WhatsApp; the user sends it there. */
    /**
     * [askToAdd] must launch WhatsApp's ENABLE_STICKER_PACK intent *for a result*: WhatsApp only shows its
     * "Add sticker pack" screen to a caller it can identify, and reports validation errors in the result.
     */
    /** Both WhatsApp apps installed and none chosen yet: the dialog asks, then this sends. */
    fun chooseWhatsApp(pkg: String): StickerRow? {
        StickerPacks.setTarget(ctx, pkg)
        val row = _state.value.chooseAppFor
        _state.update { it.copy(chooseAppFor = null) }
        refreshPermissions()
        return row
    }

    fun cancelChooseWhatsApp() = _state.update { it.copy(chooseAppFor = null) }

    fun setSendWith(pkg: String) { StickerPacks.setTarget(ctx, pkg); refreshPermissions() }

    fun sendViaWhatsApp(activity: Context, s: StickerRow, askToAdd: (Intent) -> Unit) {
        if (StickerPacks.needsAppChoice(ctx)) { _state.update { it.copy(chooseAppFor = s) }; return }
        // One at a time: a second tap while the first is still preparing is ignored.
        if (_state.value.sendingId != null) return
        _state.update { it.copy(sendingId = s.id) }
        viewModelScope.launch {
          try {
            val uri = s.primaryUri ?: return@launch toast("This sticker's file is missing. Run a scan.")
            if (s.formatEnum() == StickerFormat.LOTTIE_WAS) {
                return@launch toast("WhatsApp only accepts WebP in third-party packs, so Lottie stickers can't be sent this way.")
            }
            // Did WhatsApp pick up the previous change to this pack by itself? If not, it isn't
            // re-reading on its own, and it's asked to (via its Add screen) before it opens.
            val kindPack = if (s.isAnimated) StickerPacks.Pack.ANIMATED else StickerPacks.Pack.STATIC
            val missedLast = StickerPacks.lastReadVersion(ctx, kindPack) < StickerPacks.version(ctx, kindPack)
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
                    } else if (missedLast || StickerPacks.alwaysRefresh(ctx)) {
                        // WhatsApp hasn't re-read the pack since it last changed (or it often misses updates):
                        // showing it the pack again makes it read the current stickers. Then WhatsApp opens.
                        events.add("pack_send", mapOf("step" to "refresh", "pack" to result.pack.id,
                            "version" to StickerPacks.version(ctx, result.pack), "lastRead" to StickerPacks.lastReadVersion(ctx, result.pack)))
                        pendingPack = result.pack
                        openAfterAdd = true
                        try {
                            askToAdd(StickerPacks.addToWhatsAppIntent(ctx, result.pack))
                            toast("Updating the ${result.pack.title} pack in WhatsApp…")
                        } catch (e: ActivityNotFoundException) {
                            pendingPack = null; openAfterAdd = false
                            toast("WhatsApp didn't open. Check it is installed, then try again.")
                        }
                    } else if (StickerPacks.openWhatsApp(activity)) {
                        StickerPacks.recordSent(ctx, result.pack)
                        toast(readyHint(result.pack))
                    } else {
                        toast("WhatsApp didn't open. Check it is installed, then try again.")
                    }
                    refreshPermissions()
                }
            }
          } finally {
            _state.update { it.copy(sendingId = null) }
          }
        }
    }

    /**
     * When the user comes back after a send: did WhatsApp read the pack's new stickers? If not, a
     * banner offers to refresh it, and after two misses in a row every send refreshes the pack first.
     */
    fun checkLastSend() {
        viewModelScope.launch(Dispatchers.IO) {
            for (pack in StickerPacks.Pack.builtIns) {
                val ok = StickerPacks.checkSent(ctx, pack) ?: continue
                val misses = StickerPacks.countMiss(ctx, !ok)
                events.add("pack_send", mapOf("step" to "check", "pack" to pack.id, "whatsappRead" to ok, "missesInARow" to misses))
                if (ok) { _state.update { it.copy(sendMissed = null) }; continue }
                val switched = misses >= 2 && !StickerPacks.alwaysRefresh(ctx)
                if (switched) StickerPacks.setAlwaysRefresh(ctx, true)
                _state.update { it.copy(sendMissed = pack, alwaysRefresh = StickerPacks.alwaysRefresh(ctx)) }
                if (switched) toast("WhatsApp keeps missing new stickers, so from now on the pack is refreshed before each send (one extra tap). You can change this in Settings.")
            }
        }
    }

    /** The banner's button: WhatsApp's pack screen makes it re-read the pack, then WhatsApp opens. */
    fun refreshPackInWhatsApp(pack: StickerPacks.Pack, askToAdd: (Intent) -> Unit) {
        _state.update { it.copy(sendMissed = null) }
        pendingPack = pack
        openAfterAdd = true
        try { askToAdd(StickerPacks.addToWhatsAppIntent(ctx, pack)) } catch (e: ActivityNotFoundException) {
            pendingPack = null; openAfterAdd = false
            toast("WhatsApp didn't open. Check it is installed, then try again.")
        }
    }

    fun dismissSendMissed() = _state.update { it.copy(sendMissed = null) }

    fun setAlwaysRefresh(on: Boolean) {
        StickerPacks.setAlwaysRefresh(ctx, on)
        _state.update { it.copy(alwaysRefresh = on) }
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
                    if (StickerPacks.Pack.builtIns.all { StickerPacks.isAdded(ctx, it) }) "Both AI Sticker Search packs are already in WhatsApp."
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
            val confirmed = resultCode == android.app.Activity.RESULT_OK
            // WhatsApp opens only if the user went through with it on WhatsApp's screen (Add or OK).
            // Backing out means they changed their mind: don't open WhatsApp.
            if (openNow && pack != null) {
                if (confirmed && StickerPacks.openWhatsApp(ctx)) {
                    StickerPacks.recordSent(ctx, pack)
                    toast(readyHint(pack))
                } else if (error != null) {
                    toast("WhatsApp didn't take the pack: $error")
                } else {
                    // If WhatsApp re-read the pack on that screen anyway, the next send goes straight
                    // to WhatsApp (lastReadVersion is current), so this can't loop.
                    toast("Not sent. Tap Send in WhatsApp again when you're ready.")
                }
                refreshPermissions()
                return@launch
            }
            toast(
                when {
                    (resultCode == android.app.Activity.RESULT_OK || added) && pack?.custom == true ->
                        "“${pack.title}” is in WhatsApp's sticker picker."
                    resultCode == android.app.Activity.RESULT_OK || added ->
                        "Pack added. In WhatsApp, open a chat and the sticker picker; your sticker is first in ${pack?.title ?: "AI Sticker Search"}."
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
        previews = listOf("thumbs", "frames", "packs").sumOf { dirSize(File(ctx.filesDir, it)) } + dirSize(AnimCache.dir(ctx)),
        database = ctx.getDatabasePath("stickers.db").let { f -> f.length() + File(f.path + "-wal").length() },
    )

    fun clearAll() {
        viewModelScope.launch(Dispatchers.IO) {
            db.clearAllTables()
            events.clear()
            listOf("imported", "thumbs", "frames", "packs").forEach { File(ctx.filesDir, it).deleteRecursively() }
            ctx.getSharedPreferences("sticker_packs", Context.MODE_PRIVATE).edit().clear().apply()
            ctx.cacheDir.deleteRecursively()
            AnimCache.clearMemory()
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
                diagnosticEngine.run(listOf(SafTreeSource(ctx, listOfNotNull(p.safTree, p.businessTree)), FileSystemSource(ctx), SharedInSource(importDir))) {}
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
            .put("gemmaLastCrash", app.stickersearch.ml.DescribeGuard.lastCrash(ctx) ?: JSONObject.NULL)
            .put("gemmaCrashStreak", app.stickersearch.ml.DescribeGuard.streak(ctx))
            .put("processExits", org.json.JSONArray(app.stickersearch.ml.DescribeGuard.recentExits(ctx)))
            .put("lastAnnotationMs", st.getLong(AiWorker.KEY_LAST_ANNOTATION_MS, 0))
            .put("lastSearchMs", s.search.millis)
            .put("searchIndexSize", engine.size)
            .put("packs", JSONObject(StickerPacks.all(ctx).associate {
                it.id to "${StickerPacks.entries(ctx, it).size} stickers, added=${StickerPacks.isAdded(ctx, it)}"
            } as Map<*, *>))
            .put("batteryUnrestricted", batteryUnrestricted())
            .apply {
                val b = app.stickersearch.device.BatteryAccess.read(ctx)
                put("batteryLevel", app.stickersearch.device.BatteryAccess.level(b).name)
                put("backgroundRestricted", b.backgroundRestricted)
                put("standbyBucket", b.standbyBucket)
            }
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
     * A screenshot (or any image) shared into the application becomes the attachment for the next search.
     * It's copied into the application's cache at once: the sharing application's read permission is temporary.
     */
    /** A tapped notification: open Settings at the panel it's about. Read once, then removed. */
    fun handleOpenIntent(intent: Intent?) {
        val target = intent?.getStringExtra(app.stickersearch.index.IndexNotification.EXTRA_OPEN) ?: return
        intent.removeExtra(app.stickersearch.index.IndexNotification.EXTRA_OPEN)
        _state.update { it.copy(openSettingsAt = target) }
    }

    fun consumeOpenSettings() = _state.update { it.copy(openSettingsAt = null) }

    /** Opens Settings at a panel ("status", "pc" or "models"). */
    fun openSettingsAt(target: String) = _state.update { it.copy(openSettingsAt = target) }

    // ---------------- first-run setup ----------------

    private fun loadSetup() {
        // Someone updating from a version without the setup, who already has the search models, skips it.
        val done = if (uiPrefs.contains("setup_done")) uiPrefs.getBoolean("setup_done", false)
            else ModelStore(ctx).let { m -> m.autoDownload.all { m.has(it) } }
        _state.update { it.copy(setupDone = done, setupDismissed = uiPrefs.getBoolean("setup_dismissed", false),
            setupStep = uiPrefs.getInt("setup_step", 0)) }
    }

    fun setSetupStep(step: Int) {
        uiPrefs.edit().putInt("setup_step", step).apply()
        _state.update { it.copy(setupStep = step) }
    }

    fun finishSetup(toast: String? = "Set up. Tap the green button on a sticker to send it in WhatsApp.") {
        uiPrefs.edit().putBoolean("setup_done", true).putBoolean("setup_dismissed", false).apply()
        _state.update { it.copy(setupDone = true, setupDismissed = false, openSearch = toast != null) }
        toast?.let { toast(it) }
    }

    /** "Later": the app opens normally; Settings keeps a Finish setup card. */
    fun dismissSetup() {
        uiPrefs.edit().putBoolean("setup_dismissed", true).apply()
        _state.update { it.copy(setupDismissed = true) }
    }

    fun reopenSetup() {
        uiPrefs.edit().putBoolean("setup_dismissed", false).apply()
        _state.update { it.copy(setupDismissed = false) }
    }

    fun handleShareIntent(intent: Intent?) {
        if (intent == null || (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE)) return
        val streams = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(app.stickersearch.compat.Compat.streamOf(intent))
            else -> app.stickersearch.compat.Compat.streamsOf(intent)
        }
        // Some screenshot tools only fill in ClipData.
        val clip = intent.clipData?.let { c -> (0 until c.itemCount).mapNotNull { c.getItemAt(it).uri } }.orEmpty()
        val uris = (streams + clip).distinct().take(app.stickersearch.ml.GemmaAnnotator.MAX_IMAGES)
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
