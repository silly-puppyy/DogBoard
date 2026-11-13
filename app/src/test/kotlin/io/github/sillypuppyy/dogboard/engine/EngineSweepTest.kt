package io.github.sillypuppyy.dogboard.engine

import io.github.sillypuppyy.dogboard.touch.GaussianTouch
import io.kotest.core.spec.style.FunSpec
import java.io.File
import java.util.Random

/**
 * Parameter sweep for the autocorrect thresholds (run with DOGBOARD_SWEEP=1, prints a table).
 *
 * Every 25th word ranked below the top 5000 is held out of the lexicon to stand in for words the
 * keyboard does not know (names, slang). Measured per setting:
 *   fixed: in-lexicon words typed sloppily (2.2 x 3.2 mm) that end up right
 *   kept:  held-out words typed carefully (1.0 x 1.5 mm, raw letters right) that are left alone
 */
class EngineSweepTest : FunSpec({
    test("sweep").config(enabled = System.getenv("DOGBOARD_SWEEP") == "1") {

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
    val decoder = Decoder(lexicon)

    fun typeWord(word: String, sx: Double, sy: Double, rng: Random): Pair<List<Tap>, String> {
        val taps = word.map { c ->
            val k = keys.getValue(c)
            Tap(
                (k.centerX + rng.nextGaussian() * sx * GaussianTouch.POINTS_PER_MM).toFloat(),
                (k.centerY + rng.nextGaussian() * sy * GaussianTouch.POINTS_PER_MM).toFloat(),
            )
        }
        val literal = String(taps.map { t -> keyList.maxBy { GaussianTouch.logLikelihood(it.value, t.x, t.y, params) }.key }.toCharArray())
        return taps to literal
    }

    data class Sample(val word: String, val literal: String, val candidates: List<Candidate>, val literalCost: Double?)

    // Decode once, then score every threshold setting on the same candidates.
    val rng = Random(5)
    val sloppy = (0 until 1500).map {
        val r = rng.nextDouble() * cumulative.last()
        val word = known[cumulative.binarySearch(r).let { if (it < 0) -it - 1 else it }].first
        val (taps, literal) = typeWord(word, 2.2, 3.2, rng)
        Sample(word, literal, decoder.decode(taps, keys, params), decoder.literalCost(taps, literal, keys, params))
    }
    val normal = (0 until 1500).map {
        val r = rng.nextDouble() * cumulative.last()
        val word = known[cumulative.binarySearch(r).let { if (it < 0) -it - 1 else it }].first
        val (taps, literal) = typeWord(word, 1.5, 2.0, rng)
        Sample(word, literal, decoder.decode(taps, keys, params), decoder.literalCost(taps, literal, keys, params))
    }
    val careful = heldOut.take(1500).mapNotNull { word ->
        val (taps, literal) = typeWord(word, 1.0, 1.5, rng)
        if (literal != word) null else Sample(word, literal, decoder.decode(taps, keys, params), decoder.literalCost(taps, literal, keys, params))
    }

    run {
        println("SWEEP held-out words: ${careful.size}, sloppy samples: ${sloppy.size}")
        for (edits in listOf(2)) for (cost in listOf(10.0, 11.0, 12.0, 13.0, 14.0, 15.0, 16.0)) {
            val ac = Autocorrect(lexicon, Autocorrect.Config(unknownWordCost = cost, maxUnknownEdits = edits))
            fun final(s: Sample) = ac.correction(s.literal, s.candidates, s.literalCost) ?: s.literal
            val fixed = sloppy.count { final(it) == it.word } * 100.0 / sloppy.size
            val fixedNormal = normal.count { final(it) == it.word } * 100.0 / normal.size
            val kept = careful.count { final(it) == it.word } * 100.0 / careful.size
            println("SWEEP edits<=%d unknownWordCost %4.1f: right at 1.5x2.0 %.1f%%, at 2.2x3.2 %.1f%%, kept unknown %.1f%%".format(edits, cost, fixedNormal, fixed, kept))
        }
    }
    }
})
