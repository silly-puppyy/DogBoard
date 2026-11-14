package io.github.sillypuppyy.dogboard.engine

import io.github.sillypuppyy.dogboard.touch.GaussianTouch
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.io.File

private val KEYS = QwertyGeometry.letterKeys(375f, 216f)
private val PARAMS = GaussianTouch.params(medianKeyHalfWidth = 18.75, typingInterval = 0.3)

private fun typed(w: String) = CurrentWord().apply {
    for (c in w) addLetter(c, KEYS[c.lowercaseChar()]?.let { Tap(it.centerX, it.centerY) }, KEYS, PARAMS)
}

/** The unknown-word letter model (en.charlm.tsv) and the typed-string veto it enables. */
class CharModelTest : FunSpec({
    val asset = File("src/main/assets/dogboard/en.charlm.tsv")

    test("word-like strings score better per letter than typos and mashing") {
        val m = asset.bufferedReader().use { CharModel.parse(it) }!!
        fun perLetter(w: String) = m.logProb(w)!! / (w.length + 1)
        m.unknownLogProb shouldBeLessThan -2.0
        for (good in listOf("kaito", "kubernetes", "zendaya")) for (bad in listOf("thw", "wnat", "xqzt")) {
            perLetter(good) shouldBeGreaterThan perLetter(bad)
        }
        m.logProb("caf3") shouldBe null // letters it doesn't know: no score
    }

    test("an unknown word typed exactly stays; a typo of a known word is still fixed") {
        TypingEngine.unloadForTest()
        TypingEngine.load(
            openLexicon = { File("src/main/assets/dogboard/en.lexicon.tsv").bufferedReader() },
            openBigrams = { File("src/main/assets/dogboard/en.bigrams.tsv").bufferedReader() },
            openCharModel = { asset.bufferedReader() },
        )
        try {
            fun finished(w: String, before: String) = typed(w).let { TypingEngine.correction(it, TypingEngine.candidates(it, before)) ?: w }
            finished("Kaito", "with") shouldBe "Kaito"
            finished("Kubernetes", "the") shouldBe "Kubernetes"
            finished("Gumroad", "on") shouldBe "Gumroad"
            finished("thw", "is") shouldBe "the"
            finished("becuase", "just") shouldBe "because"
        } finally {
            TypingEngine.unloadForTest()
        }
    }
})
