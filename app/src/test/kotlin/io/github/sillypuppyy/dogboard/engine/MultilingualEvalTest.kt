package io.github.sillypuppyy.dogboard.engine

import io.github.sillypuppyy.dogboard.touch.GaussianTouch
import io.kotest.core.spec.style.FunSpec
import java.io.File

/**
 * Multilingual typing, the baseline (notes/design/multilingual.md, step 2): two language packs typed together. Each
 * case of a mixed set (tools/i18n/sim/mixed_sets.py: column 6 the target's language, 7 words since the switch) is
 * decoded in both languages, each with its own word-sequence context, the candidates merged (ln [langWeight] added to
 * the second language's), and decided by the usual autocorrect over the union word list. Reported per language and
 * right after a switch, against each language alone on the same set.
 *
 * DOGBOARD_ML_A=<pack dir>:<lang>, DOGBOARD_ML_B=<pack dir>:<lang>, DOGBOARD_ML_SET=<dir> (mixed-typos.tsv,
 * mixed-controls.tsv, union.lexicon.tsv), DOGBOARD_ROWS (the entry's layout), DOGBOARD_ML_N (cases per set).
 */
class MultilingualEvalTest : FunSpec({
    val a = System.getenv("DOGBOARD_ML_A")?.split(':')
    val b = System.getenv("DOGBOARD_ML_B")?.split(':')
    val set = System.getenv("DOGBOARD_ML_SET")?.let(::File)?.takeIf { File(it, "mixed-typos.tsv").isFile }

    class Lang(val dir: String, val tag: String) {
        private fun f(name: String) = File(dir, "$tag.$name")
        val lexicon = f("lexicon.tsv").bufferedReader().use { Lexicon.parse(it) }
        val bigrams = f("bigrams.tsv").takeIf { it.isFile }?.bufferedReader()?.use { Bigrams.parse(it, lexicon) } ?: Bigrams.parse(java.io.BufferedReader(java.io.StringReader("")), lexicon)
        val ngrams = f("ngrams.bin").inputStream().use { NgramModel.readBinary(it) }!!
        val charModel = f("charlm.bin").takeIf { it.isFile }?.inputStream()?.use { CharModel.read(it) }
        val decoder = Decoder(lexicon, Decoder.Config(), bigrams = bigrams, context = NgramScorer(ngrams, lexicon, bigrams.sentenceStart))
            .also { it.unknownWords = charModel }
        fun ctx(w: String?) = if (w == null) bigrams.contextId(Bigrams.START) else bigrams.contextId(w.lowercase())
    }

    test("two languages typed together").config(enabled = a != null && b != null && set != null, timeout = kotlin.time.Duration.parse("2h")) {
        val la = Lang(a!![0], a[1]); val lb = Lang(b!![0], b[1])
        val union = File(set!!, "union.lexicon.tsv").bufferedReader().use { Lexicon.parse(it) }
        val rows = System.getenv("DOGBOARD_ROWS")?.split(',')?.let { rs -> LetterRows(rs, rs.mapIndexed { i, r -> if (i == 2) 1.28f + 0.2133f else (10 - r.length) / 2f }) } ?: LetterRows()
        val keys = QwertyGeometry.letterKeys(375f, 216f, rows)
        fun keyOf(c: Char) = keys[c] ?: keys[java.text.Normalizer.normalize(c.toString(), java.text.Normalizer.Form.NFD)[0]]
        val params = GaussianTouch.params(medianKeyHalfWidth = 18.75, typingInterval = 0.3)
        val n = System.getenv("DOGBOARD_ML_N")?.toInt() ?: 2000
        val weights = (System.getenv("DOGBOARD_ML_WEIGHTS") ?: "1").split(',').map { it.toDouble() }
        // DOGBOARD_ML_LANGID=<train_langid.py json>: the per-word language model's weights (listed / ln P differences for
        // the word and the two before it); its ln p(language) is added to each candidate's score
        val langid = System.getenv("DOGBOARD_ML_LANGID")?.let { File(it).readText() }?.let {
            Regex("\"weights\":\\s*\\[([^\\]]*)\\]").find(it)!!.groupValues[1].split(',').map { v -> v.trim().toDouble() }
        }
        fun unigram(l: Lang, w: String): Double? = l.lexicon.logProb(w)
        /** ln p(the candidate's language) from the word and the two before it, both languages' word lists; [withWord] false: the context only. */
        fun langLogP(x: Lang, o: Lang, word: String, before: List<String>, withWord: Boolean): Double {
            val w = langid!!
            var z = 0.0
            val words = before.takeLast(2).reversed() // w-1, w-2
            for ((j, t) in (listOf(word) + words).withIndex()) {
                if (j == 0 && !withWord) continue
                val px = unigram(x, t.lowercase()); val po = unigram(o, t.lowercase())
                val listed = (if (px != null) 1.0 else 0.0) - (if (po != null) 1.0 else 0.0)
                val lnp = ((px ?: -22.0) - (po ?: -22.0)) / 10
                z += w[2 * j] * listed + w[2 * j + 1] * lnp
            }
            return -kotlin.math.ln(1 + kotlin.math.exp(-z))
        }
        // DOGBOARD_ML_CHAR=c1,c2: the context as the last DOGBOARD_ML_WINDOW characters (default 30) scored by each language's
        // letter model (user 2026-11-23: "feed it the last 20-30 characters"), weight c per mode, with the word's own evidence
        val charWeights = System.getenv("DOGBOARD_ML_CHAR")?.split(',')?.map { it.toDouble() }.orEmpty()
        val window = System.getenv("DOGBOARD_ML_WINDOW")?.toInt() ?: 30
        /** (ln P in [x] - ln P in [o]) per letter of the last [window] characters before the word (letter models; 0 when none). */
        fun charDiff(x: Lang, o: Lang, before: List<String>): Double {
            val text = before.joinToString(" ").takeLast(window)
            var diff = 0.0; var letters = 0
            for (w in text.split(' ').filter { it.isNotEmpty() }) {
                val lx = x.charModel?.logProb(w.lowercase()) ?: continue
                val lo = o.charModel?.logProb(w.lowercase()) ?: continue
                diff += lx - lo; letters += w.length
            }
            return if (letters == 0) 0.0 else diff / letters
        }
        fun charClassified(ca: List<Candidate>, cb: List<Candidate>, before: List<String>, c: Double): List<Candidate> {
            val w = langid!!
            val best = LinkedHashMap<String, Candidate>()
            for ((cand, x, o) in ca.map { Triple(it, la, lb) } + cb.map { Triple(it, lb, la) }) {
                val px = unigram(x, cand.word.lowercase()); val po = unigram(o, cand.word.lowercase())
                val z = w[0] * ((if (px != null) 1.0 else 0.0) - (if (po != null) 1.0 else 0.0)) + w[1] * ((px ?: -22.0) - (po ?: -22.0)) / 10 +
                    c * charDiff(x, o, before)
                val sc = cand.copy(score = cand.score - kotlin.math.ln(1 + kotlin.math.exp(-z)))
                val k = sc.word.lowercase(); val old = best[k]
                if (old == null || sc.score > old.score) best[k] = sc.copy(isTypedString = sc.isTypedString || old?.isTypedString == true)
                else if (sc.isTypedString && !old.isTypedString) best[k] = old.copy(isTypedString = true)
            }
            return best.values.sortedByDescending { it.score }
        }
        /** Both languages' candidates, each shifted by ln p(its language). */
        fun classified(ca: List<Candidate>, cb: List<Candidate>, before: List<String>, withWord: Boolean): List<Candidate> {
            val best = LinkedHashMap<String, Candidate>()
            for ((c, x, o) in ca.map { Triple(it, la, lb) } + cb.map { Triple(it, lb, la) }) {
                val s = c.copy(score = c.score + langLogP(x, o, c.word, before, withWord))
                val k = s.word.lowercase(); val old = best[k]
                if (old == null || s.score > old.score) best[k] = s.copy(isTypedString = s.isTypedString || old?.isTypedString == true)
                else if (s.isTypedString && !old.isTypedString) best[k] = old.copy(isTypedString = true)
            }
            return best.values.sortedByDescending { it.score }
        }

        fun decode(l: Lang, typed: String, taps: List<Tap>, before: List<String>) =
            l.decoder.decode(taps, keys, params, previous = l.ctx(before.lastOrNull()), typed = typed, previous2 = before.getOrNull(before.size - 2)?.let { l.ctx(it) } ?: -1)

        /** Both languages' candidates as one list: the best score per word, the second language's shifted by ln [w]. */
        fun merged(ca: List<Candidate>, cb: List<Candidate>, w: Double): List<Candidate> {
            val best = LinkedHashMap<String, Candidate>()
            for ((c, shift) in ca.map { it to 0.0 } + cb.map { it to kotlin.math.ln(w) }) {
                val s = c.copy(score = c.score + shift)
                val k = s.word.lowercase()
                val old = best[k]
                if (old == null || s.score > old.score) best[k] = s.copy(isTypedString = s.isTypedString || old?.isTypedString == true)
                else if (s.isTypedString && !old.isTypedString) best[k] = old.copy(isTypedString = true)
            }
            return best.values.sortedByDescending { it.score }
        }

        data class Out(val lang: String, val since: Int, val typo: Boolean, val result: String)
        val modes = listOf("${la.tag} alone", "${lb.tag} alone") + weights.map { "merged w=$it" } +
            (if (langid != null) listOf("classified", "context only") + charWeights.map { "char window c=$it" } else emptyList())
        val results = modes.associateWith { mutableListOf<Out>() }
        for (kind in listOf("typos", "controls")) {
            val rowsIn = File(set, "mixed-$kind.tsv").readLines().map { it.split('\t') }.filter { it.size >= 7 }.take(n)
            for (r in rowsIn) {
                val typed = r[3].lowercase(); val intended = r[4]; val lang = r[5]; val since = r[6].toInt()
                val before = r[2].split(' ').filter { it.isNotBlank() }
                val taps = typed.mapNotNull { c -> keyOf(c)?.let { Tap(it.centerX, it.centerY) } }
                if (taps.size != typed.length) continue
                val ca = decode(la, typed, taps, before); val cb = decode(lb, typed, taps, before)
                fun judge(got: String): String {
                    val typo = !typed.equals(intended, ignoreCase = true)
                    return when {
                        got.equals(intended, ignoreCase = true) -> if (typo) "fixed" else "kept"
                        got.equals(typed, ignoreCase = true) -> "left"
                        else -> if (typo) "wrong" else "broken"
                    }
                }
                val typo = kind == "typos"
                results.getValue(modes[0]).add(Out(lang, since, typo, judge(Autocorrect(la.lexicon).correction(typed, ca) ?: typed)))
                results.getValue(modes[1]).add(Out(lang, since, typo, judge(Autocorrect(lb.lexicon).correction(typed, cb) ?: typed)))
                for ((i, w) in weights.withIndex())
                    results.getValue(modes[2 + i]).add(Out(lang, since, typo, judge(Autocorrect(union).correction(typed, merged(ca, cb, w)) ?: typed)))
                if (langid != null) {
                    results.getValue("classified").add(Out(lang, since, typo, judge(Autocorrect(union).correction(typed, classified(ca, cb, before, true)) ?: typed)))
                    results.getValue("context only").add(Out(lang, since, typo, judge(Autocorrect(union).correction(typed, classified(ca, cb, before, false)) ?: typed)))
                    for (c in charWeights) results.getValue("char window c=$c").add(Out(lang, since, typo, judge(Autocorrect(union).correction(typed, charClassified(ca, cb, before, c)) ?: typed)))
                }
            }
        }
        fun pct(xs: List<Out>, what: String) = if (xs.isEmpty()) Double.NaN else 100.0 * xs.count { it.result == what } / xs.size
        for ((mode, outs) in results) {
            val parts = listOf(la.tag, lb.tag).map { l ->
                val t = outs.filter { it.typo && it.lang == l }; val c = outs.filter { !it.typo && it.lang == l }
                "%s typos %d: fixed %.1f wrong %.1f, controls %d: broken %.1f".format(l, t.size, pct(t, "fixed"), pct(t, "wrong"), c.size, pct(c, "broken"))
            }
            val near = outs.filter { it.since in 0..2 }
            println("MULTILINGUAL %-14s %s | after a switch (%d): fixed %.1f wrong %.1f broken %.1f".format(mode, parts.joinToString(" | "),
                near.size, pct(near.filter { it.typo }, "fixed"), pct(near.filter { it.typo }, "wrong"), pct(near.filter { !it.typo }, "broken")))
        }
    }
})
