package app.stickersearch.ml

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Compares the Kotlin tokenizers with ids produced by the reference Python library.
 * Needs the tokenizer files: pass -PspModelsDir=<dir with e5/ and siglip2/> (skipped otherwise).
 */
class SentencePieceTest {

    private val dir = System.getProperty("spModelsDir")?.let(::File)

    private fun load(rel: String): SentencePiece? {
        val f = dir?.let { File(it, rel) }
        assumeTrue("tokenizer files not provided", f?.exists() == true)
        return SentencePiece.parse(f!!.readBytes())
    }

    @Test fun xlmrUnigramMatchesReference() {
        val tok = XlmrTokenizer(load("e5/sentencepiece.bpe.model")!!)
        TokenizerFixtures.xlmr.forEach { (text, expected) ->
            assertEquals("text: $text", expected, tok.encode(text, maxLen = 512).toList())
        }
    }

    @Test fun gemmaBpeMatchesReference() {
        val tok = SiglipTokenizer(load("siglip2/tokenizer.model")!!)
        TokenizerFixtures.gemma.forEach { (text, expected) ->
            assertEquals("text: $text", expected, tok.encodeUnpadded(text).toList())
        }
    }

    @Test fun siglipPadsTo64() {
        val tok = SiglipTokenizer(load("siglip2/tokenizer.model")!!)
        val ids = tok.encode("hello")
        assertEquals(64, ids.size)
        assertEquals(0, ids.last())
    }
}
