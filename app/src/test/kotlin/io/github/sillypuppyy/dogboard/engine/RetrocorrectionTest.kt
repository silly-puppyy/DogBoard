package io.github.sillypuppyy.dogboard.engine

import io.github.sillypuppyy.dogboard.touch.GaussianTouch
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.io.File

private val KEYS = QwertyGeometry.letterKeys(375f, 216f)
private val PARAMS = GaussianTouch.params(medianKeyHalfWidth = 18.75, typingInterval = 0.3)

private fun typed(letters: String) = CurrentWord().apply {
    for (c in letters) KEYS.getValue(c).let { addLetter(c, Tap(it.centerX, it.centerY), KEYS, PARAMS) }
}

/** iOS retrocorrection: the next word can change the one before it. */
class RetrocorrectionTest : FunSpec({
    // a small context model: "I love you" is common, "lobe" never comes before "you"
    val bigrams = "#dogboard-bigrams 1\n<s>\ti\t1000\ni\tlove\t600\nlove\tyou\t900\ni\tthink\t300\nthink\tso\t200\n"

    beforeSpec {
        TypingEngine.unloadForTest()
        TypingEngine.load(
            openLexicon = { File("src/main/assets/dogboard/en.lexicon.tsv").bufferedReader() },
            openBigrams = { bigrams.reader().buffered() },
        )
    }
    afterSpec { TypingEngine.unloadForTest() }

    test("a word that only makes sense as another changes once the next word is typed") {
        TypingEngine.retrocorrection(typed("lobe"), "lobe", "i", typed("you")) shouldBe "love"
    }
    test("a word that fits stays") {
        TypingEngine.retrocorrection(typed("love"), "love", "i", typed("you")).shouldBeNull()
        TypingEngine.retrocorrection(typed("think"), "think", "i", typed("so")).shouldBeNull()
    }
    test("words with digits are never changed") {
        TypingEngine.retrocorrection(typed("lobe"), "lobe2", "i", typed("you")).shouldBeNull()
    }

    test("from the candidates already decoded, the look back decides the same (no decoding at word end)") {
        fun fast(prev: String, committed: String, before: String, cur: String) = TypingEngine.retrocorrection(
            typed(prev), committed, before, typed(cur),
            previousCandidates = TypingEngine.candidates(typed(prev), before),
            currentCandidates = TypingEngine.candidates(typed(cur), committed),
        )
        fast("lobe", "lobe", "i", "you") shouldBe "love"
        fast("love", "love", "i", "you").shouldBeNull()
        fast("think", "think", "i", "so").shouldBeNull()
    }
})
