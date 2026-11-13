package app.stickersearch.index

import app.stickersearch.data.FileStatus
import app.stickersearch.data.KnownFileRow
import app.stickersearch.model.DiscoveredFile

/** What one scan must do. Pure output of [IndexPlanner.plan]; unit-tested. */
data class IndexPlan(
    /** New or changed files (and failed ones still under the retry cap), with the known row id if any. */
    val toProcess: List<Pair<DiscoveredFile, Long?>>,
    /** Known, unchanged files: only their last-seen time is touched. */
    val unchanged: List<Long>,
    /** Known files not in this listing: marked missing (never deleted). */
    val missing: List<Long>,
    /** Failed files that hit the retry cap; left alone until the user taps "Retry failed". */
    val givenUp: Int,
)

object IndexPlanner {
    const val MAX_ATTEMPTS = 3

    /**
     * Incremental rule: a file is unchanged when its uri, size and modified time match the stored row.
     * Content identity (SHA-256) is decided later, only for files that need processing.
     * [accessibleSources] are the source ids whose listing succeeded in this scan.
     */
    fun plan(listing: List<DiscoveredFile>, known: List<KnownFileRow>, accessibleSources: Set<String>): IndexPlan {
        val byUri = known.associateBy { it.uri }
        val seen = HashSet<String>(listing.size * 2)
        val toProcess = mutableListOf<Pair<DiscoveredFile, Long?>>()
        val unchanged = mutableListOf<Long>()
        var givenUp = 0
        for (f in listing) {
            if (!seen.add(f.uri)) continue // the same uri listed twice
            val k = byUri[f.uri]
            when {
                k == null -> toProcess += f to null
                k.sizeBytes != f.sizeBytes || k.lastModified != f.lastModifiedMs -> toProcess += f to k.id
                k.status == FileStatus.FAILED && k.attempts < MAX_ATTEMPTS -> toProcess += f to k.id
                k.status == FileStatus.FAILED -> { givenUp++; unchanged += k.id }
                else -> unchanged += k.id
            }
        }
        // Only a source that was readable this time can declare its files missing: a revoked
        // permission must never look like "every sticker was deleted".
        val missing = known.filter { it.uri !in seen && it.missingSince == null && it.source in accessibleSources }.map { it.id }
        return IndexPlan(toProcess, unchanged, missing, givenUp)
    }
}
