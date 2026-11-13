package app.stickersearch.ui.kds

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.stickersearch.ui.kds.theme.Kds
import app.stickersearch.ui.kds.theme.KdsColors

/**
 * The surface every glass panel sits on: the plain background with one soft brand glow at the top,
 * behind the search bar ("top glow"). Static, drawn once per size.
 */
@Composable
fun KdsGround(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val c = Kds.colors
    Box(
        modifier
            .background(c.background)
            .drawBehind {
                val w = size.width
                val h = size.height
                // An ellipse 120% of the width across and 34% of the height down, centred on the top
                // edge: a circular gradient squashed vertically.
                val rx = w * 1.2f
                val ry = h * 0.34f
                val glow = Brush.radialGradient(
                    0f to c.brand.copy(alpha = 0.34f), 0.78f to c.brand.copy(alpha = 0f),
                    center = Offset(w / 2f, 0f), radius = rx,
                )
                scale(scaleX = 1f, scaleY = ry / rx, pivot = Offset(w / 2f, 0f)) {
                    drawRect(glow, topLeft = Offset(0f, 0f), size = androidx.compose.ui.geometry.Size(w, rx))
                }
            },
        content = content,
    )
}

/** Glass materials by role. */
enum class Glass { PANEL, FLOATING, CONTROL, LENS }

private fun KdsColors.fill(m: Glass) = when (m) {
    Glass.PANEL -> glass
    Glass.FLOATING -> glassThick
    Glass.CONTROL -> glassClear
    Glass.LENS -> glassLens
}

/**
 * Liquid glass without backdrop refraction: translucent fill over the colour field, a hairline
 * glass-border, a specular rim (bright top-left, faint bottom-right) and a soft resting shadow.
 */
@Composable
fun Modifier.glass(material: Glass, shape: Shape, elevation: Dp = 0.dp): Modifier {
    val c = Kds.colors
    val rim = Brush.linearGradient(listOf(c.glassHighlight, c.glassBorder, c.glassLowlight))
    val shadowColor = if (Kds.isDark) Color(0x8C000000) else Color(0x24000000)
    val withShadow = if (elevation > 0.dp) {
        this.shadow(elevation, shape, clip = false, ambientColor = shadowColor, spotColor = shadowColor)
    } else this
    return withShadow
        .background(c.fill(material), shape)
        .border(1.dp, rim, shape)
}

/** Solid tint (primary / brand / destructive fills at 94%), with the tinted rim. */
@Composable
fun Modifier.tint(color: Color, shape: Shape): Modifier =
    this.background(color.copy(alpha = color.alpha * 0.94f), shape)
        .border(1.dp, Brush.linearGradient(listOf(Kds.colors.glassTintRim, Color.Transparent)), shape)
