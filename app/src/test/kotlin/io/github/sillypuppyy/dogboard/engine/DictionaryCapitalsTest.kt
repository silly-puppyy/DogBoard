package io.github.sillypuppyy.dogboard.engine

import io.github.sillypuppyy.dogboard.touch.GaussianTouch
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private val KEYS = QwertyGeometry.letterKeys(375f, 216f)
private val PARAMS = GaussianTouch.params(medianKeyHalfWidth = 18.75, typingInterval = 0.3)

private fun typed(letters: String) = CurrentWord().apply {
    for (c in letters) KEYS.getValue(c).let { addLetter(c, Tap(it.centerX, it.centerY), KEYS, PARAMS) }
}

/** "Capitalise names and I" (TypingEngine.dictionaryCapitals), separate from sentence starts. */
class DictionaryCapitalsTest : FunSpec({
    afterTest { TypingEngine.dictionaryCapitals = { true } }

    test("on: a word typed without capitals takes the dictionary's (paris -> Paris, i -> I)") {
        TypingEngine.dictionaryCapitals = { true }
        TypingEngine.caseFor(typed("paris"))("Paris") shouldBe "Paris"
        TypingEngine.caseFor(typed("i"))("I") shouldBe "I"
    }

    test("off: it stays lowercase as typed") {
        TypingEngine.dictionaryCapitals = { false }
        TypingEngine.caseFor(typed("paris"))("Paris") shouldBe "paris"
        TypingEngine.caseFor(typed("i"))("I") shouldBe "i"
    }
})
