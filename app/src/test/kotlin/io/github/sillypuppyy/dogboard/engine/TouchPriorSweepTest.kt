package io.github.sillypuppyy.dogboard.engine

import io.github.sillypuppyy.dogboard.touch.GaussianTouch
import io.kotest.core.spec.style.FunSpec
import java.io.File
import java.util.Random

/**
 * Sweep for the language-model prior on key choice (run with DOGBOARD_SWEEP=1, prints a table).
 * Each tap picks a key with GaussianTouch.pick plus weight * max(floor, ln P(letter | letters so
 * far)). Measured: raw letters right for known words, raw letters right for held-out (unknown)
 * words, and words right after autocorrect.
 */
class TouchPriorSweepTest : FunSpec({
    test("sweep").config(enabled = System.getenv("DOGBOARD_SWEEP") == "1") {
        val rows = File("src/main/assets/dogboard/en.lexicon.tsv").readLines()
            .filter { !it.startsWith("#") }
            .map { it.substringBefore('\t') to it.substringAfter('\t').toLong() }
        val heldOut = rows.withIndex()
            .filter { (i, row) -> i >= 5000 && i % 25 == 0 && row.first.all { it in 'a'..'z' } && row.first.length >= 3 }
            .map { it.value.first }.toSet()
        val lexicon = Lexicon.of(rows.filter { it.first !in heldOut }.toMap())
        val known = rows.filter { it.first !in heldOut && it.first.all { c -> c in 'a'..'z' } }
        val cumulative = known.runningFold(0.0) { acc, (_, c) -> acc + c }.drop(1)
        val keys = QwertyGeometry.letterKeys(375f, 216f)
        val letters = keys.keys.toList()
        val targets = letters.map { keys.getValue(it) }
        val params = GaussianTouch.params(medianKeyHalfWidth = 18.75, typingInterval = 0.3)
        val decoder = Decoder(lexicon)
        val autocorrect = Autocorrect(lexicon)

        val rng = Random(9)
        fun taps(word: String, sx: Double, sy: Double) = word.map { c ->
            val k = keys.getValue(c)
            Tap(
                (k.centerX + rng.nextGaussian() * sx * GaussianTouch.POINTS_PER_MM).toFloat(),
                (k.centerY + rng.nextGaussian() * sy * GaussianTouch.POINTS_PER_MM).toFloat(),
            )
        }
        val knownSamples = (0 until 1500).map {
            val r = rng.nextDouble() * cumulative.last()
            val w = known[cumulative.binarySearch(r).let { i -> if (i < 0) -i - 1 else i }].first
            w to taps(w, 1.5, 2.0)
        }
        val unknownSamples = heldOut.take(1200).map { w -> w to taps(w, 1.5, 2.0) }

        fun literal(t: List<Tap>, weight: Double, floor: Double): String {
            val sb = StringBuilder()
            for (tap in t) {
                val prior = if (weight == 0.0) null else lexicon.nextLetterLogProbs(sb)
                val i = GaussianTouch.pick(targets, tap.x, tap.y, params) { idx ->
                    if (prior == null) 0.0 else weight * maxOf(floor, prior[letters[idx]] ?: floor)
                } ?: 0
                sb.append(letters[i])
            }
            return sb.toString()
        }

        println("PRIOR known=${knownSamples.size} unknown=${unknownSamples.size} at 1.5x2.0 mm")
        for ((weight, floor) in listOf(0.0 to 0.0, 0.05 to -6.0, 0.08 to -6.0, 0.1 to -6.0, 0.12 to -6.0, 0.15 to -6.0, 0.1 to -4.0, 0.1 to -9.0)) {
            var rawKnown = 0
            var finalKnown = 0
            var rawUnknown = 0
            var finalUnknown = 0
            for ((w, t) in knownSamples) {
                val lit = literal(t, weight, floor)
                if (lit == w) rawKnown++
                val fin = autocorrect.correction(lit, decoder.decode(t, keys, params), decoder.literalCost(t, lit, keys, params)) ?: lit
                if (fin == w) finalKnown++
            }
            for ((w, t) in unknownSamples) {
                val lit = literal(t, weight, floor)
                if (lit == w) rawUnknown++
                val fin = autocorrect.correction(lit, decoder.decode(t, keys, params), decoder.literalCost(t, lit, keys, params)) ?: lit
                if (fin == w) finalUnknown++
            }
            println(
                "PRIOR weight %.2f floor %.0f: known raw %.1f%% final %.1f%% | unknown raw %.1f%% final %.1f%%".format(
                    weight, floor,
                    rawKnown * 100.0 / knownSamples.size, finalKnown * 100.0 / knownSamples.size,
                    rawUnknown * 100.0 / unknownSamples.size, finalUnknown * 100.0 / unknownSamples.size,
                )
            )
        }
    }
})
