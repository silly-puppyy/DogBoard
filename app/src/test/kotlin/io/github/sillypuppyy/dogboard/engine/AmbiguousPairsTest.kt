package io.github.sillypuppyy.dogboard.engine

import io.github.sillypuppyy.dogboard.touch.GaussianTouch
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

private val KEYS = QwertyGeometry.letterKeys(375f, 216f)
private val PARAMS = GaussianTouch.params(medianKeyHalfWidth = 18.75, typingInterval = 0.3)

/** Letters tapped on their key centres; a hyphen comes from the symbol keys (no letter-key tap). */
private fun typed(text: String) = CurrentWord().apply {
    for (c in text) addLetter(c, KEYS[c.lowercaseChar()]?.let { Tap(it.centerX, it.centerY) }, KEYS, PARAMS)
}

private fun finished(text: String): String {
    val word = typed(text)
    return TypingEngine.correction(word, TypingEngine.candidates(word, null)) ?: text
}

/**
 * Both spellings of a genuinely ambiguous pair are words with their own meaning (resign / re-sign, everyday / every
 * day): whichever the user typed, exactly, stays (checklist A4). The two-word forms are their parts, typed one by one.
 */
class AmbiguousPairsTest : FunSpec({
    test("each spelling of an ambiguous pair, typed exactly, stays as typed") {
        TypingEngine.unloadForTest()
        TypingEngine.load(openLexicon = { File("src/main/assets/dogboard/en.lexicon.tsv").bufferedReader() })
        try {
            val words = listOf(
                "resign", "re-sign", "recover", "re-cover", "recreation", "re-creation", "coop", "co-op",
                "everyday", "every", "day", "anyone", "any", "one", "maybe", "may", "be", "sometime", "some", "time",
                "awhile", "a", "while", "onto", "on", "to", "already", "all", "ready",
            )
            val changed = words.map { it to finished(it) }.filter { (w, got) -> got != w }
            println("ambiguous pairs changed: $changed")
            changed shouldBe emptyList()
        } finally {
            TypingEngine.unloadForTest()
        }
    }
})
