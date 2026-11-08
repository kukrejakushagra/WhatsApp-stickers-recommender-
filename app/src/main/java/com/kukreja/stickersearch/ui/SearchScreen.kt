package com.kukreja.stickersearch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.kukreja.stickersearch.ui.kds.glass
import androidx.compose.ui.graphics.asImageBitmap
import com.kukreja.stickersearch.ui.kds.KdsIconButton
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.derivedStateOf
import com.kukreja.stickersearch.ui.kds.KdsProgress
import com.kukreja.stickersearch.ui.kds.KdsFastScroller
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kukreja.stickersearch.R
import com.kukreja.stickersearch.data.StickerRow
import com.kukreja.stickersearch.ui.kds.ButtonSize
import com.kukreja.stickersearch.ui.kds.ButtonVariant
import com.kukreja.stickersearch.ui.kds.CalloutTone
import com.kukreja.stickersearch.ui.kds.KdsAppBar
import com.kukreja.stickersearch.ui.kds.KdsButton
import com.kukreja.stickersearch.ui.kds.KdsCallout
import com.kukreja.stickersearch.ui.kds.KdsEmptyState
import com.kukreja.stickersearch.ui.kds.KdsFilterChip
import com.kukreja.stickersearch.ui.kds.KdsIcon
import com.kukreja.stickersearch.ui.kds.KdsMediaTile
import com.kukreja.stickersearch.ui.kds.KdsSearchField
import com.kukreja.stickersearch.ui.kds.SpinningLoader
import com.kukreja.stickersearch.ui.kds.theme.Kds

private val EXAMPLES = listOf(
    "something sarcastic but not offensive", "I told you so", "disbelief", "kya bakwas hai",
    "funny reaction to a stupid mistake", "playful frustration", "not impressed", "excited",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SearchScreen(state: UiState, actions: AppActions, onOpen: (StickerRow) -> Unit, goToSettings: () -> Unit) {
    val s = state.search
    val assist = state.assist
    val ai = state.ai.counts
    val selecting = state.selecting
    val tap: (StickerRow) -> Unit = if (selecting) { r -> actions.toggleSelected(r.id) } else onOpen
    // Long-press anywhere sends straight to WhatsApp (not while picking stickers for a pack).
    val send: ((StickerRow) -> Unit)? = if (selecting) null else actions.sendViaWhatsApp
    // An empty search box shows the whole library, newest first, with its filters.
    val browsing = s.query.isBlank() && assist.attachments.isEmpty()
    var filter by rememberSaveable { mutableStateOf(LibraryFilter.ALL) }
    val all = state.stickers
    val counts = remember(all) { LibraryFilter.entries.associateWith { f -> all.count { f.matches(it) } } }
    val library = remember(all, filter) { all.filter { filter.matches(it) } }
    // What "Top 30" / "First 30" picks from: what's on screen, best (or newest) first.
    val visible = if (assist.attachments.isNotEmpty() && assist.done) assist.suggestions + assist.found
        else if (s.query.isNotBlank()) s.results + s.related.map { it.first }
        else library.filterNot { it.isLottie() }
    var naming by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    val monthFmt = remember { java.text.SimpleDateFormat("MMM yyyy", java.util.Locale.getDefault()) }
    // Back to the top when switching between browsing and results, or changing the filter.
    LaunchedEffect(browsing, filter) { gridState.scrollToItem(0) }
    val scrolled by remember { derivedStateOf { gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0 } }
    Column(Modifier.fillMaxSize()) {
    // ---- Pinned: the search box, and the library filters (or the results line) ----
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KdsSearchField(
                value = s.query, onValueChange = actions.onQueryChange,
                onSearch = { if (assist.attachments.isNotEmpty()) actions.runAssist() else actions.submitSearch() },
                placeholder = if (assist.attachments.isEmpty()) "Search %,d stickers".format(all.size).takeIf { all.isNotEmpty() } ?: "Describe what you want to say"
                    else "Add what you want to say (optional)",
                modifier = Modifier.weight(1f),
            )
            KdsIconButton(R.drawable.ic_kds_image, "Add screenshots or images", actions.attachImages)
        }
        if (browsing && all.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    LibraryFilter.entries
                        .filter { f -> f == LibraryFilter.ALL || f == filter || counts.getValue(f) > 0 }
                        .forEach { f -> KdsFilterChip(f.label, filter == f, { filter = if (filter == f) LibraryFilter.ALL else f }, counts.getValue(f)) }
                }
                if (!selecting) { Spacer(Modifier.width(8.dp)); SelectButton(actions) }
            }
        } else if (s.query.isNotBlank() && assist.attachments.isEmpty()) {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (s.searching) SpinningLoader(14.dp, Kds.colors.mutedForeground)
                Text(modifier = Modifier.weight(1f), text =
                    when {
                        s.searching -> "Searching"
                        !s.searched -> ""
                        s.results.isEmpty() -> "No matches"
                        else -> "%,d matches · %d ms".format(s.results.size, s.millis)
                    },
                    style = Kds.type.caption.copy(fontFeatureSettings = "tnum"), color = Kds.colors.mutedForeground,
                )
                if (s.results.isNotEmpty() && !selecting) SelectButton(actions)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    if (scrolled) androidx.compose.material3.HorizontalDivider(thickness = 1.dp, color = Kds.colors.border)
    Box(Modifier.fillMaxWidth().weight(1f)) {
    LazyVerticalGrid(
        GridCells.Fixed(3), Modifier.fillMaxSize(), state = gridState,
        // Extra end padding while browsing keeps the fast-scroll handle off the last column.
        contentPadding = PaddingValues(start = 16.dp, end = if (browsing) 30.dp else 16.dp, bottom = BottomClearance + if (selecting) 112.dp else 0.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // ---- Scrolls away: everything else above the stickers ----
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                if (assist.attachments.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    AttachmentRow(assist.attachments, actions.removeAttachment)
                    Spacer(Modifier.height(10.dp))
                    KdsButton(
                        if (assist.running) assist.phase ?: "Working" else "Suggest stickers", actions.runAssist,
                        size = ButtonSize.LG, block = true, loading = assist.running, icon = R.drawable.ic_kds_spark,
                    )
                    Spacer(Modifier.height(10.dp))
                }
                when {
                    state.stickers.isEmpty() && state.loaded -> {
                        KdsEmptyState(R.drawable.ic_kds_folder, "No stickers yet",
                            "Pick WhatsApp's Media folder in Settings and run a scan.", actionLabel = "Go to settings", onAction = goToSettings)
                    }
                    !state.models.searchReady -> KdsCallout(
                        "Import the AI models in Settings to search by meaning. Until then, only stickers you've described are found.",
                        CalloutTone.BRAND, title = "Search needs its models",
                    )
                }
                if (assist.attachments.isNotEmpty()) {
                    AssistHeader(assist, actions, selecting)
                } else if (s.query.isBlank()) {
                    if (s.recent.isNotEmpty()) {
                        // One line: scroll sideways for more.
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            KdsIcon(R.drawable.ic_kds_history, 14.dp)
                            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                s.recent.forEach { q -> KdsFilterChip(q, false, { actions.useRecent(q) }) }
                            }
                            KdsButton("Clear", actions.clearRecent, variant = ButtonVariant.GHOST, size = ButtonSize.SM)
                        }
                    } else if (all.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("TRY", style = Kds.type.overline, color = Kds.colors.mutedForeground)
                            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                EXAMPLES.forEach { q -> KdsFilterChip(q, false, { actions.useRecent(q) }) }
                            }
                        }
                    }
                    if (s.recentlyUsed.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text("RECENTLY USED", style = Kds.type.overline, color = Kds.colors.mutedForeground)
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            s.recentlyUsed.forEach { r ->
                                KdsMediaTile(onClick = { tap(r) }, modifier = Modifier.size(84.dp), label = tileLabel(r), onLongClick = send?.let { f -> { f(r) } },
                                    actionIcon = R.drawable.ic_kds_send, actionLabel = "Send in WhatsApp",
                                    onAction = if (r.isLottie() || r.primaryUri == null) null else send?.let { f -> { f(r) } },
                                    actionBusy = state.sendingId == r.id, selectable = selecting, picked = r.id in state.selected) {
                                    Thumb(r.thumbnailPath, Modifier.fillMaxSize())
                                }
                            }
                        }
                    }
                    if (state.index.running && state.index.total > 0) {
                        Spacer(Modifier.height(10.dp))
                        KdsProgress(state.index.done.toFloat() / state.index.total)
                        Text("Indexing %,d of %,d".format(state.index.done, state.index.total),
                            style = Kds.type.caption.copy(fontFeatureSettings = "tnum"), color = Kds.colors.mutedForeground,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                    if (all.isNotEmpty()) Spacer(Modifier.height(10.dp))
                } else if (s.searched && !s.searching && s.results.isEmpty()) {
                    KdsEmptyState(R.drawable.ic_kds_search, "Nothing matched",
                        "Try other words, or fewer of them. Describing a few stickers yourself also helps.")
                }
            }
        }
        if (assist.attachments.isNotEmpty() && assist.done) {
            if (assist.suggestions.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel("Suggested replies") }
                items(assist.suggestions, key = { "s${it.id}" }) { r -> ResultTile(r, emptyList(), tap, selecting, r.id in state.selected, send, state.sendingId == r.id) }
            }
            if (assist.found.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel("In your images") }
                items(assist.found, key = { "f${it.id}" }) { r -> ResultTile(r, emptyList(), tap, selecting, r.id in state.selected, send, state.sendingId == r.id) }
            }
        } else if (s.query.isNotBlank()) {
            items(s.results, key = { it.id }) { r ->
                ResultTile(r, s.matched[r.id].orEmpty().ifEmpty { listOf(" ") }, tap, selecting, r.id in state.selected, send, state.sendingId == r.id)
            }
            if (s.related.isNotEmpty() && !s.searching) {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel("Related") }
                items(s.related, key = { "r${it.first.id}" }) { (r, why) -> ResultTile(r, listOf(why), tap, selecting, r.id in state.selected, send, state.sendingId == r.id) }
            }
        } else {
            items(library, key = { "l${it.id}" }) { r -> ResultTile(r, emptyList(), tap, selecting, r.id in state.selected, send, state.sendingId == r.id) }
        }
    }
    if (browsing && library.isNotEmpty()) {
        // Item 0 is the header; items 1.. are stickers, newest first, so the bubble shows their month.
        KdsFastScroller(
            gridState, itemCount = library.size + 1, modifier = Modifier.align(Alignment.TopEnd),
            topInset = 4.dp, bottomInset = BottomClearance,
            label = { i -> library.getOrNull(i - 1)?.newest?.takeIf { it > 0 }?.let { monthFmt.format(java.util.Date(it)) } },
        )
    }
    if (selecting) {
        SelectionBar(
            count = state.selected.size, topLabel = if (browsing) "First 30" else "Top 30",
            onTop = { actions.selectTop(visible.map { it.id }) },
            onCancel = actions.stopSelecting,
            onCreate = { naming = true },
            modifier = Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = BottomClearance - 8.dp),
        )
    }
    }
    }
    if (naming) {
        val default = s.query.trim().ifBlank { assist.queries.firstOrNull() ?: filter.packName }.replaceFirstChar { it.uppercase() }
        NameDialog(
            title = "Create a sticker pack",
            description = "${state.selected.size} sticker${if (state.selected.size == 1) "" else "s"} selected. Static and animated stickers become separate packs, as WhatsApp requires; each needs at least ${com.kukreja.stickersearch.share.StickerPacks.MIN}.",
            initial = default, confirmLabel = "Create and add",
            onConfirm = { name -> naming = false; actions.createPacks(name) }, onDismiss = { naming = false },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), style = Kds.type.overline, color = Kds.colors.mutedForeground,
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
}

@Composable
private fun ResultTile(
    r: StickerRow, why: List<String>, onTap: (StickerRow) -> Unit, selecting: Boolean = false, picked: Boolean = false,
    onSend: ((StickerRow) -> Unit)? = null, sending: Boolean = false,
) {
    Column {
        KdsMediaTile(onClick = { onTap(r) }, label = tileLabel(r), selectable = selecting, picked = picked,
            onLongClick = onSend?.let { f -> { f(r) } },
            // Lottie stickers can't go into WhatsApp packs, so they get no send button.
            actionIcon = R.drawable.ic_kds_send, actionLabel = "Send in WhatsApp",
            onAction = onSend?.takeIf { !r.isLottie() && r.primaryUri != null }?.let { f -> { f(r) } }, actionBusy = sending) {
            Thumb(r.thumbnailPath, Modifier.fillMaxSize(), placeholder = if (r.isLottie()) R.drawable.ic_kds_spark else R.drawable.ic_kds_image)
        }
        if (why.isNotEmpty()) {
            Text(why.take(2).joinToString(" · "), style = Kds.type.labelXs, color = Kds.colors.mutedForeground, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 2.dp, top = 2.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AssistHeader(assist: AssistState, actions: AppActions, selecting: Boolean) {
    if (!assist.done) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        assist.reading?.let { KdsCallout(it, CalloutTone.BRAND, title = "What's going on") }
        if (assist.queries.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                assist.queries.forEach { q -> KdsFilterChip(q, false, { actions.clearAssist(); actions.useRecent(q) }) }
            }
        }
        assist.note?.let { KdsCallout(it, CalloutTone.NEUTRAL) }
        if (assist.suggestions.isEmpty() && assist.found.isEmpty()) {
            KdsEmptyState(R.drawable.ic_kds_search, "Nothing fitting yet",
                "Add a few words about what you want to say, or let more stickers get described overnight.")
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("%d ms".format(assist.millis), style = Kds.type.caption.copy(fontFeatureSettings = "tnum"),
                    color = Kds.colors.mutedForeground, modifier = Modifier.weight(1f))
                if (!selecting) SelectButton(actions)
            }
        }
    }
}

/** Thumbnails of the attached images, each with a remove button. */
@Composable
private fun AttachmentRow(uris: List<android.net.Uri>, onRemove: (android.net.Uri) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        uris.forEach { u ->
            val bmp by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, u) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(context.contentResolver, u)) { d, info, _ ->
                            val sc = 192f / maxOf(info.size.width, info.size.height)
                            if (sc < 1f) d.setTargetSize((info.size.width * sc).toInt().coerceAtLeast(1), (info.size.height * sc).toInt().coerceAtLeast(1))
                        }.asImageBitmap()
                    }.getOrNull()
                }
            }
            Box(Modifier.size(72.dp)) {
                KdsMediaTile(onClick = {}, modifier = Modifier.fillMaxSize()) {
                    bmp?.let { androidx.compose.foundation.Image(it, null, Modifier.fillMaxSize()) }
                        ?: KdsIcon(R.drawable.ic_kds_image, 20.dp)
                }
                Box(
                    Modifier.align(Alignment.TopEnd).padding(2.dp).size(24.dp)
                        .background(Kds.colors.mediaOverlay, androidx.compose.foundation.shape.CircleShape)
                        .clickable { onRemove(u) },
                    contentAlignment = Alignment.Center,
                ) { KdsIcon(R.drawable.ic_kds_x, 12.dp, androidx.compose.ui.graphics.Color.White) }
            }
        }
    }
}

/** The library filters shown under an empty search box. */
internal enum class LibraryFilter(val label: String, val packName: String) {
    ALL("All", "My stickers"),
    ANIMATED("Animated", "Animated"),
    LOTTIE("Lottie", "My stickers"),
    DESCRIBED("Described", "Described"),
    NOT_DESCRIBED("Not described", "New stickers"),
    ATTENTION("Need a look", "My stickers");

    fun matches(s: StickerRow) = when (this) {
        ALL -> true
        ANIMATED -> s.isAnimated && !s.isLottie()
        LOTTIE -> s.isLottie()
        DESCRIBED -> s.status == com.kukreja.stickersearch.data.StickerStatus.INDEXED
        NOT_DESCRIBED -> s.status == com.kukreja.stickersearch.data.StickerStatus.PENDING
        ATTENTION -> s.needsAttention()
    }
}
