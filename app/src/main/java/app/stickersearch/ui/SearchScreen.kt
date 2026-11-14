package app.stickersearch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.stickersearch.ui.kds.glass
import androidx.compose.ui.graphics.asImageBitmap
import app.stickersearch.ui.kds.KdsIconButton
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
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.derivedStateOf
import app.stickersearch.ui.kds.KdsProgress
import app.stickersearch.ui.kds.KdsFastScroller
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.stickersearch.R
import app.stickersearch.data.StickerRow
import app.stickersearch.ui.kds.ButtonSize
import app.stickersearch.ui.kds.ButtonVariant
import app.stickersearch.ui.kds.CalloutTone
import app.stickersearch.ui.kds.KdsAppBar
import app.stickersearch.ui.kds.KdsButton
import app.stickersearch.ui.kds.KdsCallout
import app.stickersearch.ui.kds.KdsEmptyState
import app.stickersearch.ui.kds.KdsFilterChip
import app.stickersearch.ui.kds.KdsIcon
import app.stickersearch.ui.kds.KdsMediaTile
import app.stickersearch.ui.kds.KdsSearchField
import app.stickersearch.ui.kds.SpinningLoader
import app.stickersearch.ui.kds.theme.Kds

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
    // The keyboard gets out of the way as soon as you're done typing: Back, the search key,
    // scrolling, or tapping anything below hides it.
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val imeVisible = androidx.compose.foundation.layout.WindowInsets.isImeVisible
    val imeShown by androidx.compose.runtime.rememberUpdatedState(imeVisible)
    fun dismissKeyboard() { keyboard?.hide(); focus.clearFocus() }
    val hideOnScroll = remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (imeShown && source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput && available.y != 0f) {
                    keyboard?.hide(); focus.clearFocus()
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }
    val tap: (StickerRow) -> Unit = if (selecting) { r -> actions.toggleSelected(r.id) } else { r -> dismissKeyboard(); onOpen(r) }
    // Long-press anywhere sends straight to WhatsApp (not while picking stickers for a pack).
    val send: ((StickerRow) -> Unit)? = if (selecting) null else { r -> dismissKeyboard(); actions.sendViaWhatsApp(r) }
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
    // Back first undoes what's on screen (selection, attached images, the search), and only then
    // leaves the app.
    androidx.activity.compose.BackHandler(enabled = imeVisible || selecting || assist.attachments.isNotEmpty() || s.query.isNotEmpty()) {
        when {
            imeVisible -> dismissKeyboard()  // first Back: just put the keyboard away; the results stay
            selecting -> actions.stopSelecting()
            assist.attachments.isNotEmpty() -> actions.clearAssist()
            else -> actions.onQueryChange("")
        }
    }
    val scrolled by remember { derivedStateOf { gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0 } }
    // Which tiles are really on screen (not just prepared ahead): only these animate.
    val onScreen by remember { derivedStateOf { gridState.layoutInfo.visibleItemsInfo.mapTo(HashSet()) { it.key } } }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val anim = TileAnim(
        enabled = state.animateGrid && !remember(state.animateGrid) { systemWantsStill(ctx) },
        onScreen = onScreen, scrolling = gridState.isScrollInProgress,
    )
    Column(Modifier.fillMaxSize()) {
    // ---- Pinned: the search box, and the library filters (or the results line) ----
    // Short screens (phones in landscape): a slimmer field, and the filters share its row.
    val compactTop = Adaptive.short(screenHeightDp())
    val filtersInline = compactTop && browsing && all.isNotEmpty()
    val filterChips: @Composable (Modifier) -> Unit = { m ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = m) {
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
    }
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = if (compactTop) 8.dp else 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KdsSearchField(
                height = if (compactTop) 40.dp else 48.dp,
                value = s.query, onValueChange = actions.onQueryChange,
                onSearch = { if (assist.attachments.isNotEmpty()) actions.runAssist() else actions.submitSearch(); dismissKeyboard() },
                placeholder = if (assist.attachments.isEmpty()) "Search %,d stickers".format(all.size).takeIf { all.isNotEmpty() } ?: "Describe what you want to say"
                    else "Add what you want to say (optional)",
                modifier = Modifier.weight(if (filtersInline) 0.45f else 1f),
            )
            if (filtersInline) filterChips(Modifier.weight(0.55f))
            KdsIconButton(R.drawable.ic_kds_image, "Add screenshots or images", actions.attachImages)
        }
        if (browsing && all.isNotEmpty() && !filtersInline) {
            Spacer(Modifier.height(10.dp))
            filterChips(Modifier.fillMaxWidth())
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
    // Below the keyboard, nothing is lost: the grid's end padding grows to the keyboard's height.
    val imeBottom = androidx.compose.foundation.layout.WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val bottomRoom = maxOf(BottomClearance, imeBottom + 16.dp)
    Box(Modifier.fillMaxWidth().weight(1f)) {
    LazyVerticalGrid(
        // Three columns on phones, more on tablets and unfolded foldables.
        GridCells.Adaptive(minSize = 96.dp), Modifier.fillMaxSize().nestedScroll(hideOnScroll), state = gridState,
        // Extra end padding while browsing keeps the fast-scroll handle off the last column.
        contentPadding = PaddingValues(start = 16.dp, end = if (browsing) 30.dp else 16.dp, bottom = bottomRoom + if (selecting) 112.dp else 0.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // ---- Scrolls away: everything else above the stickers ----
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                state.sendMissed?.let { pack ->
                    KdsCallout(
                        "WhatsApp hasn't picked up the sticker you just sent. Refreshing the pack makes it show up.",
                        CalloutTone.WARNING, title = "Sticker not in WhatsApp yet?",
                    )
                    Row(Modifier.padding(top = 8.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        KdsButton("Refresh in WhatsApp", { actions.refreshPackInWhatsApp(pack) }, size = ButtonSize.SM, icon = R.drawable.ic_kds_refresh)
                        KdsButton("Dismiss", actions.dismissSendMissed, variant = ButtonVariant.GHOST, size = ButtonSize.SM)
                    }
                }
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
                    !state.models.searchReady -> {
                        val m = state.models
                        KdsCallout(
                            if (m.anyDownloadActive) "Downloading the search models (%,d of %,d MB). Until they're in, only stickers you've described are found."
                                .format(m.searchDone shr 20, m.searchTotal shr 20)
                            else "Download the search models (about 500 MB, free, no sign-in) to search by meaning and by look. Until then, only stickers you've described are found.",
                            CalloutTone.BRAND, title = if (m.anyDownloadActive) "Getting search ready" else "Search needs its models",
                        )
                        if (!m.anyDownloadActive) {
                            KdsButton("Go to downloads", { actions.openSettingsAt("models") }, size = ButtonSize.SM,
                                icon = R.drawable.ic_kds_download, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                        }
                    }
                }
                if (assist.attachments.isNotEmpty()) {
                    AssistHeader(assist, actions, selecting)
                } else if (s.query.isBlank()) {
                    if (s.recent.isNotEmpty()) {
                        // One line: scroll sideways for more.
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            KdsIcon(R.drawable.ic_kds_history, 14.dp)
                            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                s.recent.forEach { q -> KdsFilterChip(q, false, { dismissKeyboard(); actions.useRecent(q) }) }
                            }
                            KdsButton("Clear", actions.clearRecent, variant = ButtonVariant.GHOST, size = ButtonSize.SM)
                        }
                    } else if (all.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("TRY", style = Kds.type.overline, color = Kds.colors.mutedForeground)
                            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                EXAMPLES.forEach { q -> KdsFilterChip(q, false, { dismissKeyboard(); actions.useRecent(q) }) }
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
                items(assist.suggestions, key = { "s${it.id}" }) { r -> ResultTile(r, emptyList(), tap, selecting, r.id in state.selected, send, state.sendingId == r.id, anim, "s${r.id}") }
            }
            if (assist.found.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel("In your images") }
                items(assist.found, key = { "f${it.id}" }) { r -> ResultTile(r, emptyList(), tap, selecting, r.id in state.selected, send, state.sendingId == r.id, anim, "f${r.id}") }
            }
        } else if (s.query.isNotBlank()) {
            items(s.results, key = { it.id }) { r ->
                ResultTile(r, s.matched[r.id].orEmpty().ifEmpty { listOf(" ") }, tap, selecting, r.id in state.selected, send, state.sendingId == r.id, anim, r.id)
            }
            if (s.related.isNotEmpty() && !s.searching) {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel("Related") }
                items(s.related, key = { "r${it.first.id}" }) { (r, why) -> ResultTile(r, listOf(why), tap, selecting, r.id in state.selected, send, state.sendingId == r.id, anim, "r${r.id}") }
            }
        } else {
            items(library, key = { "l${it.id}" }) { r -> ResultTile(r, emptyList(), tap, selecting, r.id in state.selected, send, state.sendingId == r.id, anim, "l${r.id}") }
        }
    }
    if (browsing && library.isNotEmpty()) {
        // Item 0 is the header; items 1.. are stickers, newest first, so the bubble shows their month.
        KdsFastScroller(
            gridState, itemCount = library.size + 1, modifier = Modifier.align(Alignment.TopEnd),
            topInset = 4.dp, bottomInset = bottomRoom,
            label = { i -> library.getOrNull(i - 1)?.newest?.takeIf { it > 0 }?.let { monthFmt.format(java.util.Date(it)) } },
        )
    }
    if (selecting) {
        SelectionBar(
            count = state.selected.size, topLabel = if (browsing) "First 30" else "Top 30",
            onTop = { actions.selectTop(visible.map { it.id }) },
            onCancel = actions.stopSelecting,
            onCreate = { naming = true },
            modifier = Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = bottomRoom - 8.dp),
        )
    }
    }
    }
    if (naming) {
        val default = s.query.trim().ifBlank { assist.queries.firstOrNull() ?: filter.packName }.replaceFirstChar { it.uppercase() }
        NameDialog(
            title = "Create a sticker pack",
            description = "${state.selected.size} sticker${if (state.selected.size == 1) "" else "s"} selected. Static and animated stickers become separate packs, as WhatsApp requires; each needs at least ${app.stickersearch.share.StickerPacks.MIN}.",
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
    anim: TileAnim = TileAnim.Off, key: Any? = null,
) {
    Column {
        KdsMediaTile(onClick = { onTap(r) }, label = tileLabel(r), selectable = selecting, picked = picked,
            onLongClick = onSend?.let { f -> { f(r) } },
            // Lottie stickers can't go into WhatsApp packs, so they get no send button.
            actionIcon = R.drawable.ic_kds_send, actionLabel = "Send in WhatsApp",
            onAction = onSend?.takeIf { !r.isLottie() && r.primaryUri != null }?.let { f -> { f(r) } }, actionBusy = sending) {
            AnimatedTile(r, onScreen = key != null && key in anim.onScreen, scrolling = anim.scrolling, enabled = anim.enabled)
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
                "Add a few words about what you want to say. As more stickers get described (while charging, over several nights), suggestions get better.")
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
    DESCRIBED("Described", "Described"),
    NOT_DESCRIBED("Not described", "New stickers"),
    ATTENTION("Need a look", "My stickers");

    fun matches(s: StickerRow) = when (this) {
        ALL -> true
        ANIMATED -> s.isAnimated // Lottie stickers too: they're animated, just not sendable as WhatsApp stickers
        DESCRIBED -> s.status == app.stickersearch.data.StickerStatus.INDEXED
        NOT_DESCRIBED -> s.status == app.stickersearch.data.StickerStatus.PENDING
        ATTENTION -> s.needsAttention()
    }
}

/** What a grid tile needs to decide whether to animate. */
internal data class TileAnim(val enabled: Boolean, val onScreen: Set<Any>, val scrolling: Boolean) {
    companion object { val Off = TileAnim(false, emptySet(), false) }
}
