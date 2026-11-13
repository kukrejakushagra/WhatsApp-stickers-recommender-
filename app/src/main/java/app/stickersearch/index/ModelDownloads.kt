package app.stickersearch.index

import androidx.work.WorkInfo

/** Where one model's download stands, as the Download / Pause / Resume / Retry controls show it. */
enum class DlStatus { IDLE, WAITING, RUNNING, PAUSED, FAILED }

data class Dl(
    val status: DlStatus = DlStatus.IDLE,
    val done: Long = 0,
    val total: Long = 0,
    val error: String? = null,
) {
    val active get() = status == DlStatus.RUNNING || status == DlStatus.WAITING
    val fraction get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
}

object ModelDownloads {
    /**
     * The row's status from its job (null when there is none) and the bytes already on disk.
     * A stopped or never-run job with bytes on disk is Paused, so Resume carries on from there.
     */
    fun status(job: WorkInfo.State?, partBytes: Long): DlStatus = when (job) {
        WorkInfo.State.RUNNING -> DlStatus.RUNNING
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> DlStatus.WAITING
        WorkInfo.State.FAILED -> DlStatus.FAILED
        WorkInfo.State.CANCELLED, WorkInfo.State.SUCCEEDED, null -> if (partBytes > 0) DlStatus.PAUSED else DlStatus.IDLE
    }
}
