package io.github.sillypuppyy.dogboard.engine

import io.github.sillypuppyy.dogboard.touch.GaussianTouch
import io.kotest.core.spec.style.FunSpec
import java.io.File

/**
 * Prints what plain words that are also apostrophe-less contractions (ill/I'll, hell/he'll, its/it's)
 * become when typed exactly, in a few contexts, with the shipped lexicon (+ DOGBOARD_NGRAMS if set).
 * A probe, not an assertion: DOGBOARD_PROBE=1 ./gradlew :app:testDebugUnitTest --rerun
 */
class ContractionProbeTest : FunSpec({
    test("contraction look-alikes").config(enabled = System.getenv("DOGBOARD_PROBE") == "1") {
        val lexicon = File(System.getenv("DOGBOARD_LEXICON") ?: "src/main/assets/dogboard/en.lexicon.tsv").bufferedReader().use { Lexicon.parse(it) }
        val bigrams = File("src/main/assets/dogboard/en.bigrams.tsv").bufferedReader().use { Bigrams.parse(it, lexicon) }
        val ngrams = System.getenv("DOGBOARD_NGRAMS")?.let { File(it) }?.takeIf { it.isFile }?.inputStream()?.use { NgramModel.readBinary(it) }
        val decoder = Decoder(lexicon, bigrams = bigrams, context = ngrams?.let { NgramScorer(it, lexicon, bigrams.sentenceStart) })
        val keys = QwertyGeometry.letterKeys(375f, 216f)
        val params = GaussianTouch.params(medianKeyHalfWidth = 18.75, typingInterval = 0.3)
        val autocorrect = Autocorrect(lexicon)
        val phrases = listOf(
            "ill be there", "i feel ill", "hell yes", "go to hell", "he said hell be late", "a sea shell", "shell be fine",
            "its raining", "the dog wagged its tail", "lets go", "she lets him", "well see", "very well",
            "were here", "we were", "wont happen", "wed better go", "they wed in june", "id like that", "my id card",
            "wed love to", "wed be happy", "i wish wed known", "i wont go", "he wont stop", "the couple will wed", "wed like to", "if wed known", "cant wait", "dont know", "im here", "youre right", "theyre gone", "whats up", "thats it", "hes here",
        )
        for (phrase in phrases) {
            var previous = Bigrams.START
            var previous2: String? = null
            val out = phrase.split(' ').map { w ->
                val taps = w.map { c -> keys.getValue(c).let { Tap(it.centerX, it.centerY) } }
                val candidates = decoder.decode(taps, keys, params, previous = bigrams.contextId(previous), typed = w,
                    previous2 = previous2?.let { bigrams.contextId(it) } ?: -1)
                val result = autocorrect.correction(w, candidates) ?: w
                previous2 = previous
                previous = w
                result
            }
            println("PROBE %-24s -> %s".format(phrase, out.joinToString(" ")))
        }
    }
})
