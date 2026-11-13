package io.github.sillypuppyy.dogboard.engine

import io.github.sillypuppyy.dogboard.touch.GaussianTouch
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.io.File

private val LEXICON by lazy {
    File("src/main/assets/dogboard/en.lexicon.tsv").bufferedReader().use { Lexicon.parse(it) }
}
private val KEYS = QwertyGeometry.letterKeys(375f, 216f)
private val PARAMS = GaussianTouch.params(medianKeyHalfWidth = 18.75, typingInterval = 0.3)
private val DECODER by lazy { Decoder(LEXICON) }
private val AUTOCORRECT by lazy { Autocorrect(LEXICON) }

/** Taps at the centres of the keys for [letters]. */
private fun centreTaps(letters: String) =
    letters.lowercase().map { c -> KEYS.getValue(c).let { Tap(it.centerX, it.centerY) } }

private fun topWord(typed: String) = DECODER.decode(centreTaps(typed), KEYS, PARAMS).first { !it.isCompletion }.word

private fun corrected(typed: String): String {
    val candidates = DECODER.decode(centreTaps(typed), KEYS, PARAMS)
    return AUTOCORRECT.correction(typed, candidates, DECODER.literalCost(centreTaps(typed), typed, KEYS, PARAMS)) ?: typed
}

class EngineTest : FunSpec({
    context("Lexicon") {
        test("parses our format and keeps the stored casing") {
            LEXICON.size shouldBeGreaterThan 40_000
            LEXICON.contains("hello") shouldBe true
            LEXICON.storedForm("i'm") shouldBe "I'm"
            LEXICON.contains("qzxv").shouldBe(false)
        }
        test("more frequent words have higher probability") {
            (LEXICON.logProb("the")!! > LEXICON.logProb("married")!!) shouldBe true
            LEXICON.logProb("qzxv").shouldBeNull()
        }
        test("each node knows its most frequent completion") {
            var node = LEXICON.root
            for (c in "hel") node = node.child(c)!!
            node.bestWord!!.word shouldBe "help"
        }
    }

    context("Decoder") {
        test("exact taps decode to the word") {
            topWord("hello") shouldBe "hello"
            topWord("keyboard") shouldBe "keyboard"
        }
        test("a neighbouring key: hrllo -> hello") { topWord("hrllo") shouldBe "hello" }
        test("swapped letters: teh -> the") { topWord("teh") shouldBe "the" }
        test("missed letter: becuse -> because, tomorow -> tomorrow") {
            topWord("becuse") shouldBe "because"
            topWord("tomorow") shouldBe "tomorrow"
        }
        test("extra tap: thhe -> the") { topWord("thhe") shouldBe "the" }
        test("missing apostrophe: dont -> don't") { topWord("dont") shouldBe "don't" }
        test("completions are offered for a prefix") {
            val words = DECODER.decode(centreTaps("keyb"), KEYS, PARAMS).filter { it.isCompletion }.map { it.word }
            words.contains("keyboard") shouldBe true
        }
    }

    context("Autocorrect") {
        test("fixes typos") {
            corrected("teh") shouldBe "the"
            corrected("hrllo") shouldBe "hello"
            corrected("dont") shouldBe "don't"
            corrected("im") shouldBe "I'm"
            corrected("ill") shouldBe "I'll"
        }
        test("keeps correctly typed words, including ones close to a more frequent word") {
            corrected("hello") shouldBe "hello"
            corrected("form") shouldBe "form"
            corrected("well") shouldBe "well"
            corrected("were") shouldBe "were"
        }
        test("fixes the casing of i and carries over a capital") {
            corrected("i") shouldBe "I"
            corrected("Teh") shouldBe "The"
            corrected("TEH") shouldBe "THE"
        }
        test("leaves gibberish with no close word alone") {
            corrected("qzxvpq") shouldBe "qzxvpq"
            corrected("tehteh") shouldBe "tehteh"
        }
    }
})
