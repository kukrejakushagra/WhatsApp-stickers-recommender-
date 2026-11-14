package app.stickersearch.ui.kds

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.stickersearch.ui.kds.theme.Kds
import app.stickersearch.ui.kds.theme.KdsRadius

/**
 * Where a tooltip goes, in pixels: below its label if it fits, otherwise above; centred on the label
 * and clamped [margin] inside the window's safe area. [arrowX] is the arrow's centre, relative to the
 * card, still pointing at the label after clamping.
 */
object TooltipPlacement {
    data class Placement(val x: Int, val y: Int, val below: Boolean, val arrowX: Int)

    fun place(
        anchorLeft: Int, anchorTop: Int, anchorRight: Int, anchorBottom: Int,
        windowWidth: Int, windowHeight: Int, popupWidth: Int, popupHeight: Int,
        margin: Int, gap: Int, safeTop: Int = 0, safeBottom: Int = 0, arrowHalf: Int = 0,
    ): Placement {
        val centre = (anchorLeft + anchorRight) / 2
        val maxX = (windowWidth - margin - popupWidth).coerceAtLeast(margin)
        val x = (centre - popupWidth / 2).coerceIn(margin, maxX)
        val roomBelow = windowHeight - safeBottom - anchorBottom - gap
        val roomAbove = anchorTop - safeTop - gap
        val below = roomBelow >= popupHeight || roomBelow >= roomAbove
        val y = if (below) anchorBottom + gap else anchorTop - gap - popupHeight
        val arrowX = (centre - x).coerceIn(arrowHalf + margin / 2, (popupWidth - arrowHalf - margin / 2).coerceAtLeast(arrowHalf + margin / 2))
        return Placement(x, y.coerceAtLeast(safeTop), below, arrowX)
    }
}

private class TooltipPositioner(val margin: Int, val gap: Int, val safeTop: Int, val safeBottom: Int, val arrowHalf: Int) : PopupPositionProvider {
    var placement by mutableStateOf<TooltipPlacement.Placement?>(null)
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val p = TooltipPlacement.place(
            anchorBounds.left, anchorBounds.top, anchorBounds.right, anchorBounds.bottom,
            windowSize.width, windowSize.height, popupContentSize.width, popupContentSize.height,
            margin, gap, safeTop, safeBottom, arrowHalf,
        )
        if (p != placement) placement = p
        return IntOffset(p.x, p.y)
    }
}

/**
 * A small explanation next to the element it belongs to (call this inside a Box around that element).
 * Floats above everything, so nothing moves; tap outside or Back closes it.
 */
@Composable
fun KdsTooltip(text: String, onDismiss: () -> Unit) {
    val d = LocalDensity.current
    val c = Kds.colors
    val safeTop = WindowInsets.systemBars.getTop(d)
    val safeBottom = maxOf(WindowInsets.systemBars.getBottom(d), WindowInsets.ime.getBottom(d))
    val arrow = 10.dp
    val positioner = remember(safeTop, safeBottom) {
        with(d) { TooltipPositioner(12.dp.roundToPx(), 6.dp.roundToPx(), safeTop, safeBottom, (arrow / 2).roundToPx() + 4) }
    }
    val maxW = minOf(300, LocalConfiguration.current.screenWidthDp - 24).dp
    Popup(popupPositionProvider = positioner, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        val p = positioner.placement
        val shape = RoundedCornerShape(KdsRadius.lg)
        Column(horizontalAlignment = Alignment.Start) {
            if (p?.below != false) Arrow(p, arrow, below = true)
            Text(
                text, style = Kds.type.caption, color = c.background,
                modifier = Modifier.widthIn(max = maxW).shadow(10.dp, shape).background(c.foreground, shape).padding(12.dp),
            )
            if (p?.below == false) Arrow(p, arrow, below = false)
        }
    }
}

@Composable
private fun Arrow(p: TooltipPlacement.Placement?, size: androidx.compose.ui.unit.Dp, below: Boolean) {
    val d = LocalDensity.current
    val x = with(d) { ((p?.arrowX ?: 0).toDp() - size / 2) }
    // A small square turned 45°, half hidden under the card, so a point shows towards the label.
    Box(Modifier.offset(x = x, y = if (below) size / 2 else -size / 2).size(size).rotate(45f).background(Kds.colors.foreground))
}
