package app.stickersearch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.stickersearch.R
import app.stickersearch.index.DlStatus
import app.stickersearch.ml.ModelKind
import app.stickersearch.ui.kds.ButtonSize
import app.stickersearch.ui.kds.ButtonVariant
import app.stickersearch.ui.kds.KdsButton
import app.stickersearch.ui.kds.KdsFilterChip
import app.stickersearch.ui.kds.KdsInfo
import app.stickersearch.ui.kds.KdsPanel
import app.stickersearch.ui.kds.KdsProgress
import app.stickersearch.ui.kds.theme.Kds

private fun mbOf(bytes: Long) = (bytes shr 20).toInt()

/**
 * One search model's download: Download → (Waiting) → progress with Pause → Paused with Resume,
 * or Retry after a failure. Nothing starts until the user taps.
 */
@Composable
internal fun ModelDownloadControls(kind: ModelKind, models: ModelsState, actions: AppActions) {
    val d = models.dl(kind)
    val c = Kds.colors
    val tnum = Kds.type.caption.copy(fontFeatureSettings = "tnum")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (d.status) {
            DlStatus.IDLE -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                KdsButton("Download", { actions.downloadModel(kind) }, variant = ButtonVariant.OUTLINE, size = ButtonSize.SM, icon = R.drawable.ic_kds_download)
                Text("about ${kind.approxMb} MB · free, no sign-in", style = Kds.type.caption, color = c.mutedForeground)
            }
            DlStatus.WAITING -> {
                Text(if (models.wifiOnly) "Waiting for Wi-Fi" else "Waiting for a connection", style = Kds.type.caption, color = c.foreground)
                if (d.done > 0) KdsProgress(d.fraction)
                KdsButton("Pause", { actions.pauseModel(kind) }, variant = ButtonVariant.GHOST, size = ButtonSize.SM)
            }
            DlStatus.RUNNING -> {
                Text("Downloading · %,d of %,d MB".format(mbOf(d.done), mbOf(d.total)), style = tnum, color = c.foreground)
                KdsProgress(d.fraction)
                KdsButton("Pause", { actions.pauseModel(kind) }, variant = ButtonVariant.GHOST, size = ButtonSize.SM)
            }
            DlStatus.PAUSED -> {
                Text("Paused · %,d of %,d MB".format(mbOf(d.done), mbOf(d.total)), style = tnum, color = c.mutedForeground)
                KdsProgress(d.fraction)
                KdsButton("Resume", { actions.downloadModel(kind) }, variant = ButtonVariant.OUTLINE, size = ButtonSize.SM, icon = R.drawable.ic_kds_play)
            }
            DlStatus.FAILED -> {
                Text((d.error ?: "The download didn't finish.") + " What already arrived is kept.", style = Kds.type.caption, color = c.destructiveText)
                KdsButton("Retry", { actions.downloadModel(kind) }, variant = ButtonVariant.OUTLINE, size = ButtonSize.SM, icon = R.drawable.ic_kds_refresh)
            }
        }
    }
}

/** "Download all" (or Pause all / Resume all), the overall progress, and the Wi-Fi only switch. */
@Composable
internal fun DownloadAllBar(models: ModelsState, actions: AppActions) {
    val missing = models.missingSearch
    if (missing.isEmpty()) return
    val ready = models.searchKinds.size - missing.size
    val started = missing.any { models.dl(it).done > 0 || models.dl(it).status != DlStatus.IDLE }
    KdsPanel {
        Text(
            if (started) "%d of %d ready · %,d of %,d MB".format(ready, models.searchKinds.size, mbOf(models.searchDone), mbOf(models.searchTotal))
            else "Three models, about %,d MB in all".format(mbOf(models.searchTotal)),
            style = Kds.type.bodyStrong.copy(fontFeatureSettings = "tnum"), color = Kds.colors.foreground,
        )
        if (started) {
            androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 6.dp))
            KdsProgress(if (models.searchTotal > 0) models.searchDone.toFloat() / models.searchTotal else null)
        }
        androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 10.dp))
        when {
            models.anyDownloadActive -> KdsButton("Pause all", actions.pauseAll, variant = ButtonVariant.OUTLINE, block = true)
            started -> KdsButton("Resume all", actions.downloadAll, block = true, icon = R.drawable.ic_kds_play)
            else -> KdsButton("Download all", actions.downloadAll, block = true, icon = R.drawable.ic_kds_download)
        }
        androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            KdsInfo(
                "Wi-Fi only", "Downloads wait for Wi-Fi so mobile data isn't used. On mobile data and happy to use it? " +
                    "Turn this off. Or download later from Settings › AI models.",
                style = Kds.type.caption, color = Kds.colors.foreground, modifier = Modifier.weight(1f),
            )
            KdsFilterChip(if (models.wifiOnly) "On" else "Off", models.wifiOnly, { actions.setWifiOnly(!models.wifiOnly) })
        }
    }
}
