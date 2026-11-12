package com.kukreja.stickersearch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.kukreja.stickersearch.R
import com.kukreja.stickersearch.data.StickerRow
import com.kukreja.stickersearch.share.StickerPacks
import com.kukreja.stickersearch.ui.kds.BadgeTone
import com.kukreja.stickersearch.ui.kds.ButtonSize
import com.kukreja.stickersearch.ui.kds.ButtonVariant
import com.kukreja.stickersearch.ui.kds.Glass
import com.kukreja.stickersearch.ui.kds.KdsBadge
import com.kukreja.stickersearch.ui.kds.KdsButton
import com.kukreja.stickersearch.ui.kds.KdsDialog
import com.kukreja.stickersearch.ui.kds.KdsIcon
import com.kukreja.stickersearch.ui.kds.KdsSheet
import com.kukreja.stickersearch.ui.kds.KdsTextField
import com.kukreja.stickersearch.ui.kds.glass
import com.kukreja.stickersearch.ui.kds.theme.Kds
import com.kukreja.stickersearch.ui.kds.theme.KdsRadius

/** Both WhatsApp apps are installed: which one should stickers go to? Asked once, remembered. */
@Composable
internal fun ChooseWhatsAppDialog(onChoose: (String) -> Unit, onDismiss: () -> Unit) {
    val shape = RoundedCornerShape(KdsRadius.xl)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.widthIn(max = 360.dp).shadow(24.dp, shape).glass(Glass.FLOATING, shape)
                .border(1.dp, Kds.colors.border, shape).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Send with which app?", style = Kds.type.titleSm, color = Kds.colors.foreground)
            Text("Both WhatsApp and WhatsApp Business are installed. Your choice is remembered; you can change it in " +
                "Settings › WhatsApp packs.", style = Kds.type.body, color = Kds.colors.mutedForeground)
            KdsButton("WhatsApp", { onChoose(StickerPacks.WHATSAPP) }, variant = ButtonVariant.BRAND, block = true)
            KdsButton("WhatsApp Business", { onChoose(StickerPacks.WHATSAPP_BUSINESS) }, variant = ButtonVariant.OUTLINE, block = true)
            KdsButton("Cancel", onDismiss, variant = ButtonVariant.GHOST, block = true)
        }
    }
}

/** A dialog that asks for a name (new pack, rename). */
@Composable
internal fun NameDialog(
    title: String,
    description: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    val shape = RoundedCornerShape(KdsRadius.xl)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.widthIn(max = 360.dp).shadow(24.dp, shape).glass(Glass.FLOATING, shape)
                .border(1.dp, Kds.colors.border, shape).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = Kds.type.titleSm, color = Kds.colors.foreground)
            Text(description, style = Kds.type.body, color = Kds.colors.mutedForeground)
            KdsTextField("Pack name", name, { name = it.take(120) })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                KdsButton("Cancel", onDismiss, variant = ButtonVariant.OUTLINE)
                KdsButton(confirmLabel, { onConfirm(name) }, variant = ButtonVariant.BRAND, enabled = name.isNotBlank())
            }
        }
    }
}

/** Pick one of the user's packs (of the same kind) for a sticker, or start a new one. */
@Composable
internal fun AddToPackDialog(sticker: StickerRow, state: UiState, defaultName: String, actions: AppActions, onDismiss: () -> Unit) {
    var creating by remember { mutableStateOf(false) }
    val mine = state.packs.filter { it.pack.custom && it.pack.animated == sticker.isAnimated }
    if (creating || mine.isEmpty()) {
        NameDialog(
            title = "New pack",
            description = "A ${if (sticker.isAnimated) "pack of animated stickers" else "sticker pack"} you can add to WhatsApp once it has 3 or more stickers (30 at most).",
            initial = defaultName, confirmLabel = "Create and add",
            onConfirm = { actions.addToPack(sticker, null, it); onDismiss() }, onDismiss = onDismiss,
        )
        return
    }
    val shape = RoundedCornerShape(KdsRadius.xl)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.widthIn(max = 360.dp).shadow(24.dp, shape).glass(Glass.FLOATING, shape)
                .border(1.dp, Kds.colors.border, shape).padding(vertical = 16.dp),
        ) {
            Text("Add to a pack", style = Kds.type.titleSm, color = Kds.colors.foreground, modifier = Modifier.padding(horizontal = 20.dp))
            Text(if (sticker.isAnimated) "Your animated packs" else "Your packs", style = Kds.type.caption,
                color = Kds.colors.mutedForeground, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            Column(Modifier.verticalScroll(rememberScrollState())) {
                mine.forEach { info ->
                    val inIt = sticker.sha256 in info.stickers
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !inIt) { actions.addToPack(sticker, info.pack, null); onDismiss() }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        KdsIcon(R.drawable.ic_kds_folder, 18.dp)
                        Column(Modifier.weight(1f)) {
                            Text(info.pack.title, style = Kds.type.bodyStrong, color = Kds.colors.foreground)
                            Text("${info.count} of ${StickerPacks.MAX}", style = Kds.type.caption, color = Kds.colors.mutedForeground)
                        }
                        when {
                            inIt -> KdsBadge("Added", BadgeTone.SOFT)
                            info.count >= StickerPacks.MAX -> KdsBadge("Full", BadgeTone.SECONDARY)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                KdsButton("Cancel", onDismiss, variant = ButtonVariant.GHOST)
                KdsButton("New pack", { creating = true }, variant = ButtonVariant.OUTLINE, icon = R.drawable.ic_kds_folder)
            }
        }
    }
}

/** One pack: its stickers (remove any), add it to WhatsApp, rename or delete it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PackSheet(info: PackInfo, state: UiState, actions: AppActions, onDismiss: () -> Unit) {
    val bySha = remember(state.stickers) { state.stickers.associateBy { it.sha256 } }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val p = info.pack
    KdsSheet(
        title = p.title,
        subtitle = "${info.count} of ${StickerPacks.MAX} · ${if (p.animated) "animated" else "static"} · " +
            if (info.added) "in WhatsApp" else "not in WhatsApp yet",
        onDismiss = onDismiss,
        footer = {
            when {
                info.count < StickerPacks.MIN -> Text("Add ${StickerPacks.MIN - info.count} more sticker${if (StickerPacks.MIN - info.count == 1) "" else "s"} (from a sticker's sheet or a search) before WhatsApp will take this pack.",
                    style = Kds.type.caption, color = Kds.colors.mutedForeground)
                !info.added -> KdsButton("Add to WhatsApp", { actions.addPackToWhatsApp(p) }, size = ButtonSize.LG, block = true,
                    icon = R.drawable.ic_kds_phone)
                else -> {
                    Text("Changes show up in WhatsApp's sticker picker; reopen the picker if they don't.",
                        style = Kds.type.caption, color = Kds.colors.mutedForeground)
                    KdsButton("Not in WhatsApp? Add it again", { actions.readdPack(p) }, variant = ButtonVariant.GHOST, size = ButtonSize.SM)
                }
            }
            if (p.custom) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KdsButton("Rename", { renaming = true }, variant = ButtonVariant.OUTLINE, size = ButtonSize.SM)
                    KdsButton("Delete pack", { deleting = true }, variant = ButtonVariant.GHOST, size = ButtonSize.SM, icon = R.drawable.ic_kds_trash)
                }
            }
        },
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            info.stickers.forEach { sha ->
                val row = bySha[sha]
                Box(Modifier.size(76.dp)) {
                    Box(
                        Modifier.fillMaxSize().clip(RoundedCornerShape(KdsRadius.md)).background(Kds.colors.glassClear)
                            .border(1.dp, Kds.colors.glassBorder, RoundedCornerShape(KdsRadius.md)).padding(4.dp),
                    ) { Thumb(row?.thumbnailPath, Modifier.fillMaxSize()) }
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(2.dp).size(24.dp)
                            .background(Kds.colors.mediaOverlay, CircleShape).clickable { actions.removeFromPack(p, sha) },
                        contentAlignment = Alignment.Center,
                    ) { KdsIcon(R.drawable.ic_kds_x, 12.dp, Color.White) }
                }
            }
        }
    }
    if (renaming) NameDialog("Rename pack", "WhatsApp shows the new name next time it reads the pack.", p.title, "Rename",
        onConfirm = { actions.renamePack(p, it); renaming = false }, onDismiss = { renaming = false })
    if (deleting) KdsDialog(
        title = "Delete “${p.title}”?",
        description = "The pack leaves WhatsApp's sticker picker. Your stickers themselves stay where they are.",
        confirmLabel = "Delete", destructive = true,
        onConfirm = { deleting = false; actions.deletePack(p); onDismiss() }, onDismiss = { deleting = false },
    )
}

@Composable
internal fun SelectButton(actions: AppActions) =
    KdsButton("Select", { actions.startSelecting(null) }, variant = ButtonVariant.OUTLINE, size = ButtonSize.SM, icon = R.drawable.ic_kds_check)

/** Floating glass bar while picking stickers for a pack. */
@Composable
internal fun SelectionBar(count: Int, topLabel: String = "Top 30", onTop: () -> Unit, onCancel: () -> Unit, onCreate: () -> Unit, modifier: Modifier) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(com.kukreja.stickersearch.ui.kds.theme.KdsRadius.x2l)
    Column(
        modifier.fillMaxWidth().glass(Glass.FLOATING, shape, elevation = 12.dp).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("%d of %d selected".format(count, StickerPacks.MAX),
            style = Kds.type.bodyStrong.copy(fontFeatureSettings = "tnum"), color = Kds.colors.foreground)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            KdsButton(topLabel, onTop, variant = ButtonVariant.OUTLINE, size = ButtonSize.SM)
            KdsButton("Cancel", onCancel, variant = ButtonVariant.GHOST, size = ButtonSize.SM)
            Spacer(Modifier.weight(1f))
            KdsButton("Create pack", onCreate, size = ButtonSize.SM, icon = R.drawable.ic_kds_folder, enabled = count > 0)
        }
    }
}

