package io.github.sillypuppyy.dogboard.engine

import io.github.sillypuppyy.dogboard.touch.GaussianTouch
import io.kotest.core.spec.style.FunSpec
import java.io.File
import java.util.Random

/**
 * Autocorrect on real typos: words people typed on phones (their first attempt, before any fix),
 * with the word they meant and the true words before it (DOGBOARD_REAL_TYPOS: a private TSV of
 * session, index, context, typed, intended; not in the repo). The study logged letters, not touch
 * points, so each typed letter is a tap at its key's centre. Typo pairs: fixed / left as typed /
 * changed to another wrong word; correctly typed words: kept / broken. Trigram decoder as shipped
 * (DOGBOARD_NGRAMS). Runs only when DOGBOARD_REAL_TYPOS is set (the whole suite runs: Kotest ignores
 * --tests; DOGBOARD_EVAL stays off so the 2 h Tatoeba eval does not); DOGBOARD_EVAL_ERRORS=file dumps the misses.
 */
class RealTypoEvalTest : FunSpec({
    val pairs = System.getenv("DOGBOARD_REAL_TYPOS")?.let { File(it) }?.takeIf { it.isFile }
    val ngramFile = System.getenv("DOGBOARD_NGRAMS")?.let { File(it) }?.takeIf { it.isFile }

    test("real phone typos: fixed, left, broken").config(enabled = pairs != null && ngramFile != null, timeout = kotlin.time.Duration.parse("2h")) {
        val lexicon = File("src/main/assets/dogboard/en.lexicon.tsv").bufferedReader().use { Lexicon.parse(it) }
        val bigrams = File("src/main/assets/dogboard/en.bigrams.tsv").bufferedReader().use { Bigrams.parse(it, lexicon) }
        val ngrams = ngramFile!!.inputStream().use { NgramModel.readBinary(it) }!!
        val keys = QwertyGeometry.letterKeys(375f, 216f)
        val params = GaussianTouch.params(medianKeyHalfWidth = 18.75, typingInterval = 0.3)
        val autocorrect = Autocorrect(lexicon)
        val decoders = ThreadLocal.withInitial { Decoder(lexicon, bigrams = bigrams, context = NgramScorer(ngrams, lexicon, bigrams.sentenceStart)) }

        // a fixed sample of each kind (DOGBOARD_REAL_TYPOS_N per kind, default 3000)
        val n = System.getenv("DOGBOARD_REAL_TYPOS_N")?.toIntOrNull() ?: 3000
        // streamed with reservoir sampling: the file has over a million rows, too many to hold split
        val rng = Random(7)
        val typos = ArrayList<List<String>>(n); val correct = ArrayList<List<String>>(n)
        var seenTypos = 0L; var seenCorrect = 0L
        pairs!!.bufferedReader().useLines { lines ->
            for (line in lines) {
                val row = line.split('\t')
                if (row.size != 5 || row[3].isEmpty() || !row[3].all { c -> c.lowercaseChar() in keys }) continue
                val typo = !row[3].equals(row[4], ignoreCase = true)
                val (bucket, seen) = if (typo) typos to ++seenTypos else correct to ++seenCorrect
                if (bucket.size < n) bucket.add(row)
                else (rng.nextDouble() * seen).toLong().let { if (it < n) bucket[it.toInt()] = row }
            }
        }
        val sample = typos + correct

        data class Outcome(val typo: Boolean, val result: String, val row: List<String>)
        val pool = java.util.concurrent.ForkJoinPool(System.getenv("DOGBOARD_EVAL_THREADS")?.toIntOrNull() ?: 3)
        val outcomes = pool.submit(java.util.concurrent.Callable {
            sample.parallelStream().map { row ->
                val typed = row[3].lowercase(); val intended = row[4]
                // the true words before it; a sentence end restarts the context, as on the keyboard
                val raw = row[2].split(' ').filter { it.isNotEmpty() }
                val ends = { w: String -> w.last() in ".!?" }
                val clean = { w: String -> w.trim('.', ',', '!', '?', ';', ':', '"', '(', ')').lowercase() }
                val prev = raw.lastOrNull()?.takeUnless(ends)?.let(clean) ?: Bigrams.START
                val prev2 = raw.getOrNull(raw.size - 2)?.takeIf { prev != Bigrams.START && !ends(it) }?.let(clean)
                val taps = typed.map { c -> keys.getValue(c).let { Tap(it.centerX, it.centerY) } }
                val candidates = decoders.get().decode(taps, keys, params, previous = bigrams.contextId(prev), typed = typed, previous2 = prev2?.let { bigrams.contextId(it) } ?: -1)
                val got = autocorrect.correction(typed, candidates) ?: typed
                val typo = !typed.equals(intended, ignoreCase = true)
                val result = when {
                    got.equals(intended, ignoreCase = true) -> if (typo) "fixed" else "kept"
                    got.equals(typed, ignoreCase = true) -> "left"
                    else -> if (typo) "wrong" else "broken"
                }
                Outcome(typo, result, row)
            }.toList()
        }).get()
        pool.shutdown()

        fun pct(kind: Boolean, r: String) = outcomes.count { it.typo == kind && it.result == r } * 100.0 / outcomes.count { it.typo == kind }
        println("REALTYPO %d typos: fixed %.1f%%, left %.1f%%, wrong word %.1f%% | %d correct words: kept %.1f%%, broken %.1f%%".format(
            outcomes.count { it.typo }, pct(true, "fixed"), pct(true, "left"), pct(true, "wrong"),
            outcomes.count { !it.typo }, pct(false, "kept"), pct(false, "broken")))
        System.getenv("DOGBOARD_EVAL_ERRORS")?.let { path ->
            File(path).printWriter().use { w ->
                for (o in outcomes) if (o.result != "fixed" && o.result != "kept") w.println("${o.result}\t${o.row[3]}\t${o.row[4]}\t${o.row[2].takeLast(40)}")
            }
        }
    }
})
