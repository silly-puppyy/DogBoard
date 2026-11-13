package io.github.sillypuppyy.dogboard.engine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files

class TextReplacementsTest : FunSpec({
    test("a new store has iOS's sample shortcut; matching ignores case") {
        val r = TextReplacements()
        r.phraseFor("omw") shouldBe "On my way!"
        r.phraseFor("OMW") shouldBe "On my way!"
        r.phraseFor("om").shouldBeNull()
    }
    test("shortcuts can't be empty or contain spaces") {
        val r = TextReplacements()
        r.put("", "x") shouldBe false
        r.put("o m w", "x") shouldBe false
        r.put("ty", "Thank you") shouldBe true
        r.phraseFor("ty") shouldBe "Thank you"
    }
    test("saved and loaded") {
        val f = File.createTempFile("repl", ".tsv").apply { delete(); deleteOnExit() }
        TextReplacements(f).apply { put("addr", "1 Dog Park Lane") }
        val loaded = TextReplacements(f)
        loaded.phraseFor("addr") shouldBe "1 Dog Park Lane"
        loaded.phraseFor("omw") shouldBe "On my way!"
        loaded.remove("omw")
        TextReplacements(f).phraseFor("omw").shouldBeNull()
    }
    test("the engine applies a shortcut before correcting, with the typed capitals") {
        val dir = Files.createTempDirectory("dogboard-repl").toFile()
        try {
            TypingEngine.unloadForTest()
            TypingEngine.load(
                openLexicon = { File("src/main/assets/dogboard/en.lexicon.tsv").bufferedReader() },
                textReplacements = File(dir, "text_replacements.tsv"),
            )
            TypingEngine.replacementFor("omw", CaseAdapt.inferredFlags("omw"), CaseAdapt.Context.NONE) shouldBe "On my way!"
            TypingEngine.replacementFor("OMW", CaseAdapt.inferredFlags("OMW"), CaseAdapt.Context.SHIFT) shouldBe "ON MY WAY!"
            TypingEngine.replacementFor("Omw", listOf(CaseAdapt.AUTOSHIFTED, 0, 0), CaseAdapt.Context.AUTOSHIFT) shouldBe "On my way!"
            TypingEngine.replacementFor("teh", CaseAdapt.inferredFlags("teh"), CaseAdapt.Context.NONE).shouldBeNull()
        } finally {
            TypingEngine.unloadForTest()
            dir.deleteRecursively()
        }
    }
})
