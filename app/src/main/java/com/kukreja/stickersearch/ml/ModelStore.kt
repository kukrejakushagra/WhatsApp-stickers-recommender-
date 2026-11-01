package com.kukreja.stickersearch.ml

import ai.onnxruntime.OrtEnvironment
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/** The four model files the app uses. All run on the phone; none are bundled in the APK. */
enum class ModelKind(
    val title: String,
    /** Name the app stores it under. */
    val fileName: String,
    /** Name the file has when it's downloaded, so the person can recognise it. */
    val downloadName: String,
    val purpose: String,
    /** Direct file link, opened in the browser so the download starts at once. */
    val downloadUrl: String,
    val downloadNote: String,
    val approxMb: Int,
    /** For gated models: the page where the licence is accepted once. */
    val licenceUrl: String? = null,
) {
    GEMMA(
        "Gemma 3n E2B", "gemma-3n-E2B-it-int4.task", "gemma-3n-E2B-it-int4.task",
        "Understands each sticker: description, emotions, when to use it, and search phrases.",
        "https://huggingface.co/google/gemma-3n-E2B-it-litert-preview/resolve/main/gemma-3n-E2B-it-int4.task",
        "Downloads straight away if you're signed in to Hugging Face in your browser and have accepted Google's Gemma licence. " +
            "If you get an error page instead, tap Accept licence first.",
        3100,
        licenceUrl = "https://huggingface.co/google/gemma-3n-E2B-it-litert-preview",
    ),
    E5(
        "Meaning search", "e5-model_int8.onnx", "model_int8.onnx",
        "Matches what you type with the descriptions, in English, Hindi and Hinglish.",
        "https://huggingface.co/Xenova/multilingual-e5-small/resolve/main/onnx/model_int8.onnx",
        "Downloads straight away as model_int8.onnx.",
        118,
    ),
    SIGLIP_VISION(
        "Visual search, images", "siglip2-vision_model_int8.onnx", "vision_model_int8.onnx",
        "Gives every sticker a visual fingerprint on the first day.",
        "https://huggingface.co/onnx-community/siglip2-base-patch16-224-ONNX/resolve/main/onnx/vision_model_int8.onnx",
        "Downloads straight away as vision_model_int8.onnx.",
        95,
    ),
    SIGLIP_TEXT(
        "Visual search, text", "siglip2-text_model_int8.onnx", "text_model_int8.onnx",
        "Turns what you type into the same visual space.",
        "https://huggingface.co/onnx-community/siglip2-base-patch16-224-ONNX/resolve/main/onnx/text_model_int8.onnx",
        "Downloads straight away as text_model_int8.onnx.",
        283,
    ),
}

/**
 * Model files live in app-specific external storage (no permission needed, removed with the app).
 * They are imported from files the user downloaded, through the system file picker.
 */
class ModelStore(private val context: Context) {

    val dir: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "models").apply { mkdirs() }

    fun file(kind: ModelKind) = File(dir, kind.fileName)
    fun has(kind: ModelKind) = file(kind).let { it.exists() && it.length() > 1_000_000 }
    fun sizeBytes(kind: ModelKind) = file(kind).takeIf { it.exists() }?.length() ?: 0L
    fun totalBytes() = ModelKind.entries.sumOf { sizeBytes(it) }

    fun delete(kind: ModelKind) = file(kind).delete()

    data class ImportResult(val kind: ModelKind?, val name: String, val error: String?)

    /** Copies one picked file in, after working out which model it is from its name and contents. */
    fun import(uri: Uri, onProgress: (copied: Long, total: Long) -> Unit): ImportResult {
        val (name, size) = nameAndSize(uri)
        val tmp = File(dir, "import.tmp")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 20)
                    var copied = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        copied += n
                        if (copied % (32L shl 20) < n) onProgress(copied, size)
                    }
                }
            } ?: return ImportResult(null, name, "The file couldn't be opened.")
            val kind = identify(name, tmp) ?: run {
                tmp.delete()
                return ImportResult(null, name, "This isn't one of the four model files. Check the file names in the list.")
            }
            val dest = file(kind)
            dest.delete()
            if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
            return ImportResult(kind, name, null)
        } catch (e: Exception) {
            tmp.delete()
            return ImportResult(null, name, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Name first, then the ONNX inputs, so a renamed download is still recognised. */
    private fun identify(name: String, f: File): ModelKind? {
        val n = name.lowercase()
        if (n.endsWith(".task")) return if (f.length() > 500_000_000) ModelKind.GEMMA else null
        if (!n.endsWith(".onnx")) return null
        val inputs = runCatching {
            OrtEnvironment.getEnvironment().createSession(f.absolutePath).use { it.inputNames.toSet() }
        }.getOrNull() ?: return null
        return when {
            "pixel_values" in inputs -> ModelKind.SIGLIP_VISION
            "token_type_ids" in inputs -> ModelKind.E5
            inputs == setOf("input_ids") -> ModelKind.SIGLIP_TEXT
            else -> null
        }
    }

    private fun nameAndSize(uri: Uri): Pair<String, Long> = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) (it.getString(0) ?: "file") to (if (it.isNull(1)) -1L else it.getLong(1)) else null
        }
    }.getOrNull() ?: ("file" to -1L)
}
