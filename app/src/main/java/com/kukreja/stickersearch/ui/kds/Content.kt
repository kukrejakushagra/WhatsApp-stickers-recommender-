package com.kukreja.stickersearch.ui.kds

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kukreja.stickersearch.R
import com.kukreja.stickersearch.ui.kds.theme.Kds
import com.kukreja.stickersearch.ui.kds.theme.KdsMotion
import com.kukreja.stickersearch.ui.kds.theme.KdsRadius
import com.kukreja.stickersearch.ui.kds.theme.KdsSize

// ---------- containers ----------

/** A free-form panel-glass block (radius-xl). */
@Composable
fun KdsPanel(modifier: Modifier = Modifier, padding: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(KdsRadius.xl)
    Column(
        modifier.fillMaxWidth().glass(Glass.PANEL, shape, elevation = 4.dp).clip(shape)
            .then(if (padding) Modifier.padding(horizontal = 20.dp, vertical = 16.dp) else Modifier),
        content = content,
    )
}

/** Uppercase overline header over one radius-2xl glass box of rows divided by hairlines. */
@Composable
fun KdsSettingsGroup(label: String, rows: List<@Composable () -> Unit>, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(KdsRadius.x2l)
    Column(modifier.fillMaxWidth()) {
        Text(
            label.uppercase(), style = Kds.type.overline, color = Kds.colors.mutedForeground,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        Column(Modifier.fillMaxWidth().glass(Glass.PANEL, shape, elevation = 4.dp).clip(shape)) {
            rows.forEachIndexed { i, row ->
                if (i > 0) HorizontalDivider(thickness = 1.dp, color = Kds.colors.border, modifier = Modifier.padding(start = 52.dp))
                row()
            }
        }
    }
}

/** One setting: 20px icon, title, optional description, a control at the end. */
@Composable
fun KdsSettingsRow(
    title: String,
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    description: String? = null,
    danger: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = Kds.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = KdsSize.rowMin)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        KdsIcon(icon, 20.dp, if (danger) c.destructiveText else c.mutedForeground)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Kds.type.bodyStrong, color = if (danger) c.destructiveText else c.foreground)
            description?.let { Text(it, style = Kds.type.caption, color = c.mutedForeground) }
        }
        when {
            trailing != null -> trailing()
            onClick != null -> KdsIcon(R.drawable.ic_kds_chev_r, 16.dp, c.mutedForeground)
        }
    }
}

// ---------- badges, chips, progress ----------

enum class BadgeTone { SOFT, SECONDARY, WARNING, DANGER, BRAND, PRIMARY }

/** One or two words of status. The word carries the meaning; colour only repeats it. */
@Composable
fun KdsBadge(text: String, tone: BadgeTone, modifier: Modifier = Modifier) {
    val c = Kds.colors
    val (bg, fg) = when (tone) {
        BadgeTone.SOFT -> c.brandSoft to c.brand
        BadgeTone.SECONDARY -> c.secondary to c.secondaryForeground
        BadgeTone.WARNING -> c.warningSoft to c.warning
        BadgeTone.DANGER -> c.destructive.copy(alpha = 0.12f) to c.destructiveText
        BadgeTone.BRAND -> c.brand to c.brandForeground
        BadgeTone.PRIMARY -> c.primary to c.primaryForeground
    }
    Text(
        text, style = Kds.type.labelXs, color = fg,
        modifier = modifier.background(bg, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** 18px count pill in micro type: brand while in progress, primary for new, destructive for failed. */
@Composable
fun KdsCountBadge(count: Int, tone: BadgeTone, modifier: Modifier = Modifier) {
    val c = Kds.colors
    val (bg, fg) = when (tone) {
        BadgeTone.BRAND -> c.brand to c.brandForeground
        BadgeTone.DANGER -> c.destructive to c.destructiveForeground
        else -> c.primary to c.primaryForeground
    }
    Box(
        modifier.heightIn(min = 18.dp).widthIn(min = 18.dp).background(bg, CircleShape).padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) { Text(if (count > 99) "99+" else "$count", style = Kds.type.micro, color = fg, lineHeight = 18.sp) }
}

/** 32px filter pill; selected fills with primary. */
@Composable
fun KdsFilterChip(label: String, selected: Boolean, onClick: () -> Unit, count: Int? = null) {
    val c = Kds.colors
    val shape = CircleShape
    Row(
        Modifier.height(KdsSize.controlXs).clip(shape)
            .then(if (selected) Modifier.tint(c.primary, shape) else Modifier.glass(Glass.CONTROL, shape))
            .semantics { this.selected = selected }
            .clickable(role = Role.Checkbox, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val fg = if (selected) c.primaryForeground else c.foreground
        Text(label, style = Kds.type.chip, color = fg)
        count?.let {
            Text("%,d".format(it), style = Kds.type.labelXs.copy(fontFeatureSettings = "tnum"),
                color = if (selected) c.primaryForeground.copy(alpha = 0.7f) else c.mutedForeground)
        }
    }
}

/** 6px determinate bar: brand fill on a secondary track, easing over 200ms. */
@Composable
fun KdsProgress(fraction: Float?, modifier: Modifier = Modifier) {
    val c = Kds.colors
    val f by animateFloatAsState((fraction ?: 0f).coerceIn(0f, 1f), tween(KdsMotion.STANDARD, easing = KdsMotion.Standard), label = "progress")
    Box(modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(c.secondary)) {
        Box(Modifier.fillMaxWidth(if (fraction == null) 0.3f else f).height(6.dp).clip(CircleShape).background(c.brand))
    }
}

// ---------- messages ----------

enum class CalloutTone { NEUTRAL, BRAND, WARNING, ERROR }

/** Inline message: 12px text, 14px icon, radius-lg. */
@Composable
fun KdsCallout(text: String, tone: CalloutTone, modifier: Modifier = Modifier, title: String? = null, @DrawableRes icon: Int? = null) {
    val c = Kds.colors
    val (bg, fg) = when (tone) {
        CalloutTone.NEUTRAL -> c.secondary.copy(alpha = 0.9f) to c.mutedForeground
        CalloutTone.BRAND -> c.brandSoft.copy(alpha = 0.9f) to c.brand
        CalloutTone.WARNING -> c.warningSoft.copy(alpha = 0.9f) to c.warning
        CalloutTone.ERROR -> c.destructive.copy(alpha = 0.1f) to c.destructiveText
    }
    val ic = icon ?: when (tone) {
        CalloutTone.ERROR, CalloutTone.WARNING -> R.drawable.ic_kds_alert
        CalloutTone.BRAND -> R.drawable.ic_kds_spark
        CalloutTone.NEUTRAL -> R.drawable.ic_kds_lock
    }
    val shape = RoundedCornerShape(KdsRadius.lg)
    Row(
        modifier.fillMaxWidth().background(bg, shape).border(1.dp, c.glassBorder, shape).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        KdsIcon(ic, 14.dp, fg, Modifier.padding(top = 1.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            title?.let { Text(it, style = Kds.type.caption.copy(fontWeight = Kds.type.bodyStrong.fontWeight), color = c.foreground) }
            Text(text, style = Kds.type.caption, color = if (tone == CalloutTone.NEUTRAL) c.mutedForeground else c.foreground)
        }
    }
}

/** What an empty list says, and the one way to fill it. */
@Composable
fun KdsEmptyState(
    @DrawableRes icon: Int,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val c = Kds.colors
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(48.dp).background(c.secondary, CircleShape), contentAlignment = Alignment.Center) {
            KdsIcon(icon, 20.dp, c.mutedForeground)
        }
        Text(title, style = Kds.type.bodyStrong, color = c.foreground)
        Text(body, style = Kds.type.body, color = c.mutedForeground, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 260.dp))
        if (actionLabel != null && onAction != null) {
            KdsButton(actionLabel, onAction, Modifier.padding(top = 8.dp), ButtonVariant.OUTLINE)
        }
    }
}

/** One headline number with its label. */
@Composable
fun KdsStatCard(value: String, label: String, modifier: Modifier = Modifier, valueColor: Color = Kds.colors.foreground) {
    val shape = RoundedCornerShape(KdsRadius.xl)
    Column(modifier.glass(Glass.PANEL, shape, elevation = 4.dp).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(value, style = Kds.type.stat, color = valueColor, maxLines = 1)
        Text(label, style = Kds.type.chip.copy(fontWeight = Kds.type.body.fontWeight), color = Kds.colors.mutedForeground, maxLines = 1)
    }
}

/**
 * Square thumbnail tile (radius-md) with an optional white micro label on media-overlay.
 * When [selectable], a 24px dot sits top-right (white ring off, brand with a tick on), and a picked
 * tile shrinks to 84% with radius-xl.
 */
@Composable
fun KdsMediaTile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    selectable: Boolean = false,
    picked: Boolean = false,
    /** Long-press action (e.g. send straight to WhatsApp); a haptic tick confirms it. */
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val c = Kds.colors
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val scale by animateFloatAsState(if (selectable && picked) 0.84f else 1f, tween(KdsMotion.STANDARD, easing = KdsMotion.Standard), label = "pick")
    val shape = RoundedCornerShape(if (selectable && picked) KdsRadius.xl else KdsRadius.md)
    Box(modifier.aspectRatio(1f)) {
    Box(
        Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape).background(c.glassClear).border(if (picked) 2.dp else 1.dp, if (picked) c.brand else c.glassBorder, shape)
            .combinedClickable(
                role = if (selectable) Role.Checkbox else Role.Image, onClick = onClick,
                onLongClickLabel = if (onLongClick != null) "Send in WhatsApp" else null,
                onLongClick = onLongClick?.let { f -> { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); f() } },
            )
            .semantics { if (selectable) selected = picked },
    ) {
        Box(Modifier.fillMaxSize().padding(6.dp), contentAlignment = Alignment.Center, content = content)
        label?.let {
            Text(
                it, style = Kds.type.micro, color = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)
                    .background(c.mediaOverlay, RoundedCornerShape(KdsRadius.xs)).padding(horizontal = 4.dp),
            )
        }
    }
    if (selectable) {
        Box(
            Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp)
                .then(if (picked) Modifier.background(c.brand, CircleShape) else Modifier.background(c.mediaOverlay.copy(alpha = 0.25f), CircleShape))
                .border(2.dp, if (picked) c.brand else Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (picked) KdsIcon(R.drawable.ic_kds_check, 14.dp, c.brandForeground) }
    }
    }
}

/** Key–value line for detail sheets. */
@Composable
fun KdsKeyValue(key: String, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(key, style = Kds.type.caption, color = Kds.colors.mutedForeground, modifier = Modifier.widthIn(min = 84.dp))
        Text(value, style = if (mono) Kds.type.mono else Kds.type.caption, color = Kds.colors.foreground, modifier = Modifier.weight(1f))
    }
}
