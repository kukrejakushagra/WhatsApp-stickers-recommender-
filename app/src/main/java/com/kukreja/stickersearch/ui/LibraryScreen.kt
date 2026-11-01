package com.kukreja.stickersearch.ui

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import com.kukreja.stickersearch.ui.kds.KdsFastScroller
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kukreja.stickersearch.R
import com.kukreja.stickersearch.data.StickerRow
import com.kukreja.stickersearch.data.StickerStatus
import com.kukreja.stickersearch.ui.kds.KdsAppBar
import com.kukreja.stickersearch.ui.kds.KdsEmptyState
import com.kukreja.stickersearch.ui.kds.KdsFilterChip
import com.kukreja.stickersearch.ui.kds.KdsMediaTile
import com.kukreja.stickersearch.ui.kds.KdsProgress
import com.kukreja.stickersearch.ui.kds.KdsStatCard
import com.kukreja.stickersearch.ui.kds.theme.Kds

private enum class Filter { ALL, ANIMATED, LOTTIE, NOT_DESCRIBED, ATTENTION }

@Composable
internal fun LibraryScreen(state: UiState, actions: AppActions, onOpen: (StickerRow) -> Unit, goToSettings: () -> Unit) {
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    var naming by remember { mutableStateOf(false) }
    val selecting = state.selecting
    val all = state.stickers

    if (all.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            KdsAppBar("Library")
            Box(Modifier.fillMaxSize().padding(bottom = BottomClearance), contentAlignment = Alignment.Center) {
                when {
                    state.index.running -> KdsEmptyState(R.drawable.ic_kds_loader, "Indexing",
                        if (state.index.total > 0) "%,d of %,d files".format(state.index.done, state.index.total) else "Listing files")
                    state.loaded -> KdsEmptyState(R.drawable.ic_kds_grid, "No stickers yet",
                        "Pick WhatsApp's Media folder in Settings, then run a scan.", actionLabel = "Go to settings", onAction = goToSettings)
                }
            }
        }
        return
    }
    fun matches(f: Filter, s: StickerRow) = when (f) {
        Filter.ALL -> true
        Filter.ANIMATED -> s.isAnimated && !s.isLottie()
        Filter.LOTTIE -> s.isLottie()
        Filter.NOT_DESCRIBED -> s.status == StickerStatus.PENDING
        Filter.ATTENTION -> s.needsAttention()
    }
    val counts = remember(all) { Filter.entries.associateWith { f -> all.count { matches(f, it) } } }
    val items = remember(all, filter) { all.filter { matches(filter, it) } }
    val gridState = rememberLazyGridState()
    val monthFmt = remember { java.text.SimpleDateFormat("MMM yyyy", java.util.Locale.getDefault()) }
    Box(Modifier.fillMaxSize()) {
    LazyVerticalGrid(
        GridCells.Fixed(3), Modifier.fillMaxSize(), state = gridState,
        // Extra end padding keeps the fast-scroll handle off the last tile column.
        contentPadding = PaddingValues(start = 16.dp, end = 24.dp, bottom = BottomClearance + if (selecting) 112.dp else 0.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                KdsAppBar("Library", subtitle = "Newest first", inset = 4.dp,
                    trailing = { if (!selecting) SelectButton(actions) })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KdsStatCard("%,d".format(all.size), "Stickers", Modifier.weight(1f))
                    KdsStatCard("%,d".format(state.ai.counts.annotated), "Described", Modifier.weight(1f))
                    val attention = counts.getValue(Filter.ATTENTION)
                    KdsStatCard("%,d".format(attention), "Need a look", Modifier.weight(1f),
                        valueColor = if (attention > 0) Kds.colors.warning else Kds.colors.foreground)
                }
                if (state.index.running && state.index.total > 0) {
                    Spacer(Modifier.height(12.dp))
                    KdsProgress(state.index.done.toFloat() / state.index.total)
                    Spacer(Modifier.height(4.dp))
                    Text("Indexing %,d of %,d".format(state.index.done, state.index.total),
                        style = Kds.type.caption.copy(fontFeatureSettings = "tnum"), color = Kds.colors.mutedForeground)
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    listOf(Filter.ALL to "All", Filter.ANIMATED to "Animated", Filter.LOTTIE to "Lottie",
                        Filter.NOT_DESCRIBED to "Not described", Filter.ATTENTION to "Need a look")
                        .filter { (f, _) -> f == Filter.ALL || f == filter || counts.getValue(f) > 0 }
                        .forEach { (f, label) -> KdsFilterChip(label, filter == f, { filter = if (filter == f) Filter.ALL else f }, counts.getValue(f)) }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
        items(items, key = { it.id }) { s ->
            KdsMediaTile(onClick = { if (selecting) actions.toggleSelected(s.id) else onOpen(s) }, label = tileLabel(s),
                selectable = selecting, picked = s.id in state.selected) {
                Thumb(s.thumbnailPath, Modifier.fillMaxSize(), placeholder = if (s.isLottie()) R.drawable.ic_kds_spark else R.drawable.ic_kds_image)
            }
        }
    }
    // Item 0 is the header; items 1.. are stickers, newest first, so the bubble shows their month.
    KdsFastScroller(
        gridState, itemCount = items.size + 1, modifier = Modifier.align(Alignment.TopEnd),
        topInset = 96.dp, bottomInset = BottomClearance,
        label = { i -> items.getOrNull(i - 1)?.newest?.takeIf { it > 0 }?.let { monthFmt.format(java.util.Date(it)) } },
    )
    if (selecting) {
        SelectionBar(
            count = state.selected.size, topLabel = "First 30",
            // Lottie stickers can't go into WhatsApp packs, so "First 30" skips them.
            onTop = { actions.selectTop(items.filterNot { it.isLottie() }.map { it.id }) },
            onCancel = actions.stopSelecting,
            onCreate = { naming = true },
            modifier = Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = BottomClearance - 8.dp),
        )
    }
    }
    if (naming) {
        val default = when (filter) {
            Filter.ANIMATED -> "Animated"
            Filter.NOT_DESCRIBED -> "New stickers"
            else -> "My stickers"
        }
        NameDialog(
            title = "Create a sticker pack",
            description = "${state.selected.size} sticker${if (state.selected.size == 1) "" else "s"} selected. Static and animated stickers become separate packs, as WhatsApp requires; each needs at least ${com.kukreja.stickersearch.share.StickerPacks.MIN}.",
            initial = default, confirmLabel = "Create and add",
            onConfirm = { name -> naming = false; actions.createPacks(name) }, onDismiss = { naming = false },
        )
    }
}
