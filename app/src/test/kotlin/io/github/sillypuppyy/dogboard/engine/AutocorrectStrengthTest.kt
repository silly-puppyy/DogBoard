package io.github.sillypuppyy.dogboard.engine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

class AutocorrectStrengthTest : FunSpec({
    val standard = Autocorrect.Level.STANDARD

    test("no history: the chosen level unchanged") {
        AutocorrectStrength().adjust(standard) shouldBe standard
    }

    test("reverts make it more careful, picked fixes bolder, within Strong and Gentle") {
        val s = AutocorrectStrength()
        repeat(50) { s.reverted() }
        s.score shouldBe AutocorrectStrength.SCORE_LIMIT
        s.adjust(standard).leadOverTyped shouldBe (1.65 plusOrMinus 0.01)
        repeat(100) { s.fixPicked() }
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
})
