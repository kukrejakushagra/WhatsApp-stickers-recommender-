package app.stickersearch.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.stickersearch.R
import app.stickersearch.ml.ModelKind
import app.stickersearch.ui.kds.BadgeTone
import app.stickersearch.ui.kds.ButtonSize
import app.stickersearch.ui.kds.ButtonVariant
import app.stickersearch.ui.kds.CalloutTone
import app.stickersearch.ui.kds.KdsBadge
import app.stickersearch.ui.kds.KdsButton
import app.stickersearch.ui.kds.KdsCallout
import app.stickersearch.ui.kds.KdsFilterChip
import app.stickersearch.ui.kds.KdsIcon
import app.stickersearch.ui.kds.KdsInfo
import app.stickersearch.ui.kds.KdsPanel
import app.stickersearch.ui.kds.KdsProgress
import app.stickersearch.ui.kds.KdsSettingsGroup
import app.stickersearch.ui.kds.KdsSettingsRow
import app.stickersearch.ui.kds.theme.Kds

/** Setup steps, in order. The step is saved, so an interrupted setup resumes where it stopped. */
internal object SetupStep {
    const val WELCOME = 0
    /** The 26-second "how it works" video, right after Welcome. */
    const val VIDEO = 1
    const val FOLDER = 2
    const val MODELS = 3
    const val DESCRIBE = 4
}

/**
 * The first-run guided setup: welcome, sticker folder, search models (downloaded by the app while
 * the scan runs), then an optional choice of how to describe stickers. One main action per screen.
 */
@Composable
internal fun SetupScreen(state: UiState, actions: AppActions) {
    val step = state.setupStep.coerceIn(SetupStep.WELCOME, SetupStep.DESCRIBE)
    BackHandler(enabled = step > SetupStep.WELCOME) { actions.setSetupStep(step - 1) }
    if (step == SetupStep.VIDEO) { HowToStep { actions.setSetupStep(SetupStep.FOLDER) }; return }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()
            .padding(horizontal = 20.dp).padding(top = 16.dp, bottom = 24.dp),
    ) {
        when (step) {
            SetupStep.WELCOME -> Welcome(actions)
            SetupStep.FOLDER -> Folder(state, actions)
            SetupStep.MODELS -> Models(state, actions)
            else -> Describe(state, actions)
        }
    }
}

@Composable
private fun StepTitle(overline: String?, title: String, body: String) {
    overline?.let { Text(it.uppercase(), style = Kds.type.overline, color = Kds.colors.mutedForeground) }
    Spacer(Modifier.height(4.dp))
    Text(title, style = Kds.type.title, color = Kds.colors.foreground)
    Spacer(Modifier.height(6.dp))
    Text(body, style = Kds.type.body, color = Kds.colors.mutedForeground)
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Welcome(actions: AppActions) {
    Spacer(Modifier.height(48.dp))
    Box(Modifier.size(64.dp).background(Kds.colors.brandSoft, CircleShape), contentAlignment = Alignment.Center) {
        KdsIcon(R.drawable.ic_kds_search, 28.dp, Kds.colors.brand)
    }
    Spacer(Modifier.height(24.dp))
    Text("Find the right sticker by what you mean", style = Kds.type.titleLg, color = Kds.colors.foreground)
    Spacer(Modifier.height(8.dp))
    Text(
        "Type “so done with work” or “kya bakwas hai”. AI on your phone finds the stickers you already have that " +
            "mean it, in English, Hindi or Hinglish, and sends them in WhatsApp.",
        style = Kds.type.body, color = Kds.colors.mutedForeground,
    )
    Spacer(Modifier.height(16.dp))
    KdsCallout("Your stickers and chats never leave this phone. The internet is used only to download the AI models.", CalloutTone.NEUTRAL)
    Spacer(Modifier.height(32.dp))
    KdsButton("Get started", { actions.setSetupStep(SetupStep.VIDEO) }, size = ButtonSize.XL, block = true)
    Spacer(Modifier.height(4.dp))
    KdsButton("Later", actions.dismissSetup, variant = ButtonVariant.GHOST, block = true)
}

@Composable
private fun Folder(state: UiState, actions: AppActions) {
    val p = state.permissions
    val ready = p.safHeld || p.allFiles
    StepTitle("Step 1 of 3", "Find your stickers",
        "Tap Pick folder: Android opens WhatsApp's Media folder. Tap “Use this folder”, then Allow. The app reads only the sticker folders inside.")
    KdsSettingsGroup("Sticker access", listOf(
        {
            KdsSettingsRow(
                title = "WhatsApp media folder", icon = R.drawable.ic_kds_folder,
                description = if (p.safHeld) p.safTreeId?.substringAfter(':')?.replace("/", " › ") else
                    "Android/media/com.whatsapp/WhatsApp/Media. Access stays until you remove it.",
                trailing = {
                    if (p.safHeld) KdsBadge("Granted", BadgeTone.SOFT)
                    else KdsButton("Pick folder", actions.pickFolder, size = ButtonSize.SM)
                },
            )
        },
    ))
    Spacer(Modifier.height(10.dp))
    KdsInfo(
        "Can't pick the folder?",
        "Some phones hide WhatsApp's folder from the picker. Then allow ${if (android.os.Build.VERSION.SDK_INT >= 30) "All files access" else "storage access"} " +
            "instead; the app still reads only sticker folders.",
        style = Kds.type.caption, color = Kds.colors.mutedForeground, modifier = Modifier.padding(horizontal = 4.dp),
    )
    if (!p.safHeld && !p.allFiles) {
        Spacer(Modifier.height(6.dp))
        KdsButton(if (android.os.Build.VERSION.SDK_INT >= 30) "Allow all files access" else "Allow storage access",
            actions.openAllFiles, variant = ButtonVariant.GHOST, size = ButtonSize.SM)
    }
    Spacer(Modifier.height(32.dp))
    KdsButton("Continue", {
        // The scan starts now and runs while the models download.
        if (state.indexedCount == 0 && !state.index.running) actions.scan(false)
        actions.setSetupStep(SetupStep.MODELS)
    }, size = ButtonSize.XL, block = true, enabled = ready)
}

@Composable
private fun Models(state: UiState, actions: AppActions) {
    val m = state.models
    StepTitle("Step 2 of 3", "Get the search models",
        "Three free AI models (about 500 MB, no sign-in) let you search by meaning and by look. Tap Download when you're " +
            "ready: on Wi-Fi, or now on mobile data. They come from Hugging Face; nothing is uploaded.")
    DownloadAllBar(m, actions)
    Spacer(Modifier.height(16.dp))
    val search = listOf(ModelKind.E5 to "Meaning search", ModelKind.SIGLIP_VISION to "Image search, pictures", ModelKind.SIGLIP_TEXT to "Image search, words")
    KdsSettingsGroup(if (m.searchModelsReady) "Search models · ready" else "Search models", search.map { (kind, label) ->
        {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = Kds.type.bodyStrong, color = Kds.colors.foreground, modifier = Modifier.weight(1f))
                    if (m.has(kind)) KdsBadge("Ready", BadgeTone.SOFT)
                }
                if (!m.has(kind)) ModelDownloadControls(kind, m, actions)
            }
        }
    })
    Spacer(Modifier.height(16.dp))
    // The scan runs meanwhile.
    KdsPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("%,d".format(state.indexedCount), style = Kds.type.stat, color = Kds.colors.foreground)
                Text(if (state.index.running) "stickers found so far" else "stickers found", style = Kds.type.caption, color = Kds.colors.mutedForeground)
            }
            if (state.index.running) KdsBadge("Scanning", BadgeTone.SECONDARY) else if (state.indexedCount > 0) KdsBadge("Scanned", BadgeTone.SOFT)
        }
        if (state.index.running && state.index.total > 0) {
            Spacer(Modifier.height(8.dp))
            KdsProgress(state.index.done.toFloat() / state.index.total)
        }
    }
    Spacer(Modifier.height(32.dp))
    val leave = { if (!m.searchModelsReady) actions.notice("Download the search models any time in Settings \u203a AI models."); actions.setSetupStep(SetupStep.DESCRIBE) }
    KdsButton("Continue", leave, size = ButtonSize.XL, block = true,
        variant = if (m.searchModelsReady) ButtonVariant.PRIMARY else ButtonVariant.OUTLINE)
    if (!m.searchModelsReady) {
        Spacer(Modifier.height(6.dp))
        Text(if (m.anyDownloadActive) "Downloads carry on in the background; the notification shows how far they are."
            else "You can download them later from Settings \u203a AI models.",
            style = Kds.type.caption, color = Kds.colors.mutedForeground, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

@Composable
private fun Describe(state: UiState, actions: AppActions) {
    StepTitle("Step 3 of 3 · optional", "Make search smarter",
        "AI can describe each sticker (who's in it, what it says, the mood, when to send it), so search understands it fully. " +
            "Pick one now or later in Settings.")
    Option(
        title = "On a computer", badge = "Fastest", tone = BadgeTone.SOFT,
        body = "Free, no sign-in. Export your stickers, double-click the starter on the PC, then import the results. " +
            "Ollama downloads its own model on the PC. A few hours instead of many nights.",
        action = "Show me how",
    ) { actions.finishSetupQuietly(); actions.openSettingsAt("pc") }
    Spacer(Modifier.height(12.dp))
    Option(
        title = "On this phone", badge = if (state.phoneCanDescribe) null else "Not for this phone", tone = BadgeTone.WARNING,
        body = "Uses Gemma (3.1 GB). Sign-in needed: a free Hugging Face account. Sign in at huggingface.co, accept Google's " +
            "Gemma licence on the model's page, then download it in your browser and import it in Settings › AI models. " +
            "It runs while charging; with thousands of stickers it can take several nights, and the phone needs " +
            "Unrestricted battery use (Settings \u203a Background)." +
            if (state.phoneCanDescribe) "" else " This phone has %.1f GB of memory, so it's likely to fail here.".format(state.memoryGb),
        action = "Get Gemma",
    ) { actions.finishSetupQuietly(); actions.openSettingsAt("models") }
    Spacer(Modifier.height(12.dp))
    Option(
        title = "Skip for now", badge = "Free · no download", tone = BadgeTone.SECONDARY,
        body = "Search by look already works for every sticker, and stickers you describe yourself are found by your words. " +
            "You can add describing any time in Settings.",
        action = null,
    ) {}
    Spacer(Modifier.height(32.dp))
    KdsButton("Start searching", { actions.finishSetup() }, size = ButtonSize.XL, block = true)
}

@Composable
private fun Option(title: String, badge: String?, tone: BadgeTone, body: String, action: String?, onAction: () -> Unit) {
    KdsPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = Kds.type.heading, color = Kds.colors.foreground, modifier = Modifier.weight(1f))
            badge?.let { KdsBadge(it, tone) }
        }
        Spacer(Modifier.height(4.dp))
        Text(body, style = Kds.type.caption, color = Kds.colors.mutedForeground)
        action?.let {
            Spacer(Modifier.height(10.dp))
            KdsButton(it, onAction, variant = ButtonVariant.OUTLINE, size = ButtonSize.SM)
        }
    }
}
