package com.kukreja.stickersearch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
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
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KdsSearchField(
                        value = s.query, onValueChange = actions.onQueryChange,
                        onSearch = { if (assist.attachments.isNotEmpty()) actions.runAssist() else actions.submitSearch() },
                        placeholder = if (assist.attachments.isEmpty()) "Describe what you want to say" else "Add what you want to say (optional)",
                        modifier = Modifier.weight(1f),
                    )
                    KdsIconButton(R.drawable.ic_kds_image, "Add screenshots or images", actions.attachImages)
                }
                if (assist.attachments.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    AttachmentRow(assist.attachments, actions.removeAttachment)
                    Spacer(Modifier.height(10.dp))
                    KdsButton(
                        if (assist.running) assist.phase ?: "Working" else "Suggest stickers", actions.runAssist,
                        size = ButtonSize.LG, block = true, loading = assist.running, icon = R.drawable.ic_kds_spark,
                    )
                    Text(
                        "Reads the chat in your screenshots and finds a fitting reply, plus any of your stickers that appear in them. " +
                            "Images stay on this phone.",
                        style = Kds.type.caption, color = Kds.colors.mutedForeground, modifier = Modifier.padding(top = 6.dp),
                    )
                }
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
                if (assist.attachments.isNotEmpty()) {
                    AssistHeader(assist, actions)
                } else if (s.query.isBlank()) {
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
        if (assist.attachments.isNotEmpty() && assist.done) {
            if (assist.suggestions.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel("Suggested replies") }
                items(assist.suggestions, key = { "s${it.id}" }) { r -> ResultTile(r, emptyList(), onOpen) }
            }
            if (assist.found.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel("In your images") }
                items(assist.found, key = { "f${it.id}" }) { r -> ResultTile(r, emptyList(), onOpen) }
            }
        } else if (s.query.isNotBlank()) {
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

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), style = Kds.type.overline, color = Kds.colors.mutedForeground,
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
}

@Composable
private fun ResultTile(r: StickerRow, why: List<String>, onOpen: (StickerRow) -> Unit) {
    Column {
        KdsMediaTile(onClick = { onOpen(r) }, label = tileLabel(r)) {
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
private fun AssistHeader(assist: AssistState, actions: AppActions) {
    if (!assist.done) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        assist.reading?.let { KdsCallout(it, CalloutTone.BRAND, title = "What's going on") }
        if (assist.queries.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                assist.queries.forEach { q -> KdsFilterChip(q, false, { actions.clearAssist(); actions.useRecent(q) }) }
            }
        }
        assist.note?.let { KdsCallout(it, CalloutTone.NEUTRAL) }
        if (assist.suggestions.isEmpty() && assist.found.isEmpty()) {
            KdsEmptyState(R.drawable.ic_kds_search, "Nothing fitting yet",
                "Add a few words about what you want to say, or let more stickers get described overnight.")
        } else {
            Text("%d ms".format(assist.millis), style = Kds.type.caption.copy(fontFeatureSettings = "tnum"), color = Kds.colors.mutedForeground)
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
