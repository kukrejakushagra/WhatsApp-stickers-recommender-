package com.kukreja.stickersearch.ui.kds

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kukreja.stickersearch.ui.kds.theme.Kds
import com.kukreja.stickersearch.ui.kds.theme.KdsColors

/**
 * The colour field every glass surface sits on: background, three soft blobs and a 14dp dot grid.
 * Static, drawn once per size.
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
                fun blob(color: Color, cx: Float, cy: Float, r: Float) = drawCircle(
                    Brush.radialGradient(listOf(color, color.copy(alpha = 0f)), center = Offset(cx, cy), radius = r),
                    radius = r, center = Offset(cx, cy),
                )
                blob(c.field1, w * 0.12f, h * 0.10f, w * 0.85f)
                blob(c.field2, w * 0.95f, h * 0.38f, w * 0.75f)
                blob(c.field3, w * 0.30f, h * 0.92f, w * 0.80f)
                val step = 14.dp.toPx()
                val dot = 1.dp.toPx()
                var y = step / 2
                while (y < h) {
                    var x = step / 2
                    while (x < w) {
                        drawCircle(c.fieldDot, radius = dot, center = Offset(x, y))
                        x += step
                    }
                    y += step
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
