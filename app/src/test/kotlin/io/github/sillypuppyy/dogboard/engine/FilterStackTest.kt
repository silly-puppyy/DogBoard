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

/** iOS autocorrect filters. */
class FilterStackTest : FunSpec({
    fun c(w: String, score: Double) = Candidate(w, score, score, score, 1, isCompletion = false)

    test("BoostMinimumVariation: a plainer spelling within 10% goes first") {
        val ac = Autocorrect(Lexicon.of(mapOf("were" to 10L, "we're" to 10L)))
        // "we're" barely ahead of "were": the plainer "were" is kept
        ac.correction("wrre", listOf(c("we're", -5.0), c("were", -5.05))) shouldBe "were"
        // clearly ahead: the apostrophe form stays
        ac.correction("wrre", listOf(c("we're", -5.0), c("were", -5.5))) shouldBe "we're"
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
})
