package app.stickersearch.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import java.io.File

/**
 * On-device sticker understanding with Gemma 3n E2B (MediaPipe LLM Inference, image input).
 * Animated stickers are shown as a 2×2 contact sheet of keyframes in time order, so the model
 * sees the motion in one image pass.
 */
class GemmaAnnotator private constructor(
    private val llm: LlmInference,
    val backend: String,
    /** How many images one call may take (1 in low-memory mode). */
    val maxImages: Int = MAX_IMAGES,
) : AutoCloseable {

    data class Result(val annotation: Annotation?, val raw: String, val millis: Long)

    fun annotate(frames: List<Bitmap>, animated: Boolean, durationMs: Long?): Result {
        val started = System.currentTimeMillis()
        val image = if (frames.size > 1) contactSheet(frames) else onWhite(frames.first())
        val opts = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTopK(20).setTemperature(0.2f)
            .setGraphOptions(GraphOptions.builder().setEnableVisionModality(true).build())
            .build()
        val raw = LlmInferenceSession.createFromOptions(llm, opts).use { s ->
            s.addQueryChunk(prompt(animated && frames.size > 1, durationMs))
            s.addImage(BitmapImageBuilder(image).build())
            s.generateResponse()
        }
        image.recycle()
        return Result(Annotation.parse(raw), raw, System.currentTimeMillis() - started)
    }

    /**
     * Reads chat screenshots (and/or images) plus what the user typed, and plans a sticker reply:
     * what's going on, and short search queries for the right reaction.
     */
    fun planReply(images: List<Bitmap>, userText: String): Pair<ReplyPlan?, String> {
        val opts = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTopK(20).setTemperature(0.3f)
            .setGraphOptions(GraphOptions.builder().setEnableVisionModality(images.isNotEmpty()).build())
            .build()
        val raw = LlmInferenceSession.createFromOptions(llm, opts).use { s ->
            s.addQueryChunk(replyPrompt(images.size, userText))
            images.take(maxImages).forEach { s.addImage(BitmapImageBuilder(fit(it)).build()) }
            s.generateResponse()
        }
        return ReplyPlan.parse(raw) to raw
    }

    override fun close() { runCatching { llm.close() } }

    companion object {
        const val PROMPT_VERSION = 2
        const val MAX_IMAGES = 3
        const val MODEL_NAME = "gemma-3n-e2b-it-int4"
        private const val SIDE = 512

        /**
         * GPU first (faster on Adreno), CPU if the GPU delegate can't load the model. A GPU attempt
         * that never finished (the process died natively) is remembered, and the CPU is used from then on.
         */
        fun open(context: Context, model: File): GemmaAnnotator {
            val prefs = context.getSharedPreferences("gemma_backend", Context.MODE_PRIVATE)
            val gpuCrashedBefore = prefs.getBoolean("gpu_bad", false) ||
                (prefs.getBoolean("gpu_trying", false) && !prefs.getBoolean("gpu_ok", false))
            // After a crash on the CPU too, use less memory: one image per call and a shorter reply budget.
            val lite = prefs.getBoolean("lite", false)
            val images = if (lite) 1 else MAX_IMAGES
            fun build(b: LlmInference.Backend) = LlmInference.createFromOptions(
                context,
                LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(model.absolutePath)
                    .setMaxTokens(if (lite) 1024 else 1280)
                    .setMaxNumImages(images)
                    .setPreferredBackend(b)
                    .build(),
            )
            if (!gpuCrashedBefore) {
                prefs.edit().putBoolean("gpu_trying", true).commit()
                val gpu = runCatching { build(LlmInference.Backend.GPU) }.getOrNull()
                prefs.edit().putBoolean("gpu_trying", false).putBoolean("gpu_ok", gpu != null).commit()
                if (gpu != null) return GemmaAnnotator(gpu, "GPU", images)
            }
            return GemmaAnnotator(build(LlmInference.Backend.CPU), if (lite) "CPU (low memory)" else "CPU", images)
        }

        /** The GPU crashed mid-reply: use the CPU from now on (slower, but it finishes). */
        fun giveUpGpu(context: Context) =
            context.getSharedPreferences("gemma_backend", Context.MODE_PRIVATE).edit().putBoolean("gpu_bad", true).commit()

        /** The CPU crashed too: switch to the low-memory settings. */
        fun useLite(context: Context) =
            context.getSharedPreferences("gemma_backend", Context.MODE_PRIVATE).edit().putBoolean("lite", true).commit()

        fun prompt(animated: Boolean, durationMs: Long?): String = buildString {
            append("You are labelling a WhatsApp sticker so a person can find it later by typing what they want to say.\n")
            if (animated) {
                append("The image shows 4 frames of an animated sticker in time order (left to right, top to bottom)")
                durationMs?.let { append(", lasting about ${(it / 100) / 10.0} seconds") }
                append(". Describe the movement and what it expresses, not just the character.\n")
            }
            append("Notice clothing, props and setting and what they suggest (a tie or suit: office or formal; a helmet: riding; a party hat: birthday).\n")
            append("Reply with one JSON object and nothing else, with these keys:\n")
            append("\"description\": one sentence saying what is shown and what it expresses (e.g. \"Cartoon cat banging its head on a wall in exaggerated frustration\"),\n")
            append("\"text\": any words written in the sticker, exactly, or \"\",\n")
            append("\"emotions\": 1-4 of [${Annotation.EMOTIONS.joinToString(", ")}],\n")
            append("\"intents\": 1-4 of [${Annotation.INTENTS.joinToString(", ")}],\n")
            append("\"tags\": 5-10 short English words,\n")
            append("\"phrases\": 4-6 short things someone might type to find it, in English,\n")
            append("\"hinglish\": 2-4 such phrases in romanized Hindi (e.g. \"kya bakwas hai\"),\n")
            append("\"hindi\": 1-3 such phrases in Devanagari,\n")
            append("\"emoji\": one emoji that fits,\n")
            append("\"confidence\": a number from 0 to 1 for how sure you are of the meaning.")
        }

        fun replyPrompt(imageCount: Int, userText: String): String = buildString {
            append("You help someone pick a WhatsApp sticker to send. ")
            if (imageCount > 0) {
                append("You are given $imageCount image(s); they are usually screenshots of a chat, where the user's own messages ")
                append("are on the right and the other person's on the left. Read the latest messages carefully.\n")
            }
            if (userText.isNotBlank()) append("The user says: \"${userText.trim()}\"\n")
            append("Work out what is going on and how the user would want to react. Reply with one JSON object and nothing else:\n")
            append("\"reading\": one short sentence on what was said and the tone of the reply the user needs,\n")
            append("\"queries\": 2-4 short descriptions of ideal reply stickers, by emotion and intent (e.g. \"playful eye roll, not impressed\", \"laughing hard at a joke\"),\n")
            append("\"avoid\": 0-3 single words for moods that would be wrong here (e.g. \"angry\"),\n")
            append("\"seen\": short descriptions of any stickers visible in the images, or [].")
        }

        /** Keeps aspect ratio; long screenshots stay readable at up to 1024 px on the long side. */
        private fun fit(src: Bitmap): Bitmap {
            val maxSide = maxOf(src.width, src.height)
            if (maxSide <= 1024) return src
            val s = 1024f / maxSide
            return Bitmap.createScaledBitmap(src, (src.width * s).toInt(), (src.height * s).toInt(), true)
        }

        fun onWhite(src: Bitmap): Bitmap {
            val out = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888)
            Canvas(out).apply { drawColor(Color.WHITE); drawBitmap(src, null, Rect(0, 0, SIDE, SIDE), Paint(Paint.FILTER_BITMAP_FLAG)) }
            return out
        }

        /** Up to 4 frames on a 2×2 grid, white ground, thin grey gutters. */
        fun contactSheet(frames: List<Bitmap>): Bitmap {
            val picks = if (frames.size <= 4) frames else listOf(0, frames.size / 3, 2 * frames.size / 3, frames.size - 1).map { frames[it] }
            val out = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            c.drawColor(Color.rgb(228, 228, 231))
            val cell = SIDE / 2
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            picks.forEachIndexed { i, f ->
                val x = (i % 2) * cell
                val y = (i / 2) * cell
                val r = Rect(x + 2, y + 2, x + cell - 2, y + cell - 2)
                c.drawRect(r, Paint().apply { color = Color.WHITE })
                c.drawBitmap(f, null, r, paint)
            }
            return out
        }
    }
}
