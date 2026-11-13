package app.stickersearch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import app.stickersearch.ui.kds.KdsIcon
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.relocation.bringIntoViewRequester
import app.stickersearch.ui.kds.KdsFilterChip
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.stickersearch.R
import app.stickersearch.data.StickerStatus
import app.stickersearch.ml.ModelKind
import app.stickersearch.share.StickerPacks
import app.stickersearch.ui.kds.BadgeTone
import app.stickersearch.ui.kds.ButtonSize
import app.stickersearch.ui.kds.ButtonVariant
import app.stickersearch.ui.kds.CalloutTone
import app.stickersearch.ui.kds.KdsAppBar
import app.stickersearch.ui.kds.KdsBadge
import app.stickersearch.ui.kds.KdsButton
import app.stickersearch.ui.kds.KdsInfo
import app.stickersearch.ui.kds.KdsCallout
import app.stickersearch.ui.kds.KdsDialog
import app.stickersearch.ui.kds.KdsPanel
import app.stickersearch.ui.kds.KdsProgress
import app.stickersearch.ui.kds.KdsSettingsGroup
import app.stickersearch.ui.kds.KdsSettingsRow
import app.stickersearch.ui.kds.theme.Kds

internal const val PRIVACY_URL = "https://docs.google.com/document/d/15o4XnHJmeKDzgcoiLK6-bxirFbYaJd2OXg11Xv62fKQ/pub"
internal const val TERMS_URL = "https://docs.google.com/document/d/1H2AihW7UtNvx2j7c9iSAPB-KGxVeShq28_hYwUNN8p4/pub"
internal const val CONTACT_EMAIL = "squabla.adm@gmail.com"
internal const val SOURCE_URL = "https://github.com/kukrejakushagra/whatsapp-stickers-recommender-"

private fun mb(bytes: Long) = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / 1073741824.0)
    else -> "%,d MB".format(bytes shr 20)
}

@Composable
internal fun SettingsScreen(state: UiState, actions: AppActions) {
    val p = state.permissions
    var confirm by remember { mutableStateOf<String?>(null) }
    var showReport by remember { mutableStateOf(false) }
    var openPack by remember { mutableStateOf<String?>(null) }
    state.packs.firstOrNull { it.pack.id == openPack }?.let { info -> PackSheet(info, state, actions) { openPack = null } }
    // Opened from a notification: bring the panel it's about into view.
    val statusPanel = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    val pcPanel = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    androidx.compose.runtime.LaunchedEffect(state.openSettingsAt) {
        when (state.openSettingsAt) {
            null -> return@LaunchedEffect
            "pc" -> pcPanel.bringIntoView()
            else -> statusPanel.bringIntoView()
        }
        actions.settingsOpened()
    }
    val pickedStickersOnly = p.safHeld && p.safTreeId?.endsWith("WhatsApp Stickers") == true
    val modelsPanel = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    androidx.compose.runtime.LaunchedEffect(state.openSettingsAt) {
        if (state.openSettingsAt == "models") { modelsPanel.bringIntoView(); actions.settingsOpened() }
    }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        KdsAppBar("Settings")
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = BottomClearance), verticalArrangement = Arrangement.spacedBy(20.dp)) {

            // ---- 1. Finish setup (only while it isn't done) ----
            if (!state.setupDone) {
                app.stickersearch.ui.kds.KdsPanel {
                    Text("Finish setup", style = Kds.type.heading, color = Kds.colors.foreground)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        listOfNotNull(
                            "pick WhatsApp's folder".takeIf { !p.safHeld && !p.allFiles },
                            "get the search models".takeIf { !state.models.searchModelsReady },
                        ).joinToString(" and ").replaceFirstChar { it.uppercase() }.ifBlank { "Choose how to describe your stickers" } + ".",
                        style = Kds.type.caption, color = Kds.colors.mutedForeground,
                    )
                    Spacer(Modifier.height(10.dp))
                    KdsButton("Continue setup", actions.reopenSetup, block = true)
                }
            }

            // ---- 2. Your stickers ----
            Box(Modifier.bringIntoViewRequester(statusPanel)) { StatusPanel(state, actions) }

            // ---- 3. Sticker access ----
            KdsSettingsGroup("Sticker access", listOf<@Composable () -> Unit>(
                {
                    KdsSettingsRow(
                        title = "WhatsApp media folder", icon = R.drawable.ic_kds_folder,
                        description = if (p.safHeld) p.safTreeId?.substringAfter(':')?.replace("/", " › ")
                        else if (p.safTree != null) "Access was removed. Pick the folder again."
                        else "Pick the Media folder once. Access stays until you remove it.",
                        onClick = if (p.safHeld) actions.pickFolder else null,
                        trailing = {
                            if (p.safHeld) KdsBadge("Granted", BadgeTone.SOFT)
                            else KdsButton("Pick folder", actions.pickFolder, variant = ButtonVariant.OUTLINE, size = ButtonSize.SM)
                        },
                    )
                },
            ) + (if (StickerPacks.BUSINESS_ENABLED && (p.businessTree != null || StickerPacks.WHATSAPP_BUSINESS in p.whatsApps)) listOf<@Composable () -> Unit>({
                KdsSettingsRow(
                    title = "WhatsApp Business folder", icon = R.drawable.ic_kds_folder,
                    description = if (p.businessHeld) p.businessTree?.let { android.provider.DocumentsContract.getTreeDocumentId(it) }
                            ?.substringAfter(':')?.replace("/", " › ")
                        else "WhatsApp Business keeps its own stickers. Pick its Media folder too, and both go into one library.",
                    onClick = if (p.businessHeld) actions.pickBusinessFolder else null,
                    trailing = {
                        if (p.businessHeld) KdsBadge("Granted", BadgeTone.SOFT)
                        else KdsButton("Pick folder", actions.pickBusinessFolder, variant = ButtonVariant.OUTLINE, size = ButtonSize.SM)
                    },
                )
            }) else emptyList()) + listOf<@Composable () -> Unit>({
                    // Android 11+ calls it "All files access"; on Android 9-10 it's the storage permission.
                    val modern = android.os.Build.VERSION.SDK_INT >= 30
                    KdsSettingsRow(
                        title = if (modern) "All files access" else "Storage permission", icon = R.drawable.ic_kds_shield,
                        description = "Optional. Used only when the folder isn't picked.",
                        trailing = {
                            if (p.allFiles) KdsBadge("Granted", BadgeTone.SOFT)
                            else KdsButton(if (modern) "Open settings" else "Allow", actions.openAllFiles, variant = ButtonVariant.OUTLINE, size = ButtonSize.SM)
                        },
                    )
                },
            ))
            if (pickedStickersOnly) {
                KdsCallout("You picked WhatsApp Stickers only, so the stickers in Backup Excluded Stickers are missed. Pick Media instead.",
                    CalloutTone.WARNING, title = "One folder is missing")
            }

            // ---- 4. AI models, 5. Describe on a PC ----
            Box(Modifier.bringIntoViewRequester(modelsPanel)) { ModelsGroup(state, actions) { kind -> confirm = "model:${kind.name}" } }
            Box(Modifier.bringIntoViewRequester(pcPanel)) { PcPanel(state, actions) }

            // ---- 6. WhatsApp ----
            val quickReady = state.packs.filter { !it.pack.custom }.all { it.added }
            KdsSettingsGroup("WhatsApp", (if (p.whatsApps.size > 1) listOf<@Composable () -> Unit>({
                KdsSettingsRow(
                    title = "Send with", icon = R.drawable.ic_kds_phone,
                    description = "Both WhatsApp and WhatsApp Business are installed. Stickers go to the one chosen here.",
                    trailing = {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            KdsFilterChip("WhatsApp", p.sendWith == StickerPacks.WHATSAPP, { actions.setSendWith(StickerPacks.WHATSAPP) })
                            KdsFilterChip("Business", p.sendWith == StickerPacks.WHATSAPP_BUSINESS, { actions.setSendWith(StickerPacks.WHATSAPP_BUSINESS) })
                        }
                    },
                )
            }) else emptyList()) + listOf<@Composable () -> Unit>({
                KdsSettingsRow(
                    title = "Quick send", icon = R.drawable.ic_kds_phone,
                    description = if (quickReady) "Set up. Send in WhatsApp goes straight back to your chat."
                        else "Add both AI Sticker Search packs to WhatsApp once, so Send in WhatsApp never stops at WhatsApp's Add screen.",
                    onClick = if (quickReady) null else actions.setUpQuickSend,
                    trailing = { if (quickReady) KdsBadge("Ready", BadgeTone.SOFT) else KdsButton("Set up", actions.setUpQuickSend, size = ButtonSize.SM) },
                )
            }, {
                KdsSettingsRow(
                    title = "Refresh pack on every send", icon = R.drawable.ic_kds_refresh,
                    description = "Shows WhatsApp's pack screen before each send, which makes WhatsApp re-read the pack so the new " +
                        "sticker is surely there. One extra tap. Turns on by itself if WhatsApp misses new stickers twice in a row.",
                    trailing = {
                        KdsFilterChip(if (state.alwaysRefresh) "On" else "Off", state.alwaysRefresh, { actions.setAlwaysRefresh(!state.alwaysRefresh) })
                    },
                )
            }) + state.packs.map { info ->
                {
                    val pack = info.pack
                    KdsSettingsRow(
                        title = pack.title, icon = if (pack.custom) R.drawable.ic_kds_folder else R.drawable.ic_kds_phone,
                        description = when {
                            info.added -> if (pack.custom) "In WhatsApp's sticker picker · %d stickers".format(info.count)
                                else "In WhatsApp · your latest %d of %d sent, newest first; older ones drop off".format(info.count, StickerPacks.MAX)
                            info.count >= StickerPacks.MIN -> "%d stickers ready to add to WhatsApp.".format(info.count)
                            pack.custom -> "%d sticker%s. Add at least %d to send it to WhatsApp.".format(info.count, if (info.count == 1) "" else "s", StickerPacks.MIN)
                            else -> "Appears in WhatsApp after you send your first ${if (pack.animated) "animated " else ""}sticker."
                        },
                        onClick = if (info.count > 0) ({ openPack = pack.id }) else null,
                        trailing = { if (info.added) KdsBadge("Added", BadgeTone.SOFT) },
                    )
                }
            })
            KdsInfo("Make your own pack", "On the Stickers tab, tap Select, pick up to ${StickerPacks.MAX} stickers, then Create pack.",
                style = Kds.type.caption, color = Kds.colors.mutedForeground, modifier = Modifier.padding(horizontal = 4.dp))

            // ---- 7. Appearance ----
            val still = remember { systemWantsStill(ctx) }
            KdsSettingsGroup("Appearance", listOf(
                {
                    KdsSettingsRow(
                        title = "Theme", icon = R.drawable.ic_kds_sliders,
                        description = when (state.themeMode) {
                            ThemeMode.SYSTEM -> "Follows your phone's dark mode setting"
                            ThemeMode.LIGHT -> "Always light"
                            ThemeMode.DARK -> "Always dark"
                        },
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf(ThemeMode.DARK to "Dark", ThemeMode.LIGHT to "Light", ThemeMode.SYSTEM to "System").forEach { (m, label) ->
                            KdsFilterChip(label, state.themeMode == m, { actions.setThemeMode(m) })
                        }
                    }
                },
                {
                    KdsSettingsRow(
                        title = "Animate stickers in the grid", icon = R.drawable.ic_kds_play,
                        description = if (still) "Paused while Battery Saver or \u201cRemove animations\u201d is on. " +
                            "Animated stickers on screen play twice, like in WhatsApp, then stop until you scroll away and back."
                        else "Animated stickers on screen play twice, like in WhatsApp, then stop until you scroll away and back. " +
                            "Paused automatically in Battery Saver.",
                        trailing = { KdsFilterChip(if (state.animateGrid) "On" else "Off", state.animateGrid, { actions.setAnimateGrid(!state.animateGrid) }) },
                    )
                },
            ))

            // ---- 8. Background ----
            KdsSettingsGroup("Background", listOf(
                {
                    KdsSettingsRow(
                        title = "Daily check while charging", icon = R.drawable.ic_kds_refresh,
                        description = "Finds new and removed stickers, then describes new ones. Android decides the exact time.",
                        trailing = { KdsBadge("On", BadgeTone.SOFT) },
                    )
                },
                {
                    KdsSettingsRow(
                        title = "Skip battery optimisation", icon = R.drawable.ic_kds_battery,
                        description = "Lets the app keep working while the phone sleeps. On its own this often isn't enough " +
                            "for long describing runs; see Unrestricted battery use below.",
                        trailing = {
                            if (state.batteryUnrestricted) KdsBadge("Allowed", BadgeTone.SOFT)
                            else KdsButton("Allow", actions.openBatterySettings, variant = ButtonVariant.OUTLINE, size = ButtonSize.SM)
                        },
                    )
                },
                {
                    val level = app.stickersearch.device.BatteryAccess.level(state.battery)
                    val steps = app.stickersearch.device.BatteryAccess.steps(android.os.Build.VERSION.SDK_INT, android.os.Build.MANUFACTURER)
                    KdsSettingsRow(
                        title = "Unrestricted battery use", icon = R.drawable.ic_kds_battery,
                        description = when (level) {
                            app.stickersearch.device.BatteryAccess.Level.UNRESTRICTED ->
                                "Describing can run for hours while charging. If runs still stop: $steps"
                            app.stickersearch.device.BatteryAccess.Level.RESTRICTED ->
                                "Android is holding the app back in the background, so describing stops early. To fix it: $steps"
                            else -> "Needed for describing to run for hours (with thousands of stickers it takes several nights). " +
                                "Android doesn't let apps switch this on themselves; it takes a few taps: $steps"
                        },
                        trailing = {
                            when (level) {
                                app.stickersearch.device.BatteryAccess.Level.UNRESTRICTED -> KdsBadge("Unrestricted", BadgeTone.SOFT)
                                app.stickersearch.device.BatteryAccess.Level.RESTRICTED -> KdsBadge("Restricted", BadgeTone.DANGER)
                                else -> KdsBadge("Optimised", BadgeTone.WARNING)
                            }
                        },
                    )
                    if (level != app.stickersearch.device.BatteryAccess.Level.UNRESTRICTED) {
                        Row(Modifier.fillMaxWidth().padding(start = 52.dp, end = 16.dp, bottom = 14.dp)) {
                            KdsButton("Open app settings", actions.openAppSettings, variant = ButtonVariant.OUTLINE, size = ButtonSize.SM)
                        }
                    }
                },
            ))

            // ---- 9. Data & privacy ----
            KdsSettingsGroup("Data & privacy", listOf(
                {
                    KdsSettingsRow(
                        title = "Storage used", icon = R.drawable.ic_kds_file,
                        description = "Models ${mb(state.storage.models)} · previews ${mb(state.storage.previews)} · index ${mb(state.storage.database)}. " +
                            "Uninstalling the app removes all of it.",
                    )
                },
                {
                    KdsSettingsRow(
                        title = "Privacy policy", icon = R.drawable.ic_kds_lock,
                        description = "Stickers, chats and descriptions stay on this phone. The internet is used only to download the AI models.",
                        onClick = { actions.openUrl(PRIVACY_URL) },
                    )
                },
                {
                    KdsSettingsRow(title = "Terms of use", icon = R.drawable.ic_kds_file, onClick = { actions.openUrl(TERMS_URL) })
                },
                {
                    KdsSettingsRow(
                        title = "Delete index", icon = R.drawable.ic_kds_trash, danger = true,
                        description = "Thumbnails, keyframes, descriptions and your edits. Your WhatsApp stickers and the models stay.",
                        onClick = { confirm = "index" },
                    )
                },
            ))

            // ---- 10. Help & diagnostics ----
            KdsSettingsGroup("Help & diagnostics", listOf(
                {
                    KdsSettingsRow(
                        title = "Report", icon = R.drawable.ic_kds_copy,
                        description = "Counts, timings and file names only. No images, no chats.",
                        trailing = { KdsButton("Copy", actions.copyReport, variant = ButtonVariant.OUTLINE, size = ButtonSize.SM) },
                    )
                },
                {
                    KdsSettingsRow(
                        title = if (state.diagnosticRunning) "Running full diagnostic" else "Full diagnostic scan",
                        icon = R.drawable.ic_kds_search, description = "Re-checks every access method. Takes a minute or two.",
                        onClick = if (state.diagnosticRunning) null else actions.runDiagnostic,
                    )
                },
                {
                    KdsSettingsRow(
                        title = "Share from WhatsApp", icon = R.drawable.ic_kds_share,
                        description = "Long-press a sticker in a chat and share it here. ${state.eventCount} events logged.",
                    )
                },
                {
                    KdsSettingsRow(title = if (showReport) "Hide report" else "Show report", icon = R.drawable.ic_kds_file,
                        onClick = { showReport = !showReport })
                },
                {
                    KdsSettingsRow(
                        title = "Open-source licences", icon = R.drawable.ic_kds_file,
                        description = "AI Sticker Search is open source (MIT). It uses ONNX Runtime (MIT), MediaPipe (Apache 2.0), " +
                            "Lottie (Apache 2.0), AndroidX (Apache 2.0) and the Geist font (SIL OFL). Models: multilingual-e5-small (MIT), " +
                            "SigLIP2 (Apache 2.0), Gemma (Gemma Terms of Use).",
                        onClick = { actions.openUrl(SOURCE_URL) },
                    )
                },
                {
                    KdsSettingsRow(
                        title = "Contact & feedback", icon = R.drawable.ic_kds_send,
                        description = "Questions, ideas or a bug: write to $CONTACT_EMAIL.",
                        onClick = { actions.openUrl("mailto:$CONTACT_EMAIL?subject=" + android.net.Uri.encode("AI Sticker Search feedback")) },
                    )
                },
                {
                    KdsSettingsRow(title = "Version", icon = R.drawable.ic_kds_spark, trailing = {
                        Text(remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty() }, style = Kds.type.caption, color = Kds.colors.mutedForeground)
                    })
                },
            ))
            if (showReport) {
                val json by produceState("{}", state.diagnostic, state.ai.counts, state.lastRun) { value = actions.reportJson() }
                KdsPanel {
                    Text(json, style = Kds.type.mono, color = Kds.colors.foreground)
                }
            }
        }
    }
    when {
        confirm == "index" -> KdsDialog(
            title = "Delete the index?",
            description = "Thumbnails, keyframes, descriptions and your edits are removed. Your WhatsApp stickers and the AI models stay; a new scan rebuilds the rest.",
            confirmLabel = "Delete", destructive = true,
            onConfirm = { confirm = null; actions.clearAll() }, onDismiss = { confirm = null },
        )
        confirm?.startsWith("model:") == true -> {
            val kind = ModelKind.valueOf(confirm!!.removePrefix("model:"))
            KdsDialog(
                title = "Remove ${kind.title}?",
                description = "Frees ${mb(state.models.present[kind] ?: 0)}. Descriptions already made are kept; import the file again to keep going.",
                confirmLabel = "Remove", destructive = true,
                onConfirm = { confirm = null; actions.deleteModel(kind) }, onDismiss = { confirm = null },
            )
        }
    }
}

@Composable
private fun ModelsGroup(state: UiState, actions: AppActions, onRemove: (ModelKind) -> Unit) {
    val m = state.models
    val ready = m.readyCount
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Everything ready: one line, details on demand.
        if (ready == ModelKind.entries.size && !expanded) {
            KdsSettingsGroup("AI models", listOf({
                KdsSettingsRow(
                    title = "All 4 models ready", icon = R.drawable.ic_kds_check_circle,
                    description = "Search by meaning and by look, and Gemma for describing · ${mb(m.present.values.sum())}",
                    onClick = { expanded = true },
                )
            }))
            return@Column
        }
        KdsSettingsGroup(
            if (ready == ModelKind.entries.size) "AI models · all ready" else "AI models · $ready of ${ModelKind.entries.size} ready",
            ModelKind.entries.mapIndexed { i, kind -> { ModelRow(i + 1, kind, state, actions, onRemove) } },
        )
        if (!m.searchModelsReady) {
            // The three search models download in the app; Gemma is the only manual one.
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                KdsInfo("Wi-Fi only", "Downloads wait for Wi-Fi so mobile data isn't used. Turn off to download on mobile data now.",
                    style = Kds.type.caption, color = Kds.colors.foreground, modifier = Modifier.weight(1f))
                KdsFilterChip(if (m.wifiOnly) "On" else "Off", m.wifiOnly, { actions.setWifiOnly(!m.wifiOnly) })
            }
            m.downloadError?.let { KdsCallout("$it What already arrived is kept; tap Download to carry on.", CalloutTone.ERROR) }
            if (m.downloading) KdsButton("Stop downloading", actions.cancelModelDownload, variant = ButtonVariant.GHOST, size = ButtonSize.SM)
            else KdsButton("Download all search models (about 500 MB)", actions.downloadModels, size = ButtonSize.SM, icon = R.drawable.ic_kds_download)
        }
        if (ready < ModelKind.entries.size) {
            KdsInfo(
                "Where's a sign-in needed?",
                "Only for Gemma. Hugging Face asks you to sign in (a free account) and accept Google's Gemma licence on the model's " +
                    "page before it lets you download it. Then import the file here. The search models need no sign-in. " +
                    "Free alternatives to Gemma: describe on a PC (no sign-in), or skip describing: search by look works for every sticker.",
                style = Kds.type.caption, color = Kds.colors.mutedForeground,
            )
            KdsButton("Import files you downloaded", actions.importModels, variant = ButtonVariant.GHOST, size = ButtonSize.SM,
                icon = R.drawable.ic_kds_folder, enabled = m.importing == null)
        }
    }
}

@Composable
private fun ModelRow(step: Int, kind: ModelKind, state: UiState, actions: AppActions, onRemove: (ModelKind) -> Unit) {
    val m = state.models
    val c = Kds.colors
    val ready = m.has(kind)
    val importingThis = m.importing != null && m.importingKind == kind
    val auto = kind != ModelKind.GEMMA
    val downloadingThis = m.downloading && m.downloadKind == kind
    Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        // Step marker: a number while it's still to do, a tick once it's here.
        Box(
            Modifier.size(28.dp).background(if (ready) c.brandSoft else c.secondary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (ready) KdsIcon(R.drawable.ic_kds_check, 14.dp, c.brand)
            else Text("$step", style = Kds.type.chip, color = c.mutedForeground)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KdsInfo(kind.title, kind.purpose + if (ready) " · ${mb(m.present[kind] ?: 0)}" else "",
                    modifier = Modifier.weight(1f, fill = false))
                when {
                    ready -> KdsBadge("Ready", BadgeTone.SOFT)
                    downloadingThis -> {}
                    m.downloading && auto -> KdsBadge("Waiting", BadgeTone.SECONDARY)
                    kind == ModelKind.GEMMA -> KdsBadge("Sign-in needed", BadgeTone.WARNING)
                    else -> KdsBadge("Not downloaded", BadgeTone.SECONDARY)
                }
            }
            val size = if (kind.approxMb >= 1000) "%.1f GB".format(kind.approxMb / 1000.0) else "${kind.approxMb} MB"
            when {
                ready -> KdsButton("Remove", { onRemove(kind) }, variant = ButtonVariant.GHOST, size = ButtonSize.SM)
                downloadingThis -> {
                    Text("Downloading · ${m.downloadText.orEmpty()}", style = Kds.type.caption.copy(fontFeatureSettings = "tnum"), color = c.foreground)
                    KdsProgress(m.downloadFraction)
                }
                auto -> Text("About $size · free, no sign-in · downloads in the app", style = Kds.type.caption, color = c.mutedForeground)
                else -> {
                    Text("File: ${kind.downloadName} · about $size", style = Kds.type.mono, color = c.foreground)
                    Text("Needs a free Hugging Face account: sign in on huggingface.co and accept Google's Gemma licence, " +
                        "then download in your browser and import it here. Describing then runs while charging and, " +
                        "with thousands of stickers, can take several nights.",
                        style = Kds.type.caption, color = c.mutedForeground)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        kind.licenceUrl?.let { url ->
                            KdsButton("Sign in & accept", { actions.openUrl(url) }, variant = ButtonVariant.OUTLINE, size = ButtonSize.SM)
                        }
                        KdsButton("Download", { actions.openUrl(kind.downloadUrl) }, variant = ButtonVariant.OUTLINE, size = ButtonSize.SM,
                            icon = R.drawable.ic_kds_download)
                    }
                    KdsButton(
                        if (importingThis) m.importing ?: "Importing" else "Import", { actions.importModel(kind) },
                        size = ButtonSize.SM, icon = R.drawable.ic_kds_folder,
                        loading = importingThis, enabled = m.importing == null,
                    )
                }
            }
        }
    }
}

/**
 * One place for where your stickers stand: how many are indexed, searchable by look and
 * described, what's running now, and the buttons to scan and to describe. Explanations sit
 * behind dotted labels.
 */
@Composable
private fun StatusPanel(state: UiState, actions: AppActions) {
    val p = state.permissions
    val canScan = p.safHeld || p.allFiles
    val ix = state.index
    val ai = state.ai
    val c = ai.counts
    val pending = state.statusCounts[StickerStatus.PENDING] ?: 0
    KdsPanel {
        Text("Your stickers", style = Kds.type.heading, color = Kds.colors.foreground)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Stat("%,d".format(state.indexedCount), "indexed")
            Stat("%,d".format(c.visual), "by look")
            Stat("%,d".format(c.annotated), "described")
        }
        if (c.total > 0) {
            Spacer(Modifier.height(10.dp))
            KdsProgress(c.annotated.toFloat() / c.total)
        }
        Spacer(Modifier.height(8.dp))
        // One line on what's happening now.
        Text(
            when {
                ix.running -> "${ix.phase ?: "Scanning"}: " + if (ix.total > 0) "%,d of %,d files".format(ix.done, ix.total) else "listing files"
                ai.running -> "${ai.phase ?: "Working"}: %,d of %,d".format(ai.done, ai.total)
                ai.userPaused -> "Describing paused · %,d left".format(pending)
                ai.waitingForCharge -> "%,d left to describe · continues when charging".format(pending)
                pending > 0 && state.models.has(ModelKind.GEMMA) -> "%,d left to describe · runs while charging".format(pending)
                pending > 0 -> "%,d left to describe once Gemma is imported".format(pending)
                state.indexedCount == 0 -> "No scan yet"
                else -> "Everything is described"
            },
            style = Kds.type.caption.copy(fontFeatureSettings = "tnum"), color = Kds.colors.foreground,
        )
        if (ix.running && ix.total > 0) {
            Spacer(Modifier.height(6.dp))
            KdsProgress(ix.done.toFloat() / ix.total)
        }
        val details = buildList {
            if (state.failedCount > 0) add("%,d failed".format(state.failedCount))
            if (state.reviewCount > 0) add("%,d to review".format(state.reviewCount))
            if (state.missingCount > 0) add("%,d missing".format(state.missingCount))
            state.lastRun?.let { add("last scan took %d s".format((it.finishedAt - it.startedAt) / 1000)) }
            ai.gemmaBackend?.let { b -> add("Gemma on the $b" + if (ai.lastAnnotationMs > 0) ", about %.0f s per sticker".format(ai.lastAnnotationMs / 1000.0) else "") }
            if (state.graph.isNotEmpty()) {
                val g = state.graph
                add("%,d story links, %,d look-alikes, %,d shared topics".format(
                    g[app.stickersearch.data.EdgeType.STORY] ?: 0, g[app.stickersearch.data.EdgeType.LOOKS] ?: 0,
                    g[app.stickersearch.data.EdgeType.TOPIC] ?: 0))
            }
        }
        if (details.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            KdsInfo("Details", details.joinToString(" · "), style = Kds.type.caption, color = Kds.colors.mutedForeground)
        }

        // Problems stay visible: they need you.
        ix.error?.let {
            Spacer(Modifier.height(10.dp))
            KdsCallout("$it. Check the folder access above, then scan again.", CalloutTone.ERROR)
        }
        if (ai.describePaused) {
            Spacer(Modifier.height(8.dp))
            KdsCallout("Describing stopped after the app closed ${app.stickersearch.ml.DescribeGuard.PAUSE_AFTER} times in a row " +
                "(last: ${ai.lastCrash ?: "unknown"}). Close other apps, then tap Start now.", CalloutTone.ERROR)
        } else if (ai.lastCrash != null && System.currentTimeMillis() - ai.lastCrashAt < 86_400_000L) {
            Spacer(Modifier.height(8.dp))
            KdsCallout("The app closed while Gemma was working (${ai.lastCrash}). That sticker was skipped.", CalloutTone.WARNING)
        }
        ai.gemmaError?.let {
            Spacer(Modifier.height(8.dp))
            KdsCallout("Gemma couldn't start: $it. Import gemma-3n-E2B-it-int4.task again.", CalloutTone.ERROR)
        }

        if (state.models.has(ModelKind.GEMMA) && !state.phoneCanDescribe) {
            Spacer(Modifier.height(8.dp))
            KdsCallout(
                "This phone has %.1f GB of memory, so describing on it is likely to fail. Use \u201cDescribe on a PC\u201d below, or Share descriptions from another phone. Search by look works here either way."
                    .format(state.memoryGb),
                CalloutTone.NEUTRAL, title = "Describing is off on this phone",
            )
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Try anyway", style = Kds.type.caption, color = Kds.colors.foreground, modifier = Modifier.weight(1f))
                KdsFilterChip(if (state.describeAnyway) "On" else "Off", state.describeAnyway, { actions.setDescribeAnyway(!state.describeAnyway) })
            }
        }
        Spacer(Modifier.height(14.dp))
        val describing = ai.running && ai.phase == app.stickersearch.index.AiWorker.DESCRIBE_PHASE
        val ready = ModelKind.entries.any { state.models.has(it) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KdsButton(
                text = when { ix.running -> "Scanning"; !canScan -> "Pick a folder"; state.indexedCount > 0 -> "Scan"; else -> "Run scan" },
                onClick = { actions.scan(false) }, variant = ButtonVariant.OUTLINE, modifier = Modifier.weight(1f),
                enabled = canScan && !ix.running, loading = ix.running,
                icon = if (state.indexedCount > 0) R.drawable.ic_kds_refresh else R.drawable.ic_kds_search,
            )
            when {
                describing -> KdsButton("Pause", actions.pauseDescribing, variant = ButtonVariant.SECONDARY, modifier = Modifier.weight(1f))
                ai.userPaused -> KdsButton("Resume", actions.runAiNow, modifier = Modifier.weight(1f),
                    enabled = !ai.running && ready, icon = R.drawable.ic_kds_play)
                else -> KdsButton(if (ai.running) "Working" else "Describe now", actions.runAiNow, modifier = Modifier.weight(1f),
                    loading = ai.running, enabled = !ai.running && ready, icon = R.drawable.ic_kds_spark)
            }
        }
        if (!ix.running && state.failedCount + state.reviewCount > 0) {
            KdsButton("Retry %,d with problems".format(state.failedCount + state.reviewCount), { actions.scan(true) },
                variant = ButtonVariant.GHOST, block = true)
        }
        if (!describing && !ai.userPaused && state.models.has(ModelKind.GEMMA) && (ai.waitingForCharge || pending > 0)) {
            KdsButton("Pause describing", actions.pauseDescribing, variant = ButtonVariant.GHOST, block = true)
        }
        Spacer(Modifier.height(6.dp))
        KdsInfo(
            "How this works",
            "Scan finds new and removed stickers. Describing runs while charging; with thousands of stickers it can take several " +
                "nights. Describe now runs it on battery too. Pause stops it until you resume, also from the notification. " +
                "Nothing is redone: described stickers are kept.",
            style = Kds.type.caption, color = Kds.colors.mutedForeground,
        )
    }
}

/** Describe on a PC: export, run the script at home, import the results. */
@Composable
private fun PcPanel(state: UiState, actions: AppActions) {
    val left = (state.statusCounts[StickerStatus.PENDING] ?: 0) + (state.statusCounts[StickerStatus.NEEDS_REVIEW] ?: 0) +
        (state.statusCounts[StickerStatus.FAILED] ?: 0)
    KdsPanel {
        KdsInfo(
            "Faster: describe on a PC",
            ("Export your stickers to a file (%,d aren't described yet), copy it to your computer, extract it and double-click " +
                "the starter inside; it runs a local AI model (Ollama), and the README explains each step. It takes hours instead " +
                "of nights. Then import results.jsonl here. Your stickers only go where you copy them; nothing is uploaded.").format(left),
        )
        Spacer(Modifier.height(10.dp))
        state.pcProgress?.let {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                app.stickersearch.ui.kds.SpinningLoader(14.dp, Kds.colors.mutedForeground)
                Text(it, style = Kds.type.caption.copy(fontFeatureSettings = "tnum"), color = Kds.colors.foreground)
            }
            Spacer(Modifier.height(10.dp))
        }
        KdsButton("Export for PC", actions.exportForPc, variant = ButtonVariant.OUTLINE, block = true,
            icon = R.drawable.ic_kds_share, enabled = state.pcProgress == null && state.indexedCount > 0)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KdsButton("Import results", actions.importPcResults, modifier = Modifier.weight(1f),
                icon = R.drawable.ic_kds_folder, enabled = state.pcProgress == null)
            KdsButton("Share descriptions", actions.exportDescriptions, variant = ButtonVariant.OUTLINE, modifier = Modifier.weight(1f),
                icon = R.drawable.ic_kds_share, enabled = state.pcProgress == null && state.ai.counts.annotated > 0)
        }
        Spacer(Modifier.height(6.dp))
        KdsInfo(
            "What goes in the export?",
            "Every sticker, each marked as not described yet, described by your phone's AI, or described on a PC before. " +
                "The script on your computer asks whether to redo the phone's ones too (a PC model usually does better, and " +
                "its descriptions replace the phone's on import). Your own edits always stay on top. Describing on the phone " +
                "pauses after an export. Share descriptions saves everything described here (and your edits) to a file; on " +
                "another phone, Import results adds them to the same stickers, matched by each file's fingerprint.",
            style = Kds.type.caption, color = Kds.colors.mutedForeground,
        )
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column {
        Text(value, style = Kds.type.stat, color = Kds.colors.foreground)
        Text(label, style = Kds.type.caption, color = Kds.colors.mutedForeground)
    }
}
