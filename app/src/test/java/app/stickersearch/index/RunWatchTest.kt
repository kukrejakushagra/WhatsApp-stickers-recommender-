package app.stickersearch.index

import app.stickersearch.index.RunWatch.Cause
import org.junit.Assert.assertEquals
import org.junit.Test

/** ApplicationExitInfo.REASON_* numbers → who stopped the run. */
class RunWatchTest {
    @Test fun crashesAreNotThePhonesDoing() {
        for (r in listOf(4, 5, 6, 7)) assertEquals(Cause.CRASH, RunWatch.classify(r))
    }
    @Test fun lowMemory() = assertEquals(Cause.MEMORY, RunWatch.classify(3))
    @Test fun updatesAreIgnored() {
        assertEquals(Cause.UPDATE, RunWatch.classify(15)); assertEquals(Cause.UPDATE, RunWatch.classify(16))
    }
    @Test fun phoneKillsAreThePhone() {
        // signalled, excessive resource use, user requested (force-stop by a power manager), user stopped, frozen, other, unknown
        for (r in listOf(0, 2, 9, 10, 11, 12, 13, 14)) assertEquals("reason $r", Cause.PHONE, RunWatch.classify(r))
    }
    @Test fun exitedItselfIsUnknown() = assertEquals(Cause.UNKNOWN, RunWatch.classify(1))
}
