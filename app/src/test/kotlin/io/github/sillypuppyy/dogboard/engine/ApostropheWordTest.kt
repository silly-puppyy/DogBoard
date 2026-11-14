package io.github.sillypuppyy.dogboard.engine

import io.github.sillypuppyy.dogboard.touch.GaussianTouch
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

private val KEYS = QwertyGeometry.letterKeys(375f, 216f)
private val PARAMS = GaussianTouch.params(medianKeyHalfWidth = 18.75, typingInterval = 0.3)

/** Letters tapped on their key centres; an apostrophe comes from the symbol keys (no letter-key tap). */
private fun typed(text: String) = CurrentWord().apply {
    for (c in text) addLetter(c, KEYS[c.lowercaseChar()]?.let { Tap(it.centerX, it.centerY) }, KEYS, PARAMS)
}

private fun finished(text: String): String {
    val word = typed(text)
    return TypingEngine.correction(word, TypingEngine.candidates(word, null)) ?: text
}

/** Words typed with an apostrophe from the symbol keys are decoded from their letters. */
class ApostropheWordTest : FunSpec({
    test("the current word tracks a tapless apostrophe without losing its taps") {
        val w = typed("dog's")
        w.text shouldBe "dog's"
        w.decoderText shouldBe "dogs"
        w.typedApostrophes shouldBe 1
        w.tapsOrNull()!!.size shouldBe 4
        w.removeLast() // s
        w.removeLast() // the apostrophe
        w.text shouldBe "dog"
        w.typedApostrophes shouldBe 0
        w.tapsOrNull()!!.size shouldBe 3
        w.snapshot().decoderText shouldBe "dog"
    }

    test("a typo inside an apostrophe word is corrected, and the apostrophe stays") {
        TypingEngine.unloadForTest()
        TypingEngine.load(openLexicon = { File("src/main/assets/dogboard/en.lexicon.tsv").bufferedReader() })
        try {
            finished("dog's") shouldBe "dog's" // typed right: kept
            finished("dpg's") shouldBe "dog's" // one key off: fixed, not "dogs"
            finished("don't") shouldBe "don't"
            finished("dont") shouldBe "don't" // no apostrophe typed: unchanged behaviour
            println("unknown-word probe: Zorblax -> ${finished("Zorblax")}, Zorblax's -> ${finished("Zorblax's")}, Kayla's -> ${finished("Kayla's")}")
            finished("Kayla's") shouldBe "Kayla's" // a name's possessive the lexicon doesn't store stays
        } finally {
            TypingEngine.unloadForTest()
        }
    }

    test("the system spell checker: known words pass, typos get the engine's fixes") {
        TypingEngine.unloadForTest()
        TypingEngine.spell("teh", 3) shouldBe null // not loaded yet: no answer, nothing flagged
        TypingEngine.load(openLexicon = { File("src/main/assets/dogboard/en.lexicon.tsv").bufferedReader() })
        try {
            TypingEngine.spell("hello", 3) shouldBe (true to emptyList<String>())
            TypingEngine.spell("don’t", 3)!!.first shouldBe true // curly apostrophe
            TypingEngine.spell("1st", 3)!!.first shouldBe true // digits are never flagged
            val (known, fixes) = TypingEngine.spell("thw", 3)!!
            known shouldBe false
            fixes.first() shouldBe "the"
            TypingEngine.spell("Thw", 3)!!.second.first() shouldBe "The" // keeps the capital
        } finally {
            TypingEngine.unloadForTest()
        }
    }
})
