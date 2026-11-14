package io.github.sillypuppyy.dogboard.engine

import io.github.sillypuppyy.dogboard.touch.GaussianTouch
import io.kotest.core.spec.style.FunSpec
import java.io.File

/**
 * Prints the score parts for real words typed exactly that we correct to another word ("over the lazy"
 * -> "last"), on the eval geometry (375 dp) and the Pixel 10's (411 dp), slow and fast typing.
 * A probe, not an assertion: DOGBOARD_PROBE=1 DOGBOARD_NGRAMS=... ./gradlew :app:testDebugUnitTest --rerun
 */
class LazyProbeTest : FunSpec({
    test("real words typed exactly").config(enabled = System.getenv("DOGBOARD_PROBE") == "1") {
        val lexicon = File("src/main/assets/dogboard/en.lexicon.tsv").bufferedReader().use { Lexicon.parse(it) }
        val bigrams = File("src/main/assets/dogboard/en.bigrams.tsv").bufferedReader().use { Bigrams.parse(it, lexicon) }
        val ngrams = System.getenv("DOGBOARD_NGRAMS")?.let { File(it) }?.takeIf { it.isFile }?.inputStream()?.use { NgramModel.readBinary(it) }
        val decoder = Decoder(lexicon, bigrams = bigrams, context = ngrams?.let { NgramScorer(it, lexicon, bigrams.sentenceStart) })
        val autocorrect = Autocorrect(lexicon)
        val cases = listOf(
            listOf("jumps", "over", "the", "lazy"), listOf("the", "lazy"), listOf("a", "lazy"), listOf("over", "the", "last"),
        )
        for (width in listOf(375f, 411f)) for (interval in listOf(0.3, 0.12)) {
            val keys = QwertyGeometry.letterKeys(width, 216f)
            val params = GaussianTouch.params(medianKeyHalfWidth = width / 20.0, typingInterval = interval)
            for (words in cases) {
                val w = words.last()
                val previous = words.getOrNull(words.size - 2) ?: Bigrams.START
                val previous2 = words.getOrNull(words.size - 3)
                val taps = w.map { c -> keys.getValue(c).let { Tap(it.centerX, it.centerY) } }
                val candidates = decoder.decode(taps, keys, params, previous = bigrams.contextId(previous), typed = w,
                    previous2 = previous2?.let { bigrams.contextId(it) } ?: -1)
                val result = autocorrect.correction(w, candidates) ?: w
                println("PROBE-LAZY %3.0f dp %.2f s  %-22s -> %-6s  %s".format(width, interval, words.joinToString(" "), result,
                    candidates.take(4).joinToString(" | ") { "%s ω %.2f geo %.2f lm %.2f e%d".format(it.word, it.score, it.geometry, it.languageModel, it.edits) }))
            }
        }
    }
})
