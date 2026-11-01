package com.kukreja.stickersearch.ui.kds

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kukreja.stickersearch.ui.kds.theme.Kds

/** Screen title row: title style, 16 top / 20 sides / 12 bottom, one trailing action at most. */
@Composable
fun KdsAppBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    inset: Dp = 20.dp,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().padding(start = inset, end = inset, top = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Kds.type.title, color = Kds.colors.foreground)
            subtitle?.let { Text(it, style = Kds.type.caption, color = Kds.colors.mutedForeground) }
        }
        trailing?.invoke()
    }
}

data class NavItem(val label: String, @DrawableRes val icon: Int, val count: Int? = null)

/**
 * Floating glass capsule with the app's top-level destinations. A glass-lens droplet sits behind the
 * current destination and springs to the next one.
 */
@Composable
fun KdsBottomNav(items: List<NavItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Kds.colors
    BoxWithConstraints(
        modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp)
            .height(64.dp).glass(Glass.PANEL, CircleShape, elevation = 10.dp).padding(4.dp),
    ) {
        val itemW = maxWidth / items.size
        val x by animateDpAsState(
            itemW * selected,
            spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow),
            label = "droplet",
        )
        Box(Modifier.offset { IntOffset(x.roundToPx(), 0) }.width(itemW).fillMaxHeight().glass(Glass.LENS, CircleShape, elevation = 2.dp))
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            items.forEachIndexed { i, item ->
                val on = i == selected
                Column(
                    Modifier.weight(1f).fillMaxHeight()
                        .semantics { this.selected = on }
                        .clickable(remember { MutableInteractionSource() }, null, role = Role.Tab) { onSelect(i) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box {
                        KdsIcon(item.icon, 18.dp, if (on) c.foreground else c.mutedForeground)
                        item.count?.let {
                            KdsCountBadge(it, BadgeTone.BRAND, Modifier.align(Alignment.TopEnd).offset(x = 12.dp, y = (-8).dp))
                        }
                    }
                    Text(item.label, style = Kds.type.labelXs, color = if (on) c.foreground else c.mutedForeground)
                }
            }
        }
    }
}
