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
    val decoder = Decoder(lexicon, Decoder.Config(languagePower = Decoder.LANGUAGE_POWER_UNIGRAM))
    val autocorrect = Autocorrect(lexicon)

    fun sample(rng: Random): String {
        val r = rng.nextDouble() * cumulative.last()
        val i = cumulative.binarySearch(r).let { if (it < 0) -it - 1 else it }
        return words[i].first
    }

    fun nearestKey(x: Float, y: Float): Char =
        keyList.maxBy { GaussianTouch.logLikelihood(it.value, x, y, params) }.key

    data class Result(val literal: Double, val corrected: Double, val broken: Double, val msPerWord: Double)

    /**
     * The letters actually typed for [word]: with [typos], a skipped letter (3%), a doubled letter
     * (2%) or a swapped pair (2%) on top of the tap noise, the slips tap noise alone never makes.
     */
    fun typedLetters(word: String, rng: Random, typos: Boolean): String {
        val w = word.lowercase()
        if (!typos) return w
        val r = rng.nextDouble()
        return when {
            r < 0.03 && w.length > 3 -> rng.nextInt(1, w.length).let { i -> w.removeRange(i, i + 1) }
            r < 0.05 -> rng.nextInt(w.length).let { i -> w.substring(0, i + 1) + w[i] + w.substring(i + 1) }
            r < 0.07 && w.length > 2 -> rng.nextInt(w.length - 1).let { i -> w.substring(0, i) + w[i + 1] + w[i] + w.substring(i + 2) }
            else -> w
        }
    }

    fun run(sigmaXmm: Double, sigmaYmm: Double, n: Int = 2000, seed: Long = 7, ac: Autocorrect = autocorrect, typos: Boolean = false): Result {
        val rng = Random(seed)
        var literalOk = 0
        var correctedOk = 0
        var broken = 0
        var nanos = 0L
        repeat(n) {
            val word = sample(rng)
            val taps = typedLetters(word, rng, typos).map { c ->
                val k = keys.getValue(c)
                Tap(
                    (k.centerX + rng.nextGaussian() * sigmaXmm * GaussianTouch.POINTS_PER_MM).toFloat(),
                    (k.centerY + rng.nextGaussian() * sigmaYmm * GaussianTouch.POINTS_PER_MM).toFloat(),
                )
            }
            val literal = String(taps.map { nearestKey(it.x, it.y) }.toCharArray())
            val t0 = System.nanoTime()
            val candidates = decoder.decode(taps, keys, params, typed = literal)
            val final = ac.correction(literal, candidates) ?: literal
            nanos += System.nanoTime() - t0
            val isLiteralOk = literal.equals(word, ignoreCase = true)
            val isFinalOk = final.equals(word, ignoreCase = true)
            if (isLiteralOk) literalOk++
            if (isFinalOk) correctedOk++
            if (isLiteralOk && !isFinalOk) broken++
        }
        return Result(literalOk.toDouble() / n, correctedOk.toDouble() / n, broken.toDouble() / n, nanos / 1e6 / n)
    }

    test("autocorrect beats raw taps and rarely breaks a correctly typed word").config(enabled = EVAL) {
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

    test("per-keystroke decode time (printed; the phone is roughly 2-3x slower)").config(enabled = EVAL) {
        val rng = Random(11)
        val times = ArrayList<Double>()
        repeat(400) {
            val word = sample(rng)
            val taps = word.lowercase().map { c -> keys.getValue(c).let { Tap(it.centerX, it.centerY) } }
            for (n in 1..taps.size) {
                val t0 = System.nanoTime()
                decoder.decode(taps.subList(0, n), keys, params)
                times.add((System.nanoTime() - t0) / 1e6)
            }
        }
        val measured = times.drop(times.size / 4).sorted() // skip JIT warm-up
        println(
            "EVAL per-keystroke decode: mean %.2f ms, p95 %.2f ms, max %.2f ms over %d prefixes".format(
                measured.average(), measured[(measured.size * 0.95).toInt()], measured.last(), measured.size,
            )
        )
    }

    /** One typed word: what was meant, the nearest keys, and the decoder's candidates (decoded once). */
    class Sample(val word: String, val literal: String, val candidates: List<Candidate>)

    fun samples(sigmaXmm: Double, sigmaYmm: Double, n: Int, seed: Long, typos: Boolean): List<Sample> {
        val rng = Random(seed)
        return List(n) {
            val word = sample(rng)
            val taps = typedLetters(word, rng, typos).map { c ->
                val k = keys.getValue(c)
                Tap(
                    (k.centerX + rng.nextGaussian() * sigmaXmm * GaussianTouch.POINTS_PER_MM).toFloat(),
                    (k.centerY + rng.nextGaussian() * sigmaYmm * GaussianTouch.POINTS_PER_MM).toFloat(),
                )
            }
            val literal = String(taps.map { nearestKey(it.x, it.y) }.toCharArray())
            Sample(word, literal, decoder.decode(taps, keys, params, typed = literal))
        }
    }

    /** "x.xx% ±y.yy" with a 95% Wilson interval half-width. */
    fun pct(k: Int, n: Int): String {
        val p = k.toDouble() / n
        val z = 1.96
        val half = z * kotlin.math.sqrt(p * (1 - p) / n + z * z / (4.0 * n * n)) / (1 + z * z / n)
        return "%.2f%% ±%.2f".format(p * 100, half * 100)
    }

    test("noise calibration: raw and remaining word errors by tap scatter").config(enabled = System.getenv("DOGBOARD_SWEEP") == "1") {
        for (sigma in listOf(1.0, 1.5, 2.0, 2.5, 3.0, 3.5)) {
            for (typos in listOf(false, true)) {
                val ss = samples(sigma, sigma * 1.4, 2000, 11, typos)
                val raw = ss.count { !it.literal.equals(it.word, ignoreCase = true) }
                val left = ss.count { s -> !(autocorrect.correction(s.literal, s.candidates) ?: s.literal).equals(s.word, ignoreCase = true) }
                println("NOISE %.1fx%.1f mm %s: raw errors %s, after autocorrect %s".format(
                    sigma, sigma * 1.4, if (typos) "typos" else "noise", pct(raw, ss.size), pct(left, ss.size)))
            }
        }
    }

    test("autocorrect level sweep (settings-model.md §4)").config(enabled = System.getenv("DOGBOARD_SWEEP") == "1") {
        val levels = buildList {
            for (lead in listOf(1.0, 1.5, 2.0, 3.0, 5.0)) add("lead %.1f".format(lead) to Autocorrect.Level.STANDARD.copy(leadOverTyped = lead))
            for (stems in listOf(Triple(6, 5, 3), Triple(5, 4, 3), Triple(4, 3, 3))) {
                for (dom in listOf(2.7, 1.8, 1.3)) {
                    add("stems ${stems.toList()} dom $dom" to Autocorrect.Level(dom, stems.first, stems.second, stems.third))
                }
            }
        }
        val standard = Autocorrect(lexicon)
        for ((sx, sy) in listOf(1.5 to 2.1, 2.5 to 3.5)) {
            for (typos in listOf(false, true)) {
                // decoded once; every level decides on the same candidates (a paired comparison)
                val ss = samples(sx, sy, 6000, 7, typos)
                fun final(ac: Autocorrect, s: Sample) = ac.correction(s.literal, s.candidates) ?: s.literal
                for ((name, level) in levels) {
                    val ac = Autocorrect(lexicon, level)
                    var left = 0
                    var broken = 0
                    var better = 0
                    var worse = 0
                    for (s in ss) {
                        val ok = final(ac, s).equals(s.word, ignoreCase = true)
                        val okStd = final(standard, s).equals(s.word, ignoreCase = true)
                        if (!ok) left++
                        if (!ok && s.literal.equals(s.word, ignoreCase = true)) broken++
                        if (ok && !okStd) better++
                        if (!ok && okStd) worse++
                    }
                    println("LEVEL %.1fx%.1f %s %-28s errors left %s, wrongly changed %s, vs Standard +%d/-%d".format(
                        sx, sy, if (typos) "typos" else "noise", name, pct(left, ss.size), pct(broken, ss.size), better, worse))
                }
            }
        }
    }
})
