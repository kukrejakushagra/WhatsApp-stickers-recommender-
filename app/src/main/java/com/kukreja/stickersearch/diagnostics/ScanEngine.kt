package com.kukreja.stickersearch.diagnostics

import android.content.Context
import android.net.Uri
import com.kukreja.stickersearch.discovery.StickerSource
import com.kukreja.stickersearch.media.FormatSniffer
import com.kukreja.stickersearch.media.Hasher
import com.kukreja.stickersearch.media.Thumbnailer
import com.kukreja.stickersearch.model.DiscoveredFile
import com.kukreja.stickersearch.model.ProcessedFile
import com.kukreja.stickersearch.model.SourceProbe
import com.kukreja.stickersearch.model.SourceResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

data class ScanProgress(val phase: String, val done: Int, val total: Int)

data class ScanOutcome(
    val sources: List<SourceResult>,
    val processed: List<ProcessedFile>,
    val summary: ScanSummary,
    val startedAtMs: Long,
    val durationMs: Long,
)

/**
 * Runs every source, then hashes / sniffs / thumbnails each discovered file.
 * Thumbnails are keyed by SHA-256, so a rescan only decodes files it has not seen before.
 */
class ScanEngine(private val context: Context, private val thumbnailer: Thumbnailer) {

    suspend fun run(sources: List<StickerSource>, onProgress: (ScanProgress) -> Unit): ScanOutcome =
        withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            val results = sources.mapIndexed { i, s ->
                onProgress(ScanProgress("Listing ${s.id.label}", i, sources.size))
                try { s.scan() } catch (e: Exception) {
                    SourceResult(
                        SourceProbe(s.id, false, "${e.javaClass.simpleName}: ${e.message}"),
                        emptyList(),
                    )
                }
            }
            val all = results.flatMap { it.files }
            val done = AtomicInteger(0)
            val gate = Semaphore(4) // bounded parallelism keeps memory flat on mid-range devices
            val processed = coroutineScope {
                all.map { f ->
                    async {
                        gate.withPermit { process(f) }.also {
                            onProgress(ScanProgress("Processing", done.incrementAndGet(), all.size))
                        }
                    }
                }.awaitAll()
            }
            val summary = ScanAggregator.aggregate(processed)
            ScanOutcome(results, processed, summary, start, System.currentTimeMillis() - start)
        }

    private fun process(f: DiscoveredFile): ProcessedFile {
        if (f.sizeBytes > MAX_BYTES) return ProcessedFile(f, null, null, null, "skipped: ${f.sizeBytes} bytes > $MAX_BYTES")
        val bytes = try {
            context.contentResolver.openInputStream(Uri.parse(f.uri))?.use { it.readBytes() }
                ?: return ProcessedFile(f, null, null, null, "openInputStream returned null")
        } catch (e: Exception) {
            return ProcessedFile(f, null, null, null, "read failed: ${e.javaClass.simpleName}: ${e.message}")
        }
        val sha = Hasher.sha256(bytes)
        val format = FormatSniffer.sniff(bytes)
        var error: String? = null
        val thumb = try { thumbnailer.ensureThumbnail(sha, bytes) } catch (e: Exception) {
            error = "thumbnail failed: ${e.javaClass.simpleName}: ${e.message}"; null
        }
        val animOk = if (format.isAnimated) {
            try { Thumbnailer.decodesAsAnimation(bytes) } catch (e: Exception) {
                error = (error?.let { "$it; " } ?: "") + "animated decode failed: ${e.message}"; false
            }
        } else null
        return ProcessedFile(f, sha, format, thumb, error, animOk)
    }

    companion object {
        const val MAX_BYTES = 10L * 1024 * 1024
    }
}
