package com.kukreja.stickersearch.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.sqrt

/** L2-normalised float vectors, stored as little-endian float32 blobs. */
object Vectors {
    fun normalize(v: FloatArray): FloatArray {
        var s = 0.0
        for (x in v) s += x * x
        val n = sqrt(s).toFloat().takeIf { it > 0f } ?: return v
        return FloatArray(v.size) { v[it] / n }
    }

    fun dot(a: FloatArray, b: FloatArray, bOffset: Int = 0): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[bOffset + i]
        return s
    }

    fun mean(vs: List<FloatArray>): FloatArray {
        val out = FloatArray(vs.first().size)
        vs.forEach { v -> for (i in v.indices) out[i] += v[i] }
        return normalize(out)
    }

    fun toBytes(v: FloatArray): ByteArray =
        ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { asFloatBuffer().put(v) }.array()

    fun fromBytes(b: ByteArray): FloatArray =
        FloatArray(b.size / 4).also { ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
}

private fun ortSession(env: OrtEnvironment, file: File, xnnpack: Boolean = true): OrtSession {
    val opts = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        setIntraOpNumThreads(4)
        // XNNPACK speeds up quantised CPU kernels on ARM; fall back to the default CPU provider.
        if (xnnpack) runCatching { addXnnpack(mapOf("intra_op_num_threads" to "4")) }
    }
    return env.createSession(file.absolutePath, opts)
}

/** multilingual-e5-small: text → 384-d vector. Prefix "query: " or "passage: " as the model expects. */
class TextEmbedder(context: Context, modelFile: File) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    // Variable-length input: no XNNPACK here (suspected of the native crashes seen on device);
    // the default CPU provider runs this small model in milliseconds.
    private val session = ortSession(env, modelFile, xnnpack = false)
    private val tokenizer = XlmrTokenizer(context.assets.open("tokenizers/e5.model").use { SentencePiece.load(it) })

    fun query(text: String) = embed("query: $text")
    fun passage(text: String) = embed("passage: $text")

    @Synchronized
    fun embed(text: String): FloatArray {
        // Ids outside the vocabulary would read past the embedding table in native code.
        val ids = tokenizer.encode(text, maxLen = 256).map { it.coerceIn(0, VOCAB - 1) }
        val n = ids.size
        val shape = longArrayOf(1, n.toLong())
        val idT = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(n) { ids[it].toLong() }), shape)
        val maskT = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(n) { 1L }), shape)
        val typeT = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(n)), shape)
        try {
            session.run(mapOf("input_ids" to idT, "attention_mask" to maskT, "token_type_ids" to typeT)).use { r ->
                @Suppress("UNCHECKED_CAST")
                val hidden = (r[0].value as Array<Array<FloatArray>>)[0] // [n][384]
                val out = FloatArray(hidden[0].size)
                for (row in hidden) for (i in out.indices) out[i] += row[i]
                for (i in out.indices) out[i] /= n
                return Vectors.normalize(out)
            }
        } finally {
            idT.close(); maskT.close(); typeT.close()
        }
    }

    override fun close() = session.close()

    private companion object { const val VOCAB = 250_002 }
}

/** SigLIP2 base/16-224 image tower: sticker image → 768-d vector in the shared image–text space. */
class ImageEmbedder(modelFile: File) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session = ortSession(env, modelFile)

    /** Transparent stickers are composited on white, then resized to 224×224 and scaled to [-1, 1]. */
    @Synchronized
    fun embed(bitmap: Bitmap): FloatArray {
        val sq = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        Canvas(sq).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, null, Rect(0, 0, SIZE, SIZE), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        val px = IntArray(SIZE * SIZE)
        sq.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        sq.recycle()
        val plane = SIZE * SIZE
        val data = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val c = px[i]
            data[i] = (Color.red(c) / 255f - 0.5f) / 0.5f
            data[plane + i] = (Color.green(c) / 255f - 0.5f) / 0.5f
            data[2 * plane + i] = (Color.blue(c) / 255f - 0.5f) / 0.5f
        }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { t ->
            session.run(mapOf("pixel_values" to t), setOf("pooler_output")).use { r ->
                @Suppress("UNCHECKED_CAST")
                return Vectors.normalize((r[0].value as Array<FloatArray>)[0])
            }
        }
    }

    override fun close() = session.close()

    companion object { const val SIZE = 224 }
}

/** SigLIP2 text tower: a search query → 768-d vector comparable with [ImageEmbedder] output. */
class ImageTextEmbedder(context: Context, modelFile: File) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session = ortSession(env, modelFile)
    private val tokenizer = SiglipTokenizer(context.assets.open("tokenizers/siglip2.model").use { SentencePiece.load(it) })

    @Synchronized
    fun embed(text: String): FloatArray {
        val ids = tokenizer.encode(text)
        OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size) { ids[it].toLong() }), longArrayOf(1, ids.size.toLong())).use { t ->
            session.run(mapOf("input_ids" to t), setOf("pooler_output")).use { r ->
                @Suppress("UNCHECKED_CAST")
                return Vectors.normalize((r[0].value as Array<FloatArray>)[0])
            }
        }
    }

    override fun close() = session.close()
}
