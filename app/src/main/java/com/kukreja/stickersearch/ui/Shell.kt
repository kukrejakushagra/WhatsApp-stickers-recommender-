package com.kukreja.stickersearch.ui

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
import com.kukreja.stickersearch.R
import com.kukreja.stickersearch.data.StickerRow
import com.kukreja.stickersearch.discovery.SafTreeSource
import com.kukreja.stickersearch.ml.Correction
import com.kukreja.stickersearch.ml.ModelKind
import com.kukreja.stickersearch.share.StickerSharer
import com.kukreja.stickersearch.ui.kds.KdsBottomNav
import com.kukreja.stickersearch.ui.kds.KdsGround
import com.kukreja.stickersearch.ui.kds.KdsToastHost
import com.kukreja.stickersearch.ui.kds.NavItem

/** Everything the screens can ask for; keeps the screens stateless. */
data class AppActions(
    val pickFolder: () -> Unit = {},
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
)

@Composable
fun MainScreen(vm: MainViewModel, openAllFilesSettings: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { vm.onTreePicked(it) }
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.importModels(it) }
    val expected = remember { arrayOfNulls<ModelKind>(1) }
    // The system photo picker: no storage permission, and only the images chosen are shared.
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(com.kukreja.stickersearch.ml.GemmaAnnotator.MAX_IMAGES),
    ) { vm.attach(it) }
    val addPack = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        vm.onAddPackResult(r.resultCode, r.data)
    }
    val singleModelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importModels(listOf(uri), expected[0])
    }
    // Asked on the first scan: lets the long-task progress notification show. Denying is fine.
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshPermissions() }
    fun askNotifications() {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val actions = remember(vm) {
        AppActions(
            pickFolder = { treePicker.launch(SafTreeSource.initialStickerFolderUri()) },
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
        )
    }
    AppShell(state, actions, onSearchOpened = vm::consumeOpenSearch)
}

private enum class Tab { SEARCH, LIBRARY, SETTINGS }

@Composable
fun AppShell(state: UiState, actions: AppActions, initialTab: Int = 0, onSearchOpened: () -> Unit = {}) {
    // First launch without any stickers opens Settings, where access is granted.
    var tab by rememberSaveable { mutableIntStateOf(if (state.loaded && state.stickers.isEmpty()) Tab.SETTINGS.ordinal else initialTab) }
    var selected by remember { mutableStateOf<StickerRow?>(null) }
    androidx.compose.runtime.LaunchedEffect(state.openSearch) {
        if (state.openSearch) { tab = Tab.SEARCH.ordinal; onSearchOpened() }
    }
    KdsGround(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().statusBarsPadding()) {
            when (Tab.entries[tab]) {
                Tab.SEARCH -> SearchScreen(state, actions, onOpen = { selected = it }, goToSettings = { tab = Tab.SETTINGS.ordinal })
                Tab.LIBRARY -> LibraryScreen(state, onOpen = { selected = it }, goToSettings = { tab = Tab.SETTINGS.ordinal })
                Tab.SETTINGS -> SettingsScreen(state, actions)
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            KdsToastHost(state.message, actions.messageShown, Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp))
            val busy = (state.index.running && state.index.total > 0) || state.ai.running
            KdsBottomNav(
                items = listOf(
                    NavItem("Search", R.drawable.ic_kds_search),
                    NavItem("Library", R.drawable.ic_kds_grid),
                    NavItem("Settings", R.drawable.ic_kds_sliders, count = if (busy) (state.ai.total - state.ai.done).takeIf { it > 0 }
                        ?: (state.index.total - state.index.done).takeIf { it > 0 } else null),
                ),
                selected = tab,
                onSelect = { tab = it },
                modifier = Modifier.navigationBarsPadding(),
            )
        }
    }
    selected?.let { s ->
        // Keep the sheet in step with the live row (status, thumbnail) while it's open.
        val live = state.stickers.firstOrNull { it.id == s.id } ?: s
        StickerSheet(live, actions, state.message, onOpen = { selected = it }) { selected = null }
    }
}

internal val BottomClearance = 104.dp
