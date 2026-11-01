package com.kukreja.stickersearch.ml

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One Gemma instance per process (it needs ~3 GB of RAM), shared by the overnight annotator and the
 * "suggest a reply" assistant. Calls take turns; the model is unloaded after a minute without use.
 */
object GemmaHolder {
    private val lock = Mutex()
    private var gemma: GemmaAnnotator? = null
    private var idleClose: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private const val IDLE_MS = 60_000L

    /** Runs [block] with the loaded model, loading it first if needed. Throws if the model can't load. */
    suspend fun <T> use(context: Context, block: (GemmaAnnotator) -> T): T = lock.withLock {
        idleClose?.cancel()
        val g = gemma ?: GemmaAnnotator.open(context.applicationContext, ModelStore(context).file(ModelKind.GEMMA)).also { gemma = it }
        try {
            block(g)
        } finally {
            idleClose = scope.launch {
                delay(IDLE_MS)
                lock.withLock { runCatching { gemma?.close() }; gemma = null }
            }
        }
    }

    val backend: String? get() = gemma?.backend

    suspend fun close() = lock.withLock { idleClose?.cancel(); gemma?.close(); gemma = null }
}
