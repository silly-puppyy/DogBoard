package io.github.sillypuppyy.dogboard.engine

import io.github.sillypuppyy.dogboard.touch.GaussianTouch
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files

private val KEYS = QwertyGeometry.letterKeys(375f, 216f)
private val PARAMS = GaussianTouch.params(medianKeyHalfWidth = 18.75, typingInterval = 0.3)

private fun typed(letters: String) = CurrentWord().apply {
    for (c in letters) {
        val k = KEYS.getValue(c.lowercaseChar())
        addLetter(c, Tap(k.centerX, k.centerY), KEYS, PARAMS)
    }
}

private fun finished(letters: String): String {
    val word = typed(letters)
    return TypingEngine.correction(word, TypingEngine.candidates(word, null)) ?: letters
}

class LearningTest : FunSpec({
    test("a learned word is kept, suggested, and remembered across loads") {
        val dir = Files.createTempDirectory("dogboard-learn").toFile()
        val userWords = File(dir, "user_words.txt")
        fun load() = TypingEngine.load(
            openLexicon = { File("src/main/assets/dogboard/en.lexicon.tsv").bufferedReader() },
            userWords = userWords,
        )
        try {
            TypingEngine.unloadForTest()
            load()
            finished("tehteh") shouldBe "tehteh"
            finished("thibk") shouldBe "think" // one key off a common word: corrected

            TypingEngine.learn("thibk")
            finished("thibk") shouldBe "thibk"
            TypingEngine.candidates(typed("thib"), null).any { it.word == "thibk" } shouldBe true
            userWords.readLines() shouldBe listOf("thibk")

            TypingEngine.learn("thibk") // already known: not written twice
            userWords.readLines() shouldBe listOf("thibk")

            TypingEngine.unloadForTest()
            load()
            finished("thibk") shouldBe "thibk"
        } finally {
            TypingEngine.unloadForTest()
            dir.deleteRecursively()
        }
    }

    test("words with symbols or digits are not learned") {
        val dir = Files.createTempDirectory("dogboard-learn").toFile()
        val userWords = File(dir, "user_words.txt")
        try {
            TypingEngine.unloadForTest()
            TypingEngine.load(
                openLexicon = { File("src/main/assets/dogboard/en.lexicon.tsv").bufferedReader() },
                userWords = userWords,
            )
            TypingEngine.learn("abc123")
            TypingEngine.learn("a b")
            userWords.exists() shouldBe false
        } finally {
            TypingEngine.unloadForTest()
            dir.deleteRecursively()
        }
    }
})
