package io.github.sillypuppyy.dogboard.engine

import io.github.sillypuppyy.dogboard.touch.GaussianTouch
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private val KEYS = QwertyGeometry.letterKeys(375f, 216f)
private val PARAMS = GaussianTouch.params(medianKeyHalfWidth = 18.75, typingInterval = 0.3)

private fun typed(text: String) = CurrentWord().apply {
    for (c in text) addLetter(c, KEYS[c.lowercaseChar()]?.let { Tap(it.centerX, it.centerY) }, KEYS, PARAMS)
}

private fun finished(text: String): String {
    val word = typed(text)
    return TypingEngine.correction(word, TypingEngine.candidates(word, null)) ?: text
}

/** German umlauts typed as ae / oe / ue (rules.digraphs): "fuer" -> "für"; words that hold the letters stay. */
class DigraphTest : FunSpec({
    val words = mapOf("für" to 5000L, "Züge" to 300L, "Ziege" to 400L, "neue" to 2000L, "neu" to 3000L,
        "Feuer" to 500L, "Tür" to 800L, "Poet" to 100L, "schönen" to 600L, "schienen" to 700L, "fuer" to 20L, "Goethe" to 900L, "Göthe" to 30L)
    fun load(digraphs: Map<String, String>) {
        TypingEngine.unloadForTest()
        TypingEngine.load(
            openLexicon = { java.io.BufferedReader(java.io.StringReader(Lexicon.FORMAT_HEADER + "\n" + words.entries.joinToString("\n") { "${it.key}\t${it.value}" })) },
            rules = LanguageRules.ENGLISH.copy(digraphs = digraphs),
        )
    }

    test("digraph spellings reach the umlaut word; words with those letters stay") {
        load(mapOf("ä" to "ae", "ö" to "oe", "ü" to "ue"))
        try {
            finished("fuer") shouldBe "für"
            finished("Zuege") shouldBe "Züge"
            finished("schoenen") shouldBe "schönen"
            finished("tuer") shouldBe "Tür" // the stored capital, as haus -> Haus
            finished("neue") shouldBe "neue"
            finished("Feuer") shouldBe "Feuer"
            finished("Poet") shouldBe "Poet"
            finished("fuer") shouldBe "für" // a rare word of its own: für is 250x as frequent
            finished("Goethe") shouldBe "Goethe" // the more frequent spelling stays
        } finally {
            TypingEngine.unloadForTest()
        }
    }

    test("a pack's rules.json carries the digraphs") {
        LanguageRules.parse("""{"distinct_letters": "", "digraphs": {"ä": "ae", "ö": "oe", "ü": "ue"}}""").digraphs shouldBe
            mapOf("ä" to "ae", "ö" to "oe", "ü" to "ue")
        LanguageRules.parse("""{"distinct_letters": ""}""").digraphs shouldBe emptyMap()
    }

    test("without the rule nothing changes for words that hold the letters") {
        load(emptyMap())
        try {
            finished("neue") shouldBe "neue"
            finished("Feuer") shouldBe "Feuer"
        } finally {
            TypingEngine.unloadForTest()
        }
    }
})
