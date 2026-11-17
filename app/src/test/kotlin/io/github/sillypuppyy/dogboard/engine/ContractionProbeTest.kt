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
            "wed love to", "wed be happy", "i wish wed known", "i wont go", "he wont stop", "the couple will wed", "wed like to", "if wed known", "love u", "see u soon", "are u ok", "thank u", "u there", "o well", "o my god", "i owe u", "oh shit", "what the fuck", "fucking hell", "you ass", "holy shit man", "shit happens", "cant wait", "dont know", "im here", "youre right", "theyre gone", "whats up", "thats it", "hes here",
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
        // accents typed from a long-press popup: the tap is the base key's (as the keyboard records it)
        for (typed in listOf("cafè", "café", "naïve", "naive", "résumé", "resumé", "señor", "jalapeño", "fiancé", "über")) {
            val taps = typed.map { c -> keys.getValue(Lexicon.fold(c)).let { Tap(it.centerX, it.centerY) } }
            val candidates = decoder.decode(taps, keys, params, previous = bigrams.contextId(Bigrams.START), typed = typed)
            val result = autocorrect.correction(typed, candidates.filter { keepsPopupLetters(typed, it.word) }) ?: typed
            println("PROBE-ACCENT %s -> %s   top: %s".format(typed, result, candidates.take(3).joinToString { "%s %.2f".format(it.word, it.score) }))
        }
        // a sloppy "u": the tap 40 % of the way toward a neighbouring key, after a context word
        val u = keys.getValue('u')
        for (toward in listOf('y', 'i', 'j')) {
            val k = keys.getValue(toward)
            val tap = Tap(u.centerX + 0.4f * (k.centerX - u.centerX), u.centerY + 0.4f * (k.centerY - u.centerY))
            for (before in listOf("love", "are", "thank", "see", "owe", "miss")) {
                val candidates = decoder.decode(listOf(tap), keys, params, previous = bigrams.contextId(before), typed = "u")
                val result = autocorrect.correction("u", candidates) ?: "u"
                println("PROBE-U %s u(→%s) -> %s   top: %s".format(before, toward, result, candidates.take(3).joinToString { "%s %.2f".format(it.word, it.score) }))
            }
        }
    }

    test("possessives typed with an apostrophe, full engine").config(enabled = System.getenv("DOGBOARD_PROBE") == "1") {
        val keys = QwertyGeometry.letterKeys(375f, 216f)
        val params = GaussianTouch.params(medianKeyHalfWidth = 18.75, typingInterval = 0.3)
        TypingEngine.unloadForTest()
        TypingEngine.load(
            openLexicon = { File("src/main/assets/dogboard/en.lexicon.tsv").bufferedReader() },
            openBigrams = { File("src/main/assets/dogboard/en.bigrams.tsv").bufferedReader() },
            openNoSuggest = { File("src/main/assets/dogboard/en.nosuggest.txt").bufferedReader() },
        )
        try {
            for (w in listOf("Kayla's", "puppy's", "roommate's", "kids'", "parents'", "dogs'", "dog's", "puppys", "roommates", "kids", "Kaylas")) {
                val word = CurrentWord().apply {
                    // the apostrophe comes from the symbol layer: no letter-key tap
                    for (c in w) addLetter(c, keys[c.lowercaseChar()]?.let { Tap(it.centerX, it.centerY) }, keys, params)
                }
                val candidates = TypingEngine.candidates(word, "the")
                val result = TypingEngine.correction(word, candidates) ?: w
                println("PROBE-POSS %-11s -> %-11s decodable=%s top: %s".format(w, result, word.tapsOrNull() != null,
                    candidates.take(3).joinToString { "%s %.2f".format(it.word, it.score) }))
            }
        } finally {
            TypingEngine.unloadForTest()
        }
    }

    test("retrocorrection cost at the end of a word (it runs on the main thread)").config(enabled = System.getenv("DOGBOARD_PROBE") == "1") {
        val keys = QwertyGeometry.letterKeys(375f, 216f)
        val params = GaussianTouch.params(medianKeyHalfWidth = 18.75, typingInterval = 0.3)
        fun word(w: String) = CurrentWord().apply { for (c in w) addLetter(c, keys[c]?.let { Tap(it.centerX, it.centerY) }, keys, params) }
        TypingEngine.unloadForTest()
        TypingEngine.load(
            openLexicon = { File("src/main/assets/dogboard/en.lexicon.tsv").bufferedReader() },
            openBigrams = { File("src/main/assets/dogboard/en.bigrams.tsv").bufferedReader() },
        )
        System.getenv("DOGBOARD_NGRAMS")?.let { File(it) }?.takeIf { it.isFile }?.let { f ->
            java.io.RandomAccessFile(f, "r").channel.use { ch -> NgramModel.fromBuffer(ch.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, ch.size())) }
        }?.let { TypingEngine.attachNgrams(it) }
        try {
            for ((prev, cur, before) in listOf(Triple("clips", "anyways", "it"), Triple("write", "everything", "to"), Triple("the", "remove", "to"))) {
                repeat(2) { TypingEngine.retrocorrection(word(prev), prev, before, word(cur)) } // warm-up
                val t0 = System.nanoTime()
                repeat(5) { TypingEngine.retrocorrection(word(prev), prev, before, word(cur)) }
                println("PROBE-RETRO %s %s: %.1f ms per call (desktop JVM)".format(prev, cur, (System.nanoTime() - t0) / 5e6))
                val pc = TypingEngine.candidates(word(prev), before)
                val cc = TypingEngine.candidates(word(cur), prev)
                val t1 = System.nanoTime()
                repeat(50) { TypingEngine.retrocorrection(word(prev), prev, before, word(cur), previousCandidates = pc, currentCandidates = cc) }
                println("PROBE-RETRO fast %s %s: %.2f ms per call".format(prev, cur, (System.nanoTime() - t1) / 50e6))
            }
        } finally {
            TypingEngine.unloadForTest()
        }
    }

    test("unknown words typed exactly: kept, or split into known words?").config(enabled = System.getenv("DOGBOARD_PROBE") == "1") {
        val keys = QwertyGeometry.letterKeys(375f, 216f)
        val params = GaussianTouch.params(medianKeyHalfWidth = 18.75, typingInterval = 0.3)
        fun word(w: String) = CurrentWord().apply { for (c in w) addLetter(c, keys[c.lowercaseChar()]?.let { Tap(it.centerX, it.centerY) }, keys, params) }
        TypingEngine.unloadForTest()
        TypingEngine.load(
            openLexicon = { File("src/main/assets/dogboard/en.lexicon.tsv").bufferedReader() },
            openBigrams = { File("src/main/assets/dogboard/en.bigrams.tsv").bufferedReader() },
            openCharModel = System.getenv("DOGBOARD_CHARLM")?.let { path -> { File(path).bufferedReader() } },
        )
        System.getenv("DOGBOARD_NGRAMS")?.let { File(it) }?.takeIf { it.isFile }?.let { f ->
            java.io.RandomAccessFile(f, "r").channel.use { ch -> NgramModel.fromBuffer(ch.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, ch.size())) }
        }?.let { TypingEngine.attachNgrams(it) }
        try {
            for ((before, w) in listOf("teh" to "thw", "I" to "wnat", "the" to "becuase", "my" to "Zorblax", "that" to "skibidi", "with" to "Kaito", "and" to "Zendaya", "on" to "Brightspace",
                "a" to "tiktoker", "the" to "Kubernetes", "my" to "Aoife", "use" to "Figma", "on" to "Gumroad", "the" to "flavortown", "hi" to "Kenji")) {
                val cw = word(w)
                val c = TypingEngine.candidates(cw, before)
                println("PROBE-UNK %-12s -> %-14s top: %s".format(w, TypingEngine.correction(cw, c) ?: w, c.take(3).joinToString { "%s %.2f".format(it.word, it.score) }))
            }
        } finally {
            TypingEngine.unloadForTest()
        }
    }
})
