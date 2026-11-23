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
    test("two languages beside English: both sets of letters, each once") {
        ExtraLetters.forEntry("qwerty-en", listOf("es", "fi")) shouldBe mapOf("n" to listOf("ñ"), "a" to listOf("å", "ä"), "o" to listOf("ö"))
    }
    test("nothing without a second language, or when the layout has every letter") {
        ExtraLetters.forEntry("qwerty-en", null as String?) shouldBe emptyMap()
        ExtraLetters.forEntry("qwerty-es", "en") shouldBe emptyMap()
    }
})

class LayoutRowsTest : FunSpec({
    test("a layout's rows: marks hold their place, rows centred; Bulgarian ЯВЕРТЪ neighbours differ from Phonetic's") {
        val azerty = io.github.sillypuppyy.dogboard.engine.LetterRows.ofLayout(GeneratedLayouts.all.first { it.id == "azerty-fr" }.rows)
        azerty.centres.containsKey('\'') shouldBe false
        azerty.centres['w'] shouldBe (2.0f to 2.5f) // the third row (7 keys) centred under 10
        val yav = io.github.sillypuppyy.dogboard.engine.LetterRows.ofLayout(GeneratedLayouts.all.first { it.id == "bulgarian-bg" }.rows)
        yav.neighbours('я').toSet() shouldBe setOf('в', 'а', 'с')
    }
})
