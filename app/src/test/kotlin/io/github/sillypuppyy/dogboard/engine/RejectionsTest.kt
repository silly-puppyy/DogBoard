package io.github.sillypuppyy.dogboard.engine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/** iOS rejection rules (Rejections.kt) on a fake clock. */
class RejectionsTest : FunSpec({
    val day = 86_400_000L
    var t = 1_000_000_000_000L
    fun fresh(file: File? = null) = Rejections(file) { t }

    test("one undo blocks for under a day") {
        val r = fresh()
        r.recordHard("teh", "the")
        t += day / 2
        r.isBlocked("teh", "the") shouldBe true
        t += day
        r.isBlocked("teh", "the") shouldBe false
    }
    test("each further undo stretches the block: 4, 16, 64 days, then 180") {
        val r = fresh()
        repeat(2) { r.recordHard("dont", "don't") }
        t += 3 * day; r.isBlocked("dont", "don't") shouldBe true
        t += 2 * day; r.isBlocked("dont", "don't") shouldBe false
        r.recordHard("dont", "don't") // 3 undos, counted from this one
        t += 15 * day; r.isBlocked("dont", "don't") shouldBe true
        t += 2 * day; r.isBlocked("dont", "don't") shouldBe false
        repeat(3) { r.recordHard("dont", "don't") } // 6 undos: capped at 180 days
        t += 179 * day; r.isBlocked("dont", "don't") shouldBe true
        t += 2 * day; r.isBlocked("dont", "don't") shouldBe false
    }
    test("a correction made and kept forgives earlier undos") {
        val r = fresh()
        repeat(3) { r.recordHard("hte", "the") }
        r.recordKept("hte", "the")
        r.hardCount("hte", "the") shouldBe 0
        r.isBlocked("hte", "the") shouldBe false
    }
    test("deleting a word soon after it was autocorrected blocks that correction for 2 minutes") {
        val r = fresh()
        r.noteCorrected("bery", "very")
        r.noteDeleted("very")
        t += 60_000
        r.isBlocked("bery", "very") shouldBe true
        t += 61_000
        r.isBlocked("bery", "very") shouldBe false
    }
    test("deleting an ordinary word blocks nothing") {
        val r = fresh()
        r.noteDeleted("very")
        r.isBlocked("bery", "very") shouldBe false
    }
    test("soft limits: 1 for one-letter pairs, 3 for case/accent/punctuation-only, else 20") {
        Rejections.softLimit("i", "I") shouldBe 1
        Rejections.softLimit("cafe", "café") shouldBe 3
        Rejections.softLimit("dont", "don't") shouldBe 3
        Rejections.softLimit("bery", "very") shouldBe 20
        val r = fresh()
        r.noteCorrected("cafe", "café"); r.noteDeleted("café")
        // the suggestion bar's checks while typing use up nothing
        repeat(10) { r.isBlocked("cafe", "café") shouldBe true }
        r.isBlocked("cafe", "café", consume = true) shouldBe true
        r.isBlocked("cafe", "café", consume = true) shouldBe true
        r.isBlocked("cafe", "café", consume = true) shouldBe true
        r.isBlocked("cafe", "café", consume = true) shouldBe false // limit 3 used up by finished words
    }
    test("only the last 3 deletions are remembered") {
        val r = fresh()
        for ((typed, word) in listOf("a1" to "aa", "b1" to "bb", "c1" to "cc", "d1" to "dd")) {
            r.noteCorrected(typed, word); t += 1000; r.noteDeleted(word)
        }
        r.isBlocked("a1", "aa") shouldBe false
        r.isBlocked("d1", "dd") shouldBe true
    }
    test("saved and loaded; the old two-column file becomes one undo") {
        val f = File.createTempFile("rejections", ".tsv").apply { deleteOnExit() }
        fresh(f).apply { recordHard("teh", "the"); recordHard("teh", "the") }
        fresh(f).hardCount("teh", "the") shouldBe 2
        f.writeText("hrllo\thello\n")
        fresh(f).hardCount("hrllo", "hello") shouldBe 1
    }
    test("kept corrections and expired undos leave no rows behind") {
        val f = File.createTempFile("rejections", ".tsv").apply { deleteOnExit() }
        val r = Rejections(f) { t }
        repeat(500) { r.recordKept("w$it", "word$it") }
        r.recordHard("teh", "the")
        f.readLines().size shouldBe 1
        t += 200 * day
        r.recordHard("hte", "the") // saves: the expired "teh" row is dropped
        f.readLines().map { it.substringBefore('\t') } shouldBe listOf("hte")
    }
    test("a rule added in settings never expires, survives a reload, and can be removed") {
        val f = File.createTempFile("rejections", ".tsv")
        val r = Rejections(f) { t }
        r.neverCorrect("Qwopz", "Quips")
        t += 400 * day
        r.isBlocked("Qwopz", "Quips") shouldBe true
        Rejections(f) { t }.isBlocked("Qwopz", "Quips") shouldBe true
        r.restore("Qwopz", "Quips")
        r.isBlocked("Qwopz", "Quips") shouldBe false
        f.delete()
    }
})
