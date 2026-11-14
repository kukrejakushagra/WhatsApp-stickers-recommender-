package app.stickersearch.ui.kds

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.VerticalDivider
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
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
import app.stickersearch.ui.kds.theme.Kds

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
 * The app's two top-level destinations, as a solid bar docked to the bottom (portrait). Opaque, so
 * nothing scrolls behind it and labels stay readable; the whole cell is the touch target.
 */
@Composable
fun KdsBottomNav(items: List<NavItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Kds.colors
    Column(modifier.fillMaxWidth().background(c.background)) {
        HorizontalDivider(thickness = 1.dp, color = c.border)
        Box(Modifier.fillMaxWidth().navigationBarsPadding(), contentAlignment = Alignment.Center) {
            // Thumb-sized on tablets: the items stay together in the middle.
            Row(Modifier.widthIn(max = 480.dp).fillMaxWidth().height(64.dp)) {
                items.forEachIndexed { i, item ->
                    NavCell(item, i == selected, { onSelect(i) }, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

/**
 * The same destinations as a solid strip down the left edge (landscape), so the screen's full
 * height goes to content. Keeps clear of the status bar, navigation bar and display cutout.
 */
@Composable
fun KdsNavRail(items: List<NavItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Kds.colors
    Row(modifier.fillMaxHeight().background(c.background)) {
        Column(
            Modifier.fillMaxHeight()
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Start + WindowInsetsSides.Vertical))
                .width(76.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items.forEachIndexed { i, item ->
                NavCell(item, i == selected, { onSelect(i) }, Modifier.fillMaxWidth().height(68.dp))
            }
        }
        VerticalDivider(thickness = 1.dp, color = c.border)
    }
}

/** One destination: a solid pill behind the icon when selected, label below; 12 sp, high contrast. */
@Composable
private fun NavCell(item: NavItem, on: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val c = Kds.colors
    val pill by androidx.compose.animation.animateColorAsState(
        if (on) c.brandSoft else c.brandSoft.copy(alpha = 0f), androidx.compose.animation.core.tween(120), label = "pill",
    )
    Column(
        modifier.semantics { this.selected = on }
            .clickable(remember { MutableInteractionSource() }, null, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.width(64.dp).height(32.dp).background(pill, androidx.compose.foundation.shape.RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                KdsIcon(item.icon, 20.dp, if (on) c.brand else c.mutedForeground)
            }
            item.count?.let {
                KdsCountBadge(it, BadgeTone.BRAND, Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-6).dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            item.label, color = if (on) c.foreground else c.mutedForeground,
            style = Kds.type.labelXs.copy(fontSize = 12.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium),
        )
    }
}
