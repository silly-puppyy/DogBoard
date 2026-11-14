package io.github.sillypuppyy.dogboard.engine

import io.github.sillypuppyy.dogboard.touch.GaussianTouch
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files

private val KEYS = QwertyGeometry.letterKeys(375f, 216f)
private val PARAMS = GaussianTouch.params(medianKeyHalfWidth = 18.75, typingInterval = 0.3)

private fun typed(letters: String) = CurrentWord().apply {
    for (c in letters) KEYS.getValue(c).let { addLetter(c, Tap(it.centerX, it.centerY), KEYS, PARAMS) }
}

private fun finished(letters: String): String {
    val word = typed(letters)
    return TypingEngine.correction(word, TypingEngine.candidates(word, null)) ?: letters
}

/** iOS autocorrect filters, ported. */
class FilterStackTest : FunSpec({
    fun c(w: String, score: Double) = Candidate(w, score, score, score, 1, isCompletion = false)

    test("BoostMinimumVariation: a plainer spelling within 10% goes first") {
        val ac = Autocorrect(Lexicon.of(mapOf("were" to 10L, "we're" to 10L)))
        // "we're" barely ahead of "were": the plainer "were" is kept
        ac.correction("wrre", listOf(c("we're", -5.0), c("were", -5.05))) shouldBe "were"
        // clearly ahead: the apostrophe form stays
        ac.correction("wrre", listOf(c("we're", -5.0), c("were", -5.5))) shouldBe "we're"
    }

    test("#28 preferred form from word counts: plural for nouns, possessive for listed names, near-ties left to the scores") {
        val ac = Autocorrect(Lexicon.of(mapOf("abbeys" to 10L, "abbey's" to 10L, "Johns" to 10L, "John's" to 10L, "As" to 10L, "A's" to 10L, "it's" to 10L, "its" to 10L, "saturdays" to 10L, "saturday's" to 10L)))
        ac.preferredForms = PreferredForms.parse("# test\njohn\n=saturday\n".reader().buffered())
        ac.correction("abbwys", listOf(c("abbey's", -5.0), c("abbeys", -5.6))) shouldBe "abbeys"
        ac.correction("jphns", listOf(c("Johns", -5.0), c("John's", -5.6))) shouldBe "John's"
        ac.correction("ad", listOf(c("as", -5.0), c("A's", -5.6))) shouldBe "as"
        // contractions are not possessives, near-ties keep the score order
        ac.correction("iys", listOf(c("it's", -5.0), c("its", -5.6))) shouldBe "it's"
        ac.correction("saturdsys", listOf(c("saturday's", -5.0), c("saturdays", -5.6))) shouldBe "saturday's"
    }

    test("FilterCommonLearnedTypos: i never becomes o, can never can't, and never abs") {
        isCommonLearnedTypo("i", "o") shouldBe true
        isCommonLearnedTypo("I", "O") shouldBe true
        isCommonLearnedTypo("can", "can’t") shouldBe true
        isCommonLearnedTypo("and", "abs") shouldBe true
        isCommonLearnedTypo("and", "an") shouldBe false
        isCommonLearnedTypo("Can", "can't") shouldBe false // exact case, as iOS
    }

    test("#13 a correction keeps the typed ampersands") {
        keepsAmpersands("at&y", "at&t") shouldBe true
        keepsAmpersands("at&y", "arty") shouldBe false
        keepsAmpersands("arty", "at&t") shouldBe true
    }

    test("never-suggest words are not offered, but stay typeable") {
        val dir = Files.createTempDirectory("dogboard-nosuggest").toFile()
        try {
            TypingEngine.unloadForTest()
            TypingEngine.load(
                openLexicon = { File("src/main/assets/dogboard/en.lexicon.tsv").bufferedReader() },
                openNoSuggest = { "# test\nduck\n".reader().buffered() },
            )
            TypingEngine.candidates(typed("dicj"), null).none { it.word.equals("duck", ignoreCase = true) } shouldBe true
            finished("duck") shouldBe "duck" // typed exactly: kept
        } finally {
            TypingEngine.unloadForTest()
            dir.deleteRecursively()
        }
    }

    test("FilterLearnedTypo: a run-together word learned once gives way to the split") {
        val dir = Files.createTempDirectory("dogboard-learned-typo").toFile()
        try {
            TypingEngine.unloadForTest()
            TypingEngine.load(
                openLexicon = { File("src/main/assets/dogboard/en.lexicon.tsv").bufferedReader() },
                userWords = File(dir, "user_words.txt"),
            )
            TypingEngine.learn("ofthe")
            finished("ofthe") shouldBe "of the"
        } finally {
            TypingEngine.unloadForTest()
            dir.deleteRecursively()
        }
    }

    test("RescoreShortWords: \"u\" scores like \"you\"") {
        val lex = Lexicon.of(linkedMapOf("you" to 100_000L, "i" to 50_000L, "y" to 300L, "u" to 100L))
        val keys = KEYS
        val u = keys.getValue('u').let { Tap(it.centerX, it.centerY) }
        val top = Decoder(lex).decode(listOf(u), keys, PARAMS, includeCompletions = false).first()
        top.word shouldBe "u"
        (top.languageModel > lex.logProb("u")!!) shouldBe true
    }

    test("a typed contact name is not corrected to another word (iOS contact-name filter)") {
        TypingEngine.unloadForTest()
        TypingEngine.load(openLexicon = { File("src/main/assets/dogboard/en.lexicon.tsv").bufferedReader() })
        try {
            val without = finished("jom")
            TypingEngine.setContactNames(listOf("Jom"))
            println("contact probe: jom -> $without without the contact, ${finished("jom")} with it")
            finished("jom") shouldBe "Jom"
            finished("thw") shouldBe "the" // other words are corrected as before
        } finally {
            TypingEngine.unloadForTest()
        }
    }

    test("learning a case variant of a dictionary word adds no new casing (what iOS filters #26/#27 guard)") {
        val lex = Lexicon.of(mapOf("hello" to 1000L, "the" to 5000L))
        lex.addWord("Hello", -12.0) // learned with a capital, e.g. at a sentence start
        lex.formsOf("hello") shouldBe listOf("hello")
        lex.addWord("Zorblax", -12.0) // a new word keeps the casing it was learned with
        lex.formsOf("zorblax") shouldBe listOf("Zorblax")
    }

    test("#17: a typed one-letter word isn't swapped for another; #22: no clean alternatives under a typed vulgar top") {
        TypingEngine.unloadForTest()
        TypingEngine.load(
            openLexicon = { File("src/main/assets/dogboard/en.lexicon.tsv").bufferedReader() },
            openNoSuggest = { "duck\n".reader().buffered() },
        )
        try {
            val a = typed("a")
            TypingEngine.correction(a, TypingEngine.candidates(a, "is")).let { it == null || it.length != 1 || it == "a" } shouldBe true
            val d = typed("duck")
            TypingEngine.candidates(d, null).map { it.word } shouldBe listOf("duck")
        } finally {
            TypingEngine.unloadForTest()
        }
    }

    test("an extra first tap is dropped, as iOS does (ghello → hello, gthe → the)") {
        TypingEngine.unloadForTest()
        TypingEngine.load(openLexicon = { File("src/main/assets/dogboard/en.lexicon.tsv").bufferedReader() })
        try {
            println("first-tap probe: ghello -> ${finished("ghello")}, gthe -> ${finished("gthe")}, ahello -> ${finished("ahello")}")
            finished("ghello") shouldBe "hello"
            finished("gthe") shouldBe "the"
            finished("hello") shouldBe "hello"
        } finally {
            TypingEngine.unloadForTest()
        }
    }
})
