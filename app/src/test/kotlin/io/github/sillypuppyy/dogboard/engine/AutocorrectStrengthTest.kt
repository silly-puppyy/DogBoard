package io.github.sillypuppyy.dogboard.engine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

class AutocorrectStrengthTest : FunSpec({
    val standard = Autocorrect.Level.STANDARD

    test("no history: the chosen level unchanged") {
        AutocorrectStrength().adjust(standard) shouldBe standard
    }

    test("a few reverts change nothing (dead zone); a clear pattern does") {
        val s = AutocorrectStrength()
        repeat(5) { s.reverted() }
        s.adjust(standard) shouldBe standard
        s.reverted()
        (s.adjust(standard).leadOverTyped > standard.leadOverTyped) shouldBe true
    }

    test("30 % undone: no change for the first ~20 corrections, full effect after ~100") {
        val s = AutocorrectStrength()
        fun tenCorrections() { repeat(3) { s.reverted() }; repeat(7) { s.kept() } }
        repeat(2) { tenCorrections() }
        s.adjust(standard) shouldBe standard
        repeat(8) { tenCorrections() }
        s.adjust(standard).leadOverTyped shouldBe (1.65 plusOrMinus 0.03) // at the cap, less the last few kept
    }

    test("reverts make it more careful, picked fixes bolder, within Strong and Gentle") {
        val s = AutocorrectStrength()
        repeat(50) { s.reverted() }
        s.score shouldBe AutocorrectStrength.SCORE_LIMIT
        s.adjust(standard).leadOverTyped shouldBe (1.65 plusOrMinus 0.01)
        repeat(200) { s.fixPicked() }
        s.score shouldBe -AutocorrectStrength.SCORE_LIMIT
        s.adjust(standard).leadOverTyped shouldBe (1.17 plusOrMinus 0.01)
        // never past Strong (1.0) or Gentle (2.0) from Standard
        (s.adjust(standard).leadOverTyped > 1.0) shouldBe true
    }

    test("a steady 10 % revert rate holds the score still") {
        val s = AutocorrectStrength()
        repeat(20) {
            s.reverted()
            repeat(9) { s.kept() }
        }
        s.score shouldBe (0.0 plusOrMinus 1e-9)
    }

    test("a correction deleted right away counts half a revert") {
        val s = AutocorrectStrength()
        s.deletedAfterCorrection()
        s.score shouldBe 0.5
    }

    test("the score is kept across restarts") {
        val f = kotlin.io.path.createTempFile("strength", ".txt").toFile()
        AutocorrectStrength(f).apply { reverted(); reverted() }
        AutocorrectStrength(f).score shouldBe 2.0
        f.delete()
    }

    test("the step the user is told about: halfway to full effect, back only inside the dead zone, no flicker") {
        val s = AutocorrectStrength()
        val heard = ArrayList<Int>()
        s.onStepChange = { heard.add(it) }
        repeat(12) { s.reverted() }            // score 12: past the dead zone, not yet halfway
        heard shouldBe emptyList()
        s.reverted()                            // 13 >= 12.5: more careful
        heard shouldBe listOf(1); s.step shouldBe 1
        repeat(60) { s.kept() }                 // 13 - 6.7 = 6.3: still adjusting, no message
        heard shouldBe listOf(1)
        repeat(20) { s.kept() }                 // 6.3 - 2.2 = 4.1: inside the dead zone: back
        heard shouldBe listOf(1, 0); s.step shouldBe 0
        repeat(18) { s.fixPicked() }            // to -13.9: bolder
        heard shouldBe listOf(1, 0, -1)
    }

    test("the step is kept across restarts") {
        val f = kotlin.io.path.createTempFile("strength", ".txt").toFile()
        AutocorrectStrength(f).apply { repeat(13) { reverted() } }
        AutocorrectStrength(f).let { it.step shouldBe 1; it.score shouldBe 13.0 }
        f.delete()
    }
})
