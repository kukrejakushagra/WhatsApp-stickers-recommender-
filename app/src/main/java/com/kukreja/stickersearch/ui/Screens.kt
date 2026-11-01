package com.kukreja.stickersearch.ui

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.LruCache
import android.widget.ImageView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kukreja.stickersearch.R
import com.kukreja.stickersearch.diagnostics.ScanSummary
import com.kukreja.stickersearch.discovery.SafTreeSource
import com.kukreja.stickersearch.model.SourceId
import com.kukreja.stickersearch.model.StickerFormat
import com.kukreja.stickersearch.model.UniqueSticker
import com.kukreja.stickersearch.share.StickerSharer
import com.kukreja.stickersearch.ui.kds.BadgeTone
import com.kukreja.stickersearch.ui.kds.ButtonSize
import com.kukreja.stickersearch.ui.kds.ButtonVariant
import com.kukreja.stickersearch.ui.kds.CalloutTone
import com.kukreja.stickersearch.ui.kds.KdsAppBar
import com.kukreja.stickersearch.ui.kds.KdsBadge
import com.kukreja.stickersearch.ui.kds.KdsBottomNav
import com.kukreja.stickersearch.ui.kds.KdsButton
import com.kukreja.stickersearch.ui.kds.KdsCallout
import com.kukreja.stickersearch.ui.kds.KdsDialog
import com.kukreja.stickersearch.ui.kds.KdsEmptyState
import com.kukreja.stickersearch.ui.kds.KdsFilterChip
import com.kukreja.stickersearch.ui.kds.KdsGround
import com.kukreja.stickersearch.ui.kds.KdsIcon
import com.kukreja.stickersearch.ui.kds.KdsKeyValue
import com.kukreja.stickersearch.ui.kds.KdsMediaTile
import com.kukreja.stickersearch.ui.kds.KdsPanel
import com.kukreja.stickersearch.ui.kds.KdsProgress
import com.kukreja.stickersearch.ui.kds.KdsSettingsGroup
import com.kukreja.stickersearch.ui.kds.KdsSettingsRow
import com.kukreja.stickersearch.ui.kds.KdsSheet
import com.kukreja.stickersearch.ui.kds.KdsStatCard
import com.kukreja.stickersearch.ui.kds.KdsToastHost
import com.kukreja.stickersearch.ui.kds.NavItem
import com.kukreja.stickersearch.ui.kds.theme.Kds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Everything the screens can ask for; keeps the screens stateless and previewable. */
data class AppActions(
    val pickFolder: () -> Unit = {},
    val openAllFiles: () -> Unit = {},
    val scan: () -> Unit = {},
    val clearAll: () -> Unit = {},
    val copyReport: () -> Unit = {},
    val shareReport: () -> Unit = {},
    val send: (UniqueSticker, StickerSharer.Mode) -> Unit = { _, _ -> },
    val messageShown: () -> Unit = {},
    val reportJson: () -> String = { "{}" },
)

@Composable
fun MainScreen(vm: MainViewModel, openAllFilesSettings: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { vm.onTreePicked(it) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshPermissions() }
    val actions = remember(vm) {
        AppActions(
            pickFolder = { treePicker.launch(SafTreeSource.initialStickerFolderUri()) },
            openAllFiles = openAllFilesSettings,
            scan = vm::scan,
            clearAll = vm::clearAll,
            copyReport = vm::copyReport,
            shareReport = { vm.shareReport(context) },
            send = { s, m -> vm.send(context, s, m) },
            messageShown = vm::consumeMessage,
            reportJson = vm::reportJson,
        )
    }
    AppShell(state, actions)
}

private enum class Tab { SETUP, STICKERS, REPORT }

@Composable
fun AppShell(state: UiState, actions: AppActions, initialTab: Int = 0) {
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    KdsGround(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().statusBarsPadding()) {
            when (Tab.entries[tab]) {
                Tab.SETUP -> SetupScreen(state, actions) { tab = Tab.STICKERS.ordinal }
                Tab.STICKERS -> StickersScreen(state, actions) { tab = Tab.SETUP.ordinal }
                Tab.REPORT -> ReportScreen(state, actions)
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            KdsToastHost(state.message, actions.messageShown, Modifier.padding(bottom = 12.dp))
            KdsBottomNav(
                items = listOf(
                    NavItem("Setup", R.drawable.ic_kds_sliders),
                    NavItem("Stickers", R.drawable.ic_kds_grid),
                    NavItem("Report", R.drawable.ic_kds_file),
                ),
                selected = tab,
                onSelect = { tab = it },
                modifier = Modifier.navigationBarsPadding(),
            )
        }
    }
}

private val BottomClearance = 104.dp

// ---------------- Setup ----------------

@Composable
private fun SetupScreen(state: UiState, actions: AppActions, onScanned: () -> Unit) {
    val p = state.permissions
    var confirmDelete by remember { mutableStateOf(false) }
    val pickedStickersOnly = p.safHeld && p.safTreeId?.endsWith("WhatsApp Stickers") == true
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        KdsAppBar("Sticker access", subtitle = "Diagnostics, step 1")
        Column(
            Modifier.padding(horizontal = 16.dp).padding(bottom = BottomClearance),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            KdsCallout(
                "Pick WhatsApp's Media folder, run a scan, then copy the report. Nothing leaves your phone unless you share it.",
                CalloutTone.NEUTRAL,
            )
            KdsSettingsGroup("Access", listOf(
                {
                    KdsSettingsRow(
                        title = "WhatsApp media folder",
                        icon = R.drawable.ic_kds_folder,
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
                        title = "All files access",
                        icon = R.drawable.ic_kds_shield,
                        description = "Optional. Finds sticker folders the picker misses.",
                        trailing = {
                            if (p.allFiles) KdsBadge("Granted", BadgeTone.SOFT)
                            else KdsButton("Open settings", actions.openAllFiles, variant = ButtonVariant.OUTLINE, size = ButtonSize.SM)
                        },
                    )
                },
            ))
            if (pickedStickersOnly) {
                KdsCallout(
                    "You picked WhatsApp Stickers only, so 125 or so stickers in Backup Excluded Stickers are missed. Pick Media instead.",
                    CalloutTone.WARNING, title = "One folder is missing",
                )
            }
            KdsSettingsGroup("Manual test", listOf {
                KdsSettingsRow(
                    title = "Share from WhatsApp",
                    icon = R.drawable.ic_kds_share,
                    description = "Long-press a sticker in a chat and share it here. ${state.eventCount} events logged.",
                )
            })
            ScanPanel(state, actions, onScanned)
            KdsSettingsGroup("Data", listOf {
                KdsSettingsRow(
                    title = "Delete scan data",
                    icon = R.drawable.ic_kds_trash,
                    description = "Thumbnails, imports and logs. Your WhatsApp stickers stay untouched.",
                    danger = true,
                    onClick = { confirmDelete = true },
                )
            })
        }
    }
    if (confirmDelete) {
        KdsDialog(
            title = "Delete scan data?",
            description = "Thumbnails, imported stickers and logs are removed. Your WhatsApp stickers stay untouched.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = { confirmDelete = false; actions.clearAll() },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun ScanPanel(state: UiState, actions: AppActions, onScanned: () -> Unit) {
    val p = state.permissions
    val canScan = p.safHeld || p.allFiles
    KdsPanel {
        val summary = state.outcome?.summary
        when {
            state.scanning -> {
                val prog = state.progress
                Text(prog?.phase ?: "Starting", style = Kds.type.bodyStrong, color = Kds.colors.foreground)
                Spacer(Modifier.height(10.dp))
                KdsProgress(prog?.takeIf { it.total > 0 }?.let { it.done.toFloat() / it.total })
                Spacer(Modifier.height(6.dp))
                Text(
                    prog?.let { "%,d of %,d".format(it.done, it.total) } ?: "Listing files",
                    style = Kds.type.caption.copy(fontFeatureSettings = "tnum"), color = Kds.colors.mutedForeground,
                )
            }
            summary != null -> {
                Text("%,d unique stickers".format(summary.uniqueCount), style = Kds.type.heading, color = Kds.colors.foreground)
                Text(
                    "%,d animated · %,d Lottie · %d s".format(
                        summary.animatedCount, summary.lottieCount, (state.outcome.durationMs / 1000),
                    ),
                    style = Kds.type.caption.copy(fontFeatureSettings = "tnum"), color = Kds.colors.mutedForeground,
                )
            }
            else -> Text("No scan yet", style = Kds.type.bodyStrong, color = Kds.colors.foreground)
        }
        if (!state.scanning) {
            Spacer(Modifier.height(14.dp))
            KdsButton(
                text = when { !canScan -> "Pick a folder to start"; summary != null -> "Scan again"; else -> "Run scan" },
                onClick = { actions.scan(); onScanned() },
                size = ButtonSize.XL, block = true, enabled = canScan,
                icon = if (summary != null) R.drawable.ic_kds_refresh else R.drawable.ic_kds_search,
            )
        }
    }
}

// ---------------- Stickers ----------------

private enum class Filter { ALL, ANIMATED, LOTTIE }

@Composable
private fun StickersScreen(state: UiState, actions: AppActions, goToSetup: () -> Unit) {
    val summary = state.outcome?.summary
    var source by rememberSaveable { mutableStateOf<SourceId?>(null) }
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    var selected by remember { mutableStateOf<UniqueSticker?>(null) }

    if (summary == null) {
        Column(Modifier.fillMaxSize()) {
            KdsAppBar("Stickers")
            Box(Modifier.fillMaxSize().padding(bottom = BottomClearance), contentAlignment = Alignment.Center) {
                if (state.scanning) {
                    KdsEmptyState(R.drawable.ic_kds_loader, "Scanning",
                        state.progress?.let { "%,d of %,d files".format(it.done, it.total) } ?: "Listing files")
                } else {
                    KdsEmptyState(
                        R.drawable.ic_kds_grid, "No stickers yet",
                        "Pick WhatsApp's Media folder on Setup, then run a scan.",
                        actionLabel = "Go to setup", onAction = goToSetup,
                    )
                }
            }
        }
        return
    }
    val items = remember(summary, source, filter) {
        summary.unique.filter { s ->
            (source == null || source in s.sources) && when (filter) {
                Filter.ALL -> true
                Filter.ANIMATED -> s.format.isAnimated && s.format.format != StickerFormat.LOTTIE_WAS
                Filter.LOTTIE -> s.format.format == StickerFormat.LOTTIE_WAS
            }
        }
    }
    LazyVerticalGrid(
        GridCells.Fixed(3), Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = BottomClearance),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                KdsAppBar("Stickers", subtitle = "Newest first", inset = 4.dp)
                StatRow(summary)
                Spacer(Modifier.height(16.dp))
                ChipRow(summary, source, filter, { source = it }, { filter = it })
                Spacer(Modifier.height(10.dp))
                Text("Showing %,d".format(items.size), style = Kds.type.caption, color = Kds.colors.mutedForeground)
                Spacer(Modifier.height(4.dp))
            }
        }
        items(items, key = { it.sha256 }) { s ->
            KdsMediaTile(
                onClick = { selected = s },
                label = when {
                    s.format.format == StickerFormat.LOTTIE_WAS -> "LOTTIE"
                    s.format.isAnimated -> "ANIM"
                    else -> null
                },
            ) { Thumb(s.thumbnailPath, Modifier.fillMaxSize()) }
        }
    }
    selected?.let { StickerSheet(it, actions) { selected = null } }
}

@Composable
private fun StatRow(summary: ScanSummary) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KdsStatCard("%,d".format(summary.uniqueCount), "Unique", Modifier.weight(1f))
        KdsStatCard("%,d".format(summary.animatedCount), "Animated", Modifier.weight(1f))
        val problems = summary.failures.count { isStickerProblem(it.format?.format) }
        KdsStatCard(
            "%,d".format(problems), "Problems", Modifier.weight(1f),
            valueColor = if (problems > 0) Kds.colors.destructiveText else Kds.colors.foreground,
        )
    }
}

/** Unknown files (e.g. encrypted backups) and not-yet-rendered Lottie are not problems with stickers. */
private fun isStickerProblem(f: StickerFormat?) = f != null && f != StickerFormat.UNKNOWN && f != StickerFormat.LOTTIE_WAS

@Composable
private fun ChipRow(
    summary: ScanSummary,
    source: SourceId?,
    filter: Filter,
    onSource: (SourceId?) -> Unit,
    onFilter: (Filter) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        KdsFilterChip("All", source == null && filter == Filter.ALL, { onSource(null); onFilter(Filter.ALL) }, summary.uniqueCount)
        KdsFilterChip("Animated", filter == Filter.ANIMATED, { onFilter(if (filter == Filter.ANIMATED) Filter.ALL else Filter.ANIMATED) },
            summary.animatedCount - summary.lottieCount)
        if (summary.lottieCount > 0) {
            KdsFilterChip("Lottie", filter == Filter.LOTTIE, { onFilter(if (filter == Filter.LOTTIE) Filter.ALL else Filter.LOTTIE) },
                summary.lottieCount)
        }
        Box(Modifier.width(1.dp).height(20.dp).background(Kds.colors.border))
        summary.perSource.values.filter { it.uniqueHashes > 0 }.forEach { s ->
            KdsFilterChip(sourceLabel(s.source), source == s.source,
                { onSource(if (source == s.source) null else s.source) }, s.uniqueHashes)
        }
    }
}

private fun sourceLabel(s: SourceId) = when (s) {
    SourceId.SAF_TREE -> "Folder"
    SourceId.FILE_SYSTEM -> "All files"
    SourceId.SHARE_IN -> "Shared"
}

private val thumbCache = LruCache<String, ImageBitmap>(400)

@Composable
private fun Thumb(path: String?, modifier: Modifier) {
    val bmp by produceState<ImageBitmap?>(path?.let { thumbCache.get(it) }, path) {
        if (path != null && value == null) {
            value = withContext(Dispatchers.IO) {
                BitmapFactory.decodeFile(path)?.asImageBitmap()?.also { thumbCache.put(path, it) }
            }
        }
    }
    val b = bmp
    if (b != null) Image(b, null, modifier)
    else Box(modifier, contentAlignment = Alignment.Center) { KdsIcon(R.drawable.ic_kds_image, 20.dp) }
}

private fun formatName(s: UniqueSticker) = when (s.format.format) {
    StickerFormat.WEBP_ANIMATED -> "Animated sticker"
    StickerFormat.WEBP_STATIC -> "Sticker"
    StickerFormat.LOTTIE_WAS -> "Lottie sticker"
    StickerFormat.GIF -> "GIF"
    else -> s.format.format.name.lowercase().replaceFirstChar { it.uppercase() }
}

@Composable
private fun StickerSheet(s: UniqueSticker, actions: AppActions, onDismiss: () -> Unit) {
    val dims = if (s.format.width > 0) "${s.format.width}×${s.format.height} · " else ""
    val frames = if (s.format.frameCount > 1) "${s.format.frameCount} frames · " else ""
    KdsSheet(
        title = formatName(s),
        subtitle = "$dims$frames${"%,d".format(s.sizeBytes / 1024)} KB",
        onDismiss = onDismiss,
        footer = {
            Text(
                "WhatsApp receives a shared sticker as a photo for now. Sending it as a real sticker comes in a later build.",
                style = Kds.type.caption, color = Kds.colors.mutedForeground,
            )
            KdsButton("Send to WhatsApp", { actions.send(s, StickerSharer.Mode.WHATSAPP_DIRECT) },
                size = ButtonSize.LG, block = true, icon = R.drawable.ic_kds_phone)
            KdsButton("Share…", { actions.send(s, StickerSharer.Mode.SYSTEM_SHEET) },
                variant = ButtonVariant.OUTLINE, size = ButtonSize.LG, block = true, icon = R.drawable.ic_kds_share)
        },
    ) {
        val shape = RoundedCornerShape(18.dp)
        Box(
            Modifier.fillMaxWidth().height(240.dp).clip(shape).background(Kds.colors.glassClear),
            contentAlignment = Alignment.Center,
        ) {
            if (s.format.format == StickerFormat.LOTTIE_WAS) {
                KdsEmptyState(R.drawable.ic_kds_spark, "Preview not ready", "Lottie stickers play in the next build.")
            } else {
                AnimatedPreview(Uri.parse(s.primary.uri), Modifier.fillMaxSize().padding(12.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        KdsKeyValue("Hash", s.sha256.take(16) + "…", mono = true)
        if (s.format.isAnimated && s.format.format != StickerFormat.LOTTIE_WAS) {
            KdsKeyValue("Playback", if (s.animatedDecodeOk == false) "Didn't decode as animation" else "Plays natively")
        }
        KdsKeyValue("Found", if (s.occurrences.size == 1) "1 place" else "${s.occurrences.size} places")
        s.occurrences.forEach { KdsKeyValue(sourceLabel(it.source), it.displayPath.substringAfterLast("Media/"), mono = true) }
    }
}

/** Plays the original file; animated WebP stays animated (AnimatedImageDrawable). */
@Composable
private fun AnimatedPreview(uri: Uri, modifier: Modifier) {
    val context = LocalContext.current
    val drawable by produceState<Drawable?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.contentResolver, uri)) }.getOrNull()
        }
    }
    val d = drawable ?: return Box(modifier, contentAlignment = Alignment.Center) {
        Text("Loading", style = Kds.type.caption, color = Kds.colors.mutedForeground)
    }
    AndroidView(
        factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
        modifier = modifier,
        update = { iv -> iv.setImageDrawable(d); (d as? AnimatedImageDrawable)?.start() },
    )
}

// ---------------- Report ----------------

@Composable
private fun ReportScreen(state: UiState, actions: AppActions) {
    val json = remember(state.outcome, state.eventCount, state.permissions) { actions.reportJson() }
    Column(Modifier.fillMaxSize()) {
        KdsAppBar("Report", subtitle = state.outcome?.let { "%,d unique stickers".format(it.summary.uniqueCount) } ?: "No scan yet")
        Column(Modifier.padding(horizontal = 16.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            KdsCallout("Counts, formats and file names only. No images, no chats.", CalloutTone.NEUTRAL)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KdsButton("Copy report", actions.copyReport, icon = R.drawable.ic_kds_copy)
                KdsButton("Share report", actions.shareReport, variant = ButtonVariant.OUTLINE, icon = R.drawable.ic_kds_share)
            }
            KdsPanel(Modifier.weight(1f).padding(bottom = BottomClearance), padding = false) {
                Text(
                    json, style = Kds.type.mono, color = Kds.colors.foreground,
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
                )
            }
        }
    }
}
