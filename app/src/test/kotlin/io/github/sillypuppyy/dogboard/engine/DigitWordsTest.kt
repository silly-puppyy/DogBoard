package io.github.sillypuppyy.dogboard.engine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Suffixes after a number (DigitWords): one slipped key fixed, anything else kept. */
class DigitWordsTest : FunSpec({
    test("ordinal suffixes follow the number") {
        DigitWords.suffixes("1") shouldBe listOf("st", "am", "pm")
        DigitWords.suffixes("22") shouldBe listOf("nd")
        DigitWords.suffixes("3") shouldBe listOf("rd", "am", "pm")
        DigitWords.suffixes("11") shouldBe listOf("th", "am", "pm")
        DigitWords.suffixes("113") shouldBe listOf("th")
        DigitWords.suffixes("101") shouldBe listOf("st")
    }
    test("a neighbouring key in the suffix is fixed") {
        DigitWords.fix("1", "sr") shouldBe "st" // r is next to t
        DigitWords.fix("5", "om") shouldBe "pm" // o is next to p
        DigitWords.fix("2", "bd") shouldBe "nd" // b is next to n
        DigitWords.fix("9", "AN") shouldBe "AM" // capitals kept
    }
    test("right, or too far off, stays as typed") {
        DigitWords.fix("1", "st") shouldBe null
        DigitWords.fix("5", "pm") shouldBe null
        DigitWords.fix("1", "th") shouldBe null // t is not next to s: maybe meant, leave it
        DigitWords.fix("30", "kg") shouldBe null // units are not suffixes we know
        DigitWords.fix("20", "km") shouldBe null
        DigitWords.fix("4", "x") shouldBe null
        DigitWords.fix("", "st") shouldBe null
    }
})
