package io.github.sillypuppyy.dogboard.engine

import io.github.sillypuppyy.dogboard.touch.GaussianTouch
import io.kotest.core.spec.style.FunSpec
import java.io.File

/**
 * A word missing its last letter ("going hom" -> home) against a one-key substitution that is also a word ("him"):
 * what the space gives, with the context (user 2026-11-19: "going hom" became "going him"). DOGBOARD_PROBE=1 runs it;
 * DOGBOARD_NGRAMS optional (the trigram file).
 */
class MissingLastLetterProbeTest : FunSpec({
    test("missing last letter vs substitution").config(enabled = System.getenv("DOGBOARD_PROBE") != null) {
        val keys = QwertyGeometry.keysWithSpace(411f, 216f)
        val params = GaussianTouch.params(medianKeyHalfWidth = 411 / 20.0, typingInterval = 0.2)
        TypingEngine.unloadForTest()
        TypingEngine.load(
            openLexicon = { File("src/main/assets/dogboard/en.lexicon.tsv").bufferedReader() },
            openBigrams = { File("src/main/assets/dogboard/en.bigrams.tsv").bufferedReader() },
            openNoSuggest = { File("src/main/assets/dogboard/en.nosuggest.txt").bufferedReader() },
            openMisspellings = { File("src/main/assets/dogboard/en.misspellings.txt").bufferedReader() },
            openAbbreviations = { File("src/main/assets/dogboard/en.abbreviations.txt").bufferedReader() },
        )
        System.getenv("DOGBOARD_NGRAMS")?.let { File(it) }?.takeIf { it.isFile }?.inputStream()?.use { NgramModel.readBinary(it) }
            ?.let { TypingEngine.attachNgrams(it) }
        // (previous2, previous, typed, wanted)
        val cases = listOf(
            listOf("i'm", "going", "hom", "home"), listOf("on", "my", "wa", "way"), listOf("see", "you", "tomorro", "tomorrow"),
            listOf("thank", "you", "s", "so"), listOf("let", "me", "kno", "know"), listOf("what", "a", "grea", "great"),
            listOf("i", "need", "hel", "help"), listOf("in", "the", "mornin", "morning"), listOf("it", "was", "fu", "fun"),
            listOf("i", "love", "yo", "you"), listOf("going", "to", "th", "the"), listOf("have", "a", "nic", "nice"),
        )
        try {
            for ((p2, p1, typed, wanted) in cases) {
                val word = CurrentWord().apply { for (c in typed) addLetter(c, keys[c]?.let { Tap(it.centerX, it.centerY) }, keys, params) }
                val candidates = TypingEngine.candidates(word, p1, previous2 = p2)
                val out = TypingEngine.correction(word, candidates, commit = false) ?: typed
                val top = candidates.take(5).joinToString { it.word }
                println("MISSING ${if (out == wanted) "ok  " else "MISS"} $p2 $p1 [$typed] -> $out (wanted $wanted; candidates: $top)")
            }
        } finally {
            TypingEngine.unloadForTest()
        }
    }
})
