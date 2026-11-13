package app.stickersearch.ui.kds

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.stickersearch.R
import app.stickersearch.ui.kds.theme.Kds
import app.stickersearch.ui.kds.theme.KdsSize

/** A 24px outline icon, tinted by the caller (muted-foreground beside text, brand to confirm, destructive for errors). */
@Composable
fun KdsIcon(@DrawableRes id: Int, size: Dp = 16.dp, tint: Color = Kds.colors.mutedForeground, modifier: Modifier = Modifier) {
    Icon(painterResource(id), contentDescription = null, modifier = modifier.size(size), tint = tint)
}

@Composable
fun SpinningLoader(size: Dp, tint: Color) {
    val t = rememberInfiniteTransition(label = "loader")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "angle")
    KdsIcon(R.drawable.ic_kds_loader, size, tint, Modifier.rotate(angle))
}

enum class ButtonVariant { PRIMARY, BRAND, OUTLINE, SECONDARY, GHOST, DESTRUCTIVE }

enum class ButtonSize(val height: Dp, val fontSize: Int, val hPad: Dp, val icon: Dp) {
    SM(KdsSize.controlXs, 12, 12.dp, 14.dp),
    MD(KdsSize.controlMd, 14, 16.dp, 16.dp),
    LG(KdsSize.controlLg, 14, 18.dp, 16.dp),
    XL(KdsSize.controlXl, 15, 20.dp, 16.dp),
}

/** Capsule button. One primary per view; labels are verb-first and say the outcome. */
@Composable
fun KdsButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.PRIMARY,
    size: ButtonSize = ButtonSize.MD,
    @DrawableRes icon: Int? = null,
    block: Boolean = false,
    loading: Boolean = false,
    enabled: Boolean = true,
) {
    val c = Kds.colors
    val shape = CircleShape
    val (bg, fg) = when (variant) {
        ButtonVariant.PRIMARY -> Modifier.tint(c.primary, shape) to c.primaryForeground
        ButtonVariant.BRAND -> Modifier.tint(c.brand, shape) to c.brandForeground
        ButtonVariant.DESTRUCTIVE -> Modifier.tint(c.destructive, shape) to c.destructiveForeground
        ButtonVariant.OUTLINE -> Modifier.glass(Glass.CONTROL, shape) to c.foreground
        ButtonVariant.SECONDARY -> Modifier.background(c.secondary, shape) to c.secondaryForeground
        ButtonVariant.GHOST -> Modifier to c.foreground
    }
    val active = enabled && !loading
    Row(
        modifier
            .then(if (block) Modifier.fillMaxWidth() else Modifier)
            .height(size.height)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(shape)
            .then(bg)
            .clickable(enabled = active, role = Role.Button, onClick = onClick)
            .padding(horizontal = size.hPad),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            loading -> SpinningLoader(size.icon, fg)
            icon != null -> KdsIcon(icon, size.icon, fg)
        }
        Text(
            text, maxLines = 1, overflow = TextOverflow.Ellipsis, color = fg,
            style = Kds.type.bodyStrong.merge(TextStyle(fontSize = size.fontSize.sp)),
        )
    }
}

/** Icon-only 36px glass button; always pass [label] for accessibility. */
@Composable
fun KdsIconButton(@DrawableRes icon: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(KdsSize.controlSm).clip(CircleShape).glass(Glass.CONTROL, CircleShape)
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { KdsIcon(icon, 16.dp, Kds.colors.foreground) }
}
