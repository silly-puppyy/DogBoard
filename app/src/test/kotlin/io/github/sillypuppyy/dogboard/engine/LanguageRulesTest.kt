package io.github.sillypuppyy.dogboard.engine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/** The en-US pack's rules.json and the engine's built-in English rules are the same (i18n step 3). */
class LanguageRulesTest : FunSpec({
    test("en-US rules.json equals LanguageRules.ENGLISH") {
        LanguageRules.parse(File("src/main/assets/dogboard/packs/en-US/rules.json").readText()) shouldBe LanguageRules.ENGLISH
    }
})
