package app.stickersearch.ui.kds

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.stickersearch.ui.kds.theme.Kds
import app.stickersearch.ui.kds.theme.KdsMotion
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A draggable handle on the right edge of a long grid. Dragging jumps through the list; while held,
 * a bubble shows [label] for the item under the handle (e.g. the month). Shown only for long lists.
 */
@Composable
fun KdsFastScroller(
    state: LazyGridState,
    itemCount: Int,
    modifier: Modifier = Modifier,
    topInset: androidx.compose.ui.unit.Dp = 0.dp,
    bottomInset: androidx.compose.ui.unit.Dp = 0.dp,
    label: (Int) -> String? = { null },
) {
    if (itemCount < 60) return
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val scrolled by remember(state, itemCount) {
        derivedStateOf { (state.firstVisibleItemIndex.toFloat() / (itemCount - 1).coerceAtLeast(1)).coerceIn(0f, 1f) }
    }
    val fraction = if (dragging) dragFraction else scrolled
    val handleW by animateDpAsState(if (dragging) 32.dp else 26.dp, tween(KdsMotion.QUICK), label = "handleW")
    val handleH by animateDpAsState(if (dragging) 112.dp else 96.dp, tween(KdsMotion.QUICK), label = "handleH")
    BoxWithConstraints(modifier.fillMaxHeight().width(200.dp).padding(top = topInset, bottom = bottomInset)) {
        val density = LocalDensity.current
        val trackPx = with(density) { (maxHeight - handleH).toPx() }.coerceAtLeast(1f)
        fun jump(f: Float) {
            dragFraction = f.coerceIn(0f, 1f)
            val target = (dragFraction * (itemCount - 1)).roundToInt()
            scope.launch { state.scrollToItem(target) }
        }
        val y = (fraction * trackPx).roundToInt()
        // A visible track, so the scroller is easy to find before touching it.
        Box(
            Modifier.align(Alignment.TopEnd).padding(end = 15.dp).fillMaxHeight().width(6.dp)
                .background(Kds.colors.glassTrack, CircleShape),
        )
        // 64dp touch strip: well over the 48dp minimum, easy to catch with a thumb.
        Box(
            Modifier.align(Alignment.TopEnd).fillMaxHeight().width(64.dp)
                .pointerInput(itemCount, trackPx) {
                    detectVerticalDragGestures(
                        onDragStart = { o -> dragging = true; jump((o.y - handleH.toPx() / 2) / trackPx) },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                        onVerticalDrag = { change, _ -> change.consume(); jump((change.position.y - handleH.toPx() / 2) / trackPx) },
                    )
                }
                .semantics { contentDescription = "Fast scroll" },
        ) {
            Column(
                Modifier.align(Alignment.TopEnd).offset { IntOffset(0, y) }.padding(end = 18.dp - handleW / 2) // centred on the track
                    .width(handleW).height(handleH)
                    .glass(if (dragging) Glass.LENS else Glass.CONTROL, CircleShape, elevation = if (dragging) 6.dp else 2.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Grip lines.
                repeat(3) {
                    Box(Modifier.width(handleW * 0.5f).height(3.dp)
                        .background(if (dragging) Kds.colors.foreground else Kds.colors.mutedForeground, CircleShape))
                }
            }
        }
        AnimatedVisibility(
            visible = dragging,
            enter = fadeIn(tween(KdsMotion.QUICK)), exit = fadeOut(tween(KdsMotion.STANDARD)),
            // Beside the handle, vertically centred on it, clear of the thumb.
            modifier = Modifier.align(Alignment.TopEnd).offset {
                IntOffset(-with(density) { 72.dp.roundToPx() }, y + with(density) { (handleH / 2 - 26.dp).roundToPx() })
            },
        ) {
            val text = label((dragFraction * (itemCount - 1)).roundToInt()) ?: "${(dragFraction * 100).roundToInt()}%"
            Text(
                text, style = Kds.type.heading.copy(fontFeatureSettings = "tnum", fontSize = 20.sp), color = Kds.colors.background,
                modifier = Modifier.background(Kds.colors.foreground.copy(alpha = 0.94f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }
}
