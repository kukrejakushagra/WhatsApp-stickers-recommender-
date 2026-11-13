package app.stickersearch.index

import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelDownloadsTest {
    @Test fun noJobNoBytesIsIdle() = assertEquals(DlStatus.IDLE, ModelDownloads.status(null, 0))
    @Test fun noJobWithBytesIsPaused() = assertEquals(DlStatus.PAUSED, ModelDownloads.status(null, 1234))
    @Test fun cancelledWithBytesIsPaused() = assertEquals(DlStatus.PAUSED, ModelDownloads.status(WorkInfo.State.CANCELLED, 5))
    @Test fun cancelledWithoutBytesIsIdle() = assertEquals(DlStatus.IDLE, ModelDownloads.status(WorkInfo.State.CANCELLED, 0))
    @Test fun queuedIsWaiting() {
        assertEquals(DlStatus.WAITING, ModelDownloads.status(WorkInfo.State.ENQUEUED, 0))
        assertEquals(DlStatus.WAITING, ModelDownloads.status(WorkInfo.State.BLOCKED, 10))
    }
    @Test fun runningIsRunning() = assertEquals(DlStatus.RUNNING, ModelDownloads.status(WorkInfo.State.RUNNING, 10))
    @Test fun failedIsFailed() = assertEquals(DlStatus.FAILED, ModelDownloads.status(WorkInfo.State.FAILED, 10))
}
