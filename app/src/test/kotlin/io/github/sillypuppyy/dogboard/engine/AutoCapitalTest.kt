package io.github.sillypuppyy.dogboard.engine

import io.github.sillypuppyy.dogboard.touch.GaussianTouch
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

private val KEYS = QwertyGeometry.letterKeys(375f, 216f)
private val PARAMS = GaussianTouch.params(medianKeyHalfWidth = 18.75, typingInterval = 0.3)

/** [text] tapped on key centres; its first capital set by the keyboard ([auto]) or by the user's shift. */
private fun typed(text: String, auto: Boolean) = CurrentWord().apply {
    for ((i, c) in text.withIndex()) {
        val flag = when {
            i == 0 && auto && c.isUpperCase() -> CaseAdapt.AUTOSHIFTED
            c.isUpperCase() -> CaseAdapt.SHIFTED
            else -> 0
        }
        addLetter(c, KEYS[c.lowercaseChar()]?.let { Tap(it.centerX, it.centerY) }, KEYS, PARAMS, flag,
            if (i == 0 && auto) CaseAdapt.Context.AUTOSHIFT else null)
    }
}

private fun finished(text: String, auto: Boolean): String {
    val word = typed(text, auto)
    return TypingEngine.correction(word, TypingEngine.candidates(word, Bigrams.START)) ?: text
}

/** A capital the keyboard added at a sentence start: "Aldo" (the user meant "Also", 2026-11-21) still corrects (the full JVM engine does; the phone kept "Aldo", unreproduced: likely the neural path). */
class AutoCapitalTest : FunSpec({
    test("Aldo at a sentence start, capitalised by the keyboard, is also") {
        TypingEngine.unloadForTest()
        // as the app loads it: word pairs, trigram (where present), abbreviations, misspellings, letter model
        val a = "src/main/assets/dogboard/"
        TypingEngine.load(
            openLexicon = { File(a + "en.lexicon.tsv").bufferedReader() },
            openBigrams = { File(a + "en.bigrams.tsv").bufferedReader() },
            openAbbreviations = { File(a + "en.abbreviations.txt").bufferedReader() },
            openNoSuggest = { File(a + "en.nosuggest.txt").bufferedReader() },
            openMisspellings = { File(a + "en.misspellings.txt").bufferedReader() },
        )
        TypingEngine.attachCharModel(File(a + "en.charlm.bin").inputStream().use { CharModel.read(it) })
        File(a + "en.ngrams.bin").takeIf { it.isFile }?.let { f -> TypingEngine.attachNgrams(f.inputStream().use { NgramModel.readBinary(it) }) }
        try {
            finished("Aldo", auto = true) shouldBe "Also"
            // the user's "ie" became "or" (2026-11-21): a full stop is skipped like an apostrophe, as iOS folds it out of
            // its sort key, so "ie" reaches "i.e."; "am" stays (a.m. is rarer than the word)
            finished("ie", auto = false) shouldBe "i.e."
            finished("am", auto = false) shouldBe "am"
        } finally {
            TypingEngine.unloadForTest()
        }
    }
})
