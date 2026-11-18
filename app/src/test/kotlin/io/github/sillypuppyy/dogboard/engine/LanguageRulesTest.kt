package io.github.sillypuppyy.dogboard.engine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/** The en-US pack's rules.json and the engine's built-in English rules are the same (i18n step 3). */
class LanguageRulesTest : FunSpec({
    test("en-US rules.json equals LanguageRules.ENGLISH") {
        LanguageRules.parse(File("src/main/assets/dogboard/packs/en-US/rules.json").readText()) shouldBe LanguageRules.ENGLISH
    }
    test("French keeps a space before ? ! : ; in France and Belgium only (packs fr, fr-FR, fr-BE)") {
        for (t in listOf("fr", "fr-FR", "fr-BE")) {
            LanguageRules.parse(File("src/main/assets/dogboard/packs/$t/rules.json").readText()).spaceBeforeMarks shouldBe "?!:;"
        }
        File("src/main/assets/dogboard/packs/fr-CA").exists() shouldBe false
        LanguageRules.ENGLISH.spaceBeforeMarks shouldBe ""
    }
    test("pack tags are normalised as the layouts give them") {
        val n = io.github.sillypuppyy.dogboard.ime.LanguageRulesRegistry::normalize
        n("fr_FR") shouldBe "fr-FR"; n("fr-fr") shouldBe "fr-FR"; n("FR") shouldBe "fr"; n("fr-be-x") shouldBe "fr-BE"; n("") shouldBe ""
    }
})
