package io.github.sillypuppyy.dogboard.feel

import io.github.sillypuppyy.dogboard.feel.SmartPunctuation.Output
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/** iOS smart punctuation rules (SmartPunctuation.kt). */
class SmartPunctuationTest : FunSpec({
    fun type(input: String, before: String, after: String = "", quotes: Boolean = true, dashes: Boolean = true) =
        SmartPunctuation.forInput(input, before, after, quotes, dashes)

    test("double quotes open at the start, after space and openers, close otherwise") {
        type("\"", "") shouldBe Output(0, "“")
        type("\"", "He said ") shouldBe Output(0, "“")
        type("\"", "(") shouldBe Output(0, "“")
        type("\"", "He said “hi") shouldBe Output(0, "”")
        type("\"", "He said “hi.") shouldBe Output(0, "”")
    }
    test("single quotes: opening, closing, apostrophe between letters") {
        type("'", "say ") shouldBe Output(0, "‘")
        type("'", "‘hi") shouldBe Output(0, "’")
        type("'", "don", "t") shouldBe Output(0, "’") // apostrophe
        type("'", "dogs") shouldBe Output(0, "’") // possessive at the end: closing = apostrophe character
        type("'", "“") shouldBe Output(0, "‘") // right after an opening quote
    }
    test("dashes: en dash between digits, em dash otherwise, en dash upgrades") {
        type("-", "10-") shouldBe Output(1, "–")
        type("-", "wait-") shouldBe Output(1, "—")
        type("-", "a--") shouldBe Output(2, "—")
        type("-", "10–") shouldBe Output(1, "—")
        type("-", "wait—").shouldBeNull()
        type("-", "well").shouldBeNull()
    }
    test("ellipsis: a third period") {
        type(".", "Hmm..") shouldBe Output(2, "…")
        type(".", "Hmm.").shouldBeNull()
    }
    test("switches: quotes and dashes independently, as on iOS") {
        type("\"", "", quotes = false).shouldBeNull()
        type("-", "wait-", dashes = false).shouldBeNull()
        type(".", "Hmm..", dashes = false).shouldBeNull()
        type("\"", "", dashes = false) shouldBe Output(0, "“")
    }
})
