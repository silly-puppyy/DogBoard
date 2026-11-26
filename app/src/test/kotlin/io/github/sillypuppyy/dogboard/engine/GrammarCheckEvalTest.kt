package io.github.sillypuppyy.dogboard.engine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * GrammarCheck with the pack's own (pruned) trigram. DOGBOARD_GRAMMAR_DIR=<packs-work/fr> DOGBOARD_GRAMMAR_LANG=fr
 * [DOGBOARD_GRAMMAR_CHAT=<wildchat-fr.tsv>]: false alarms per sentence on correct held-out text (news, and people's
 * chat turns when given), and on the agreement set the errors caught with the right word suggested (one wrong form put
 * in place of the written word).
 */
class GrammarCheckEvalTest : FunSpec({
    test("family: determiners and endings") {
        val known = setOf("petit", "petite", "petits", "petites", "maison").let { s -> { w: String -> w in s } }
        GrammarCheck.family("fr", "le", known) shouldBe (listOf("la", "les") to true)
        GrammarCheck.family("fr", "petite", known).first.toSet() shouldBe setOf("petit", "petits", "petites")
        GrammarCheck.family("fr", "maison", known).first shouldBe emptyList()
    }

    test("languageOf: the keyboard language that knows the most words, the first on a tie") {
        val en = setOf("i", "am", "looking", "for", "the", "red", "house", "a", "la", "carte").let { s -> { w: String -> w in s } }
        val fr = setOf("je", "cherche", "le", "la", "maison", "rouge", "a", "carte").let { s -> { w: String -> w in s } }
        GrammarCheck.languageOf("Je cherche le maison rouge", listOf(en, fr)) shouldBe 1
        GrammarCheck.languageOf("I am looking for the red house", listOf(en, fr)) shouldBe 0
        GrammarCheck.languageOf("a la carte", listOf(en, fr)) shouldBe 0
        GrammarCheck.languageOf("Je cherche", listOf(fr)) shouldBe 0
    }

    // DOGBOARD_GRAMMAR_PROBE="Je cherche le maison rouge|...": each sentence's flags, and per word how much better its
    // family's best form fits (log10; flagged at DET_MARGIN / ENDING_MARGIN)
    test("probe sentences").config(enabled = System.getenv("DOGBOARD_GRAMMAR_PROBE") != null) {
        val dir = File(System.getenv("DOGBOARD_GRAMMAR_DIR")!!)
        val lang = System.getenv("DOGBOARD_GRAMMAR_LANG")!!
        val model = File(dir, "$lang.ngrams.bin").inputStream().use { NgramModel.readBinary(it) }!!
        val lex = File(dir, "$lang.lexicon.tsv").useLines { ls -> ls.filter { !it.startsWith("#") }.map { it.substringBefore('\t').lowercase() }.toHashSet() }
        val known = { w: String -> w in lex }
        for (s in System.getenv("DOGBOARD_GRAMMAR_PROBE")!!.split('|')) {
            val words = s.lowercase().split(' ').filter { it.isNotEmpty() }
            val gaps = words.indices.mapNotNull { i ->
                val (forms, _) = GrammarCheck.family(lang, words[i], known)
                if (forms.isEmpty()) return@mapNotNull null
                fun fit(w: String): Double {
                    val a = if (i >= 2) model.idOf(words[i - 2]) else -1; val b = if (i >= 1) model.idOf(words[i - 1]) else -1
                    val id = model.idOf(w); val next = if (i + 1 < words.size) model.idOf(words[i + 1]) else -1
                    return model.logProbOf(a, b, id) + (if (next >= 0) model.logProbOf(b, id, next) else 0.0)
                }
                val own = fit(words[i]); val best = forms.filter { model.idOf(it) >= 0 }.maxByOrNull { fit(it) } ?: return@mapNotNull null
                "${words[i]}->$best %.2f".format((fit(best) - own) / kotlin.math.ln(10.0))
            }
            println("PROBE $s: flags ${GrammarCheck.check(s, lang, model, known).map { s.substring(it.start, it.start + it.length) + "->" + it.suggestion }}; gaps $gaps")
        }
    }

    test("grammar check on real text").config(enabled = System.getenv("DOGBOARD_GRAMMAR_DIR") != null) {
        val dir = File(System.getenv("DOGBOARD_GRAMMAR_DIR")!!)
        val lang = System.getenv("DOGBOARD_GRAMMAR_LANG")!!
        val model = File(dir, "$lang.ngrams.bin").inputStream().use { NgramModel.readBinary(it) }!!
        val lex = File(dir, "$lang.lexicon.tsv").useLines { ls -> ls.filter { !it.startsWith("#") }.map { it.substringBefore('\t').lowercase() }.toHashSet() }
        val known = { w: String -> w in lex }
        System.getenv("DOGBOARD_GRAMMAR_MARGINS")?.split(',')?.let { (d, e) -> GrammarCheck.DET_MARGIN = d.toDouble(); GrammarCheck.ENDING_MARGIN = e.toDouble() }
        println("GRAMMAR margins ${GrammarCheck.DET_MARGIN} / ${GrammarCheck.ENDING_MARGIN}")
        val skip = System.getenv("DOGBOARD_GRAMMAR_SKIP")?.toIntOrNull() ?: 0 // 3000: the sentences the margins were not chosen on
        fun falseAlarms(name: String, lines: List<String>) {
            var flagged = 0; var words = 0; val ex = ArrayList<String>()
            for (s in lines) {
                val f = GrammarCheck.check(s, lang, model, known)
                words += s.split(' ').size
                if (f.isNotEmpty()) { flagged++; if (ex.size < 8) ex.add(f.joinToString { "${s.substring(it.start, it.start + it.length)} -> ${it.suggestion}" }) }
            }
            println("GRAMMAR $lang $name: ${lines.size} sentences, $words words, flagged ${"%.2f".format(100.0 * flagged / lines.size)} % of sentences; e.g. $ex")
        }
        falseAlarms("news", File(dir, "$lang.heldout.txt").readLines().filter { it.split(' ').size in 4..40 }.drop(skip).take(3000))
        System.getenv("DOGBOARD_GRAMMAR_CHAT")?.let { c ->
            falseAlarms("chat", File(c).readLines().map { it.substringAfter('\t') }.filter { it.split(' ').size in 4..40 }.drop(skip).take(3000))
        }
        val rows = File(dir, "$lang-agreement.tsv").readLines().map { it.split('\t') }.filter { it.size >= 5 && it[4] in setOf("DET", "ADJ") }
        var tried = 0; var caught = 0
        for (r in rows) {
            val word = r[1]; val alts = r[2].split('|').filter { it.isNotEmpty() }
            val wrong = alts.firstOrNull { it in GrammarCheck.family(lang, word.lowercase(), known).first } ?: continue
            val text = "${r[0]} $wrong ${r[3]}"
            val start = r[0].length + 1
            tried++
            if (GrammarCheck.check(text, lang, model, known).any { it.start == start && it.suggestion.equals(word, true) }) caught++
        }
        println("GRAMMAR $lang agreement: $tried errors, caught with the right word ${"%.1f".format(100.0 * caught / tried)} %")
    }
})
