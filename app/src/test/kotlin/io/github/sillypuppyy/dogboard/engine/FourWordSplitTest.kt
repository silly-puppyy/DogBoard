package io.github.sillypuppyy.dogboard.engine

import io.github.sillypuppyy.dogboard.touch.GaussianTouch
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * User report 2026-11-22: "check it out shortly" typed with the spaces landing on n and b ("checknitnoitbshortly")
 * was offered as "check it lightningbug": the decoder split a run of taps into at most three words. Four now
 * (Decoder.MAX_WORDS; error lists identical on the four benchmark sets, speed unchanged).
 */
class FourWordSplitTest : FunSpec({
    test("four words with the spaces on n and b") {
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
        try {
            val word = CurrentWord().apply {
                for (c in "checknitnoitbshortly") keys.getValue(c).let { addLetter(c, Tap(it.centerX, it.centerY), keys, params) }
            }
            val candidates = TypingEngine.candidates(word, "I'll")
            candidates.first().word shouldBe "check it out shortly"
        } finally {
            TypingEngine.unloadForTest()
        }
    }
})
