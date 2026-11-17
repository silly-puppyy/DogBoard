package io.github.sillypuppyy.dogboard.engine

import io.github.sillypuppyy.dogboard.touch.GaussianTouch
import io.kotest.core.spec.style.FunSpec
import java.io.File

/**
 * Capitalisation as the user types it: every word of real sentences typed in lowercase with exact
 * taps (the first word of a sentence auto-shifted, as the keyboard does), finished through the full
 * engine, compared with the original's capitals. Per kind of word: sentence starts, I-forms, names
 * and other capitalised words mid-sentence, all-caps words, lowercase words (wrongly capitalised).
 * DOGBOARD_CAPS = text file (one sentence or message a line), DOGBOARD_CAPS_N lines (default 1000),
 * DOGBOARD_NGRAMS optional; DOGBOARD_CAPS_MISSES = file for the misses.
 */
class CapsEvalTest : FunSpec({
    val text = System.getenv("DOGBOARD_CAPS")?.let { File(it) }?.takeIf { it.isFile }

    test("capitalisation of lowercase typing").config(enabled = text != null, timeout = kotlin.time.Duration.parse("1h")) {
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
        File("src/main/assets/dogboard/en.charlm.bin").inputStream().use { CharModel.read(it) }?.let { TypingEngine.attachCharModel(it) }
        val iForms = setOf("i", "i'm", "i'll", "i've", "i'd")
        class Tally { var n = 0; var right = 0; var otherWord = 0 }
        val tally = linkedMapOf("start" to Tally(), "I" to Tally(), "capitalised" to Tally(), "all caps" to Tally(), "lowercase" to Tally())
        val misses = ArrayList<String>()
        val lines = text!!.readLines().filter { it.isNotBlank() }.take(System.getenv("DOGBOARD_CAPS_N")?.toIntOrNull() ?: 1000)
        try {
            for (line in lines) {
                // sentences: a new one after . ! ? (what auto-shift capitalises)
                var start = true
                var previous: String? = null
                var previous2: String? = null
                for (raw in line.split(' ').filter { it.isNotEmpty() }) {
                    val w = raw.trim { !it.isLetterOrDigit() && it != '\'' }.trim('\'')
                    val endsSentence = raw.trimEnd('"', ')', '\'').lastOrNull() in setOf('.', '!', '?')
                    if (w.isEmpty() || !w.all { it.isLetter() || it == '\'' } || w.length > 30) {
                        previous2 = previous; previous = null; start = endsSentence; continue
                    }
                    val kind = when {
                        start -> "start"
                        w.lowercase() in iForms -> "I"
                        w.length > 1 && w.all { !it.isLetter() || it.isUpperCase() } -> "all caps"
                        w[0].isUpperCase() -> "capitalised"
                        else -> "lowercase"
                    }
                    val lower = w.lowercase()
                    val word = CurrentWord().apply {
                        for ((i, c) in lower.withIndex()) {
                            val letter = if (i == 0 && start) c.uppercaseChar() else c
                            val tap = keys[c]?.let { Tap(it.centerX, it.centerY) }
                            addLetter(letter, tap, keys, params, shiftFlag = if (i == 0 && start) CaseAdapt.AUTOSHIFTED else 0,
                                context = CaseAdapt.Context.AUTOSHIFT)
                        }
                    }
                    val candidates = TypingEngine.candidates(word, previous ?: Bigrams.START, previous2 = previous2)
                    val out = TypingEngine.correction(word, candidates, commit = true) ?: word.text
                    val t = tally.getValue(kind)
                    t.n++
                    when {
                        out == w -> t.right++
                        !out.equals(w, ignoreCase = true) -> t.otherWord++
                        else -> misses.add("$kind\t$w\t$out\t${line.take(80)}")
                    }
                    previous2 = previous; previous = lower; start = endsSentence
                }
            }
        } finally {
            TypingEngine.unloadForTest()
        }
        for ((kind, t) in tally) {
            val caseWrong = t.n - t.right - t.otherWord
            println("CAPS %-12s %6d words: right %5.1f %%, wrong capitals %5.1f %%, changed to another word %4.1f %%".format(
                kind, t.n, 100.0 * t.right / t.n, 100.0 * caseWrong / t.n, 100.0 * t.otherWord / t.n))
        }
        System.getenv("DOGBOARD_CAPS_MISSES")?.let { File(it).writeText(misses.joinToString("\n", postfix = "\n")) }
    }

    test("probe short capitalised words").config(enabled = System.getenv("DOGBOARD_PROBE") == "1") {
        val keys = QwertyGeometry.keysWithSpace(411f, 216f)
        val params = GaussianTouch.params(medianKeyHalfWidth = 411 / 20.0, typingInterval = 0.2)
        TypingEngine.unloadForTest()
        TypingEngine.load(
            openLexicon = { File("src/main/assets/dogboard/en.lexicon.tsv").bufferedReader() },
            openBigrams = { File("src/main/assets/dogboard/en.bigrams.tsv").bufferedReader() },
        )
        File("src/main/assets/dogboard/en.charlm.bin").inputStream().use { CharModel.read(it) }?.let { TypingEngine.attachCharModel(it) }
        try {
            for ((prev, w) in listOf("to" to "mr", "to" to "dr", "was" to "ok", "to" to "mrs", "on" to "friday", "in" to "london")) {
                val word = CurrentWord().apply { for (c in w) addLetter(c, keys.getValue(c).let { Tap(it.centerX, it.centerY) }, keys, params, context = CaseAdapt.Context.AUTOSHIFT) }
                val cands = TypingEngine.candidates(word, prev)
                println("PROBE-CAPS %s %s -> %s   %s".format(prev, w, TypingEngine.correction(word, cands, commit = true),
                    cands.take(4).joinToString(" | ") { "%s %.2f%s".format(it.word, it.score, if (it.isTypedString) " typed" else "") }))
            }
        } finally { TypingEngine.unloadForTest() }
    }
})
