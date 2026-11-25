package io.github.sillypuppyy.dogboard.engine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * The spell checker's suggestions for typed non-words, by source: spelling candidates (SpellCandidates), the touch
 * decoder on key centres (before 2026-11-25 the only source), and both (the default). DOGBOARD_SPELL_EVAL=<tsv> with
 * rows "sentence, index, context, typed, intended" (the predbench typo sets); prints how often the intended word is
 * first and among three. English pack (DOGBOARD_NGRAMS optional).
 */
class SpellSuggestEvalTest : FunSpec({
    test("rethorical is spelled rhetorical") {
        TypingEngine.unloadForTest()
        val dir = "src/main/assets/dogboard/"
        TypingEngine.load(openLexicon = { File("${dir}en.lexicon.tsv").bufferedReader() }, rules = LanguageRules())
        TypingEngine.spell("rethorical", 3)!!.second.first() shouldBe "rhetorical"
        TypingEngine.spell("Rethorical", 3)!!.second.first() shouldBe "Rhetorical"
    }

    test("spell checker suggestions on real typos").config(enabled = System.getenv("DOGBOARD_SPELL_EVAL") != null) {
        TypingEngine.unloadForTest()
        val dir = "src/main/assets/dogboard/"
        fun reader(name: String) = File("${dir}en.$name").takeIf { it.isFile }?.bufferedReader() ?: java.io.BufferedReader(java.io.StringReader(""))
        TypingEngine.load(openLexicon = { reader("lexicon.tsv") }, openBigrams = { reader("bigrams.tsv") },
            openMisspellings = { reader("misspellings.txt") }, rules = LanguageRules())
        System.getenv("DOGBOARD_NGRAMS")?.let { File(it) }?.takeIf { it.isFile }?.inputStream()?.use { NgramModel.readBinary(it) }
            ?.let { TypingEngine.attachNgrams(it) }
        val rows = File(System.getenv("DOGBOARD_SPELL_EVAL")!!).readLines().map { it.split('\t') }.filter { it.size >= 5 }
            .map { it[3] to it[4] }.filter { (t, want) -> !t.equals(want, true) && t.all(Char::isLetter) && want.all(Char::isLetter) }
            .filter { (t, _) -> TypingEngine.spell(t, 3)?.first == false }
            .shuffled(java.util.Random(7)).take(System.getenv("DOGBOARD_SPELL_EVAL_N")?.toIntOrNull() ?: 3000)
        for (src in TypingEngine.SpellSources.entries) {
            var first = 0; var top3 = 0
            val t0 = System.nanoTime()
            for ((t, want) in rows) {
                val got = TypingEngine.spell(t, 3, src)!!.second.map { it.lowercase() }
                if (got.firstOrNull() == want.lowercase()) first++
                if (want.lowercase() in got) top3++
            }
            val ms = (System.nanoTime() - t0) / 1e6 / rows.size
            println("SPELL-EVAL %-8s %d non-words: intended first %.1f %%, in top 3 %.1f %%, %.1f ms each".format(
                src, rows.size, 100.0 * first / rows.size, 100.0 * top3 / rows.size, ms))
        }
    }
})
