package app.stickersearch.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.stickersearch.R
import app.stickersearch.data.StickerRow
import app.stickersearch.discovery.SafTreeSource
import app.stickersearch.ml.Correction
import app.stickersearch.ml.ModelKind
import app.stickersearch.share.StickerSharer
import app.stickersearch.ui.kds.KdsBottomNav
import app.stickersearch.ui.kds.KdsGround
import app.stickersearch.ui.kds.KdsToastHost
import app.stickersearch.ui.kds.NavItem

/** Everything the screens can ask for; keeps the screens stateless. */
data class AppActions(
    val pickFolder: () -> Unit = {},
    val pickBusinessFolder: () -> Unit = {},
    val setSendWith: (String) -> Unit = {},
    val setDescribeAnyway: (Boolean) -> Unit = {},
    val openAllFiles: () -> Unit = {},
    val scan: (retryFailed: Boolean) -> Unit = {},
    val runDiagnostic: () -> Unit = {},
    val clearAll: () -> Unit = {},
    val copyReport: () -> Unit = {},
    val shareReport: () -> Unit = {},
    val send: (StickerRow, StickerSharer.Mode) -> Unit = { _, _ -> },
    val sendViaWhatsApp: (StickerRow) -> Unit = {},
    val messageShown: () -> Unit = {},
    val reportJson: suspend () -> String = { "{}" },
    val details: suspend (Long) -> StickerDetails = { StickerDetails(emptyList(), emptyList()) },
    val importModels: () -> Unit = {},
    /** Import one file from that model's row. */
    val importModel: (ModelKind) -> Unit = {},
    /** Opens a download page in the browser (the app itself has no internet access). */
    val openUrl: (String) -> Unit = {},
    val deleteModel: (ModelKind) -> Unit = {},
    val runAiNow: () -> Unit = {},
    val pauseDescribing: () -> Unit = {},
    val onQueryChange: (String) -> Unit = {},
    val submitSearch: () -> Unit = {},
    val useRecent: (String) -> Unit = {},
    val clearRecent: () -> Unit = {},
    val saveCorrection: (Long, Correction) -> Unit = { _, _ -> },
    val resetCorrection: (Long) -> Unit = {},
    val openBatterySettings: () -> Unit = {},
    val attachImages: () -> Unit = {},
    val removeAttachment: (android.net.Uri) -> Unit = {},
    val runAssist: () -> Unit = {},
    val clearAssist: () -> Unit = {},
    // Custom sticker packs
    val startSelecting: (Long?) -> Unit = {},
    val stopSelecting: () -> Unit = {},
    val toggleSelected: (Long) -> Unit = {},
    val selectTop: (List<Long>) -> Unit = {},
    val createPacks: (String) -> Unit = {},
    val addToPack: (StickerRow, app.stickersearch.share.StickerPacks.Pack?, String?) -> Unit = { _, _, _ -> },
    val addPackToWhatsApp: (app.stickersearch.share.StickerPacks.Pack) -> Unit = {},
    val removeFromPack: (app.stickersearch.share.StickerPacks.Pack, String) -> Unit = { _, _ -> },
    val deletePack: (app.stickersearch.share.StickerPacks.Pack) -> Unit = {},
    val renamePack: (app.stickersearch.share.StickerPacks.Pack, String) -> Unit = { _, _ -> },
    val setUpQuickSend: () -> Unit = {},
    val setThemeMode: (ThemeMode) -> Unit = {},
    val setAnimateGrid: (Boolean) -> Unit = {},
    val exportForPc: () -> Unit = {},
    val importPcResults: () -> Unit = {},
    val exportDescriptions: () -> Unit = {},
    val refreshPackInWhatsApp: (app.stickersearch.share.StickerPacks.Pack) -> Unit = {},
    val dismissSendMissed: () -> Unit = {},
    val setAlwaysRefresh: (Boolean) -> Unit = {},
    val settingsOpened: () -> Unit = {},
    val readdPack: (app.stickersearch.share.StickerPacks.Pack) -> Unit = {},
    // Model downloads and the first-run setup
    val downloadModels: () -> Unit = {},
    val cancelModelDownload: () -> Unit = {},
    val setWifiOnly: (Boolean) -> Unit = {},
    val setSetupStep: (Int) -> Unit = {},
    val finishSetup: () -> Unit = {},
    /** Ends the setup without the "send" tip (another screen opens instead). */
    val finishSetupQuietly: () -> Unit = {},
    val dismissSetup: () -> Unit = {},
    val reopenSetup: () -> Unit = {},
    val openSettingsAt: (String) -> Unit = {},
)

@Composable
fun MainScreen(vm: MainViewModel, openAllFilesSettings: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { vm.onTreePicked(it) }
    val businessTreePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { vm.onTreePicked(it, business = true) }
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.importModels(it) }
    val expected = remember { arrayOfNulls<ModelKind>(1) }
    // The system photo picker: no storage permission, and only the images chosen are shared.
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(app.stickersearch.ml.GemmaAnnotator.MAX_IMAGES),
    ) { vm.attach(it) }
    val addPack = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        vm.onAddPackResult(r.resultCode, r.data)
    }
    val pcExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) vm.exportForPc(uri)
    }
    val descriptionsExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) vm.exportDescriptions(uri)
    }
    val pcImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importPcResults(uri)
    }
    val singleModelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importModels(listOf(uri), expected[0])
    }
    // Asked on the first scan: lets the long-task progress notification show. Denying is fine.
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshPermissions(); vm.checkLastSend() }
    fun askNotifications() {
        if (app.stickersearch.compat.Compat.needsNotificationPermission(context)) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val actions = remember(vm) {
        AppActions(
            pickFolder = { treePicker.launch(SafTreeSource.initialStickerFolderUri()) },
            pickBusinessFolder = { businessTreePicker.launch(SafTreeSource.initialStickerFolderUri(business = true)) },
            setSendWith = vm::setSendWith,
            setDescribeAnyway = vm::setDescribeAnyway,
            openAllFiles = openAllFilesSettings,
            scan = { retry -> askNotifications(); vm.scan(retry) },
            runDiagnostic = vm::runDiagnostic,
            clearAll = vm::clearAll,
            copyReport = vm::copyReport,
            shareReport = { vm.shareReport(context) },
            send = { s, m -> vm.send(context, s, m) },
            sendViaWhatsApp = { s -> vm.sendViaWhatsApp(context, s) { intent -> addPack.launch(intent) } },
            messageShown = vm::consumeMessage,
            reportJson = vm::reportJson,
            details = vm::details,
            importModels = { modelPicker.launch(arrayOf("*/*")) },
            importModel = { kind -> expected[0] = kind; singleModelPicker.launch(arrayOf("*/*")) },
            openUrl = { url ->
                runCatching {
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            },
            deleteModel = vm::deleteModel,
            runAiNow = { askNotifications(); vm.runAiNow() },
            pauseDescribing = vm::pauseDescribing,
            onQueryChange = vm::onQueryChange,
            submitSearch = vm::submitSearch,
            useRecent = vm::useRecent,
            clearRecent = vm::clearRecentSearches,
            saveCorrection = vm::saveCorrection,
            resetCorrection = vm::resetCorrection,
            openBatterySettings = { runCatching { context.startActivity(vm.batteryIntent()) } },
            attachImages = {
                photoPicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            removeAttachment = vm::removeAttachment,
            runAssist = vm::runAssist,
            clearAssist = vm::clearAssist,
            startSelecting = vm::startSelecting,
            stopSelecting = vm::stopSelecting,
            toggleSelected = vm::toggleSelected,
            selectTop = vm::selectTop,
            createPacks = { name -> vm.createPacksFromSelection(name) { intent -> addPack.launch(intent) } },
            addToPack = vm::addToPack,
            addPackToWhatsApp = { pack -> vm.addPackToWhatsApp(pack) { intent -> addPack.launch(intent) } },
            removeFromPack = vm::removeFromPack,
            deletePack = vm::deletePack,
            renamePack = vm::renamePack,
            setThemeMode = vm::setThemeMode,
            setAnimateGrid = vm::setAnimateGrid,
            exportForPc = { pcExport.launch("sticker-search-export.zip") },
            importPcResults = { pcImport.launch(arrayOf("*/*")) },
            refreshPackInWhatsApp = { pack -> vm.refreshPackInWhatsApp(pack) { intent -> addPack.launch(intent) } },
            dismissSendMissed = vm::dismissSendMissed,
            setAlwaysRefresh = vm::setAlwaysRefresh,
            settingsOpened = vm::consumeOpenSettings,
            exportDescriptions = {
                descriptionsExport.launch("sticker-descriptions-%s.jsonl".format(java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())))
            },
            setUpQuickSend = { vm.setUpQuickSend { intent -> addPack.launch(intent) } },
            readdPack = { pack -> vm.readdPack(pack) { intent -> addPack.launch(intent) } },
            downloadModels = { askNotifications(); vm.downloadModels() },
            cancelModelDownload = vm::cancelModelDownload,
            setWifiOnly = vm::setWifiOnly,
            setSetupStep = vm::setSetupStep,
            finishSetup = { vm.finishSetup() },
            finishSetupQuietly = { vm.finishSetup(null) },
            dismissSetup = vm::dismissSetup,
            reopenSetup = vm::reopenSetup,
            openSettingsAt = vm::openSettingsAt,
        )
    }
    AppShell(state, actions, onSearchOpened = vm::consumeOpenSearch)
    if (state.chooseAppFor != null) {
        ChooseWhatsAppDialog(
            onChoose = { pkg -> vm.chooseWhatsApp(pkg)?.let { row -> vm.sendViaWhatsApp(context, row) { intent -> addPack.launch(intent) } } },
            onDismiss = vm::cancelChooseWhatsApp,
        )
    }
}

private enum class Tab { SEARCH, SETTINGS }

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AppShell(state: UiState, actions: AppActions, initialTab: Int = 0, onSearchOpened: () -> Unit = {}) {
    // First launch without any stickers opens Settings, where access is granted.
    var tab by rememberSaveable { mutableIntStateOf(if (state.loaded && state.stickers.isEmpty()) Tab.SETTINGS.ordinal else initialTab) }
    var selected by remember { mutableStateOf<StickerRow?>(null) }
    androidx.compose.runtime.LaunchedEffect(state.openSearch) {
        if (state.openSearch) { tab = Tab.SEARCH.ordinal; onSearchOpened() }
    }
    // A tapped notification: Settings, where SettingsScreen scrolls to the panel and clears the flag.
    androidx.compose.runtime.LaunchedEffect(state.openSettingsAt) {
        if (state.openSettingsAt != null) {
            selected = null
            if (state.selecting) actions.stopSelecting()
            tab = Tab.SETTINGS.ordinal
        }
    }
    // First run: the guided setup takes the whole screen until it's finished or put off.
    if (!state.setupDone && !state.setupDismissed) {
        KdsGround(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().statusBarsPadding()) { SetupScreen(state, actions) }
            KdsToastHost(state.message, actions.messageShown,
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 12.dp))
        }
        return
    }
    KdsGround(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().statusBarsPadding()) {
            when (Tab.entries.getOrElse(tab) { Tab.SEARCH }) {
                Tab.SEARCH -> SearchScreen(state, actions, onOpen = { selected = it }, goToSettings = { tab = Tab.SETTINGS.ordinal })
                Tab.SETTINGS -> SettingsScreen(state, actions)
            }
        }
        // While the keyboard is up, the tab bar steps aside (it would sit right on top of the keyboard)
        // and messages move up above it.
        val imeVisible = androidx.compose.foundation.layout.WindowInsets.isImeVisible
        val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().imePadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            KdsToastHost(state.message, actions.messageShown, Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp))
            val busy = (state.index.running && state.index.total > 0) || state.ai.running
            if (!imeVisible) KdsBottomNav(
                items = listOf(
                    NavItem("Stickers", R.drawable.ic_kds_search),
                    NavItem("Settings", R.drawable.ic_kds_sliders, count = if (busy) (state.ai.total - state.ai.done).takeIf { it > 0 }
                        ?: (state.index.total - state.index.done).takeIf { it > 0 } else null),
                ),
                selected = tab,
                onSelect = { if (it != tab && state.selecting) actions.stopSelecting(); keyboard?.hide(); tab = it },
                modifier = Modifier.navigationBarsPadding(),
            )
        }
    }
    selected?.let { s ->
        // Keep the sheet in step with the live row (status, thumbnail) while it's open.
        val live = state.stickers.firstOrNull { it.id == s.id } ?: s
        StickerSheet(live, actions, state, onOpen = { selected = it }, onSearch = { q ->
            selected = null; actions.stopSelecting(); tab = Tab.SEARCH.ordinal; actions.useRecent(q)
        }) { selected = null }
    }
}

internal val BottomClearance = 104.dp
