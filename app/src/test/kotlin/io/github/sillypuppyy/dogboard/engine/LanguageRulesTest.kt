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
    test("sentence ends come from the rules: a Hindi danda or a Chinese full stop starts a sentence when the pack says so") {
        previousContext("Hello. ") shouldBe Bigrams.START
        previousContext("नमस्ते। ", "।") shouldBe Bigrams.START
        previousContext("Hello। ") shouldBe null // not a sentence end here: a mark, no word (as before)
        AutoShift.shouldShift("你好。", AutoShift.Type.SENTENCES, null, "。！？") shouldBe false // no space after: as for "."
        AutoShift.shouldShift("नमस्ते। ", AutoShift.Type.SENTENCES, null, ".!?।") shouldBe true
        AutoShift.shouldShift("नमस्ते। ", AutoShift.Type.SENTENCES, null) shouldBe false
    }
    test("quotes come from the rules: English by default, a pack's own set when it has one") {
        val en = LanguageRules.ENGLISH.smartQuotes
        io.github.sillypuppyy.dogboard.feel.SmartPunctuation.forInput("\"", "", "", true, false, en)?.insert shouldBe "“"
        val de = LanguageRules.parse("""{"quotes": "„“‚‘’"}""").smartQuotes
        io.github.sillypuppyy.dogboard.feel.SmartPunctuation.forInput("\"", "", "", true, false, de)?.insert shouldBe "„"
        io.github.sillypuppyy.dogboard.feel.SmartPunctuation.forInput("\"", "Hallo", "", true, false, de)?.insert shouldBe "“"
        LanguageRules.parse("""{"quotes": "xx"}""").smartQuotes shouldBe en // malformed: the default set
    }
    test("the double-space replacement is a rule: \". \" by default") {
        LanguageRules().doubleSpace shouldBe ". "
        LanguageRules.ENGLISH.doubleSpace shouldBe ". "
        LanguageRules.parse("""{"double_space": "। "}""").doubleSpace shouldBe "। "
    }
    test("the possessive suffix is a rule: English \"'s\", none by default (a pack must not inherit English's)") {
        LanguageRules.ENGLISH.possessiveSuffix shouldBe "'s"
        LanguageRules.ENGLISH.possessiveLetters shouldBe "s"
        LanguageRules().possessiveLetters shouldBe null
        LanguageRules.parse("""{"sentence_end": ".!?"}""").possessiveLetters shouldBe null
    }
    test("pack tags are normalised as the layouts give them") {
        val n = io.github.sillypuppyy.dogboard.ime.LanguageRulesRegistry::normalize
        n("fr_FR") shouldBe "fr-FR"; n("fr-fr") shouldBe "fr-FR"; n("FR") shouldBe "fr"; n("fr-be-x") shouldBe "fr-BE"; n("") shouldBe ""
    }
})
