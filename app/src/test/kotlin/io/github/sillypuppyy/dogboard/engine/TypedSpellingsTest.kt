package io.github.sillypuppyy.dogboard.engine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** One table of typed spellings: German digraphs and Vietnamese Telex as rules. */
class TypedSpellingsTest : FunSpec({
    val german = TypedSpellings(listOf("ä" to "ae", "ö" to "oe", "ü" to "ue").map { LanguageRules.Spelling(letter = it.first, typed = it.second) })
    val telex = TypedSpellings(
        listOf("â" to "aa", "ă" to "aw", "ê" to "ee", "ô" to "oo", "ơ" to "ow", "ư" to "uw", "đ" to "dd").map { LanguageRules.Spelling(letter = it.first, typed = it.second) } +
            listOf("́" to "s", "̀" to "f", "̉" to "r", "̃" to "x", "̣" to "j").map { LanguageRules.Spelling(mark = it.first, typed = it.second, at = LanguageRules.Spelling.AT_END) },
    )
    fun kept(t: TypedSpellings, s: String) = s.filterIndexed { i, _ -> !t.extraLetters(s)[i] }

    test("German: spelled forms and the letters set aside") {
        german.spelled("für") shouldBe "fuer"
        german.spelled("Züge") shouldBe "zuege"
        kept(german, "fuer") shouldBe "fur"
        kept(german, "waere") shouldBe "ware"
    }

    test("Telex: vowel letters, đ and a tone at the end") {
        telex.spelled("việt") shouldBe "vieetj"
        telex.spelled("Đà") shouldBe "ddaf"
        telex.spelled("người") shouldBe "nguwowif" // Telex also takes "nguowif" (one w for both): not a simple rule, not covered
        telex.spelled("ma") shouldBe "ma"
        kept(telex, "vieetj") shouldBe "viet"
        kept(telex, "ddaf") shouldBe "da"
        kept(telex, "nguwowif") shouldBe "nguoi"
    }

    test("Telex ươ typed as uow: u and o stay, only the w and the tone are set aside (được, người)") {
        val vi = TypedSpellings(listOf(LanguageRules.Spelling(letter = "ươ", typed = "uow")) +
            listOf("ơ" to "ow", "ư" to "uw", "đ" to "dd").map { LanguageRules.Spelling(letter = it.first, typed = it.second) } +
            listOf("̣" to "j", "̀" to "f").map { LanguageRules.Spelling(mark = it.first, typed = it.second, at = LanguageRules.Spelling.AT_END) })
        vi.spelled("được") shouldBe "dduowcj"
        vi.spelled("người") shouldBe "nguowif"
        "nguowif".filterIndexed { i, _ -> !vi.extraLetters("nguowif")[i] } shouldBe "nguoi"
        "dduowcj".filterIndexed { i, _ -> !vi.extraLetters("dduowcj")[i] } shouldBe "duoc"
    }

    test("no rules: nothing set aside") {
        TypedSpellings(emptyList()).isEmpty shouldBe true
        TypedSpellings(emptyList()).extraLetters("fuer").none { it } shouldBe true
    }
})
