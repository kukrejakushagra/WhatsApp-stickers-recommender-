package app.stickersearch.ml

/** Generated with the reference SentencePiece library (python). */
object TokenizerFixtures {
    val xlmr: List<Pair<String, List<Int>>> = listOf(
        "query: something sarcastic but not offensive" to listOf(0, 41, 1294, 12, 9844, 57, 42964, 28692, 1284, 959, 162333, 13, 2),
        "passage: Cartoon character banging head against a wall in frustration" to listOf(0, 46692, 12, 199693, 62816, 11339, 214, 10336, 26548, 10, 58982, 23, 208844, 2),
        "kya bakwas hai yaar 😂" to listOf(0, 30427, 3472, 13689, 1337, 151, 147, 6, 115114, 2),
        "भाई तू तो गया" to listOf(0, 53761, 38351, 2073, 5349, 2),
        "Find a sticker that says I told you so!" to listOf(0, 26040, 10, 156717, 450, 17378, 87, 30745, 398, 221, 38, 2),
        "disbelief, shock & surprise..." to listOf(0, 2837, 372, 51821, 4, 129517, 619, 55640, 27, 2),
        "  multiple   spaces\tand tab" to listOf(0, 48716, 32628, 7, 136, 20928, 2),
        "Ⅻ ﬁ full-width ＡＢＣ" to listOf(0, 39198, 809, 4393, 9, 146984, 47457, 2),
        "mujhe gussa aa raha hai" to listOf(0, 91420, 2497, 1192, 10, 11, 8980, 1337, 2),
        "नमस्ते दोस्तों 🙏" to listOf(0, 68700, 97883, 99154, 6, 113612, 2),
        "OK OK okay!!! 100%" to listOf(0, 14315, 14315, 68403, 1564, 3555, 2),
    )
    /** Lowercased as SigLIP2 does; eos appended, no padding. */
    val gemma: List<Pair<String, List<Int>>> = listOf(
        "query: something sarcastic but not offensive" to listOf(3630, 235292, 2775, 108135, 901, 780, 27769, 1),
        "passage: Cartoon character banging head against a wall in frustration" to listOf(135945, 235292, 16279, 3285, 141057, 2206, 2691, 476, 3587, 575, 46524, 1),
        "kya bakwas hai yaar 😂" to listOf(235273, 2194, 10718, 6570, 20302, 3965, 486, 24924, 1),
        "भाई तू तो गया" to listOf(236124, 52254, 10321, 236161, 34638, 44370, 1),
        "Find a sticker that says I told you so!" to listOf(3655, 476, 13331, 674, 2889, 496, 4203, 692, 712, 235341, 1),
        "disbelief, shock & surprise..." to listOf(1999, 70032, 235269, 15733, 724, 14285, 955, 1),
        "  multiple   spaces\tand tab" to listOf(139, 38571, 140, 46633, 226, 639, 6684, 1),
        "Ⅻ ﬁ full-width ＡＢＣ" to listOf(443, 350, 404, 59561, 2247, 235290, 2791, 235248, 241252, 242902, 240714, 1),
        "mujhe gussa aa raha hai" to listOf(2704, 235312, 480, 583, 76878, 28811, 169809, 20302, 1),
        "नमस्ते दोस्तों 🙏" to listOf(235530, 235579, 45884, 235483, 51484, 45884, 10370, 68226, 1),
        "OK OK okay!!! 100%" to listOf(1124, 4634, 12763, 4762, 235248, 235274, 235276, 235276, 235358, 1),
    )
}
