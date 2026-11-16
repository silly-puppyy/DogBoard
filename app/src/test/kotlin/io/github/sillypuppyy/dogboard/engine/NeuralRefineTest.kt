package io.github.sillypuppyy.dogboard.engine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

class NeuralRefineTest : FunSpec({
    fun c(word: String, geometry: Double, lm: Double) =
        Candidate(word, geometry + 0.24 * lm, geometry, lm, edits = 0, isCompletion = false)

    test("context the neural model is sure of reorders the candidates") {
        // typed "on" in "I live ? London": the n-gram slightly prefers "on", the neural model "in"
        val typed = c("on", 0.0, -6.0)
        val other = c("in", -2.0, -6.5)
        val out = withNeural(listOf(typed, other), mapOf("on" to -20.0, "in" to -1.0)::get, 0.5, 0.24)
        out.first().word shouldBe "in"
        out.first().languageModel shouldBe (0.5 * -6.5 + 0.5 * -1.0 plusOrMinus 1e-9)
        out.first().score shouldBe (-2.0 + 0.24 * (0.5 * -6.5 + 0.5 * -1.0) plusOrMinus 1e-9)
    }

    test("a candidate without a neural score keeps its n-gram score") {
        val a = c("cat", 0.0, -5.0)
        val out = withNeural(listOf(a), { null }, 0.5, 0.24)
        out.single() shouldBe a
    }
})
