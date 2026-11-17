package io.github.sillypuppyy.dogboard.engine

import io.github.sillypuppyy.dogboard.touch.GaussianTouch
import io.kotest.core.spec.style.FunSpec
import java.io.File

/**
 * Prints what a space tapped on the letter above the space bar becomes ("of a" typed as "ofna", the
 * space tap on n), for that tap at several heights between the letter's centre (0) and the space
 * bar's (1), on the Pixel 10's geometry. A probe: DOGBOARD_PROBE=1 DOGBOARD_NGRAMS=... gradle test.
 */
class SpaceOnLetterProbeTest : FunSpec({
    test("space taps landing on the bottom row").config(enabled = System.getenv("DOGBOARD_PROBE") == "1") {
        val lexicon = File("src/main/assets/dogboard/en.lexicon.tsv").bufferedReader().use { Lexicon.parse(it) }
        val bigrams = File("src/main/assets/dogboard/en.bigrams.tsv").bufferedReader().use { Bigrams.parse(it, lexicon) }
        val ngrams = System.getenv("DOGBOARD_NGRAMS")?.let { File(it) }?.takeIf { it.isFile }?.inputStream()?.use { NgramModel.readBinary(it) }
        val decoder = Decoder(lexicon, Decoder.Config(languagePower = Decoder.LANGUAGE_POWER_CONTEXT), bigrams = bigrams,
            context = ngrams?.let { NgramScorer(it, lexicon, bigrams.sentenceStart) })
        val autocorrect = Autocorrect(lexicon)
        val width = 411f
        val keys = QwertyGeometry.keysWithSpace(width, 216f)
        val params = GaussianTouch.params(medianKeyHalfWidth = width / 20.0, typingInterval = 0.15)
        val space = keys.getValue(' ')
        // previous word, intended words, the letter the space landed on
        val cases = listOf(Triple("ahead", "of a", 'n'), Triple("only", "if the", 'n'), Triple("letter", "or space", 'n'),
            Triple("below", "it that", 'n'), Triple("straight", "below it", 'b'), Triple("the", "language model", 'n'),
            Triple("if", "the language model", 'n'), Triple("if", "the language", 'n'), Triple("tap", "within a letter", 'n'))
        for ((before, words, on) in cases) for (h in listOf(0.0, 0.25, 0.4, 0.5)) {
            val letter = keys.getValue(on)
            val taps = words.map { c ->
                if (c == ' ') Tap(letter.centerX, (letter.centerY + h * (space.centerY - letter.centerY)).toFloat())
                else keys.getValue(c).let { Tap(it.centerX, it.centerY) }
            }
            val typed = words.replace(' ', on)
            val candidates = decoder.decode(taps, keys, params, previous = bigrams.contextId(before), typed = typed)
            val result = autocorrect.correction(typed, candidates) ?: typed
            println("PROBE-SPACE %-8s %-15s on %c at %.2f -> %-15s %s".format(before, words, on, h, result,
                candidates.take(3).joinToString(" | ") { "%s %.2f".format(it.word, it.score) }))
        }
    }

    test("space taps on the bottom row, full engine").config(enabled = System.getenv("DOGBOARD_PROBE") == "1") {
        val width = 411f
        val keys = QwertyGeometry.keysWithSpace(width, 216f)
        val params = GaussianTouch.params(medianKeyHalfWidth = width / 20.0, typingInterval = 0.15)
        val space = keys.getValue(' ')
        TypingEngine.unloadForTest()
        TypingEngine.load(
            openLexicon = { File("src/main/assets/dogboard/en.lexicon.tsv").bufferedReader() },
            openBigrams = { File("src/main/assets/dogboard/en.bigrams.tsv").bufferedReader() },
            openNoSuggest = { File("src/main/assets/dogboard/en.nosuggest.txt").bufferedReader() },
            openMisspellings = { File("src/main/assets/dogboard/en.misspellings.txt").bufferedReader() },
        )
        System.getenv("DOGBOARD_NGRAMS")?.let { File(it) }?.takeIf { it.isFile }?.inputStream()?.use { NgramModel.readBinary(it) }
            ?.let { TypingEngine.attachNgrams(it) }
        File("src/main/assets/dogboard/en.charlm.bin").inputStream().use { CharModel.read(it) }?.let { TypingEngine.attachCharModel(it) }
        try {
            for ((before, words) in listOf("only" to "if the", "only if" to "the language", "if" to "the language model", "of" to "a letter or space",
                    "." to "straight below it")) {
                for (h in listOf(0.0, 0.3)) {
                    val n = keys.getValue(if (words.startsWith("straight")) 'b' else 'n')
                    val word = CurrentWord().apply {
                        for (c in words) {
                            val tap = if (c == ' ') Tap(n.centerX, (n.centerY + h * (space.centerY - n.centerY)).toFloat())
                                else keys.getValue(c).let { Tap(it.centerX, it.centerY) }
                            addLetter(if (c == ' ') (if (words.startsWith("straight")) 'b' else 'n') else c, tap, keys, params)
                        }
                    }
                    val candidates = TypingEngine.candidates(word, before.split(' ').last(), previous2 = before.split(' ').getOrNull(before.split(' ').size - 2))
                    val result = TypingEngine.correction(word, candidates, commit = true) ?: word.text
                    println("PROBE-FULL %-8s %-20s at %.1f -> %-20s %s".format(before, words, h, result,
                        candidates.take(3).joinToString(" | ") { "%s %.2f".format(it.word, it.score) }))
                }
            }
        } finally {
            TypingEngine.unloadForTest()
        }
    }
})
