package io.github.sillypuppyy.dogboard.engine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Accented words sit on their plain letters' path and keep their accents (Lexicon.fold). */
class LexiconAccentTest : FunSpec({
    test("café is found by typing cafe and comes back with its accent") {
        val lex = Lexicon.of(mapOf("the" to 100L, "café" to 10L, "naïve" to 5L))
        lex.contains("cafe") shouldBe true
        lex.storedForm("cafe") shouldBe "café"
        lex.storedForm("naive") shouldBe "naïve"
        lex.storedForm("the") shouldBe "the"
    }

    test("fold keeps plain letters and strips accents") {
        Lexicon.fold('E') shouldBe 'e'
        Lexicon.fold('é') shouldBe 'e'
        Lexicon.fold('Ö') shouldBe 'o'
        Lexicon.fold('\'') shouldBe '\''
    }

    test("a letter typed from a popup stays in the correction (iOS FilterPopupVariantMismatch)") {
        keepsPopupLetters("café", "cafe") shouldBe false
        keepsPopupLetters("café", "café") shouldBe true
        keepsPopupLetters("cafè", "café") shouldBe false
        keepsPopupLetters("Über", "Uber") shouldBe false
        keepsPopupLetters("über", "Über") shouldBe true // case may change, the accent may not
        keepsPopupLetters("cafe", "café") shouldBe true // plain letters typed: the accent may be added
        keepsPopupLetters("don’t", "don't") shouldBe true // a curly apostrophe is not a popup letter
        keepsPopupLetters("thw", "the") shouldBe true
    }
})
