package io.github.sillypuppyy.dogboard.engine

import io.github.sillypuppyy.dogboard.engine.AutoShift.Type
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** The iOS auto-shift rules (notes/design/settings-model.md §3), case by case. */
class AutoShiftTest : FunSpec({
    // a tiny dictionary: plain words plus abbreviations that keep their period
    val stored = listOf("Mr", "etc", "approx", "hello", "world", "us", "US", "Smith", "e.g.", "i.e.", "U.S.", "a.m.", "Ph.D.")
    val words = AutoShift.WordForms { w -> stored.filter { it.removeSuffix(".").equals(w, ignoreCase = true) } }
    fun shift(before: String, type: Type = Type.SENTENCES) = AutoShift.shouldShift(before, type, words)

    test("start of text and new lines") {
        shift("") shouldBe true
        shift("Hello\n") shouldBe true
        shift("Hello") shouldBe false
        shift("Hello ") shouldBe false
    }
    test("after . ? ! and a space") {
        shift("Hello. ") shouldBe true
        shift("Really? ") shouldBe true
        shift("Wow! ") shouldBe true
        shift("Hello.") shouldBe false // no space yet
        shift("Hello.  ") shouldBe true // more spaces are fine
        shift("Hello. ") shouldBe true // no-break space counts
    }
    test("closing quotes after the delimiter, opening ones after the space") {
        shift("He said “hi.” ") shouldBe true
        shift("Done. (") shouldBe true
        shift("Done. “") shouldBe true
        shift("Done. ¿") shouldBe true
    }
    test("three spaces start a sentence") {
        shift("one   ") shouldBe true
        shift("one  ") shouldBe false
    }
    test("abbreviations that keep their period do not end a sentence") {
        shift("Use a tool, e.g. ") shouldBe false
        shift("That is, i.e. ") shouldBe false
        shift("Made in the U.S. ") shouldBe false
        shift("At 9 a.m. ") shouldBe false
        shift("She has a Ph.D. ") shouldBe false
    }
    test("like iOS, single-period abbreviations do capitalise the next word") {
        shift("Ask Mr. ") shouldBe true
        shift("apples, pears, etc. ") shouldBe true
    }
    test("two periods never start a sentence") {
        shift("Wait.. ") shouldBe false
        shift("Hmm... ") shouldBe false
    }
    test("a capitalised word that starts a sentence is looked up ignoring case") {
        // "E.g." at a sentence start still keeps its period
        shift("Hello. E.g. ") shouldBe false
        // "US" mid-sentence is matched by case: US (no period) -> the period ends the sentence
        shift("I live in the US. ") shouldBe true
    }
    test("unknown words end sentences") {
        shift("Zzyzx. ") shouldBe true
        AutoShift.shouldShift("Hello e.g. ", Type.SENTENCES, null) shouldBe true // no dictionary loaded
    }
    test("letters outside ASCII are word letters") {
        shift("Café. ") shouldBe true
        shift("Ça va! ") shouldBe true
        shift("naïve") shouldBe false
    }
    test("the pattern uses only flags Android's regex engine supports") {
        // Android (ICU) throws on UNICODE_CHARACTER_CLASS and CANON_EQ; this crashed the keyboard once
        val unsupported = java.util.regex.Pattern.UNICODE_CHARACTER_CLASS or java.util.regex.Pattern.CANON_EQ
        (AutoShift.BOUNDARY.flags() and unsupported) shouldBe 0
    }
    test("word and character fields") {
        shift("john ", Type.WORDS) shouldBe true
        shift("john", Type.WORDS) shouldBe false
        shift("", Type.WORDS) shouldBe true
        shift("abc", Type.ALL) shouldBe true
        shift("", Type.NONE) shouldBe false
        shift("Hello. ", Type.NONE) shouldBe false
    }
})
