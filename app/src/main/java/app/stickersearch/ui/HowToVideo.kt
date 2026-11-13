package app.stickersearch.ui

import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.stickersearch.R
import app.stickersearch.ui.kds.ButtonSize
import app.stickersearch.ui.kds.ButtonVariant
import app.stickersearch.ui.kds.KdsButton
import app.stickersearch.ui.kds.theme.Kds

/**
 * The 8-second "type, see stickers, tap send" clip (silent, one caption). Portrait (res/raw/how_to)
 * or landscape (res/raw/how_to_land), whichever fits the screen right now. Plays once, then
 * [onEnded]; [play] changes restart it.
 */
@Composable
internal fun HowToVideoPlayer(modifier: Modifier = Modifier, play: Int = 0, onEnded: () -> Unit = {}) {
    var view by remember { mutableStateOf<VideoView?>(null) }
    val land = isLandscape()
    // The clip's own background, so any space around it on odd screen shapes blends in.
    Box(modifier.background(androidx.compose.ui.graphics.Color(0xFF09090B), androidx.compose.foundation.shape.RoundedCornerShape(18.dp)),
        contentAlignment = Alignment.Center) {
    androidx.compose.runtime.key(land) {
    AndroidView(
        modifier = Modifier.aspectRatio(if (land) 16f / 9f else 9f / 16f, matchHeightConstraintsFirst = !land),
        factory = { ctx ->
            VideoView(ctx).apply {
                setVideoURI(Uri.parse("android.resource://${ctx.packageName}/${if (land) R.raw.how_to_land else R.raw.how_to}"))
                setOnPreparedListener { mp -> mp.setVolume(0f, 0f); start() }
                setOnCompletionListener { onEnded() }
                // A video that can't play (rare codecs) must never block the setup.
                setOnErrorListener { _, _, _ -> onEnded(); true }
                view = this
            }
        },
        update = { v -> if (play > 0 && !v.isPlaying) { v.seekTo(0); v.start() } },
    )
    }
    }
    DisposableEffect(Unit) { onDispose { view?.stopPlayback() } }
}

/** Setup's "How it works" step: the video, Skip while it plays, Continue and Watch again after. */
@Composable
internal fun HowToStep(onContinue: () -> Unit) {
    var ended by remember { mutableStateOf(false) }
    var replay by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("HOW IT WORKS", style = Kds.type.overline, color = Kds.colors.mutedForeground, modifier = Modifier.weight(1f))
            if (!ended) KdsButton("Skip", onContinue, variant = ButtonVariant.GHOST, size = ButtonSize.SM)
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
            HowToVideoPlayer(Modifier.fillMaxSize(), replay) { ended = true }
        }
        KdsButton("Continue", onContinue, size = ButtonSize.XL, block = true,
            variant = if (ended) ButtonVariant.PRIMARY else ButtonVariant.OUTLINE)
        if (ended) {
            Spacer(Modifier.height(4.dp))
            KdsButton("Watch again", { ended = false; replay++ }, variant = ButtonVariant.GHOST, block = true)
        }
    }
}

/** Settings › How to use: the same video, full screen. */
@Composable
internal fun HowToDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(Kds.colors.background).statusBarsPadding().navigationBarsPadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                HowToVideoPlayer(Modifier.fillMaxSize())
            }
            KdsButton("Close", onDismiss, variant = ButtonVariant.OUTLINE, block = true)
        }
    }
}
