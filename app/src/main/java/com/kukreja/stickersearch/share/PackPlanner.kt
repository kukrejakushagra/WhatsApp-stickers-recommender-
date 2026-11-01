package com.kukreja.stickersearch.share

/**
 * Turns a selection of stickers into packs WhatsApp will accept: one kind per pack (static or
 * animated), 3 to 30 stickers each, no Lottie. Pure; unit-tested.
 */
object PackPlanner {

    data class Item(val id: Long, val animated: Boolean, val lottie: Boolean)

    data class Planned(val title: String, val animated: Boolean, val ids: List<Long>)

    data class Plan(val packs: List<Planned>, val notes: List<String>)

    fun plan(name: String, items: List<Item>, max: Int = StickerPacks.MAX, min: Int = StickerPacks.MIN): Plan {
        val notes = mutableListOf<String>()
        val title = name.trim().ifBlank { "My stickers" }
        val lottie = items.count { it.lottie }
        if (lottie > 0) notes += "$lottie Lottie sticker${if (lottie == 1) "" else "s"} left out: WhatsApp packs only take WebP."
        val usable = items.filter { !it.lottie }
        val static = usable.filter { !it.animated }.map { it.id }
        val animated = usable.filter { it.animated }.map { it.id }
        val both = static.isNotEmpty() && animated.isNotEmpty()
        val packs = mutableListOf<Planned>()
        for ((isAnim, ids) in listOf(false to static, true to animated)) {
            if (ids.isEmpty()) continue
            val kind = if (isAnim) "animated" else "static"
            if (ids.size < min) {
                notes += "Only ${ids.size} $kind sticker${if (ids.size == 1) "" else "s"} selected; WhatsApp needs at least $min in a pack, so ${if (ids.size == 1) "it was" else "they were"} left out."
                continue
            }
            if (ids.size > max) notes += "${ids.size - max} $kind stickers over WhatsApp's $max-per-pack limit were left out."
            packs += Planned(if (both && isAnim) "$title · animated" else title, isAnim, ids.take(max))
        }
        return Plan(packs, notes)
    }
}
