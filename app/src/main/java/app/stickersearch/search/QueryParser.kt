package app.stickersearch.search

/**
 * Turns what someone types into search signals: positive words, words to avoid ("not offensive",
 * "without anger", "gussa nahi"), and English synonyms for common Hinglish so the English-leaning
 * models understand romanized Hindi. Pure; unit-tested.
 */
object QueryParser {

    data class Parsed(
        val original: String,
        /** Text sent to the embedding models: the query plus English glosses of Hinglish words. */
        val expanded: String,
        val terms: List<String>,
        val avoid: List<String>,
    )

    private val STOP = setOf(
        "a", "an", "the", "of", "to", "for", "and", "or", "but", "is", "it", "that", "this", "when", "with", "me",
        "my", "i", "am", "something", "sticker", "stickers", "find", "show", "give", "some", "someone", "say", "says",
        "saying", "be", "being", "in", "on", "at", "like", "very", "so", "way", "just", "kind", "sort", "one",
        "ek", "koi", "kuch", "wala", "wali", "wale", "hai", "ho", "hain", "ka", "ki", "ke", "ko", "se", "aur",
    )

    private val NEGATORS = setOf("not", "no", "without", "never", "non", "nahi", "nahin", "mat", "bina")
    private val POSTFIX_NEGATORS = setOf("nahi", "nahin", "mat")

    /** Hinglish (and a few Hindi) words → English glosses. */
    val HINGLISH: Map<String, List<String>> = mapOf(
        "hasi" to listOf("laugh", "funny"), "hassi" to listOf("laugh", "funny"), "haso" to listOf("laugh"),
        "hasna" to listOf("laugh"), "mazak" to listOf("joke", "teasing"), "mazaak" to listOf("joke", "teasing"),
        "majak" to listOf("joke"), "gussa" to listOf("angry", "anger"), "ghussa" to listOf("angry"),
        "naraz" to listOf("upset", "angry"), "naraaz" to listOf("upset"), "dukh" to listOf("sad", "sadness"),
        "dukhi" to listOf("sad"), "udaas" to listOf("sad"), "udas" to listOf("sad"), "rona" to listOf("crying", "sad"),
        "ro" to listOf("crying"), "khush" to listOf("happy"), "khushi" to listOf("happiness", "joy"),
        "pyaar" to listOf("love"), "pyar" to listOf("love"), "ishq" to listOf("love"), "dil" to listOf("heart", "love"),
        "dosti" to listOf("friendship"), "dost" to listOf("friend"), "yaar" to listOf("friend", "buddy"),
        "bhai" to listOf("bro", "brother"), "behen" to listOf("sister"), "pagal" to listOf("crazy", "silly"),
        "bewakoof" to listOf("stupid", "fool"), "bevakoof" to listOf("stupid"), "ullu" to listOf("fool"),
        "bakwas" to listOf("nonsense", "rubbish"), "faltu" to listOf("useless", "nonsense"),
        "sharam" to listOf("embarrassment", "shame"), "sharmindgi" to listOf("embarrassment"),
        "dar" to listOf("fear", "scared"), "darr" to listOf("fear"), "darna" to listOf("scared"),
        "hairan" to listOf("surprised", "shocked"), "hairaan" to listOf("surprised"), "kya" to listOf("what"),
        "sach" to listOf("really", "truth"), "sachchi" to listOf("really"), "jhooth" to listOf("lie"),
        "bol" to listOf("tell"), "bola" to listOf("told"), "boli" to listOf("told"), "kaha" to listOf("said", "told"),
        "maine" to listOf("i"), "tha" to listOf("was"), "thi" to listOf("was"), "gaya" to listOf("gone", "done"),
        "gayi" to listOf("gone"), "mast" to listOf("awesome", "great"), "badhiya" to listOf("great", "nice"),
        "zabardast" to listOf("amazing"), "shabaash" to listOf("well done", "praise"), "wah" to listOf("wow", "praise"),
        "waah" to listOf("wow"), "chal" to listOf("ok", "go"), "chalo" to listOf("okay", "lets go"),
        "theek" to listOf("okay", "fine"), "thik" to listOf("okay"), "acha" to listOf("okay", "good"),
        "accha" to listOf("okay", "good"), "achha" to listOf("okay", "good"), "haan" to listOf("yes", "agree"),
        "ha" to listOf("yes"), "nahi" to listOf("no"), "bas" to listOf("enough", "stop"), "ruk" to listOf("wait", "stop"),
        "ruko" to listOf("wait"), "intezaar" to listOf("waiting"), "neend" to listOf("sleep", "goodnight"),
        "sona" to listOf("sleep"), "shubh" to listOf("good"), "ratri" to listOf("night"), "subah" to listOf("morning"),
        "shukriya" to listOf("thanks"), "dhanyavad" to listOf("thanks"), "sorry" to listOf("apology"),
        "maafi" to listOf("apology", "sorry"), "maaf" to listOf("sorry", "forgive"), "jeet" to listOf("win", "celebrate"),
        "party" to listOf("celebrate"), "nach" to listOf("dance"), "naach" to listOf("dance"),
        "taunt" to listOf("sarcasm", "mocking"), "tana" to listOf("sarcasm", "taunt"), "chidhana" to listOf("teasing"),
        "chhed" to listOf("teasing"), "uff" to listOf("frustration", "annoyed"), "ufff" to listOf("frustration"),
        "pareshan" to listOf("worried", "frustrated"), "tension" to listOf("stress", "worry"), "bore" to listOf("bored"),
        "thak" to listOf("tired"), "thaka" to listOf("tired"), "bhook" to listOf("hungry"), "khana" to listOf("food"),
        "ghoor" to listOf("stare", "angry"), "aankh" to listOf("eye", "wink"), "seedha" to listOf("straight"),
        "bindaas" to listOf("cool", "carefree"), "dhamaal" to listOf("fun", "party"), "jhakaas" to listOf("awesome"),
        "kamaal" to listOf("amazing", "wow"), "bekar" to listOf("bad", "useless"), "bura" to listOf("bad"),
        "achcha" to listOf("good"),
        "हंसी" to listOf("laugh"), "गुस्सा" to listOf("angry"), "प्यार" to listOf("love"), "दुख" to listOf("sad"),
        "धन्यवाद" to listOf("thanks"), "भाई" to listOf("bro"), "क्या" to listOf("what"), "मज़ाक" to listOf("joke"),
    )

    /** English synonyms that bridge everyday words with the annotation vocabulary. */
    private val SYNONYMS: Map<String, List<String>> = mapOf(
        "sarcastic" to listOf("sarcasm"), "sarcasm" to listOf("sarcastic"), "lol" to listOf("laugh", "funny"),
        "lmao" to listOf("laugh", "funny"), "haha" to listOf("laugh"), "shocked" to listOf("surprise", "disbelief"),
        "shock" to listOf("surprise", "disbelief"), "omg" to listOf("surprise", "disbelief"), "wtf" to listOf("disbelief"),
        "unbelievable" to listOf("disbelief"), "really" to listOf("disbelief"), "mistake" to listOf("reacting_to_mistake"),
        "stupid" to listOf("reacting_to_mistake", "mocking"), "facepalm" to listOf("frustration", "disbelief"),
        "annoyed" to listOf("frustration"), "irritated" to listOf("frustration"), "mad" to listOf("anger"),
        "angry" to listOf("anger"), "sad" to listOf("sadness"), "happy" to listOf("happiness"), "excited" to listOf("excitement"),
        "thanks" to listOf("saying_thanks", "gratitude"), "thank" to listOf("saying_thanks"), "bye" to listOf("ending_conversation"),
        "goodnight" to listOf("saying_goodnight"), "night" to listOf("saying_goodnight"), "congrats" to listOf("celebrating"),
        "congratulations" to listOf("celebrating"), "hi" to listOf("greeting"), "hello" to listOf("greeting"),
        "told" to listOf("saying_i_told_you_so"), "unimpressed" to listOf("not_impressed"), "impressed" to listOf("not_impressed"),
        "meh" to listOf("not_impressed", "boredom"), "offensive" to listOf("rude", "insulting", "aggressive", "vulgar"),
        "aggressive" to listOf("anger", "rude"), "rude" to listOf("insulting", "offensive"), "love" to listOf("expressing_love"),
        "sorry" to listOf("apologising"), "wait" to listOf("waiting"), "tease" to listOf("teasing"), "mock" to listOf("mocking_friend"),
        "playful" to listOf("teasing", "amusement"), "disappointed" to listOf("disappointment"), "disappointment" to listOf("showing_disappointment"),
        "surprised" to listOf("surprise"), "surprises" to listOf("surprise"), "confused" to listOf("confusion"),
        "embarrassed" to listOf("embarrassment"), "frustrated" to listOf("frustration"),
    )

    private val WORD = Regex("[\\p{L}\\p{M}\\p{N}_']+")

    fun parse(query: String): Parsed {
        val words = WORD.findAll(query.lowercase()).map { it.value.trim('\'') }.filter { it.isNotEmpty() }.toList()
        val avoid = mutableListOf<String>()
        val terms = mutableListOf<String>()
        var negateNext = false
        var lastWasTerm = false
        for (w in words) {
            when {
                // Hindi order: "gussa nahi", "offensive mat" negate the word before.
                w in POSTFIX_NEGATORS && lastWasTerm -> { avoid += terms.removeAt(terms.size - 1); lastWasTerm = false }
                w in NEGATORS -> { negateNext = true; lastWasTerm = false }
                w in STOP -> lastWasTerm = false
                negateNext -> { avoid += w; negateNext = false; lastWasTerm = false }
                else -> { terms += w; lastWasTerm = true }
            }
        }
        val gloss = terms.flatMap { HINGLISH[it].orEmpty() }
        val avoidGloss = avoid.flatMap { HINGLISH[it].orEmpty() + SYNONYMS[it].orEmpty() }
        val allTerms = (terms + gloss + terms.flatMap { SYNONYMS[it].orEmpty() } + gloss.flatMap { SYNONYMS[it].orEmpty() })
            .filter { it.length >= 2 }.distinct()
        // Embeddings don't understand "not X": the models see the query without the negated words.
        val negated = avoid.toSet()
        val positive = words.filter { it !in NEGATORS && it !in negated }.joinToString(" ")
        val expanded = buildString {
            append(positive.ifBlank { query.trim() })
            if (gloss.isNotEmpty()) append(" (").append(gloss.distinct().joinToString(", ")).append(')')
        }
        return Parsed(query, expanded, allTerms, (avoid + avoidGloss).filter { it.length >= 2 }.distinct())
    }
}
