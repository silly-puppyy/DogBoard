package io.github.sillypuppyy.dogboard.engine

import io.github.sillypuppyy.dogboard.touch.GaussianTouch
import io.kotest.core.spec.style.FunSpec
import java.io.File

/**
 * The look back (iOS retrocorrection) on a pack: each case types a word (a simulated typo, or as meant), commits what
 * autocorrect makes of it, types the next word as meant, and looks back. Reports how often the look back fixes a word
 * autocorrect got wrong or left, breaks one it got right, and breaks a correct word (controls).
 *
 * DOGBOARD_RETRO_SET (tools/i18n/sim/retro_sets.py), DOGBOARD_PACK_DIR + DOGBOARD_PACK_LANG (lexicon, bigrams, ngrams,
 * charlm, rules.json), DOGBOARD_ROWS; DOGBOARD_RETRO_SECOND=<dir>:<lang> loads another language typed together.
 */
class RetroEvalTest : FunSpec({
    val setFile = System.getenv("DOGBOARD_RETRO_SET")?.let(::File)

    test("the look back on a pack").config(enabled = setFile?.isFile == true, timeout = kotlin.time.Duration.parse("2h")) {
        val dir = System.getenv("DOGBOARD_PACK_DIR")!!.trimEnd('/') + "/"
        val lang = System.getenv("DOGBOARD_PACK_LANG") ?: "en"
        fun f(name: String) = File("$dir$lang.$name")
        val rules = File("${dir}rules.json").takeIf { it.isFile }?.let { LanguageRules.parse(it.readText()) } ?: LanguageRules.ENGLISH
        Lexicon.distinctLetters = rules.distinctLetters.toSet()
        Lexicon.keyOf = rules.keyOfLetters
        TypingEngine.unloadForTest()
        TypingEngine.load(
            openLexicon = { f("lexicon.tsv").bufferedReader() },
            openBigrams = { f("bigrams.tsv").bufferedReader() },
            rules = rules,
        )
        TypingEngine.attachNgrams(f("ngrams.bin").inputStream().use { NgramModel.readBinary(it) })
        f("charlm.bin").takeIf { it.isFile }?.inputStream()?.use { CharModel.read(it) }?.let { TypingEngine.attachCharModel(it) }
        System.getenv("DOGBOARD_RETRO_SECOND")?.split(':')?.let { (d, l) ->
            val sd = d.trimEnd('/') + "/"
            val sr = File("${sd}rules.json").takeIf { it.isFile }?.let { LanguageRules.parse(it.readText()) } ?: LanguageRules()
            // its words, pairs, trigram and letter model, as the phone loads them
            val lex = Lexicon.parse(File("$sd$l.lexicon.tsv").bufferedReader(), TypingEngine.pathOf(sr))
            val bi = File("$sd$l.bigrams.tsv").takeIf { it.isFile }?.bufferedReader()?.use { Bigrams.parse(it, lex) }
            val ng = File("$sd$l.ngrams.bin").takeIf { it.isFile }?.inputStream()?.use { NgramModel.readBinary(it) }
            val cm = File("$sd$l.charlm.bin").takeIf { it.isFile }?.inputStream()?.use { CharModel.read(it) }
            TypingEngine.loadSecondary(lex, bi, sr, ng, cm)
        }
        val rows = System.getenv("DOGBOARD_ROWS")?.split(',')?.let { LetterRows.ofLayout(it) } ?: LetterRows()
        val keys = QwertyGeometry.letterKeys(375f, 216f, rows)
        val params = GaussianTouch.params(medianKeyHalfWidth = 18.75, typingInterval = 0.3)
        fun typed(text: String): CurrentWord? = CurrentWord().apply {
            for (c in text) addLetter(c, (keys[c.lowercaseChar()] ?: return null).let { Tap(it.centerX, it.centerY) }, keys, params)
        }

        var typos = 0; var acRight = 0; var finalRight = 0; var gained = 0; var lost = 0; var retroWrong = 0
        var controls = 0; var acBroken = 0; var retroBroken = 0
        for (line in setFile!!.readLines()) {
            val r = line.split('\t')
            if (r.size < 5) continue
            val (kind, ctx, typedText, intended, next) = r
            val before = ctx.split(' ').filter { it.isNotBlank() }
            val w1 = typed(typedText) ?: continue
            val w2 = typed(next) ?: continue
            val prev = before.lastOrNull() ?: Bigrams.START
            val prev2 = before.getOrNull(before.size - 2)
            val c1 = TypingEngine.candidates(w1, prev, previous2 = prev2, textBefore = ctx)
            val committed = TypingEngine.correction(w1, c1) ?: typedText
            val c2 = TypingEngine.candidates(w2, committed, previous2 = prev, textBefore = "$ctx $committed")
            val looked = TypingEngine.retrocorrection(w1, committed, prev, w2, previousCandidates = c1, currentCandidates = c2)
            val final = looked ?: committed
            val okAc = committed.equals(intended, ignoreCase = true)
            val okFinal = final.equals(intended, ignoreCase = true)
            if (kind == "typo") {
                typos++
                if (okAc) acRight++
                if (okFinal) finalRight++
                if (!okAc && okFinal) gained++
                if (okAc && !okFinal) lost++
                if (looked != null && !okFinal) retroWrong++
            } else {
                controls++
                if (!okAc) acBroken++
                if (okAc && !okFinal) retroBroken++
            }
        }
        fun pct(a: Int, b: Int) = if (b == 0) Double.NaN else 100.0 * a / b
        println("RETRO %s%s: %d typos: autocorrect right %.1f %%, after the look back %.1f %% (fixed %.1f %%, unfixed %.1f %%, a wrong change %.1f %%) | %d controls: broken by autocorrect %.1f %%, by the look back %.2f %%".format(
            lang, System.getenv("DOGBOARD_RETRO_SECOND")?.let { " + ${it.substringAfterLast(':')}" } ?: "",
            typos, pct(acRight, typos), pct(finalRight, typos), pct(gained, typos), pct(lost, typos), pct(retroWrong, typos),
            controls, pct(acBroken, controls), pct(retroBroken, controls)))
        TypingEngine.unloadSecondary()
        TypingEngine.unloadForTest()
    }
})
