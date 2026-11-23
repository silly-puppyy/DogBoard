package io.github.sillypuppyy.dogboard.ime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ExtraLettersTest : FunSpec({
    test("Russian on the Bulgarian phonetic layout: ы on и, э on е") {
        ExtraLetters.forEntry("bulgarian-phonetic-bg", "ru") shouldBe mapOf("и" to listOf("ы"), "е" to listOf("э"))
    }
    test("Spanish on English QWERTY: ñ on n; Finnish: å ä on a, ö on o") {
        ExtraLetters.forEntry("qwerty-en", "es") shouldBe mapOf("n" to listOf("ñ"))
        ExtraLetters.forEntry("qwerty-en", "fi") shouldBe mapOf("a" to listOf("å", "ä"), "o" to listOf("ö"))
    }
    test("nothing without a second language, or when the layout has every letter") {
        ExtraLetters.forEntry("qwerty-en", null) shouldBe emptyMap()
        ExtraLetters.forEntry("qwerty-es", "en") shouldBe emptyMap()
    }
})
