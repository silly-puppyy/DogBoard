package io.github.sillypuppyy.dogboard.engine

import io.github.sillypuppyy.dogboard.touch.GaussianTouch
import io.kotest.core.spec.style.FunSpec
import java.io.File

/**
 * Contractions typed without the apostrophe ("well" for "we'll"): what the word end gives after a
 * context (iOS keeps both as variants of one sort key, well 0.816 / we'll 0.184, and lets the
 * language model choose). DOGBOARD_CONTRACTIONS=1 runs it; DOGBOARD_NGRAMS optional.
 */
class ContractionProbeTest : FunSpec({
    test("apostrophe-less contractions").config(enabled = System.getenv("DOGBOARD_CONTRACTIONS") != null) {
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
            listOf("from", "those", "well", "we'll"), listOf("i", "think", "well", "we'll"), listOf("as", "you", "well", "well"),
            listOf("i", "hope", "were", "we're"), listOf("they", "said", "were", "we're"), listOf("the", "people", "were", "were"),
            listOf("i", "think", "its", "it's"), listOf("on", "the", "its", "its"), listOf("and", "then", "id", "i'd"),
            listOf("i", "think", "shed", "she'd"), listOf("in", "the", "shed", "shed"), listOf("i", "really", "cant", "can't"),
            listOf("i", "know", "youre", "you're"), listOf("i", "think", "hell", "he'll"), listOf("what", "the", "hell", "hell"),
        )
        try {
            for ((p2, p1, typed, wanted) in cases) {
                val word = CurrentWord().apply { for (c in typed) addLetter(c, keys[c]?.let { Tap(it.centerX, it.centerY) }, keys, params) }
                val candidates = TypingEngine.candidates(word, p1, previous2 = p2)
                val out = TypingEngine.correction(word, candidates, commit = false) ?: typed
                val top = candidates.take(3).joinToString { it.word }
                println("CONTRACTION ${if (out == wanted) "ok  " else "MISS"} $p2 $p1 [$typed] -> $out (wanted $wanted; top: $top)")
            }
            // the look back: the next word typed, the earlier one may change (iOS retrocorrection)
            val retroCases = listOf(
                listOf("those", "well", "make", "we'll"), listOf("then", "well", "see", "we'll"), listOf("said", "were", "going", "we're"),
                listOf("people", "were", "going", "were"), listOf("as", "well", "as", "well"), listOf("the", "shed", "door", "shed"),
            )
            fun typed(t: String) = CurrentWord().apply { for (c in t) addLetter(c, keys[c]?.let { Tap(it.centerX, it.centerY) }, keys, params) }
            for ((before, prev, next, wanted) in retroCases) {
                val out = TypingEngine.retrocorrection(typed(prev), prev, before, typed(next)) ?: prev
                println("CONTRACTION ${if (out == wanted) "ok  " else "MISS"} retro: $before [$prev] $next -> $out (wanted $wanted)")
            }
        } finally {
            TypingEngine.unloadForTest()
        }
    }
})
