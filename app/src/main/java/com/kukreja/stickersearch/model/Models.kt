package com.kukreja.stickersearch.model

/** Where a sticker file was found. Each id is one of the feasibility options under test. */
enum class SourceId(val label: String) {
    SAF_TREE("SAF folder (Option B)"),
    FILE_SYSTEM("All-files access (Option A)"),
    SHARE_IN("Shared into app (Option D/E)"),
}

/** A file one source reported. [uri] is either content:// or file:// and is opened via ContentResolver. */
data class DiscoveredFile(
    val source: SourceId,
    val uri: String,
    val displayPath: String,
    val name: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val mimeType: String? = null,
)

enum class StickerFormat {
    WEBP_STATIC,
    WEBP_ANIMATED,
    GIF,
    PNG,
    APNG,
    JPEG,
    LOTTIE_WAS, // WhatsApp ".was": a zip holding a Lottie animation
    LOTTIE_JSON,
    UNKNOWN,
}

data class FormatInfo(
    val format: StickerFormat,
    val isAnimated: Boolean,
    val frameCount: Int,
    val width: Int,
    val height: Int,
    /** 0 = infinite; null when the format carries no loop count. */
    val loopCount: Int? = null,
)

/** Per-directory counts, used to discover sticker locations we did not anticipate. */
data class DirStats(
    var fileCount: Int = 0,
    var totalBytes: Long = 0,
    var hasNoMedia: Boolean = false,
)

/** What a source could (or could not) see. */
data class SourceProbe(
    val source: SourceId,
    val accessible: Boolean,
    val error: String? = null,
    val rootsScanned: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
)

data class SourceResult(
    val probe: SourceProbe,
    val files: List<DiscoveredFile>,
    val inventory: Map<String, DirStats> = emptyMap(),
)

/** One file after hashing, sniffing and thumbnailing. */
data class ProcessedFile(
    val file: DiscoveredFile,
    val sha256: String?,
    val format: FormatInfo?,
    val thumbnailPath: String?,
    val error: String?,
    /** For animated files: whether the platform decoder produced an AnimatedImageDrawable. */
    val animatedDecodeOk: Boolean? = null,
    /** For .was archives: entry names and sizes, to learn their layout. */
    val zipEntries: List<String>? = null,
)

/** One unique sticker (by SHA-256) and every place it was found. */
data class UniqueSticker(
    val sha256: String,
    val format: FormatInfo,
    val sizeBytes: Long,
    val thumbnailPath: String?,
    val occurrences: List<DiscoveredFile>,
    val animatedDecodeOk: Boolean?,
) {
    val sources: Set<SourceId> get() = occurrences.mapTo(linkedSetOf()) { it.source }
    val primary: DiscoveredFile get() = occurrences.first()
}
