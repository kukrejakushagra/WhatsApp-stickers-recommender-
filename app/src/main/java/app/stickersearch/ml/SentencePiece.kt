package app.stickersearch.ml

import java.io.InputStream
import java.text.Normalizer

/**
 * Minimal SentencePiece tokenizer: reads a `.model` file (protobuf) and encodes text with
 * either the Unigram (Viterbi) or the BPE algorithm, matching the reference library on the
 * two models this app ships with (multilingual-e5 / XLM-R and SigLIP2 / Gemma).
 * Pure Kotlin; verified against the Python sentencepiece library in unit tests.
 */
class SentencePiece private constructor(
    private val pieces: List<String>,
    private val scores: FloatArray,
    private val types: IntArray,
    private val modelType: Int,
    private val byteFallback: Boolean,
    val unkId: Int,
    val bosId: Int,
    val eosId: Int,
    val padId: Int,
    private val nfkc: Boolean,
    private val addDummyPrefix: Boolean,
    private val removeExtraWhitespaces: Boolean,
    private val escapeWhitespaces: Boolean,
) {
    private val index = HashMap<String, Int>(pieces.size * 2)
    private val userDefined = ArrayList<String>()
    private val maxPieceChars: Int
    private val minScore: Float

    init {
        var maxLen = 1
        var min = Float.MAX_VALUE
        for (i in pieces.indices) {
            val t = types[i]
            if (t == NORMAL || t == USER_DEFINED || t == BYTE) index[pieces[i]] = i
            if (t == USER_DEFINED) userDefined += pieces[i]
            if (t == NORMAL) {
                maxLen = maxOf(maxLen, pieces[i].length)
                min = minOf(min, scores[i])
            }
        }
        userDefined.sortByDescending { it.length }
        maxPieceChars = maxLen
        minScore = if (min == Float.MAX_VALUE) 0f else min
    }

    val vocabSize get() = pieces.size

    fun normalize(text: String): String {
        var s = if (nfkc) Normalizer.normalize(text, Normalizer.Form.NFKC) else text
        if (removeExtraWhitespaces) s = s.trim().replace(WS_RUN, " ")
        if (addDummyPrefix && s.isNotEmpty()) s = " $s"
        if (escapeWhitespaces) s = s.replace(' ', SPACE)
        return s
    }

    /** SentencePiece ids for [text] (no bos/eos). */
    fun encode(text: String): IntArray {
        val norm = normalize(text)
        if (norm.isEmpty()) return IntArray(0)
        return if (modelType == BPE_TYPE) bpe(norm) else unigram(norm)
    }

    private fun unigram(s: String): IntArray {
        val n = s.length
        val best = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }
        val backPos = IntArray(n + 1) { -1 }
        val backId = IntArray(n + 1) { -1 }
        best[0] = 0f
        val unkScore = minScore - UNK_PENALTY
        var i = 0
        while (i < n) {
            if (best[i] == Float.NEGATIVE_INFINITY) { i++; continue }
            var matched = false
            var end = i
            var chars = 0
            while (end < n && chars < maxPieceChars) {
                end += Character.charCount(s.codePointAt(end))
                chars++
                val id = index[s.substring(i, end)] ?: continue
                if (types[id] == BYTE) continue
                val sc = best[i] + (if (types[id] == USER_DEFINED) 0f else scores[id])
                if (sc > best[end]) { best[end] = sc; backPos[end] = i; backId[end] = id }
                if (end == i + Character.charCount(s.codePointAt(i))) matched = true
            }
            if (!matched) {
                val e = i + Character.charCount(s.codePointAt(i))
                val sc = best[i] + unkScore
                if (sc > best[e]) { best[e] = sc; backPos[e] = i; backId[e] = unkId }
            }
            i += Character.charCount(s.codePointAt(i))
        }
        val out = ArrayList<Int>()
        var p = n
        while (p > 0) { out += backId[p]; p = backPos[p] }
        out.reverse()
        // Consecutive unknowns collapse into one, as the reference implementation does.
        val merged = ArrayList<Int>(out.size)
        for (id in out) if (!(id == unkId && merged.lastOrNull() == unkId)) merged += id
        return merged.toIntArray()
    }

    private fun bpe(s: String): IntArray {
        // Initial symbols: user-defined pieces matched greedily, otherwise single code points.
        val syms = ArrayList<String>()
        var i = 0
        while (i < s.length) {
            val ud = userDefined.firstOrNull { s.startsWith(it, i) }
            if (ud != null) { syms += ud; i += ud.length; continue }
            val e = i + Character.charCount(s.codePointAt(i))
            syms += s.substring(i, e)
            i = e
        }
        val frozen = BooleanArray(syms.size) { syms[it] in userDefinedSet }
        val sym = syms.toMutableList()
        val lock = frozen.toMutableList()
        while (sym.size > 1) {
            var bestIdx = -1
            var bestScore = Float.NEGATIVE_INFINITY
            for (k in 0 until sym.size - 1) {
                if (lock[k] || lock[k + 1]) continue
                val id = index[sym[k] + sym[k + 1]] ?: continue
                if (types[id] != NORMAL) continue
                if (scores[id] > bestScore) { bestScore = scores[id]; bestIdx = k }
            }
            if (bestIdx < 0) break
            sym[bestIdx] = sym[bestIdx] + sym[bestIdx + 1]
            sym.removeAt(bestIdx + 1)
            lock.removeAt(bestIdx + 1)
        }
        val out = ArrayList<Int>(sym.size)
        for (t in sym) {
            val id = index[t]
            when {
                id != null && types[id] != BYTE -> out += id
                byteFallback -> for (b in t.toByteArray(Charsets.UTF_8)) {
                    out += index["<0x%02X>".format(b.toInt() and 0xFF)] ?: unkId
                }
                else -> out += unkId
            }
        }
        return out.toIntArray()
    }

    private val userDefinedSet by lazy { userDefined.toHashSet() }

    companion object {
        const val SPACE = '▁'
        private val WS_RUN = Regex("\\s+")
        private const val UNK_PENALTY = 10f
        private const val NORMAL = 1
        private const val USER_DEFINED = 4
        private const val BYTE = 6
        private const val BPE_TYPE = 2

        fun load(input: InputStream): SentencePiece = parse(input.readBytes())

        fun parse(b: ByteArray): SentencePiece {
            val pieces = ArrayList<String>(260_000)
            val scores = ArrayList<Float>(260_000)
            val types = ArrayList<Int>(260_000)
            var modelType = 1
            var byteFallback = false
            var unk = 0; var bos = 1; var eos = 2; var pad = -1
            var normName = ""
            var dummy = true; var rmws = true; var escape = true
            val r = ProtoReader(b, 0, b.size)
            while (r.hasMore()) {
                val (field, wire) = r.tag()
                when {
                    field == 1 && wire == 2 -> {
                        val sub = r.sub()
                        var piece = ""; var score = 0f; var type = NORMAL
                        while (sub.hasMore()) {
                            val (f, w) = sub.tag()
                            when {
                                f == 1 && w == 2 -> piece = sub.string()
                                f == 2 && w == 5 -> score = sub.float()
                                f == 3 && w == 0 -> type = sub.varint().toInt()
                                else -> sub.skip(w)
                            }
                        }
                        pieces += piece; scores += score; types += type
                    }
                    field == 2 && wire == 2 -> {
                        val sub = r.sub()
                        while (sub.hasMore()) {
                            val (f, w) = sub.tag()
                            when {
                                f == 3 && w == 0 -> modelType = sub.varint().toInt()
                                f == 35 && w == 0 -> byteFallback = sub.varint() != 0L
                                f == 40 && w == 0 -> unk = sub.varint().toInt()
                                f == 41 && w == 0 -> bos = sub.varint().toInt()
                                f == 42 && w == 0 -> eos = sub.varint().toInt()
                                f == 43 && w == 0 -> pad = sub.varint().toInt()
                                else -> sub.skip(w)
                            }
                        }
                    }
                    field == 3 && wire == 2 -> {
                        val sub = r.sub()
                        while (sub.hasMore()) {
                            val (f, w) = sub.tag()
                            when {
                                f == 1 && w == 2 -> normName = sub.string()
                                f == 3 && w == 0 -> dummy = sub.varint() != 0L
                                f == 4 && w == 0 -> rmws = sub.varint() != 0L
                                f == 5 && w == 0 -> escape = sub.varint() != 0L
                                else -> sub.skip(w)
                            }
                        }
                    }
                    else -> r.skip(wire)
                }
            }
            return SentencePiece(
                pieces, scores.toFloatArray(), types.toIntArray(), modelType, byteFallback, unk, bos, eos, pad,
                nfkc = normName.contains("nfkc"), addDummyPrefix = dummy, removeExtraWhitespaces = rmws, escapeWhitespaces = escape,
            )
        }
    }
}

/** Just enough protobuf wire-format reading for SentencePiece model files. */
internal class ProtoReader(private val b: ByteArray, private var pos: Int, private val end: Int) {
    fun hasMore() = pos < end
    fun varint(): Long {
        var shift = 0; var result = 0L
        while (true) {
            val x = b[pos++].toInt()
            result = result or ((x and 0x7F).toLong() shl shift)
            if (x and 0x80 == 0) return result
            shift += 7
        }
    }
    fun tag(): Pair<Int, Int> { val t = varint().toInt(); return (t ushr 3) to (t and 7) }
    fun sub(): ProtoReader { val len = varint().toInt(); val r = ProtoReader(b, pos, pos + len); pos += len; return r }
    fun string(): String { val len = varint().toInt(); val s = String(b, pos, len, Charsets.UTF_8); pos += len; return s }
    fun float(): Float {
        val v = (b[pos].toInt() and 0xFF) or ((b[pos + 1].toInt() and 0xFF) shl 8) or
            ((b[pos + 2].toInt() and 0xFF) shl 16) or ((b[pos + 3].toInt() and 0xFF) shl 24)
        pos += 4
        return java.lang.Float.intBitsToFloat(v)
    }
    fun skip(wire: Int) {
        when (wire) {
            0 -> varint()
            1 -> pos += 8
            2 -> { val len = varint().toInt(); pos += len } // read the length before moving pos
            5 -> pos += 4
            else -> error("unsupported wire type $wire")
        }
    }
}

/** XLM-R (multilingual-e5) ids: fairseq offset of 1 over SentencePiece ids, with <s> … </s>. */
class XlmrTokenizer(private val sp: SentencePiece) {
    fun encode(text: String, maxLen: Int = 128): IntArray {
        val ids = sp.encode(text).map { if (it == sp.unkId) UNK else it + 1 }.take(maxLen - 2)
        return (listOf(BOS) + ids + listOf(EOS)).toIntArray()
    }
    companion object { const val BOS = 0; const val PAD = 1; const val EOS = 2; const val UNK = 3 }
}

/** SigLIP2 text ids: Gemma SentencePiece on lowercased text, eos appended, right-padded to 64. */
class SiglipTokenizer(private val sp: SentencePiece) {
    fun encode(text: String, length: Int = 64): IntArray {
        val ids = (sp.encode(text.lowercase()).toList().take(length - 1) + sp.eosId)
        return IntArray(length) { i -> if (i < ids.size) ids[i] else sp.padId.coerceAtLeast(0) }
    }
    fun encodeUnpadded(text: String): IntArray = (sp.encode(text.lowercase()).toList() + sp.eosId).toIntArray()
}
