package io.github.sillypuppyy.dogboard.ime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Whether a layout types a loaded language: the bar and autocorrect are on only when it does. */
class LanguageLayoutsTest : FunSpec({
    test("a layout types its own language, region or not") {
        LanguageLayouts.types("qwerty-en", listOf("en-US")) shouldBe true
        LanguageLayouts.types("azerty-fr", listOf("fr")) shouldBe true
    }

    test("a layout of another language doesn't") {
        LanguageLayouts.types("azerty-fr", listOf("en-US")) shouldBe false
        LanguageLayouts.types("qwerty-en", listOf("ru")) shouldBe false
    }

    test("a combined keyboard on its second language's layout types a loaded language") {
        // English + French on French AZERTY: English loaded first, French with it (only the first was checked:
        // no suggestions and no autocorrect at all, 2026-11-24)
        LanguageLayouts.types("azerty-fr", listOf("en-US", "fr")) shouldBe true
        LanguageLayouts.types("qwerty-en", listOf("fr", "en-US")) shouldBe true
    }
})
