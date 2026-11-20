package io.github.sillypuppyy.dogboard.engine

import io.github.sillypuppyy.dogboard.engine.CaseAdapt.AUTOSHIFTED
import io.github.sillypuppyy.dogboard.engine.CaseAdapt.Context
import io.github.sillypuppyy.dogboard.engine.CaseAdapt.SHIFTED
import io.github.sillypuppyy.dogboard.engine.CaseAdapt.Type
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/** The iOS case rules on google / Google / GOOGLE and friends (CaseAdapt). */
class CaseAdaptTest : FunSpec({
    // dictionary: "Google" exists capitalised (as well as "google"); "the" only lowercase
    val casing = object : CaseAdapt.Casing {
        val capitalised = setOf("google", "paris", "smith")
        override fun hasCapitalisedForm(word: String) = word.lowercase() in capitalised
    }

    /** Flags for [typed]: user shift on capitals, or AUTOSHIFTED on the first letter when [auto]. */
    fun flags(typed: String, auto: Boolean = false, capsLock: Boolean = false) = typed.mapIndexed { i, c ->
        when {
            auto && i == 0 -> AUTOSHIFTED
            c.isUpperCase() || capsLock -> SHIFTED
            else -> 0
        }
    }
    fun adapt(candidate: String, typed: String, auto: Boolean = false, context: Context = Context.NONE, capsLock: Boolean = false) =
        CaseAdapt.apply(candidate, typed, flags(typed, auto, capsLock), if (capsLock) Context.CAPS_LOCK else context, casing)

    test("a capital shifted inside a word is kept (user 2026-11-20: DiT -> Dit -> Fit)") {
        adapt("fit", "DiT", auto = true, context = Context.AUTOSHIFT) shouldBe null
        adapt("dit", "DiT", auto = true, context = Context.AUTOSHIFT) shouldBe null
        adapt("fit", "DiT") shouldBe null
        // a candidate that keeps what was typed still goes
        adapt("DiTs", "DiT") shouldBe "DiTs"
    }

    test("classification") {
        CaseAdapt.classify("google", flags("google"), Context.NONE) shouldBe Type.NONE
        CaseAdapt.classify("Google", flags("Google"), Context.SHIFT) shouldBe Type.EXPLICIT_SHIFT
        CaseAdapt.classify("Google", flags("Google", auto = true), Context.AUTOSHIFT) shouldBe Type.AUTOSHIFT
        CaseAdapt.classify("GOOGLE", flags("GOOGLE"), Context.SHIFT) shouldBe Type.ALL_CAPS
        CaseAdapt.classify("gOOgle", flags("gOOgle"), Context.NONE) shouldBe Type.MIXED
        CaseAdapt.classify("G", flags("G", capsLock = true), Context.CAPS_LOCK) shouldBe Type.ALL_CAPS
        CaseAdapt.classify("ABC", listOf(AUTOSHIFTED, AUTOSHIFTED, AUTOSHIFTED), Context.AUTOSHIFT_ALL) shouldBe Type.AUTOSHIFT_ALL
    }
    test("google: lowercase keeps the dictionary's casing of the chosen word") {
        adapt("google", "googel") shouldBe "google" // the verb
        adapt("Google", "googel") shouldBe "Google" // the company: which one wins is the language model's call
        adapt("Paris", "pari") shouldBe "Paris"
    }
    test("Google: a shift you pressed capitalises the correction") {
        adapt("google", "Googel") shouldBe "Google"
        adapt("Google", "Googel") shouldBe "Google"
        adapt("the", "Teh") shouldBe "The"
    }
    test("Google at a sentence start (keyboard shift)") {
        adapt("google", "Googel", auto = true, context = Context.AUTOSHIFT) shouldBe "Google"
        adapt("the", "Teh", auto = true, context = Context.AUTOSHIFT) shouldBe "The"
    }
    test("iPhone keeps its lowercase start under keyboard shift") {
        adapt("iPhone", "Iphine", auto = true, context = Context.AUTOSHIFT) shouldBe "iPhone"
    }
    test("GOOGLE: all caps stays all caps") {
        adapt("google", "GOOGEL") shouldBe "GOOGLE"
        adapt("Google", "GOOGEL") shouldBe "GOOGLE"
        adapt("the", "TEH", capsLock = true) shouldBe "THE"
    }
    test("gOOgle: mixed case is not corrected into another word") {
        adapt("google", "gOOgel").shouldBeNull()
        adapt("google", "gOOgle").shouldBeNull() // iOS keeps the typed capitals (no case-insensitive accept; decompile 2026-11-20)
    }
    test("McDonald: one shifted capital is corrected, two shifted capitals are left as typed") {
        adapt("McDonald", "Mcdonals") shouldBe "McDonald" // explicit shift; the dictionary's capitals stay
        adapt("McDonald", "McDonals").shouldBeNull() // mixed case with 2 capitals you pressed: iOS keeps your word
    }
    test("capitaliseFirst keeps existing capitals and skips a leading apostrophe") {
        CaseAdapt.capitaliseFirst("iPhone") shouldBe "IPhone"
        CaseAdapt.capitaliseFirst("'tis") shouldBe "'Tis"
    }
})
