package com.kukreja.stickersearch.diagnostics

import com.kukreja.stickersearch.model.ProcessedFile
import com.kukreja.stickersearch.model.SourceId
import com.kukreja.stickersearch.model.StickerFormat
import com.kukreja.stickersearch.model.UniqueSticker

data class SourceSummary(
    val source: SourceId,
    val files: Int,
    val uniqueHashes: Int,
    val duplicateFiles: Int,
    val totalBytes: Long,
    val animated: Int,
    val formats: Map<StickerFormat, Int>,
    val failures: Int,
    val oldestMs: Long?,
    val newestMs: Long?,
)

data class ScanSummary(
    val unique: List<UniqueSticker>,
    val perSource: Map<SourceId, SourceSummary>,
    val failures: List<ProcessedFile>,
    /** Unique hashes found only by that source; shows which access paths are redundant. */
    val exclusiveToSource: Map<SourceId, Int>,
) {
    val uniqueCount get() = unique.size
    val animatedCount get() = unique.count { it.format.isAnimated }
    val formatBreakdown: Map<StickerFormat, Int>
        get() = unique.groupingBy { it.format.format }.eachCount().toSortedMap()
    val animatedDecodeFailures get() = unique.count { it.format.isAnimated && it.animatedDecodeOk == false }
}

/** Deduplicates processed files by SHA-256 and summarises per source. Pure logic: unit-tested. */
object ScanAggregator {

    fun aggregate(processed: List<ProcessedFile>): ScanSummary {
        val ok = processed.filter { it.sha256 != null && it.format != null }
        val failures = processed.filter { it.sha256 == null || it.format == null || it.error != null }

        val unique = ok.groupBy { it.sha256!! }.map { (sha, group) ->
            val first = group.first()
            UniqueSticker(
                sha256 = sha,
                format = first.format!!,
                sizeBytes = first.file.sizeBytes,
                thumbnailPath = group.firstNotNullOfOrNull { it.thumbnailPath },
                occurrences = group.map { it.file },
                animatedDecodeOk = group.firstNotNullOfOrNull { it.animatedDecodeOk },
            )
        }.sortedByDescending { s -> s.occurrences.maxOf { it.lastModifiedMs } }

        val perSource = processed.groupBy { it.file.source }.mapValues { (source, group) ->
            val good = group.filter { it.sha256 != null && it.format != null }
            val hashes = good.mapTo(hashSetOf()) { it.sha256!! }
            val byHash = good.distinctBy { it.sha256 }
            val times = group.map { it.file.lastModifiedMs }.filter { it > 0 }
            SourceSummary(
                source = source,
                files = group.size,
                uniqueHashes = hashes.size,
                duplicateFiles = good.size - hashes.size,
                totalBytes = group.sumOf { it.file.sizeBytes },
                animated = byHash.count { it.format!!.isAnimated },
                formats = byHash.groupingBy { it.format!!.format }.eachCount(),
                failures = group.count { it.sha256 == null || it.format == null || it.error != null },
                oldestMs = times.minOrNull(),
                newestMs = times.maxOrNull(),
            )
        }

        val exclusive = SourceId.entries.associateWith { src ->
            unique.count { it.sources == setOf(src) }
        }.filterValues { it > 0 }

        return ScanSummary(unique, perSource, failures, exclusive)
    }
}
