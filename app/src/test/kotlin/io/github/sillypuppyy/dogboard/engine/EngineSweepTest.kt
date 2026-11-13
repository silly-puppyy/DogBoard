package io.github.sillypuppyy.dogboard.engine

import io.github.sillypuppyy.dogboard.touch.GaussianTouch
import io.kotest.core.spec.style.FunSpec
import java.io.File
import java.util.Random

/**
 * Unknown words (run with DOGBOARD_SWEEP=1, prints a line): every 25th word ranked below the top
 * 5000 is held out of the lexicon to stand in for words the keyboard does not know (names, slang).
 *   fixed: in-lexicon words typed with tap error that end up right (1.5x2.0 and 2.2x3.2 mm)
 *   kept:  held-out words typed carefully (1.0x1.5 mm, raw letters right) that are left alone
 */
class EngineSweepTest : FunSpec({
    test("unknown words kept vs typos fixed").config(enabled = System.getenv("DOGBOARD_SWEEP") == "1") {
        val rows = File("src/main/assets/dogboard/en.lexicon.tsv").readLines()
            .filter { !it.startsWith("#") }
            .map { it.substringBefore('\t') to it.substringAfter('\t').toLong() }
        val heldOut = rows.withIndex()
            .filter { (i, row) -> i >= 5000 && i % 25 == 0 && row.first.all { it in 'a'..'z' } && row.first.length >= 3 }
            .map { it.value.first }
        val heldOutSet = heldOut.toSet()
        val known = rows.filter { it.first !in heldOutSet && it.first.all { c -> c in 'a'..'z' } }
        val lexicon = Lexicon.of(rows.filter { it.first !in heldOutSet }.toMap())
        val cumulative = known.runningFold(0.0) { acc, (_, c) -> acc + c }.drop(1)
        val keys = QwertyGeometry.letterKeys(375f, 216f)
        val keyList = keys.entries.toList()
        val params = GaussianTouch.params(medianKeyHalfWidth = 18.75, typingInterval = 0.3)
        val decoder = Decoder(lexicon, Decoder.Config(languagePower = Decoder.LANGUAGE_POWER_UNIGRAM))
        val autocorrect = Autocorrect(lexicon)
        val rng = Random(5)

        fun final(word: String, sx: Double, sy: Double): Pair<String, String> {
            val taps = word.map { c ->
                val k = keys.getValue(c)
                Tap(
                    (k.centerX + rng.nextGaussian() * sx * GaussianTouch.POINTS_PER_MM).toFloat(),
                    (k.centerY + rng.nextGaussian() * sy * GaussianTouch.POINTS_PER_MM).toFloat(),
                )
            }
            val literal = String(taps.map { t -> keyList.maxBy { GaussianTouch.logLikelihood(it.value, t.x, t.y, params) }.key }.toCharArray())
            return literal to (autocorrect.correction(literal, decoder.decode(taps, keys, params, typed = literal)) ?: literal)
        }
        fun sample(): String {
            val r = rng.nextDouble() * cumulative.last()
            return known[cumulative.binarySearch(r).let { if (it < 0) -it - 1 else it }].first
        }
        val fixedNormal = (0 until 1500).count { val w = sample(); final(w, 1.5, 2.0).second == w } / 15.0
        val fixedSloppy = (0 until 1500).count { val w = sample(); final(w, 2.2, 3.2).second == w } / 15.0
        var careful = 0
        var kept = 0
        for (w in heldOut.take(1500)) {
            val (literal, fin) = final(w, 1.0, 1.5)
            if (literal != w) continue
            careful++
            if (fin == w) kept++
        }
        println("UNKNOWN right at 1.5x2.0 %.1f%%, at 2.2x3.2 %.1f%%, kept unknown %.1f%% of %d".format(fixedNormal, fixedSloppy, kept * 100.0 / careful, careful))
    }
})
