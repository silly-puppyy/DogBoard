package io.github.sillypuppyy.dogboard.engine

import io.github.sillypuppyy.dogboard.touch.GaussianTouch
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import java.io.File
import java.util.Random

/**
 * Typing real sentences: Tatoeba sentences left out of the bigram data (ids ending in 0) are
 * typed word by word with tap error and autocorrected, with and without the previous word as
 * context. Needs the private Tatoeba export (DOGBOARD_TATOEBA, default under keyboard/data/);
 * skipped without it.
 */
class EngineContextEvalTest : FunSpec({
    val tatoeba = File(
        System.getenv("DOGBOARD_TATOEBA")
            ?: "${System.getProperty("user.home")}/Documents/Code/AI/keyboard/data/tatoeba/eng_sentences.tsv"
    )

    test("context from the previous word makes more words come out right").config(enabled = tatoeba.exists()) {
        val lexicon = File("src/main/assets/dogboard/en.lexicon.tsv").bufferedReader().use { Lexicon.parse(it) }
        val bigrams = File("src/main/assets/dogboard/en.bigrams.tsv").bufferedReader().use { Bigrams.parse(it, lexicon) }
        val keys = QwertyGeometry.letterKeys(375f, 216f)
        val keyList = keys.entries.toList()
        val params = GaussianTouch.params(medianKeyHalfWidth = 18.75, typingInterval = 0.3)
        val plain = Decoder(lexicon)
        val withContext = Decoder(lexicon, bigrams = bigrams)
        val autocorrect = Autocorrect(lexicon)
        val token = Regex("[A-Za-z]+(?:'[A-Za-z]+)*|[.!?]")

        val sentences = tatoeba.bufferedReader().lineSequence()
            .map { it.split('\t') }
            .filter { it.size >= 3 && it[0].endsWith("0") }
            .map { it[2].replace('’', '\'') }
            .filter { s -> token.findAll(s).none { it.value.lowercase() in setOf("tom", "mary") } }
            .take(800)
            .toList()

        for ((sx, sy) in listOf(1.0 to 1.5, 1.5 to 2.0, 2.2 to 3.2)) {
            val rng = Random(3)
            var words = 0
            var raw = 0
            var rightPlain = 0
            var rightContext = 0
            for (sentence in sentences) {
                var previous = Bigrams.START
                for (m in token.findAll(sentence)) {
                    val t = m.value
                    if (t in setOf(".", "!", "?")) {
                        previous = Bigrams.START
                        continue
                    }
                    val letters = t.lowercase().filter { it in keys }
                    if (letters.isEmpty()) continue
                    val taps = letters.map { c ->
                        val k = keys.getValue(c)
                        Tap(
                            (k.centerX + rng.nextGaussian() * sx * GaussianTouch.POINTS_PER_MM).toFloat(),
                            (k.centerY + rng.nextGaussian() * sy * GaussianTouch.POINTS_PER_MM).toFloat(),
                        )
                    }
                    val literal = String(taps.map { tap -> keyList.maxBy { GaussianTouch.logLikelihood(it.value, tap.x, tap.y, params) }.key }.toCharArray())
                    val literalCost = plain.literalCost(taps, literal, keys, params)
                    fun final(decoder: Decoder, context: Int): String {
                        val candidates = decoder.decode(taps, keys, params, previous = context)
                        return autocorrect.correction(literal, candidates, literalCost) ?: literal
                    }
                    words++
                    if (literal == letters) raw++
                    if (final(plain, -1).equals(t, ignoreCase = true)) rightPlain++
                    if (final(withContext, bigrams.contextId(previous)).equals(t, ignoreCase = true)) rightContext++
                    previous = t.lowercase()
                }
            }
            println(
                "CONTEXT sigma %.1fx%.1f mm, %d words: raw %.1f%%, unigram %.1f%%, bigram %.1f%%".format(
                    sx, sy, words, raw * 100.0 / words, rightPlain * 100.0 / words, rightContext * 100.0 / words,
                )
            )
            if (sx == 1.5) (rightContext.toDouble() / words) shouldBeGreaterThan (rightPlain.toDouble() / words)
        }
    }
})
