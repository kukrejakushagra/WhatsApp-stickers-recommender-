package com.kukreja.stickersearch.index

import com.kukreja.stickersearch.data.FileStatus
import com.kukreja.stickersearch.data.KnownFileRow
import com.kukreja.stickersearch.model.DiscoveredFile
import com.kukreja.stickersearch.model.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexPlannerTest {

    private fun listed(uri: String, size: Long = 100, mtime: Long = 1, src: SourceId = SourceId.SAF_TREE) =
        DiscoveredFile(src, uri, uri, uri, size, mtime)

    private fun known(id: Long, uri: String, size: Long = 100, mtime: Long = 1, status: FileStatus = FileStatus.OK,
                      attempts: Int = 0, missing: Long? = null, src: SourceId = SourceId.SAF_TREE) =
        KnownFileRow(id, src.name, uri, size, mtime, if (status == FileStatus.OK) id else null, status, attempts, missing)

    private val saf = setOf(SourceId.SAF_TREE.name)

    @Test fun onlyNewAndChangedAreProcessed() {
        // 1,000 indexed stickers + 5 new favourites -> exactly the 5 are processed.
        val knownRows = (1L..1000L).map { known(it, "u$it") }
        val listing = (1..1000).map { listed("u$it") } + (1..5).map { listed("new$it") }
        val plan = IndexPlanner.plan(listing, knownRows, saf)
        assertEquals(5, plan.toProcess.size)
        assertTrue(plan.toProcess.all { it.first.uri.startsWith("new") && it.second == null })
        assertEquals(1000, plan.unchanged.size)
        assertTrue(plan.missing.isEmpty())
    }

    @Test fun changedSizeOrTimeIsReprocessedWithItsRowId() {
        val plan = IndexPlanner.plan(
            listOf(listed("a", size = 200), listed("b", mtime = 9), listed("c")),
            listOf(known(1, "a"), known(2, "b"), known(3, "c")), saf,
        )
        assertEquals(listOf(1L, 2L), plan.toProcess.map { it.second })
        assertEquals(listOf(3L), plan.unchanged)
    }

    @Test fun removedFilesAreMarkedMissingOnce() {
        val plan = IndexPlanner.plan(
            listOf(listed("a")),
            listOf(known(1, "a"), known(2, "gone"), known(3, "already", missing = 5)), saf,
        )
        assertEquals(listOf(2L), plan.missing)
    }

    @Test fun revokedSourceNeverMarksEverythingMissing() {
        val plan = IndexPlanner.plan(emptyList(), (1L..50L).map { known(it, "u$it") }, accessibleSources = emptySet())
        assertTrue(plan.missing.isEmpty())
    }

    @Test fun failedFilesRetryUntilTheCap() {
        val plan = IndexPlanner.plan(
            listOf(listed("f1"), listed("f2")),
            listOf(known(1, "f1", status = FileStatus.FAILED, attempts = 1),
                known(2, "f2", status = FileStatus.FAILED, attempts = IndexPlanner.MAX_ATTEMPTS)), saf,
        )
        assertEquals(listOf(1L), plan.toProcess.map { it.second })
        assertEquals(1, plan.givenUp)
    }

    @Test fun duplicateUrisInListingAreProcessedOnce() {
        val plan = IndexPlanner.plan(listOf(listed("x"), listed("x")), emptyList(), saf)
        assertEquals(1, plan.toProcess.size)
    }
}
