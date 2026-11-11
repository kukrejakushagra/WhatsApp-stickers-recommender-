package com.kukreja.stickersearch.ui

import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.os.PowerManager
import android.provider.Settings
import android.widget.ImageView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieComposition
import com.kukreja.stickersearch.R
import com.kukreja.stickersearch.data.StickerRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

/** Grid tiles decode animations at about this size: plenty for a third of the screen, far cheaper than 512. */
private const val TILE_PX = 256

/** True when the phone wants less motion or less work: Battery Saver, or "Remove animations". */
internal fun systemWantsStill(ctx: Context): Boolean {
    val saver = ctx.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
    val scale = runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f)
    return saver || scale == 0f
}

/**
 * A grid tile's picture. Animated stickers play like in WhatsApp's picker: twice, once the tile is
 * on screen and scrolling has settled, then they stop on their last frame. Scrolling away and back
 * (or leaving the app and returning) plays them again. Off-screen tiles show the still thumbnail
 * and do no work.
 */
@Composable
internal fun AnimatedTile(s: StickerRow, onScreen: Boolean, scrolling: Boolean, enabled: Boolean, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val canAnimate = enabled && s.primaryUri != null && (s.isAnimated || s.isLottie())
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val foreground = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    val isScrolling by rememberUpdatedState(scrolling)
    var webp by remember(s.id) { mutableStateOf<AnimatedImageDrawable?>(null) }
    var lottie by remember(s.id) { mutableStateOf<LottieComposition?>(null) }

    LaunchedEffect(s.id, canAnimate && onScreen && foreground) {
        if (!(canAnimate && onScreen && foreground)) {
            // Off screen (or in the background): back to the still picture; it plays again next time.
            webp?.stop(); webp = null; lottie = null
            return@LaunchedEffect
        }
        // Wait for scrolling to settle, then a beat, so flings and fast-scroll drags start nothing.
        while (isScrolling) delay(100)
        delay(250)
        if (s.isLottie()) {
            lottie = AnimCache.lottie(ctx, s)
        } else {
            val bytes = AnimCache.webp(ctx, s) ?: return@LaunchedEffect
            webp = withContext(Dispatchers.IO) {
                runCatching {
                    ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { d, info, _ ->
                        val scale = TILE_PX.toFloat() / maxOf(info.size.width, info.size.height)
                        if (scale < 1f) d.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                    } as? AnimatedImageDrawable
                }.getOrNull()
            }?.apply { repeatCount = 1 } // plays twice, then rests on its last frame
        }
    }

    val w = webp
    val l = lottie
    when {
        w != null -> AndroidView(
            factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
            modifier = modifier.fillMaxSize(),
            update = { iv -> if (iv.drawable !== w) { iv.setImageDrawable(w); w.start() } },
            onRelease = { (it.drawable as? AnimatedImageDrawable)?.stop(); it.setImageDrawable(null) },
        )
        l != null -> AndroidView(
            factory = { LottieAnimationView(it).apply { repeatCount = 1 } },
            modifier = modifier.fillMaxSize(),
            update = { v -> if (v.composition !== l) { v.setComposition(l); v.playAnimation() } },
            onRelease = { it.cancelAnimation() },
        )
        else -> Thumb(s.thumbnailPath, modifier.fillMaxSize(), placeholder = if (s.isLottie()) R.drawable.ic_kds_spark else R.drawable.ic_kds_image)
    }
}
