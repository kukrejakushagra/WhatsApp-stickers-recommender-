package app.stickersearch.ui.kds

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.stickersearch.ui.kds.theme.Kds
import app.stickersearch.ui.kds.theme.KdsMotion
import app.stickersearch.ui.kds.theme.KdsRadius
import kotlinx.coroutines.delay

/**
 * Inverted toast at the bottom: foreground ground, background text, radius-lg. Past tense.
 * Stays 2.6s; a new message replaces the old one.
 */
@Composable
fun KdsToastHost(message: String?, onShown: () -> Unit, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf<String?>(null) }
    var shownAt by remember { mutableStateOf(0L) }
    // Take the new message, then mark it consumed. Consuming sets [message] back to null, which restarts
    // this effect, so the hide timer must not live here (it used to, and got cancelled: toasts never went away).
    LaunchedEffect(message) {
        if (message != null) {
            visible = message
            shownAt = System.nanoTime()
            onShown()
        }
    }
    // Hides 2.6 s after the latest message; a newer message restarts the timer.
    LaunchedEffect(shownAt) {
        if (shownAt != 0L) {
            delay(2600)
            visible = null
        }
    }
    val c = Kds.colors
    val shape = RoundedCornerShape(KdsRadius.lg)
    AnimatedVisibility(
        visible = visible != null,
        modifier = modifier,
        enter = fadeIn(tween(250, easing = KdsMotion.Toast)) + slideInVertically(tween(250, easing = KdsMotion.Toast)) { it / 4 },
        exit = fadeOut(tween(KdsMotion.QUICK, easing = KdsMotion.Exit)),
    ) {
        Text(
            visible ?: "",
            style = Kds.type.bodyStrong, color = c.background,
            modifier = Modifier.widthIn(max = 360.dp)
                .shadow(16.dp, shape)
                .background(c.foreground.copy(alpha = 0.94f), shape)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

/** Bottom sheet: floating glass, radius-2xl top corners, 6×48 handle, header and a footer kept in view. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KdsSheet(
    title: String,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    /** Pinned under the title, above the scrolling body (e.g. a playing preview). */
    header: (@Composable ColumnScope.() -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Kds.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = KdsRadius.x2l, topEnd = KdsRadius.x2l),
        containerColor = c.glassThick,
        contentColor = c.foreground,
        scrimColor = c.scrim,
        tonalElevation = 0.dp,
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 6.dp).width(48.dp).height(6.dp).background(c.muted, CircleShape))
        },
    ) {
        // Header and footer stay put; only the body scrolls, so the footer's actions are always on screen.
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).padding(horizontal = 20.dp)
            .then(if (footer == null) Modifier.imePadding() else Modifier)) {
            Text(title, style = Kds.type.heading, color = c.foreground)
            subtitle?.let { Text(it, style = Kds.type.caption, color = c.mutedForeground) }
            header?.let { Column(Modifier.fillMaxWidth().padding(top = 12.dp), content = it) }
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
                content = content,
            )
        }
        if (footer != null) {
            HorizontalDivider(thickness = 1.dp, color = c.border)
            Column(
                // The keyboard (editing a description) pushes the buttons and the body up instead of covering them.
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).navigationBarsPadding().imePadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = footer,
            )
        }
    }
}

/** Centred modal question with one decision; dismiss first, commit last. */
@Composable
fun KdsDialog(
    title: String,
    description: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    dismissLabel: String = "Cancel",
) {
    val c = Kds.colors
    val shape = RoundedCornerShape(KdsRadius.xl)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.widthIn(max = 340.dp).shadow(24.dp, shape).glass(Glass.FLOATING, shape)
                .border(1.dp, c.border, shape).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = Kds.type.titleSm, color = c.foreground)
            Text(description, style = Kds.type.body, color = c.mutedForeground)
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                KdsButton(dismissLabel, onDismiss, variant = ButtonVariant.OUTLINE)
                KdsButton(confirmLabel, onConfirm, variant = if (destructive) ButtonVariant.DESTRUCTIVE else ButtonVariant.PRIMARY)
            }
        }
    }
}
