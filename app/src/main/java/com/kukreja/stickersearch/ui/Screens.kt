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
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieDrawable
import com.kukreja.stickersearch.R
import com.kukreja.stickersearch.data.StickerRow
import com.kukreja.stickersearch.data.StickerStatus
import com.kukreja.stickersearch.discovery.SafTreeSource
import com.kukreja.stickersearch.index.WasRenderer
import com.kukreja.stickersearch.model.StickerFormat
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
    val scan: (retryFailed: Boolean) -> Unit = {},
    val runDiagnostic: () -> Unit = {},
    val clearAll: () -> Unit = {},
    val copyReport: () -> Unit = {},
    val shareReport: () -> Unit = {},
    val send: (StickerRow, StickerSharer.Mode) -> Unit = { _, _ -> },
    val messageShown: () -> Unit = {},
    val reportJson: suspend () -> String = { "{}" },
    val details: suspend (Long) -> StickerDetails = { StickerDetails(emptyList(), emptyList()) },
)

@Composable
fun MainScreen(vm: MainViewModel, openAllFilesSettings: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { vm.onTreePicked(it) }
    // Asked once, on the first scan: lets the long-scan progress notification show. Denying is fine.
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshPermissions() }
    val actions = remember(vm) {
        AppActions(
            pickFolder = { treePicker.launch(SafTreeSource.initialStickerFolderUri()) },
            openAllFiles = openAllFilesSettings,
            scan = { retry ->
                if (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                vm.scan(retry)
            },
            runDiagnostic = vm::runDiagnostic,
            clearAll = vm::clearAll,
            copyReport = vm::copyReport,
            shareReport = { vm.shareReport(context) },
            send = { s, m -> vm.send(context, s, m) },
            messageShown = vm::consumeMessage,
            reportJson = vm::reportJson,
            details = vm::details,
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
                    NavItem("Stickers", R.drawable.ic_kds_grid, count = state.index.total.takeIf { state.index.running && it > 0 }
                        ?.let { it - state.index.done }),
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
        KdsAppBar("Sticker library", subtitle = "Index your WhatsApp stickers")
        Column(
            Modifier.padding(horizontal = 16.dp).padding(bottom = BottomClearance),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (!p.safHeld) {
                KdsCallout(
                    "Pick WhatsApp's Media folder, then run a scan. Everything stays on your phone.",
                    CalloutTone.NEUTRAL,
                )
            }
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
                        description = "Optional. Used only when the folder isn't picked.",
                        trailing = {
                            if (p.allFiles) KdsBadge("Granted", BadgeTone.SOFT)
                            else KdsButton("Open settings", actions.openAllFiles, variant = ButtonVariant.OUTLINE, size = ButtonSize.SM)
                        },
                    )
                },
            ))
            if (pickedStickersOnly) {
                KdsCallout(
                    "You picked WhatsApp Stickers only, so the stickers in Backup Excluded Stickers are missed. Pick Media instead.",
                    CalloutTone.WARNING, title = "One folder is missing",
                )
            }
            ScanPanel(state, actions, onScanned)
            KdsSettingsGroup("Manual test", listOf {
                KdsSettingsRow(
                    title = "Share from WhatsApp",
                    icon = R.drawable.ic_kds_share,
                    description = "Long-press a sticker in a chat and share it here. ${state.eventCount} events logged.",
                )
            })
            KdsSettingsGroup("Data", listOf {
                KdsSettingsRow(
                    title = "Delete index",
                    icon = R.drawable.ic_kds_trash,
                    description = "Thumbnails, keyframes, imports and logs. Your WhatsApp stickers stay untouched.",
                    danger = true,
                    onClick = { confirmDelete = true },
                )
            })
        }
    }
    if (confirmDelete) {
        KdsDialog(
            title = "Delete the index?",
            description = "Thumbnails, keyframes, imported stickers and logs are removed. Your WhatsApp stickers stay untouched, and a new scan rebuilds everything.",
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
    val ix = state.index
    KdsPanel {
        when {
            ix.running -> {
                Text(ix.phase ?: "Starting", style = Kds.type.bodyStrong, color = Kds.colors.foreground)
                Spacer(Modifier.height(10.dp))
                KdsProgress(ix.total.takeIf { it > 0 }?.let { ix.done.toFloat() / it })
                Spacer(Modifier.height(6.dp))
                Text(
                    if (ix.total > 0) "%,d of %,d new or changed files".format(ix.done, ix.total) else "Listing files",
                    style = Kds.type.caption.copy(fontFeatureSettings = "tnum"), color = Kds.colors.mutedForeground,
                )
            }
            state.indexedCount > 0 -> {
                Text("%,d stickers indexed".format(state.indexedCount), style = Kds.type.heading, color = Kds.colors.foreground)
                Text(statusLine(state), style = Kds.type.caption.copy(fontFeatureSettings = "tnum"), color = Kds.colors.mutedForeground)
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
                onClick = { actions.scan(false); onScanned() },
                size = ButtonSize.XL, block = true, enabled = canScan,
                icon = if (state.indexedCount > 0) R.drawable.ic_kds_refresh else R.drawable.ic_kds_search,
            )
            if (state.failedCount > 0) {
                Spacer(Modifier.height(8.dp))
                KdsButton("Retry %,d failed".format(state.failedCount), { actions.scan(true) },
                    variant = ButtonVariant.GHOST, block = true)
            }
        }
    }
}

private fun statusLine(state: UiState): String = buildList {
    add("%,d ready".format(state.statusCounts[StickerStatus.PENDING] ?: 0))
    if (state.failedCount > 0) add("%,d failed".format(state.failedCount))
    if (state.reviewCount > 0) add("%,d to review".format(state.reviewCount))
    if (state.missingCount > 0) add("%,d missing".format(state.missingCount))
    state.lastRun?.let { add("last scan %d s".format((it.finishedAt - it.startedAt) / 1000)) }
}.joinToString(" · ")

// ---------------- Stickers ----------------

private enum class Filter { ALL, ANIMATED, LOTTIE, ATTENTION }

private fun StickerRow.isLottie() = format == StickerFormat.LOTTIE_WAS.name
private fun StickerRow.needsAttention() = status == StickerStatus.FAILED || status == StickerStatus.NEEDS_REVIEW

@Composable
private fun StickersScreen(state: UiState, actions: AppActions, goToSetup: () -> Unit) {
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    var selected by remember { mutableStateOf<StickerRow?>(null) }
    val all = state.stickers

    if (all.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            KdsAppBar("Stickers")
            Box(Modifier.fillMaxSize().padding(bottom = BottomClearance), contentAlignment = Alignment.Center) {
                when {
                    state.index.running -> KdsEmptyState(R.drawable.ic_kds_loader, "Indexing",
                        if (state.index.total > 0) "%,d of %,d files".format(state.index.done, state.index.total) else "Listing files")
                    state.loaded -> KdsEmptyState(
                        R.drawable.ic_kds_grid, "No stickers yet",
                        "Pick WhatsApp's Media folder on Setup, then run a scan.",
                        actionLabel = "Go to setup", onAction = goToSetup,
                    )
                }
            }
        }
        return
    }
    val counts = remember(all) {
        mapOf(
            Filter.ALL to all.size,
            Filter.ANIMATED to all.count { it.isAnimated && !it.isLottie() },
            Filter.LOTTIE to all.count { it.isLottie() },
            Filter.ATTENTION to all.count { it.needsAttention() },
        )
    }
    val items = remember(all, filter) {
        all.filter {
            when (filter) {
                Filter.ALL -> true
                Filter.ANIMATED -> it.isAnimated && !it.isLottie()
                Filter.LOTTIE -> it.isLottie()
                Filter.ATTENTION -> it.needsAttention()
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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KdsStatCard("%,d".format(all.size), "Stickers", Modifier.weight(1f))
                    KdsStatCard("%,d".format(counts.getValue(Filter.ANIMATED) + counts.getValue(Filter.LOTTIE)), "Animated", Modifier.weight(1f))
                    val attention = counts.getValue(Filter.ATTENTION)
                    KdsStatCard("%,d".format(attention), "Need a look", Modifier.weight(1f),
                        valueColor = if (attention > 0) Kds.colors.warning else Kds.colors.foreground)
                }
                if (state.index.running) {
                    Spacer(Modifier.height(12.dp))
                    KdsProgress(state.index.total.takeIf { it > 0 }?.let { state.index.done.toFloat() / it })
                    Spacer(Modifier.height(4.dp))
                    Text("Indexing %,d of %,d".format(state.index.done, state.index.total),
                        style = Kds.type.caption.copy(fontFeatureSettings = "tnum"), color = Kds.colors.mutedForeground)
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    listOf(Filter.ALL to "All", Filter.ANIMATED to "Animated", Filter.LOTTIE to "Lottie", Filter.ATTENTION to "Need a look")
                        .filter { (f, _) -> f == Filter.ALL || f == filter || counts.getValue(f) > 0 }
                        .forEach { (f, label) ->
                            KdsFilterChip(label, filter == f, { filter = if (filter == f) Filter.ALL else f }, counts.getValue(f))
                        }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
        items(items, key = { it.id }) { s ->
            KdsMediaTile(
                onClick = { selected = s },
                label = when {
                    s.status == StickerStatus.FAILED -> "CHECK"
                    s.isLottie() -> "LOTTIE"
                    s.status == StickerStatus.NEEDS_REVIEW -> "REVIEW"
                    s.isAnimated -> "ANIM"
                    else -> null
                },
            ) {
                Thumb(s.thumbnailPath, Modifier.fillMaxSize(),
                    placeholder = if (s.isLottie()) R.drawable.ic_kds_spark else R.drawable.ic_kds_image)
            }
        }
    }
    selected?.let { StickerSheet(it, actions) { selected = null } }
}

private val thumbCache = LruCache<String, ImageBitmap>(400)

@Composable
private fun Thumb(path: String?, modifier: Modifier, placeholder: Int = R.drawable.ic_kds_image) {
    val bmp by produceState<ImageBitmap?>(path?.let { thumbCache.get(it) }, path) {
        if (path != null && value == null) {
            value = withContext(Dispatchers.IO) {
                BitmapFactory.decodeFile(path)?.asImageBitmap()?.also { thumbCache.put(path, it) }
            }
        }
    }
    val b = bmp
    if (b != null) Image(b, null, modifier)
    else Box(modifier, contentAlignment = Alignment.Center) { KdsIcon(placeholder, 20.dp) }
}

private fun formatName(s: StickerRow) = when (s.formatEnum()) {
    StickerFormat.WEBP_ANIMATED -> "Animated sticker"
    StickerFormat.WEBP_STATIC -> "Sticker"
    StickerFormat.LOTTIE_WAS -> "Lottie sticker"
    StickerFormat.GIF -> "GIF"
    else -> s.format.lowercase().replaceFirstChar { it.uppercase() }
}

@Composable
private fun StickerSheet(s: StickerRow, actions: AppActions, onDismiss: () -> Unit) {
    val details by produceState<StickerDetails?>(null, s.id) { value = actions.details(s.id) }
    val dims = if (s.width > 0) "${s.width}×${s.height} · " else ""
    val frames = if (s.frameCount > 1 && !s.isLottie()) "${s.frameCount} frames · " else ""
    val dur = s.durationMs?.takeIf { it > 0 }?.let { "%.1f s · ".format(it / 1000.0) } ?: ""
    KdsSheet(
        title = formatName(s),
        subtitle = "$dims$frames$dur${"%,d".format(s.sizeBytes / 1024)} KB",
        onDismiss = onDismiss,
        footer = {
            Text(
                "WhatsApp receives a shared sticker as a photo for now. Sending it as a real sticker comes in a later build.",
                style = Kds.type.caption, color = Kds.colors.mutedForeground,
            )
            KdsButton("Send to WhatsApp", { actions.send(s, StickerSharer.Mode.WHATSAPP_DIRECT) },
                size = ButtonSize.LG, block = true, icon = R.drawable.ic_kds_phone, enabled = s.primaryUri != null && !s.isLottie())
            KdsButton("Share…", { actions.send(s, StickerSharer.Mode.SYSTEM_SHEET) },
                variant = ButtonVariant.OUTLINE, size = ButtonSize.LG, block = true, icon = R.drawable.ic_kds_share,
                enabled = s.primaryUri != null)
        },
    ) {
        Box(
            Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(18.dp)).background(Kds.colors.glassClear),
            contentAlignment = Alignment.Center,
        ) {
            val uri = s.primaryUri?.let(Uri::parse)
            when {
                uri == null -> KdsEmptyState(R.drawable.ic_kds_image, "File missing", "Run a scan to find it again.")
                s.isLottie() -> LottiePreview(uri, Modifier.fillMaxSize().padding(12.dp))
                else -> AnimatedPreview(uri, Modifier.fillMaxSize().padding(12.dp))
            }
        }
        s.statusError?.let {
            Spacer(Modifier.height(10.dp))
            KdsCallout(
                if (s.isLottie()) "This Lottie file has no animation we can read yet: $it" else it,
                if (s.status == StickerStatus.FAILED) CalloutTone.ERROR else CalloutTone.WARNING,
            )
            Spacer(Modifier.height(8.dp))
            KdsButton("Try again", { actions.scan(true) }, variant = ButtonVariant.OUTLINE, size = ButtonSize.SM,
                icon = R.drawable.ic_kds_refresh)
        }
        val fr = details?.frames.orEmpty()
        if (fr.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("KEYFRAMES", style = Kds.type.overline, color = Kds.colors.mutedForeground)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                fr.forEach { f ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)).background(Kds.colors.glassClear)
                                .border(1.dp, Kds.colors.glassBorder, RoundedCornerShape(8.dp)).padding(4.dp),
                        ) { Thumb(f.framePath, Modifier.fillMaxSize()) }
                        Text("%.1f s".format(f.timestampMs / 1000.0), style = Kds.type.labelXs.copy(fontFeatureSettings = "tnum"),
                            color = Kds.colors.mutedForeground)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        KdsKeyValue("Hash", s.sha256.take(16) + "…", mono = true)
        KdsKeyValue("Status", s.status.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() })
        details?.paths?.forEach { KdsKeyValue("File", it.substringAfterLast("Media/"), mono = true) }
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

/** Plays a WhatsApp .was in a loop, loaded the same way indexing loads it. */
@Composable
private fun LottiePreview(uri: Uri, modifier: Modifier) {
    val context = LocalContext.current
    val result by produceState<WasRenderer.Result?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("file couldn't be opened")
                WasRenderer.load(context, bytes)
            }.getOrElse { WasRenderer.Result.Failed("${it.javaClass.simpleName}: ${it.message}") }
        }
    }
    val c = when (val r = result) {
        null -> return Box(modifier, contentAlignment = Alignment.Center) {
            Text("Loading", style = Kds.type.caption, color = Kds.colors.mutedForeground)
        }
        is WasRenderer.Result.Failed -> return KdsEmptyState(R.drawable.ic_kds_spark, "Can't play this one yet",
            "The details below say why.", modifier)
        is WasRenderer.Result.Loaded -> r.composition
    }
    AndroidView(
        factory = { LottieAnimationView(it).apply { repeatCount = LottieDrawable.INFINITE } },
        modifier = modifier,
        update = { v -> v.setComposition(c); v.playAnimation() },
    )
}

// ---------------- Report ----------------

@Composable
private fun ReportScreen(state: UiState, actions: AppActions) {
    val json by produceState("{}", state.diagnostic, state.eventCount, state.indexedCount, state.lastRun) { value = actions.reportJson() }
    Column(Modifier.fillMaxSize()) {
        KdsAppBar("Report", subtitle = "%,d stickers indexed".format(state.indexedCount))
        Column(Modifier.padding(horizontal = 16.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            KdsCallout("Counts, formats and file names only. No images, no chats.", CalloutTone.NEUTRAL)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KdsButton("Copy report", actions.copyReport, icon = R.drawable.ic_kds_copy)
                KdsButton("Share", actions.shareReport, variant = ButtonVariant.OUTLINE, icon = R.drawable.ic_kds_share)
            }
            KdsButton(
                if (state.diagnosticRunning) "Running diagnostic" else "Run full diagnostic",
                actions.runDiagnostic, variant = ButtonVariant.GHOST, loading = state.diagnosticRunning,
                icon = R.drawable.ic_kds_search,
            )
            KdsPanel(Modifier.weight(1f).padding(bottom = BottomClearance), padding = false) {
                Text(
                    json, style = Kds.type.mono, color = Kds.colors.foreground,
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
                )
            }
        }
    }
}
