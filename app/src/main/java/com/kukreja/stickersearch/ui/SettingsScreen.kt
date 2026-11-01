package com.kukreja.stickersearch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import com.kukreja.stickersearch.ui.kds.KdsIcon
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kukreja.stickersearch.R
import com.kukreja.stickersearch.data.StickerStatus
import com.kukreja.stickersearch.ml.ModelKind
import com.kukreja.stickersearch.share.StickerPacks
import com.kukreja.stickersearch.ui.kds.BadgeTone
import com.kukreja.stickersearch.ui.kds.ButtonSize
import com.kukreja.stickersearch.ui.kds.ButtonVariant
import com.kukreja.stickersearch.ui.kds.CalloutTone
import com.kukreja.stickersearch.ui.kds.KdsAppBar
import com.kukreja.stickersearch.ui.kds.KdsBadge
import com.kukreja.stickersearch.ui.kds.KdsButton
import com.kukreja.stickersearch.ui.kds.KdsCallout
import com.kukreja.stickersearch.ui.kds.KdsDialog
import com.kukreja.stickersearch.ui.kds.KdsPanel
import com.kukreja.stickersearch.ui.kds.KdsProgress
import com.kukreja.stickersearch.ui.kds.KdsSettingsGroup
import com.kukreja.stickersearch.ui.kds.KdsSettingsRow
import com.kukreja.stickersearch.ui.kds.theme.Kds

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
    val pickedStickersOnly = p.safHeld && p.safTreeId?.endsWith("WhatsApp Stickers") == true
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        KdsAppBar("Settings", subtitle = "Everything runs on this phone")
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = BottomClearance), verticalArrangement = Arrangement.spacedBy(20.dp)) {

            // ---- Library ----
            KdsSettingsGroup("Sticker access", listOf(
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
                {
                    KdsSettingsRow(
                        title = "All files access", icon = R.drawable.ic_kds_shield,
                        description = "Optional. Used only when the folder isn't picked.",
                        trailing = {
                            if (p.allFiles) KdsBadge("Granted", BadgeTone.SOFT)
                            else KdsButton("Open settings", actions.openAllFiles, variant = ButtonVariant.OUTLINE, size = ButtonSize.SM)
                        },
                    )
                },
            ))
            if (pickedStickersOnly) {
                KdsCallout("You picked WhatsApp Stickers only, so the stickers in Backup Excluded Stickers are missed. Pick Media instead.",
                    CalloutTone.WARNING, title = "One folder is missing")
            }
            ScanPanel(state, actions)

            // ---- AI ----
            ModelsGroup(state, actions) { kind -> confirm = "model:${kind.name}" }
            AiPanel(state, actions)

            // ---- WhatsApp ----
            val quickReady = state.packs.filter { !it.pack.custom }.all { it.added }
            KdsSettingsGroup("WhatsApp packs", listOf<@Composable () -> Unit>({
                KdsSettingsRow(
                    title = "Quick send", icon = R.drawable.ic_kds_phone,
                    description = if (quickReady) "Set up. Send in WhatsApp goes straight back to your chat."
                        else "Add both Sticker Search packs to WhatsApp once, so Send in WhatsApp never stops at WhatsApp's Add screen.",
                    onClick = if (quickReady) null else actions.setUpQuickSend,
                    trailing = { if (quickReady) KdsBadge("Ready", BadgeTone.SOFT) else KdsButton("Set up", actions.setUpQuickSend, size = ButtonSize.SM) },
                )
            }) + state.packs.map { info ->
                {
                    val pack = info.pack
                    KdsSettingsRow(
                        title = pack.title, icon = if (pack.custom) R.drawable.ic_kds_folder else R.drawable.ic_kds_phone,
                        description = when {
                            info.added -> "In WhatsApp's sticker picker · %d stickers%s".format(info.count, if (pack.custom) "" else ", last sent first")
                            info.count >= StickerPacks.MIN -> "%d stickers ready to add to WhatsApp.".format(info.count)
                            pack.custom -> "%d sticker%s. Add at least %d to send it to WhatsApp.".format(info.count, if (info.count == 1) "" else "s", StickerPacks.MIN)
                            else -> "Appears in WhatsApp after you send your first ${if (pack.animated) "animated " else ""}sticker."
                        },
                        onClick = if (info.count > 0) ({ openPack = pack.id }) else null,
                        trailing = { if (info.added) KdsBadge("Added", BadgeTone.SOFT) },
                    )
                }
            })
            Text("Make your own pack from a search: tap Select above the results, pick up to ${StickerPacks.MAX}, then Create pack.",
                style = Kds.type.caption, color = Kds.colors.mutedForeground, modifier = Modifier.padding(horizontal = 4.dp))

            // ---- Background ----
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
                        title = "Battery optimisation", icon = R.drawable.ic_kds_battery,
                        description = if (state.batteryUnrestricted) "Unrestricted, so overnight runs aren't cut short."
                        else "Allow unrestricted use so overnight runs finish. On Honor phones, also set Sticker Search to " +
                            "“Manage manually” in Settings › Battery › App launch.",
                        trailing = {
                            if (state.batteryUnrestricted) KdsBadge("Allowed", BadgeTone.SOFT)
                            else KdsButton("Allow", actions.openBatterySettings, variant = ButtonVariant.OUTLINE, size = ButtonSize.SM)
                        },
                    )
                },
            ))

            // ---- Data ----
            KdsSettingsGroup("Data", listOf(
                {
                    KdsSettingsRow(
                        title = "Storage used", icon = R.drawable.ic_kds_file,
                        description = "Models ${mb(state.storage.models)} · previews ${mb(state.storage.previews)} · index ${mb(state.storage.database)}",
                    )
                },
                {
                    KdsSettingsRow(
                        title = "Delete index", icon = R.drawable.ic_kds_trash, danger = true,
                        description = "Thumbnails, keyframes, descriptions and your edits. Your WhatsApp stickers and the models stay.",
                        onClick = { confirm = "index" },
                    )
                },
            ))

            // ---- Diagnostics ----
            KdsSettingsGroup("Diagnostics", listOf(
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
private fun ScanPanel(state: UiState, actions: AppActions) {
    val p = state.permissions
    val canScan = p.safHeld || p.allFiles
    val ix = state.index
    KdsPanel {
        when {
            ix.running -> {
                Text(ix.phase ?: "Starting", style = Kds.type.bodyStrong, color = Kds.colors.foreground)
                Spacer(Modifier.height(10.dp))
                KdsProgress(ix.total.takeIf { it > 0 }?.let { ix.done.toFloat() / it })
                Spacer(Modifier.height(6.dp))
                Text(if (ix.total > 0) "%,d of %,d new or changed files".format(ix.done, ix.total) else "Listing files",
                    style = Kds.type.caption.copy(fontFeatureSettings = "tnum"), color = Kds.colors.mutedForeground)
            }
            state.indexedCount > 0 -> {
                Text("%,d stickers indexed".format(state.indexedCount), style = Kds.type.heading, color = Kds.colors.foreground)
                Text(buildList {
                    if (state.failedCount > 0) add("%,d failed".format(state.failedCount))
                    if (state.reviewCount > 0) add("%,d to review".format(state.reviewCount))
                    if (state.missingCount > 0) add("%,d missing".format(state.missingCount))
                    state.lastRun?.let { add("last scan %d s".format((it.finishedAt - it.startedAt) / 1000)) }
                }.joinToString(" · "), style = Kds.type.caption.copy(fontFeatureSettings = "tnum"), color = Kds.colors.mutedForeground)
            }
            else -> Text("No scan yet", style = Kds.type.bodyStrong, color = Kds.colors.foreground)
        }
        ix.error?.let {
            Spacer(Modifier.height(10.dp))
            KdsCallout("$it. Check the folder access above, then scan again.", CalloutTone.ERROR)
        }
        if (!ix.running) {
            Spacer(Modifier.height(14.dp))
            KdsButton(
                text = when { !canScan -> "Pick a folder to start"; state.indexedCount > 0 -> "Scan for changes"; else -> "Run scan" },
                onClick = { actions.scan(false) }, size = ButtonSize.LG, block = true, enabled = canScan,
                icon = if (state.indexedCount > 0) R.drawable.ic_kds_refresh else R.drawable.ic_kds_search,
            )
            if (state.failedCount + state.reviewCount > 0) {
                Spacer(Modifier.height(6.dp))
                KdsButton("Retry %,d with problems".format(state.failedCount + state.reviewCount), { actions.scan(true) },
                    variant = ButtonVariant.GHOST, block = true)
            }
        }
    }
}

@Composable
private fun ModelsGroup(state: UiState, actions: AppActions, onRemove: (ModelKind) -> Unit) {
    val m = state.models
    val ready = m.readyCount
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        KdsSettingsGroup(
            if (ready == ModelKind.entries.size) "AI models · all ready" else "AI models · $ready of ${ModelKind.entries.size} ready",
            ModelKind.entries.mapIndexed { i, kind -> { ModelRow(i + 1, kind, state, actions, onRemove) } },
        )
        if (ready < ModelKind.entries.size) {
            KdsProgress(ready.toFloat() / ModelKind.entries.size)
            Text(
                "For each model: tap Download, which opens your browser. When the file has finished downloading, tap Import " +
                    "next to it and pick the file from Downloads. The app itself never goes online.",
                style = Kds.type.caption, color = Kds.colors.mutedForeground,
            )
            KdsButton("Import several files at once", actions.importModels, variant = ButtonVariant.GHOST, size = ButtonSize.SM,
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
    Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        // Step marker: a number while it's still to do, a tick once imported.
        Box(
            Modifier.size(28.dp).background(if (ready) c.brandSoft else c.secondary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (ready) KdsIcon(R.drawable.ic_kds_check, 14.dp, c.brand)
            else Text("$step", style = Kds.type.chip, color = c.mutedForeground)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(kind.title, style = Kds.type.bodyStrong, color = c.foreground, modifier = Modifier.weight(1f, fill = false))
                if (ready) KdsBadge("Ready", BadgeTone.SOFT) else KdsBadge("Not imported", BadgeTone.SECONDARY)
            }
            Text(kind.purpose, style = Kds.type.caption, color = c.mutedForeground)
            if (ready) {
                Text("Imported · ${mb(m.present[kind] ?: 0)}", style = Kds.type.caption, color = c.mutedForeground)
                KdsButton("Remove", { onRemove(kind) }, variant = ButtonVariant.GHOST, size = ButtonSize.SM)
            } else {
                Text("File: ${kind.downloadName} · about ${if (kind.approxMb >= 1000) "%.1f GB".format(kind.approxMb / 1000.0) else "${kind.approxMb} MB"}",
                    style = Kds.type.mono, color = c.foreground)
                Text(kind.downloadNote, style = Kds.type.caption, color = c.mutedForeground)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KdsButton("Download", { actions.openUrl(kind.downloadUrl) }, variant = ButtonVariant.OUTLINE, size = ButtonSize.SM,
                        icon = R.drawable.ic_kds_share)
                    kind.licenceUrl?.let { url ->
                        KdsButton("Accept licence", { actions.openUrl(url) }, variant = ButtonVariant.GHOST, size = ButtonSize.SM)
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

@Composable
private fun AiPanel(state: UiState, actions: AppActions) {
    val ai = state.ai
    val c = ai.counts
    val pending = state.statusCounts[StickerStatus.PENDING] ?: 0
    KdsPanel {
        Text("Search readiness", style = Kds.type.heading, color = Kds.colors.foreground)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Stat("%,d".format(c.visual), "by look")
            Stat("%,d".format(c.annotated), "described")
            Stat("%,d".format(c.total), "total")
        }
        if (c.total > 0) {
            Spacer(Modifier.height(10.dp))
            KdsProgress(c.annotated.toFloat() / c.total)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                ai.running -> "${ai.phase ?: "Working"}: %,d of %,d".format(ai.done, ai.total)
                ai.waitingForCharge -> "%,d left to describe. Continues when the phone is charging.".format(pending)
                pending > 0 && state.models.has(ModelKind.GEMMA) -> "%,d left to describe. Runs while charging, or start it now.".format(pending)
                pending > 0 -> "%,d left to describe once Gemma is imported.".format(pending)
                else -> "Everything is described."
            },
            style = Kds.type.caption.copy(fontFeatureSettings = "tnum"), color = Kds.colors.mutedForeground,
        )
        if (state.graph.isNotEmpty()) {
            val g = state.graph
            Text(
                "Connections: %,d story links · %,d look-alikes · %,d shared topics".format(
                    g[com.kukreja.stickersearch.data.EdgeType.STORY] ?: 0, g[com.kukreja.stickersearch.data.EdgeType.LOOKS] ?: 0,
                    g[com.kukreja.stickersearch.data.EdgeType.TOPIC] ?: 0,
                ),
                style = Kds.type.caption.copy(fontFeatureSettings = "tnum"), color = Kds.colors.mutedForeground,
            )
        }
        ai.gemmaBackend?.let { b ->
            val per = if (ai.lastAnnotationMs > 0) " · about %.0f s per sticker".format(ai.lastAnnotationMs / 1000.0) else ""
            Text("Gemma runs on the $b$per", style = Kds.type.caption, color = Kds.colors.mutedForeground)
        }
        ai.gemmaError?.let {
            Spacer(Modifier.height(8.dp))
            KdsCallout("Gemma couldn't start: $it. Check that the file is gemma-3n-E2B-it-int4.task, then import it again.", CalloutTone.ERROR)
        }
        Spacer(Modifier.height(12.dp))
        KdsButton(
            if (ai.running) "Working" else "Start now", actions.runAiNow, variant = ButtonVariant.OUTLINE, block = true,
            loading = ai.running, enabled = !ai.running && ModelKind.entries.any { state.models.has(it) },
            icon = R.drawable.ic_kds_spark,
        )
        Text("Starting now runs on battery too; describing all stickers takes a few nights.",
            style = Kds.type.caption, color = Kds.colors.mutedForeground, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column {
        Text(value, style = Kds.type.stat, color = Kds.colors.foreground)
        Text(label, style = Kds.type.caption, color = Kds.colors.mutedForeground)
    }
}
