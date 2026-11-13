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

    test("a removed word is never suggested or corrected to, is unlearned, and stays removed") {
        val dir = Files.createTempDirectory("dogboard-block").toFile()
        val userWords = File(dir, "user_words.txt")
        val blockedWords = File(dir, "blocked_words.txt")
        fun load() = TypingEngine.load(
            openLexicon = { File("src/main/assets/dogboard/en.lexicon.tsv").bufferedReader() },
            userWords = userWords,
            blockedWords = blockedWords,
        )
        try {
            TypingEngine.unloadForTest()
            load()
            finished("thibk") shouldBe "think"
            TypingEngine.block("think")
            TypingEngine.candidates(typed("thibk"), null).none { it.word == "think" } shouldBe true
            (finished("thibk") == "think") shouldBe false

            TypingEngine.learn("zorbit")
            TypingEngine.block("Zorbit")
            userWords.readLines() shouldBe emptyList()

            TypingEngine.unloadForTest()
            load()
            TypingEngine.candidates(typed("thibk"), null).none { it.word == "think" } shouldBe true
        } finally {
            TypingEngine.unloadForTest()
            dir.deleteRecursively()
        }
    }

    test("an unknown word kept three times is learned") {
        val dir = Files.createTempDirectory("dogboard-kept").toFile()
        try {
            TypingEngine.unloadForTest()
            TypingEngine.load(
                openLexicon = { File("src/main/assets/dogboard/en.lexicon.tsv").bufferedReader() },
                userWords = File(dir, "user_words.txt"),
            )
            TypingEngine.isKnownWord("zorbit") shouldBe false
            TypingEngine.noteKept("zorbit")
            TypingEngine.noteKept("zorbit")
            TypingEngine.isKnownWord("zorbit") shouldBe false
            TypingEngine.noteKept("zorbit")
            TypingEngine.isKnownWord("zorbit") shouldBe true
        } finally {
            TypingEngine.unloadForTest()
            dir.deleteRecursively()
        }
    }
})
