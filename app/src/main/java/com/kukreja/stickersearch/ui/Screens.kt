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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kukreja.stickersearch.discovery.SafTreeSource
import com.kukreja.stickersearch.model.SourceId
import com.kukreja.stickersearch.model.UniqueSticker
import com.kukreja.stickersearch.share.StickerSharer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(), content = content)
}

private enum class Tab(val label: String) { SETUP("Setup"), STICKERS("Stickers"), REPORT("Report") }

@Composable
fun MainScreen(vm: MainViewModel, openAllFilesSettings: () -> Unit, mediaPermissions: Array<String>) {
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshPermissions() }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEachIndexed { i, t ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = {}, label = { Text(t.label) })
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (Tab.entries[tab]) {
                Tab.SETUP -> SetupScreen(vm, state, openAllFilesSettings, mediaPermissions) { tab = Tab.STICKERS.ordinal }
                Tab.STICKERS -> StickerGrid(vm, state)
                Tab.REPORT -> ReportScreen(vm, state)
            }
        }
    }
}

// ---------------- Setup ----------------

@Composable
private fun SetupScreen(
    vm: MainViewModel,
    state: UiState,
    openAllFilesSettings: () -> Unit,
    mediaPermissions: Array<String>,
    onScanned: () -> Unit,
) {
    val p = state.permissions
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { vm.onTreePicked(it) }
    val mediaRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.refreshPermissions()
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Sticker access diagnostics", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Milestone 1 tests which ways of reading your WhatsApp stickers work on this phone. " +
                "Grant any or all of the options below, run a scan, then export the report. " +
                "Nothing leaves your phone unless you export it.",
            style = MaterialTheme.typography.bodyMedium,
        )
        AccessCard(
            title = "A. Pick the WhatsApp Stickers folder",
            body = "Recommended. Uses the system folder picker (Storage Access Framework). It opens at " +
                "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Stickers; tap \"Use this folder\". " +
                "Access is limited to that folder and survives restarts.",
            status = when {
                p.safHeld -> "Granted: ${p.safTree?.let { android.provider.DocumentsContract.getTreeDocumentId(it) }}"
                p.safTree != null -> "Permission was revoked; pick again"
                else -> "Not granted"
            },
            ok = p.safHeld,
            action = "Pick folder",
        ) { treePicker.launch(SafTreeSource.initialStickerFolderUri()) }
        AccessCard(
            title = "B. All files access",
            body = "Lets the app read Android/media/com.whatsapp directly and look for sticker folders it " +
                "doesn't know about. It's a broad permission, used here only for this experiment. It can't " +
                "read WhatsApp's private data, chats or database.",
            status = if (p.allFiles) "Granted" else "Not granted",
            ok = p.allFiles,
            action = "Open settings",
            onClick = openAllFilesSettings,
        )
        AccessCard(
            title = "C. Photos and media",
            body = "Checks whether Android's media index (MediaStore) lists WhatsApp stickers. It is usually " +
                "empty for stickers because WhatsApp hides that folder from the gallery.",
            status = when { p.mediaImages -> "Granted"; p.mediaPartial -> "Selected photos only"; else -> "Not granted" },
            ok = p.mediaImages,
            action = "Request",
        ) { mediaRequest.launch(mediaPermissions) }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("D. Share from WhatsApp (manual test)", fontWeight = FontWeight.SemiBold)
                Text(
                    "In WhatsApp, long-press a sticker in a chat and look for Share or Forward to this app. " +
                        "If the app appears as a target, the sticker is imported and logged in the report.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("Events logged: ${state.eventCount}", style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.height(4.dp))
        if (state.scanning) {
            val prog = state.progress
            Text(prog?.let { "${it.phase} ${it.done}/${it.total}" } ?: "Starting…")
            if (prog != null && prog.total > 0) {
                LinearProgressIndicator(progress = { prog.done.toFloat() / prog.total }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        } else {
            Button(onClick = { vm.scan(); onScanned() }, modifier = Modifier.fillMaxWidth()) { Text("Run scan") }
        }
        state.outcome?.summary?.let {
            Text("Last scan: ${it.uniqueCount} unique stickers (${it.animatedCount} animated)")
        }
        TextButton(onClick = { vm.clearAll() }) { Text("Delete all app data (thumbnails, imports, logs)") }
    }
}

@Composable
private fun AccessCard(title: String, body: String, status: String, ok: Boolean, action: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    status, Modifier.weight(1f),
                    color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelMedium,
                )
                OutlinedButton(onClick = onClick) { Text(action) }
            }
        }
    }
}

// ---------------- Grid ----------------

@Composable
private fun StickerGrid(vm: MainViewModel, state: UiState) {
    val summary = state.outcome?.summary
    var filter by rememberSaveable { mutableStateOf<SourceId?>(null) }
    var animatedOnly by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<UniqueSticker?>(null) }

    if (summary == null) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(if (state.scanning) "Scanning… ${state.progress?.let { "${it.done}/${it.total}" } ?: ""}"
                 else "No scan yet. Grant access on the Setup tab and run a scan.")
        }
        return
    }
    val items = summary.unique.filter { (filter == null || filter in it.sources) && (!animatedOnly || it.format.isAnimated) }

    LazyVerticalGrid(GridCells.Adaptive(96.dp), Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(4.dp)) {
                Text("${summary.uniqueCount} unique stickers", style = MaterialTheme.typography.titleLarge)
                Text("${summary.animatedCount} animated · ${summary.failures.size} with problems · showing ${items.size}",
                    style = MaterialTheme.typography.bodySmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(filter == null, { filter = null }, label = { Text("All") })
                    summary.perSource.values.forEach { s ->
                        FilterChip(filter == s.source, { filter = s.source }, label = { Text("${s.source.name} ${s.uniqueHashes}") })
                    }
                    FilterChip(animatedOnly, { animatedOnly = !animatedOnly }, label = { Text("Animated") })
                }
            }
        }
        items(items, key = { it.sha256 }) { s ->
            Box(
                Modifier.padding(4.dp).aspectRatio(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                    .clickable { selected = s },
            ) {
                Thumb(s.thumbnailPath, Modifier.fillMaxSize().padding(4.dp))
                if (s.format.isAnimated) {
                    Text(
                        "ANIM", Modifier.align(Alignment.TopEnd).padding(4.dp)
                            .background(Color(0xCC000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
                        color = Color.White, fontSize = 9.sp,
                    )
                }
            }
        }
    }
    selected?.let { StickerDetail(vm, it) { selected = null } }
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
    bmp?.let { Image(it, null, modifier) } ?: Box(modifier) { Text("?", Modifier.align(Alignment.Center)) }
}

@Composable
private fun StickerDetail(vm: MainViewModel, s: UniqueSticker, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { vm.send(context, s, StickerSharer.Mode.WHATSAPP_DIRECT) }) { Text("Send to WhatsApp") }
        },
        dismissButton = {
            TextButton(onClick = { vm.send(context, s, StickerSharer.Mode.SYSTEM_SHEET) }) { Text("Share…") }
        },
        title = { Text(s.format.format.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AnimatedPreview(Uri.parse(s.primary.uri), Modifier.fillMaxWidth().height(220.dp))
                Text("${s.format.width}×${s.format.height} · ${s.format.frameCount} frame(s) · ${s.sizeBytes / 1024} KB",
                    style = MaterialTheme.typography.bodySmall)
                if (s.format.isAnimated) {
                    Text("Platform animated decode: ${s.animatedDecodeOk ?: "n/a"}", style = MaterialTheme.typography.bodySmall)
                }
                Text("SHA-256 ${s.sha256.take(16)}…", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                s.occurrences.forEach {
                    Text("${it.source.name}: ${it.displayPath}\n${DateFormat.getDateTimeInstance().format(Date(it.lastModifiedMs))}",
                        style = MaterialTheme.typography.labelSmall)
                }
                Text("Send test: after WhatsApp opens, note whether the sticker arrives as a sticker or as a photo, " +
                    "and whether it stays animated.", style = MaterialTheme.typography.labelSmall)
            }
        },
    )
}

/** Plays the original file; animated WebP/GIF stay animated (AnimatedImageDrawable). */
@Composable
private fun AnimatedPreview(uri: Uri, modifier: Modifier) {
    val context = LocalContext.current
    val drawable by produceState<Drawable?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.contentResolver, uri)) }.getOrNull()
        }
    }
    val d = drawable
    if (d == null) {
        Box(modifier, contentAlignment = Alignment.Center) { Text("Loading…") }
        return
    }
    AndroidView(
        factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
        modifier = modifier,
        update = { iv ->
            iv.setImageDrawable(d)
            (d as? AnimatedImageDrawable)?.start()
        },
    )
}

// ---------------- Report ----------------

@Composable
private fun ReportScreen(vm: MainViewModel, state: UiState) {
    val context = LocalContext.current
    val json = remember(state.outcome, state.eventCount, state.permissions) { vm.reportJson() }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Diagnostics report", style = MaterialTheme.typography.headlineSmall)
        Text("Contains counts, formats, folder names and hash file names only. No images, no chats.",
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.copyReport() }) { Text("Copy") }
            OutlinedButton(onClick = { vm.shareReport(context) }) { Text("Share file") }
        }
        Text(
            json,
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(8.dp),
            fontFamily = FontFamily.Monospace, fontSize = 10.sp,
        )
        Spacer(Modifier.size(0.dp))
    }
}
