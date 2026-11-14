package app.stickersearch.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Screen sizes the layouts adapt to: phones (portrait and landscape), 7-inch and 10-inch tablets.
 * Width classes follow Android's window size classes (compact < 600 dp, medium < 840 dp, expanded).
 */
internal object Adaptive {
    /** Reading width for setup, Settings and the video: comfortable on a tablet, full width on a phone. */
    val ContentMaxWidth = 640.dp
    val SetupMaxWidth = 560.dp
    /** The bottom tab bar stays thumb-sized on wide screens. */
    val NavMaxWidth = 480.dp

    /**
     * Results and details side by side: on expanded widths (10-inch tablets in landscape), and in any
     * landscape window at least 600 dp wide (phones and 7-inch tablets in landscape), where a bottom
     * sheet would have almost no height to show the details in.
     */
    fun twoPane(widthDp: Int, landscape: Boolean) = widthDp >= 840 || (landscape && widthDp >= 600)

    /** Short screens (phones in landscape): smaller fixed headers so content still fits. */
    fun short(heightDp: Int) = heightDp < 480
}

@Composable
internal fun screenWidthDp() = LocalConfiguration.current.screenWidthDp

@Composable
internal fun screenHeightDp() = LocalConfiguration.current.screenHeightDp

@Composable
internal fun isLandscape() = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

/** Centres [content] in a column no wider than [maxWidth]; on phones it simply fills the screen. */
@Composable
internal fun CenteredColumn(maxWidth: Dp = Adaptive.ContentMaxWidth, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = maxWidth).fillMaxWidth().fillMaxHeight(), content = content)
    }
}
