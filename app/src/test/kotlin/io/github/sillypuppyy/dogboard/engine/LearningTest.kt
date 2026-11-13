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
            finished("thibk") shouldBe "think" // one key off a common word: corrected

            // iOS: undoing a correction rejects that pair; keeping the word learns it
            TypingEngine.reject("thibk", "think")
            TypingEngine.learn("thibk")
            finished("thibk") shouldBe "thibk"
            TypingEngine.candidates(typed("thib"), null).any { it.word == "thibk" } shouldBe true
            userWords.readLines() shouldBe listOf("thibk")

            TypingEngine.learn("thibk") // already known: not written twice
            userWords.readLines() shouldBe listOf("thibk")

            TypingEngine.unloadForTest()
            load()
            TypingEngine.isKnownWord("thibk") shouldBe true
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
            // iOS: one accept learns an unknown word (its strength then comes from use)
            TypingEngine.accepted("a", "zorbit")
            TypingEngine.isKnownWord("zorbit") shouldBe true
            // and it is still known after a restart
            TypingEngine.unloadForTest()
            TypingEngine.load(
                openLexicon = { File("src/main/assets/dogboard/en.lexicon.tsv").bufferedReader() },
                userWords = File(dir, "user_words.txt"),
            )
            TypingEngine.isKnownWord("zorbit") shouldBe true
        } finally {
            TypingEngine.unloadForTest()
            dir.deleteRecursively()
        }
    }
})

class ResetDictionaryTest : FunSpec({
    test("resetting the dictionary forgets learned words and rejections") {
        val dir = Files.createTempDirectory("dogboard-reset").toFile()
        val userWords = File(dir, "user_words.txt")
        try {
            TypingEngine.unloadForTest()
            TypingEngine.load(
                openLexicon = { File("src/main/assets/dogboard/en.lexicon.tsv").bufferedReader() },
                userWords = userWords,
                rejectedCorrections = File(dir, "rejected.txt"),
                personalModel = File(dir, "personal.tsv"),
                addedWords = File(dir, "added_words.txt"),
            )
            TypingEngine.learn("thibk")
            TypingEngine.addWord("Qwopz")
            TypingEngine.isKnownWord("thibk") shouldBe true
            TypingEngine.resetDictionary()
            TypingEngine.isKnownWord("thibk") shouldBe false
            userWords.exists() shouldBe false
            // added words go too: the list must not show words the engine no longer knows
            TypingEngine.dictionaryLists().added shouldBe emptyList()
            TypingEngine.isKnownWord("Qwopz") shouldBe false
        } finally {
            TypingEngine.unloadForTest()
            dir.deleteRecursively()
        }
    }
})

class ContactNamesTest : FunSpec({
    test("contact names are known words while set, and forgotten when removed") {
        try {
            TypingEngine.unloadForTest()
            TypingEngine.load(openLexicon = { File("src/main/assets/dogboard/en.lexicon.tsv").bufferedReader() })
            TypingEngine.isKnownWord("Zyxandra") shouldBe false
            TypingEngine.setContactNames(listOf("Zyxandra", "Quolby"))
            TypingEngine.isKnownWord("Zyxandra") shouldBe true
            TypingEngine.setContactNames(listOf("Quolby"))
            TypingEngine.isKnownWord("Zyxandra") shouldBe false
            TypingEngine.isKnownWord("Quolby") shouldBe true
        } finally {
            TypingEngine.unloadForTest()
        }
    }
})

class DictionaryListsTest : FunSpec({
    test("the settings lists: learned, added, blocked, undone, and taking each back") {
        val dir = Files.createTempDirectory("dogboard-lists").toFile()
        try {
            TypingEngine.unloadForTest()
            TypingEngine.load(
                openLexicon = { File("src/main/assets/dogboard/en.lexicon.tsv").bufferedReader() },
                userWords = File(dir, "user_words.txt"),
                blockedWords = File(dir, "blocked.txt"),
                rejectedCorrections = File(dir, "rejected.txt"),
                personalModel = File(dir, "personal.tsv"),
                addedWords = File(dir, "added.txt"),
            )
            TypingEngine.learn("thibk")
            TypingEngine.addWord("Zyxandra")
            TypingEngine.block("keyboards")
            TypingEngine.reject("teh", "the")
            var l = TypingEngine.dictionaryLists()
            l.learned.map { it.first } shouldBe listOf("thibk")
            l.added shouldBe listOf("Zyxandra")
            l.blocked shouldBe listOf("keyboards")
            l.undone.map { it.first to it.second } shouldBe listOf("teh" to "the")

            TypingEngine.forgetWord("thibk")
            TypingEngine.isKnownWord("thibk") shouldBe false
            TypingEngine.isKnownWord("Zyxandra") shouldBe true // other learned data survives the reload
            TypingEngine.unblock("keyboards")
            TypingEngine.restoreCorrection("teh", "the")
            l = TypingEngine.dictionaryLists()
            l.learned shouldBe emptyList()
            l.blocked shouldBe emptyList()
            l.undone shouldBe emptyList()
        } finally {
            TypingEngine.unloadForTest()
            dir.deleteRecursively()
        }
    }
})
