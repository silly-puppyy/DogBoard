package io.github.sillypuppyy.dogboard.engine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

class TelexComposerTest : FunSpec({
    test("shapes, tones on the right vowel, ươ") {
        mapOf(
            "vieetj" to "việt", "dduowcj" to "được", "nguowif" to "người", "truowngf" to "trường", "tooi" to "tôi",
            "ddi" to "đi", "hocj" to "học", "ngayf" to "ngày", "ngay" to "ngay", "cuar" to "của", "khoong" to "không",
            "quas" to "quá", "gias" to "giá", "muaf" to "mùa", "awn" to "ăn", "huw" to "hư",
        ).forEach { (keys, word) -> TelexComposer.compose(keys) shouldBe word }
    }
    test("as typed so far, mid-word") {
        TelexComposer.compose("vie") shouldBe "vie"
        TelexComposer.compose("viee") shouldBe "viê"
        TelexComposer.compose("vieet") shouldBe "viêt"
        TelexComposer.compose("dd") shouldBe "đ" // before the vowel too
        TelexComposer.compose("ng") shouldBe "ng"
    }
    test("undo: a doubled shape key or tone key types the letter") {
        TelexComposer.compose("aaa") shouldBe "aa"
        TelexComposer.compose("ass") shouldBe "as"
    }
    test("old and new tone style for oa, oe, uy") {
        TelexComposer.compose("hoaf") shouldBe "hòa"
        TelexComposer.compose("hoaf", newStyle = true) shouldBe "hoà"
    }
    test("keys that can't be a syllable stay as typed") {
        TelexComposer.compose("website") shouldBe "website"
        TelexComposer.compose("internet") shouldBe "internet"
    }
    test("the current word shows the drawn word, cased as typed, and the decoder keeps the keys") {
        val sp = LanguageRules(spellings = listOf(LanguageRules.Spelling(letter = "ê", typed = "ee"),
            LanguageRules.Spelling(mark = "̣", typed = "j", at = LanguageRules.Spelling.AT_END))).typedSpellings
        val w = CurrentWord().apply { composer = { TelexComposer.compose(it) }; decomposer = sp::spelled }
        for (c in "Vieetj") w.addLetter(c, null, emptyMap(), null)
        w.text shouldBe "Việt"
        w.decoderText shouldBe "Vieetj"
        // back into a drawn word: tracked as its keys
        w.seedFrom("ở việ", emptyMap(), null)
        w.decoderText shouldBe "vieej"
        w.text shouldBe "việ"
    }

    // DOGBOARD_VI_LEXICON=<vi.lexicon.tsv> + DOGBOARD_VI_RULES=<rules.json>: the word list composed back (by uses)
    val lex = System.getenv("DOGBOARD_VI_LEXICON")?.let(::File)
    val rules = System.getenv("DOGBOARD_VI_RULES")?.let(::File)
    test("the word list composes back").config(enabled = lex?.isFile == true && rules?.isFile == true) {
        val sp = LanguageRules.parse(rules!!.readText()).typedSpellings
        var total = 0.0; var ok = 0.0
        lex!!.forEachLine { line ->
            if (line.startsWith("#")) return@forEachLine
            val (w, c) = line.split('\t').let { it[0].lowercase() to it[1].toDouble() }
            if (!w.all { it.isLetter() }) return@forEachLine
            val keys = sp.spelled(w)
            if (keys.any { it.code > 127 }) return@forEachLine
            total += c
            if (TelexComposer.compose(keys) == w || TelexComposer.compose(keys, newStyle = true) == w) ok += c
        }
        println("TELEX composed back: %.2f %% of word uses".format(100 * ok / total))
        (ok / total > 0.99) shouldBe true
    }
})
