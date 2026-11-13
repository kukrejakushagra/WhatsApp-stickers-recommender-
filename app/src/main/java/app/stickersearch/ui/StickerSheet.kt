package app.stickersearch.ui

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.LruCache
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieDrawable
import app.stickersearch.R
import app.stickersearch.data.StickerRow
import app.stickersearch.data.StickerStatus
import app.stickersearch.index.WasRenderer
import app.stickersearch.ml.Correction
import app.stickersearch.model.StickerFormat
import app.stickersearch.share.StickerSharer
import app.stickersearch.ui.kds.BadgeTone
import app.stickersearch.ui.kds.ButtonSize
import app.stickersearch.ui.kds.ButtonVariant
import app.stickersearch.ui.kds.CalloutTone
import app.stickersearch.ui.kds.KdsBadge
import app.stickersearch.ui.kds.KdsFilterChip
import app.stickersearch.ui.kds.KdsButton
import app.stickersearch.ui.kds.KdsCallout
import app.stickersearch.ui.kds.KdsEmptyState
import app.stickersearch.ui.kds.KdsIcon
import app.stickersearch.ui.kds.KdsKeyValue
import app.stickersearch.ui.kds.KdsSheet
import app.stickersearch.ui.kds.KdsTextField
import app.stickersearch.ui.kds.KdsToastHost
import app.stickersearch.ui.kds.theme.Kds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun StickerRow.isLottie() = format == StickerFormat.LOTTIE_WAS.name
internal fun StickerRow.needsAttention() = status == StickerStatus.FAILED || status == StickerStatus.NEEDS_REVIEW

internal fun tileLabel(s: StickerRow) = when {
    s.status == StickerStatus.FAILED -> "CHECK"
    s.isLottie() -> "LOTTIE"
    s.status == StickerStatus.NEEDS_REVIEW -> "REVIEW"
    s.isAnimated -> "ANIM"
    else -> null
}

/** Decoded thumbnails, capped by bytes (not count) so a long scroll can't balloon memory and stall on GC. */
private val thumbCache = object : LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
}

@Composable
internal fun Thumb(path: String?, modifier: Modifier, @DrawableRes placeholder: Int = R.drawable.ic_kds_image) {
    val bmp by produceState(path?.let { thumbCache.get(it) }, path) {
        if (path != null && value == null) {
            value = withContext(Dispatchers.IO) {
                // prepareToDraw uploads the pixels off the main thread, so the first frame doesn't hitch.
                BitmapFactory.decodeFile(path)?.also { it.prepareToDraw() }?.asImageBitmap()?.also { thumbCache.put(path, it) }
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

private fun pretty(label: String) = label.replace('_', ' ')

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StickerSheet(
    s: StickerRow,
    actions: AppActions,
    state: UiState,
    onOpen: (StickerRow) -> Unit,
    onSearch: (String) -> Unit = {},
    onDismiss: () -> Unit,
) {
    val message = state.message
    var choosingPack by remember { mutableStateOf(false) }
    if (choosingPack) {
        AddToPackDialog(s, state, state.search.query.trim().ifBlank { "My stickers" }, actions) { choosingPack = false }
    }
    var reload by remember { mutableIntStateOf(0) }
    // Switching to a related sticker keeps the previous details until the new ones arrive, so the
    // page doesn't collapse and its scroll position stays where it was.
    var details by remember { mutableStateOf<StickerDetails?>(null) }
    LaunchedEffect(s.id, reload, s.status) { details = actions.details(s.id) }
    var editing by remember(s.id) { mutableStateOf(false) }
    var showFiles by remember(s.id) { mutableStateOf(false) }
    val a = details?.annotation
    val dims = if (s.width > 0) "${s.width}×${s.height} · " else ""
    val dur = s.durationMs?.takeIf { it > 0 }?.let { "%.1f s · ".format(it / 1000.0) } ?: ""
    KdsSheet(
        title = if (editing) "Edit description" else formatName(s),
        subtitle = if (editing) "Your changes are kept when stickers are analysed again" else "$dims$dur${"%,d".format(s.sizeBytes / 1024)} KB",
        onDismiss = onDismiss,
        // The playing preview stays pinned at the top while the rest scrolls.
        header = if (editing) null else {
            {
                Box(
                    Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(18.dp)).background(Kds.colors.glassClear),
                    contentAlignment = Alignment.Center,
                ) {
                    val uri = s.primaryUri?.let(Uri::parse)
                    when {
                        uri == null -> KdsEmptyState(R.drawable.ic_kds_image, "File missing", "Run a scan to find it again.")
                        s.isLottie() -> LottiePreview(uri, Modifier.fillMaxSize().padding(12.dp))
                        else -> AnimatedPreview(uri, Modifier.fillMaxSize().padding(12.dp))
                    }
                }
            }
        },
        footer = if (editing) null else {
            {
                // The sheet covers the app's own toasts, so messages about sending show here.
                KdsToastHost(message, actions.messageShown, Modifier.align(Alignment.CenterHorizontally))
                KdsButton(if (state.sendingId == s.id) "Preparing for WhatsApp" else "Send in WhatsApp", { actions.sendViaWhatsApp(s) },
                    size = ButtonSize.LG, block = true, loading = state.sendingId == s.id,
                    icon = R.drawable.ic_kds_send, enabled = s.primaryUri != null && !s.isLottie())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KdsButton("Add to pack", { choosingPack = true }, variant = ButtonVariant.OUTLINE, modifier = Modifier.weight(1f),
                        icon = R.drawable.ic_kds_folder, enabled = s.primaryUri != null && !s.isLottie())
                    KdsButton("Share as photo", { actions.send(s, StickerSharer.Mode.SYSTEM_SHEET) },
                        variant = ButtonVariant.GHOST, modifier = Modifier.weight(1f), icon = R.drawable.ic_kds_share, enabled = s.primaryUri != null)
                }
            }
        },
    ) {
        if (editing) {
            EditForm(s.id, details, actions, onDone = { editing = false; reload++ })
            return@KdsSheet
        }
        if (a != null) {
            Text(a.description, style = Kds.type.body, color = Kds.colors.foreground)
            if (a.text.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text("Says “${a.text}”", style = Kds.type.caption, color = Kds.colors.mutedForeground)
            }
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                a.emotions.forEach { KdsBadge(pretty(it), BadgeTone.SOFT) }
                a.intents.forEach { KdsBadge(pretty(it), BadgeTone.SECONDARY) }
            }
            Keywords(a, onSearch)
            Spacer(Modifier.height(8.dp))
            Text(
                if (details?.corrected == true) "Edited by you · saved on this phone"
                else "Described on this phone · saved, so the AI never redoes it",
                style = Kds.type.labelXs, color = Kds.colors.mutedForeground,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KdsButton("Edit", { editing = true }, variant = ButtonVariant.OUTLINE, size = ButtonSize.SM)
                if (details?.corrected == true) {
                    KdsButton("Use AI description", { actions.resetCorrection(s.id); reload++ }, variant = ButtonVariant.GHOST, size = ButtonSize.SM)
                    KdsBadge("Edited by you", BadgeTone.SECONDARY)
                } else if ((details?.confidence ?: 1f) in 0.01f..0.45f) {
                    KdsBadge("Low confidence", BadgeTone.WARNING)
                }
            }
        } else if (s.status == StickerStatus.PENDING) {
            KdsCallout("Not described yet. It's found by how it looks until the AI describes it, usually overnight while charging.",
                CalloutTone.NEUTRAL)
            Spacer(Modifier.height(8.dp))
            KdsButton("Describe it myself", { editing = true }, variant = ButtonVariant.OUTLINE, size = ButtonSize.SM)
        }
        s.statusError?.takeIf { s.needsAttention() }?.let {
            Spacer(Modifier.height(10.dp))
            KdsCallout(
                if (s.isLottie()) "This Lottie file has no animation we can read yet: $it" else it,
                if (s.status == StickerStatus.FAILED) CalloutTone.ERROR else CalloutTone.WARNING,
            )
            Spacer(Modifier.height(8.dp))
            KdsButton("Try again", { actions.scan(true) }, variant = ButtonVariant.OUTLINE, size = ButtonSize.SM, icon = R.drawable.ic_kds_refresh)
        }
        val fr = details?.frames.orEmpty()
        if (fr.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text("KEYFRAMES", style = Kds.type.overline, color = Kds.colors.mutedForeground)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                fr.forEach { f ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(Kds.colors.glassClear)
                                .border(1.dp, Kds.colors.glassBorder, RoundedCornerShape(8.dp)).padding(4.dp),
                        ) { Thumb(f.framePath, Modifier.fillMaxSize()) }
                        Text("%.1f s".format(f.timestampMs / 1000.0), style = Kds.type.labelXs.copy(fontFeatureSettings = "tnum"),
                            color = Kds.colors.mutedForeground)
                    }
                }
            }
        }
        details?.let { d ->
            if (d.story.size > 1) RelatedRow("Part of a story · ${d.story.indexOfFirst { it.id == s.id } + 1} of ${d.story.size}", d.story, s.id, onOpen, actions.sendViaWhatsApp)
            if (d.looks.isNotEmpty()) RelatedRow("Looks alike", d.looks, s.id, onOpen, actions.sendViaWhatsApp)
            d.topics.forEach { (label, rows) -> RelatedRow("Also about $label", rows, s.id, onOpen, actions.sendViaWhatsApp) }
        }
        Spacer(Modifier.height(10.dp))
        if (showFiles) {
            KdsKeyValue("Hash", s.sha256.take(16) + "…", mono = true)
            KdsKeyValue("Status", s.status.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() })
            details?.paths?.forEach { KdsKeyValue("File", it.substringAfterLast("Media/"), mono = true) }
        } else {
            KdsButton("Show file details", { showFiles = true }, variant = ButtonVariant.GHOST, size = ButtonSize.SM)
        }
    }
}

@Composable
private fun EditForm(id: Long, details: StickerDetails?, actions: AppActions, onDone: () -> Unit) {
    val a = details?.annotation
    var description by remember(a) { mutableStateOf(a?.description.orEmpty()) }
    var emotions by remember(a) { mutableStateOf(a?.emotions.orEmpty().joinToString(", ") { pretty(it) }) }
    var intents by remember(a) { mutableStateOf(a?.intents.orEmpty().joinToString(", ") { pretty(it) }) }
    var tags by remember(a) { mutableStateOf(a?.tags.orEmpty().joinToString(", ")) }
    fun list(s: String, label: Boolean) = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        .map { if (label) it.lowercase().replace(' ', '_') else it.lowercase() }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        KdsTextField("Description", description, { description = it }, minLines = 2,
            hint = "What it shows and what it says, e.g. “Cat rolling its eyes, not impressed”")
        KdsTextField("Emotions", emotions, { emotions = it }, hint = "Comma separated, e.g. sarcasm, amusement")
        KdsTextField("When to use it", intents, { intents = it }, hint = "e.g. mocking friend, saying I told you so")
        KdsTextField("Tags", tags, { tags = it }, hint = "Words you'd search for, in any language")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            KdsButton("Cancel", onDone, variant = ButtonVariant.OUTLINE)
            KdsButton("Save", {
                actions.saveCorrection(id, Correction(
                    description = description.trim().takeIf { it.isNotEmpty() },
                    emotions = list(emotions, true), intents = list(intents, true), tags = list(tags, false),
                ))
                onDone()
            }, variant = ButtonVariant.BRAND)
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** Plays the original file; animated WebP stays animated (AnimatedImageDrawable). */
@Composable
internal fun AnimatedPreview(uri: Uri, modifier: Modifier) {
    val context = LocalContext.current
    // Keeps showing the previous sticker until this one has loaded, instead of flashing "Loading".
    var drawable by remember { mutableStateOf<Drawable?>(null) }
    LaunchedEffect(uri) {
        drawable = withContext(Dispatchers.IO) {
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
internal fun LottiePreview(uri: Uri, modifier: Modifier) {
    val context = LocalContext.current
    var result by remember { mutableStateOf<WasRenderer.Result?>(null) }
    LaunchedEffect(uri) {
        result = withContext(Dispatchers.IO) {
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

/** A titled, scrolling row of related stickers; the current one is outlined. */
@Composable
private fun RelatedRow(title: String, rows: List<StickerRow>, currentId: Long, onOpen: (StickerRow) -> Unit, onSend: (StickerRow) -> Unit = {}) {
    Spacer(Modifier.height(14.dp))
    Text(title.uppercase(), style = Kds.type.overline, color = Kds.colors.mutedForeground)
    Spacer(Modifier.height(6.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { r ->
            val current = r.id == currentId
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(Kds.colors.glassClear)
                    .border(if (current) 2.dp else 1.dp, if (current) Kds.colors.brand else Kds.colors.glassBorder, RoundedCornerShape(8.dp))
                    .then(if (current) Modifier else Modifier.combinedClickable(onLongClick = { onSend(r) }) { onOpen(r) }).padding(4.dp),
            ) { Thumb(r.thumbnailPath, Modifier.fillMaxSize(), placeholder = if (r.isLottie()) R.drawable.ic_kds_spark else R.drawable.ic_kds_image) }
        }
    }
}

/** Tags and search phrases (English, Hinglish, Hindi); tapping one searches for it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Keywords(a: app.stickersearch.ml.Annotation, onSearch: (String) -> Unit) {
    val tags = a.tags.distinct()
    val phrases = a.phrases.filter { p -> tags.none { it.equals(p, ignoreCase = true) } }.distinct()
    if (tags.isEmpty() && phrases.isEmpty() && a.emoji.isBlank()) return
    Spacer(Modifier.height(14.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("KEYWORDS", style = Kds.type.overline, color = Kds.colors.mutedForeground)
        if (a.emoji.isNotBlank()) Text(a.emoji, style = Kds.type.heading)
    }
    if (tags.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            tags.forEach { t -> KdsFilterChip(t, false, { onSearch(t) }) }
        }
    }
    if (phrases.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        Text("Search phrases", style = Kds.type.caption, color = Kds.colors.mutedForeground)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            phrases.forEach { t -> KdsFilterChip(t, false, { onSearch(t) }) }
        }
    }
}
