package io.github.sillypuppyy.dogboard.engine

import io.github.sillypuppyy.dogboard.touch.GaussianTouch
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

private val KEYS = QwertyGeometry.letterKeys(375f, 216f)
private val PARAMS = GaussianTouch.params(medianKeyHalfWidth = 18.75, typingInterval = 0.3)

private fun typed(text: String) = CurrentWord().apply {
    for (c in text) addLetter(c, KEYS[c.lowercaseChar()]?.let { Tap(it.centerX, it.centerY) }, KEYS, PARAMS)
}

private fun finished(text: String): String {
    val word = typed(text)
    return TypingEngine.correction(word, TypingEngine.candidates(word, null)) ?: text
}

/** A second language typed together with the first (TypingEngine.loadSecondary): both decoded, candidates merged. */
class SecondLanguageTest : FunSpec({
    // a small second language: words English lacks, as a Spanish pack would bring them
    val spanish = "#dogboard-lexicon 1\nmañana\t5000\ncuchara\t3000\nperro\t4000\ndesayuno\t4000\n"

    test("a word of the second language is kept, and its typos are corrected to it") {
        TypingEngine.unloadForTest()
        TypingEngine.load(openLexicon = { File("src/main/assets/dogboard/en.lexicon.tsv").bufferedReader() })
        try {
            // the control: English alone has no "desayuno" to correct to
            (finished("desayubo") == "desayuno") shouldBe false
            TypingEngine.loadSecondary(Lexicon.parse(spanish.reader().buffered()), null, LanguageRules(), null, null)
            TypingEngine.hasSecondary shouldBe true
            finished("cuchara") shouldBe "cuchara"
            finished("desayubo") shouldBe "desayuno"
            finished("the") shouldBe "the" // the first language still types
            // the look back leaves a word of the second language alone
            val prev = typed("perro"); val cur = typed("is")
            TypingEngine.retrocorrection(prev, "perro", null, cur, previousCandidates = TypingEngine.candidates(prev, null), currentCandidates = TypingEngine.candidates(cur, "perro")) shouldBe null
            // a third language: its words count too, the second's stay
            (finished("boulangeroe") == "boulangerie") shouldBe false // not yet: no language has it
            TypingEngine.loadSecondary(Lexicon.parse("#dogboard-lexicon 1\nboulangerie\t4000\n".reader().buffered()), null, LanguageRules(), null, null)
            finished("boulangeroe") shouldBe "boulangerie"
            finished("desayubo") shouldBe "desayuno"
            TypingEngine.unloadSecondary()
            TypingEngine.hasSecondary shouldBe false
        } finally {
            TypingEngine.unloadSecondary()
            TypingEngine.unloadForTest()
        }
    }
})
