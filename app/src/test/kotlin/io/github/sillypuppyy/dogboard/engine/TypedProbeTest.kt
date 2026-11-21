package io.github.sillypuppyy.dogboard.engine

import io.github.sillypuppyy.dogboard.touch.GaussianTouch
import io.kotest.core.spec.style.FunSpec
import java.io.File

/**
 * Prints what the full engine makes of typed strings (key-centre taps), one "previous words<TAB>typed" per line of
 * DOGBOARD_PROBE_CASES. A probe for user reports: DOGBOARD_PROBE=1 DOGBOARD_PROBE_CASES=... DOGBOARD_NGRAMS=... gradle test.
 */
class TypedProbeTest : FunSpec({
    test("typed strings through the full engine").config(enabled = System.getenv("DOGBOARD_PROBE") == "1") {
        val cases = File(System.getenv("DOGBOARD_PROBE_CASES") ?: return@config).readLines().filter { '\t' in it }
        val width = 411f
        val keys = QwertyGeometry.keysWithSpace(width, 216f)
        val params = GaussianTouch.params(medianKeyHalfWidth = width / 20.0, typingInterval = 0.15)
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
            // DOGBOARD_PROBE_NOISE=n: n tries per case with taps scattered 1.5 x 2 mm (6.3 dp a mm), outcomes counted
            val tries = System.getenv("DOGBOARD_PROBE_NOISE")?.toIntOrNull()
            val rnd = java.util.Random(1)
            if (tries != null) for (line in cases) {
                val (before, typed) = line.split('\t')
                val words = before.split(' ').filter { it.isNotEmpty() }
                val outcomes = HashMap<String, Int>()
                repeat(tries) {
                    val word = CurrentWord().apply {
                        for (c in typed) keys[c.lowercaseChar()]?.let {
                            addLetter(c, Tap((it.centerX + rnd.nextGaussian() * 1.5 * 6.3).toFloat(), (it.centerY + rnd.nextGaussian() * 2.0 * 6.3).toFloat()), keys, params)
                        }
                    }
                    val candidates = TypingEngine.candidates(word, words.lastOrNull() ?: ".", previous2 = words.getOrNull(words.size - 2))
                    val result = TypingEngine.correction(word, candidates, commit = true) ?: word.text
                    outcomes.merge(result, 1, Int::plus)
                }
                println("PROBE-NOISE %-12s %-10s %s".format(before.takeLast(12), typed, outcomes.entries.sortedByDescending { it.value }.take(8).joinToString(" ") { "${it.key}:${it.value}" }))
            }
            if (tries == null) for (line in cases) {
                val (before, typed) = line.split('\t')
                val word = CurrentWord().apply {
                    for (c in typed) keys[c.lowercaseChar()]?.let { addLetter(c, Tap(it.centerX, it.centerY), keys, params) }
                }
                val words = before.split(' ').filter { it.isNotEmpty() }
                val candidates = TypingEngine.candidates(word, words.lastOrNull() ?: ".", previous2 = words.getOrNull(words.size - 2))
                val result = TypingEngine.correction(word, candidates, commit = true) ?: word.text
                println("PROBE-TYPED %-12s %-24s -> %-24s %s".format(before, typed, result,
                    candidates.take(4).joinToString(" | ") { "%s %.2f".format(it.word, it.score) }))
            }
        } finally {
            TypingEngine.unloadForTest()
        }
    }
})
