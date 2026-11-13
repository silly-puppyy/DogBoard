package io.github.sillypuppyy.dogboard.engine

import io.github.sillypuppyy.dogboard.touch.GaussianTouch
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import java.io.File
import java.util.Random

/**
 * Simulated typing: words drawn by frequency from the stand-in lexicon, typed with Gaussian tap
 * error, decoded and autocorrected. Reports how often the final word is right versus the raw
 * letters under the taps. Every test word is in the lexicon, so this measures the decoder, not
 * vocabulary coverage; the private eval in keyboard/eval/ uses real text.
 */
class EngineEvalTest : FunSpec({
    val lexicon = File("src/main/assets/dogboard/en.lexicon.tsv").bufferedReader().use { Lexicon.parse(it) }
    val words = File("src/main/assets/dogboard/en.lexicon.tsv").readLines()
        .filter { !it.startsWith("#") }
        .map { it.substringBefore('\t') to it.substringAfter('\t').toDouble() }
        .filter { (w, _) -> w.all { it.isLetter() && it.code < 128 } }
    val cumulative = words.runningFold(0.0) { acc, (_, c) -> acc + c }.drop(1)
    val keys = QwertyGeometry.letterKeys(375f, 216f)
    val keyList = keys.entries.toList()
    val params = GaussianTouch.params(medianKeyHalfWidth = 18.75, typingInterval = 0.3)
    val decoder = Decoder(lexicon)
    val autocorrect = Autocorrect(lexicon)

    fun sample(rng: Random): String {
        val r = rng.nextDouble() * cumulative.last()
        val i = cumulative.binarySearch(r).let { if (it < 0) -it - 1 else it }
        return words[i].first
    }

    fun nearestKey(x: Float, y: Float): Char =
        keyList.maxBy { GaussianTouch.logLikelihood(it.value, x, y, params) }.key

    data class Result(val literal: Double, val corrected: Double, val broken: Double, val msPerWord: Double)

    fun run(sigmaXmm: Double, sigmaYmm: Double, n: Int = 2000, seed: Long = 7): Result {
        val rng = Random(seed)
        var literalOk = 0
        var correctedOk = 0
        var broken = 0
        var nanos = 0L
        repeat(n) {
            val word = sample(rng)
            val taps = word.lowercase().map { c ->
                val k = keys.getValue(c)
                Tap(
                    (k.centerX + rng.nextGaussian() * sigmaXmm * GaussianTouch.POINTS_PER_MM).toFloat(),
                    (k.centerY + rng.nextGaussian() * sigmaYmm * GaussianTouch.POINTS_PER_MM).toFloat(),
                )
            }
            val literal = String(taps.map { nearestKey(it.x, it.y) }.toCharArray())
            val t0 = System.nanoTime()
            val candidates = decoder.decode(taps, keys, params)
            val final = autocorrect.correction(literal, candidates, decoder.literalCost(taps, literal, keys, params)) ?: literal
            nanos += System.nanoTime() - t0
            val isLiteralOk = literal.equals(word, ignoreCase = true)
            val isFinalOk = final.equals(word, ignoreCase = true)
            if (isLiteralOk) literalOk++
            if (isFinalOk) correctedOk++
            if (isLiteralOk && !isFinalOk) broken++
        }
        return Result(literalOk.toDouble() / n, correctedOk.toDouble() / n, broken.toDouble() / n, nanos / 1e6 / n)
    }

    test("autocorrect beats raw taps and rarely breaks a correctly typed word") {
        // warm up the JIT so the timing is closer to steady state
        run(1.5, 2.0, n = 200, seed = 1)
        for ((sx, sy) in listOf(1.0 to 1.5, 1.5 to 2.0, 2.2 to 3.2)) {
            val r = run(sx, sy)
            println(
                "EVAL sigma %.1fx%.1f mm: words right raw %.1f%% -> corrected %.1f%%, broken %.1f%%, %.2f ms/word"
                    .format(sx, sy, r.literal * 100, r.corrected * 100, r.broken * 100, r.msPerWord)
            )
            r.corrected shouldBeGreaterThan r.literal
            r.broken shouldBeLessThan 0.03
        }
    }
})
