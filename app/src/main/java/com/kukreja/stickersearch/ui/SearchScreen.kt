package com.kukreja.stickersearch.ui

import androidx.compose.foundation.clickable
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
    val ai = state.ai.counts
    LazyVerticalGrid(
        GridCells.Fixed(3), Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = BottomClearance),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                KdsAppBar(
                    "Search", inset = 4.dp,
                    subtitle = if (ai.total > 0) "%,d stickers · %,d described".format(ai.total, ai.annotated) else null,
                )
                KdsSearchField(
                    value = s.query, onValueChange = actions.onQueryChange, onSearch = actions.submitSearch,
                    placeholder = "Describe what you want to say",
                )
                Spacer(Modifier.height(12.dp))
                when {
                    state.stickers.isEmpty() && state.loaded -> {
                        KdsEmptyState(R.drawable.ic_kds_folder, "No stickers yet",
                            "Pick WhatsApp's Media folder in Settings and run a scan.", actionLabel = "Go to settings", onAction = goToSettings)
                    }
                    !state.models.searchReady -> KdsCallout(
                        "Import the AI models in Settings to search by meaning. Until then, only stickers you've described are found.",
                        CalloutTone.BRAND, title = "Search needs its models",
                    )
                    ai.total > 0 && ai.annotated < ai.total && ai.visual == 0 -> KdsCallout(
                        "Stickers are being prepared for search. Keep the phone charging; it works on its own.",
                        CalloutTone.NEUTRAL,
                    )
                }
                if (s.query.isBlank()) {
                    if (s.recent.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            KdsIcon(R.drawable.ic_kds_history, 14.dp)
                            Text("RECENT", style = Kds.type.overline, color = Kds.colors.mutedForeground, modifier = Modifier.weight(1f))
                            KdsButton("Clear", actions.clearRecent, variant = ButtonVariant.GHOST, size = ButtonSize.SM)
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            s.recent.forEach { q -> KdsFilterChip(q, false, { actions.useRecent(q) }) }
                        }
                    }
                    if (s.recentlyUsed.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Text("RECENTLY USED", style = Kds.type.overline, color = Kds.colors.mutedForeground)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            s.recentlyUsed.forEach { r ->
                                KdsMediaTile(onClick = { onOpen(r) }, modifier = Modifier.size(76.dp), label = tileLabel(r)) {
                                    Thumb(r.thumbnailPath, Modifier.fillMaxSize())
                                }
                            }
                        }
                    }
                    if (s.recent.isEmpty() && state.stickers.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Text("TRY", style = Kds.type.overline, color = Kds.colors.mutedForeground)
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            EXAMPLES.forEach { q -> KdsFilterChip(q, false, { actions.useRecent(q) }) }
                        }
                    }
                } else {
                    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (s.searching) SpinningLoader(14.dp, Kds.colors.mutedForeground)
                        Text(
                            when {
                                s.searching -> "Searching"
                                !s.searched -> ""
                                s.results.isEmpty() -> "No matches"
                                else -> "%,d matches · %d ms".format(s.results.size, s.millis)
                            },
                            style = Kds.type.caption.copy(fontFeatureSettings = "tnum"), color = Kds.colors.mutedForeground,
                        )
                    }
                    if (s.searched && !s.searching && s.results.isEmpty()) {
                        KdsEmptyState(R.drawable.ic_kds_search, "Nothing matched",
                            "Try other words, or fewer of them. Describing a few stickers yourself also helps.")
                    }
                }
            }
        }
        if (s.query.isNotBlank()) {
            items(s.results, key = { it.id }) { r ->
                Column(Modifier.clickable { onOpen(r) }) {
                    KdsMediaTile(onClick = { onOpen(r) }, label = tileLabel(r)) {
                        Thumb(r.thumbnailPath, Modifier.fillMaxSize(), placeholder = if (r.isLottie()) R.drawable.ic_kds_spark else R.drawable.ic_kds_image)
                    }
                    val why = s.matched[r.id].orEmpty().take(2).joinToString(" · ")
                    Text(why.ifEmpty { " " }, style = Kds.type.labelXs, color = Kds.colors.mutedForeground, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 2.dp, top = 2.dp))
                }
            }
        }
    }
}
