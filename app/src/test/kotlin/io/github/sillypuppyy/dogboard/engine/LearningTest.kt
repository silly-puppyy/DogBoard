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
            // iOS filter 21: not offered for other input until it has been used twice
            TypingEngine.candidates(typed("thib"), null).any { it.word == "thibk" } shouldBe false
            TypingEngine.accepted(null, "thibk"); TypingEngine.accepted(null, "thibk")
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

    test("iOS negative learning: undoing a correction into a little-used learned word forgets it") {
        val dir = Files.createTempDirectory("dogboard-neg").toFile()
        val userWords = File(dir, "user_words.txt")
        try {
            TypingEngine.unloadForTest()
            TypingEngine.load(openLexicon = { File("src/main/assets/dogboard/en.lexicon.tsv").bufferedReader() }, userWords = userWords)
            TypingEngine.learn("comman")
            userWords.readLines() shouldBe listOf("comman")
            TypingEngine.reject("commen", "comman") // the user undid a correction into the learned typo
            val end = System.currentTimeMillis() + 3000
            while (userWords.readLines().isNotEmpty() && System.currentTimeMillis() < end) Thread.sleep(20)
            userWords.readLines() shouldBe emptyList()
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

    test("texting words stay as typed, not recased to an acronym (Hm, bc, rn)") {
        // user 2026-11-19: "Hm" at a sentence start became "HM" (the lexicon held only the acronym casing)
        TypingEngine.unloadForTest()
        TypingEngine.load(openLexicon = { File("src/main/assets/dogboard/en.lexicon.tsv").bufferedReader() })
        try {
            finished("Hm") shouldBe "Hm"
            finished("bc") shouldBe "bc"
            finished("rn") shouldBe "rn"
            finished("FBI") shouldBe "FBI"
            // an ampersand word is reached from its letters (the decoder steps over '&'), offered, not forced
            TypingEngine.candidates(typed("hm"), null).map { it.word }.contains("H&M") shouldBe true
            TypingEngine.candidates(typed("att"), null).map { it.word }.contains("AT&T") shouldBe true
            finished("hm") shouldBe "hm"
        } finally {
            TypingEngine.unloadForTest()
        }
    }

    test("a lowercase word kept beside a capitalised-only one is learned in its casing") {
        // emulator 2026-11-19: "hom" (the lexicon has only the name "Hom") kept twice was then corrected to "Hom"
        val dir = Files.createTempDirectory("dogboard-casing").toFile()
        val userWords = File(dir, "user_words.txt")
        fun load() = TypingEngine.load(openLexicon = { File("src/main/assets/dogboard/en.lexicon.tsv").bufferedReader() }, userWords = userWords)
        try {
            TypingEngine.unloadForTest()
            load()
            TypingEngine.accepted("going", "hom")
            TypingEngine.accepted("going", "hom")
            TypingEngine.candidates(typed("hom"), "going").map { it.word }.contains("hom") shouldBe true
            TypingEngine.candidates(typed("hom"), "going").first().word shouldBe "hom"
            typed("hom").let { TypingEngine.correction(it, TypingEngine.candidates(it, "going")) } shouldBe null
            userWords.readLines() shouldBe listOf("hom")
            TypingEngine.unloadForTest()
            load()
            TypingEngine.candidates(typed("hom"), "going").any { it.word == "hom" } shouldBe true
            // a capitalised word kept (a sentence start) adds nothing
            TypingEngine.accepted(null, "Going")
            userWords.readLines() shouldBe listOf("hom")
        } finally {
            TypingEngine.unloadForTest()
            dir.deleteRecursively()
        }
    }

    test("iOS #24: an added word isn't offered for typing whose non-letter core differs") {
        val dir = Files.createTempDirectory("dogboard-added").toFile()
        try {
            TypingEngine.unloadForTest()
            TypingEngine.load(
                openLexicon = { File("src/main/assets/dogboard/en.lexicon.tsv").bufferedReader() },
                userWords = File(dir, "user_words.txt"), addedWords = File(dir, "added_words.txt"),
            )
            TypingEngine.addWord("zork'ql")
            // the added word has an apostrophe the typing lacks: never the correction
            (finished("zorkql") == "zork'ql") shouldBe false
            TypingEngine.addWord("blorptastic")
            finished("blorptastuc") shouldBe "blorptastic" // same core (none): a slip is still fixed
        } finally {
            TypingEngine.unloadForTest()
            dir.deleteRecursively()
        }
    }
})
