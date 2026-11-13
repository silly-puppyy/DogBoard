package io.github.sillypuppyy.dogboard.engine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.io.File

/** The personal model's iOS numbers (PersonalModel.kt) on a fake clock. */
class PersonalModelTest : FunSpec({
    val day = 86_400_000L
    var t = 1_000_000_000_000L
    fun fresh(file: File? = null) = PersonalModel(file) { t }

    test("weight ramps over the first 1000 words to 0.65 with context, 0.45 without") {
        val m = fresh()
        m.weight(true) shouldBe 0.0
        repeat(500) { m.accept(null, "word") }
        m.weight(true) shouldBe (0.65 * 0.25 plusOrMinus 1e-9)
        repeat(600) { m.accept(null, "word") }
        m.weight(true) shouldBe 0.65
        m.weight(false) shouldBe 0.45
    }
    test("words the user uses get probability; pairs follow context") {
        val m = fresh()
        repeat(10) { m.accept("dog", "park") }
        repeat(10) { m.accept("the", "dog") }
        m.probability("dog", "park") shouldBeGreaterThan m.probability("dog", "dog")
        m.probability(null, "zebra") shouldBe 0.0
    }
    test("counts fade with a 99-day half-life") {
        val m = fresh()
        repeat(4) { m.accept(null, "old") }
        t += 99 * day
        repeat(2) { m.accept(null, "new") }
        // old: 4 * exp(-0.007 * 99) = 2.0; new: 2
        m.probability(null, "old") shouldBe (m.probability(null, "new") plusOrMinus 0.01)
    }
    test("mixing: no effect before any typing, the personal share after") {
        val m = fresh()
        m.mix(0.01, "the", "dogpark") shouldBe 0.01
        repeat(1000) { m.accept("the", "dogpark") }
        m.mix(0.0, "the", "dogpark") shouldBeGreaterThan 0.6
    }
    test("forgetting: rare words go, often-used words are only lowered") {
        val m = fresh()
        repeat(3) { m.accept(null, "typo") }
        repeat(10) { m.accept(null, "dogboard") }
        repeat(10) { m.accept(null, "other") } // so a lower count shows as a lower share
        m.forget("typo")
        m.knows("typo") shouldBe false
        val before = m.probability(null, "dogboard")
        m.forget("dogboard")
        m.knows("dogboard") shouldBe true
        m.probability(null, "dogboard") shouldBeLessThan before
    }
    test("saved and loaded") {
        val f = File.createTempFile("personal", ".tsv").apply { deleteOnExit() }
        fresh(f).apply { repeat(25) { accept("hi", "there") }; save() }
        val loaded = fresh(f)
        loaded.wordsTyped shouldBe 25L
        loaded.probability("hi", "there") shouldBeGreaterThan 0.5
    }
})
