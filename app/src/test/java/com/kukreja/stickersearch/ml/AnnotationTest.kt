package com.kukreja.stickersearch.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationTest {

    @Test fun parsesJsonInsideMarkdownAndChatter() {
        val reply = """
            Sure! Here is the JSON:
            ```json
            {"description": "Cartoon cat banging its head on a wall in exaggerated frustration",
             "text": "", "emotions": ["Frustration", "disbelief"], "intents": ["expressing frustration playfully"],
             "tags": ["Cat", "headbang", "wall", "facepalm", "ugh"], "phrases": ["why me", "I give up"],
             "hinglish": ["kya yaar", "pagal ho gaya"], "hindi": ["उफ़"], "emoji": "😫", "confidence": 0.84}
            ```
        """.trimIndent()
        val a = Annotation.parse(reply)!!
        assertTrue(a.description.startsWith("Cartoon cat"))
        assertEquals(listOf("frustration", "disbelief"), a.emotions)
        assertEquals(listOf("expressing_frustration_playfully"), a.intents)
        assertEquals("cat", a.tags.first())
        assertTrue(a.phrases.containsAll(listOf("why me", "kya yaar", "उफ़")))
        assertEquals("😫", a.emoji)
        assertEquals(0.84f, a.confidence, 0.001f)
    }

    @Test fun acceptsStringsWhereListsWereAsked() {
        val a = Annotation.parse("""{"description":"Dog laughing","emotions":"amusement, happiness","tags":"dog,laugh"}""")!!
        assertEquals(listOf("amusement", "happiness"), a.emotions)
        assertEquals(listOf("dog", "laugh"), a.tags)
    }

    @Test fun rejectsUnusableReplies() {
        assertNull(Annotation.parse("I can't see the image."))
        assertNull(Annotation.parse("{not json"))
        assertNull(Annotation.parse("""{"confidence": 0.2}"""))
    }

    @Test fun correctionsWinFieldByField() {
        val model = Annotation(description = "A cat", emotions = listOf("happiness"), tags = listOf("cat"))
        val eff = model.overriddenBy(Correction(description = "Cat rolling its eyes", tags = listOf("unimpressed")))
        assertEquals("Cat rolling its eyes", eff.description)
        assertEquals(listOf("happiness"), eff.emotions) // not corrected: kept
        assertEquals(listOf("unimpressed"), eff.tags)
        assertEquals(model, model.overriddenBy(null))
    }

    @Test fun searchTextCarriesAllFields() {
        val t = Annotation("Monkey covering eyes", "OOPS", listOf("embarrassment"), listOf("reacting_to_mistake"),
            listOf("monkey"), listOf("my bad", "galti ho gayi")).searchText()
        listOf("Monkey covering eyes", "OOPS", "embarrassment", "reacting to mistake", "monkey", "galti ho gayi").forEach {
            assertTrue("missing $it in $t", t.contains(it))
        }
    }
}
