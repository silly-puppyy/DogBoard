package io.github.sillypuppyy.dogboard.ime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.Locale

/** Which keyboard languages a first start picks from the phone's languages. */
class FirstLanguagesTest : FunSpec({
    val withLayouts = setOf("en", "de", "es", "fr", "it", "ka")
    fun pick(vararg tags: String) = FirstLanguages.choose(tags.map { Locale.forLanguageTag(it) }) { it in withLayouts }

    test("a phone in English only keeps the default") {
        pick("en-US") shouldBe emptyList()
        pick("en-GB", "en-US") shouldBe emptyList()
    }

    test("another language comes first, English stays only when the phone has it") {
        pick("de-DE") shouldBe listOf("de")
        pick("es-MX", "en-US") shouldBe listOf("es", "en-US")
        pick("fr-FR", "fr-CA", "it-IT") shouldBe listOf("fr", "it")
    }

    test("languages without a layout are left out") {
        pick("xx-YY", "ka-GE") shouldBe listOf("ka")
        pick("xx-YY") shouldBe emptyList()
    }
})
