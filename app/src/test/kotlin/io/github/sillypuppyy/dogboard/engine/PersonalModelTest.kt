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

    test("two words of context: triples tell apart what one word can't") {
        val m = fresh()
        // after "to", the user writes "see" in "want to see" but "go" in "have to go"
        repeat(10) { m.accept("to", "see", "want") }
        repeat(10) { m.accept("to", "go", "have") }
        m.probability("to", "see", "want") shouldBeGreaterThan m.probability("to", "go", "want")
        m.probability("to", "go", "have") shouldBeGreaterThan m.probability("to", "see", "have")
        // one word of context alone: the two are even
        m.probability("to", "see") shouldBe (m.probability("to", "go") plusOrMinus 1e-12)
        // an unseen two-word context falls back to the pair
        m.probability("to", "see", "need") shouldBe (m.probability("to", "see") plusOrMinus 1e-12)
    }
    test("triples are saved, loaded and forgotten with their words") {
        val f = File.createTempFile("personal", ".tsv").apply { deleteOnExit() }
        val m = fresh(f)
        repeat(3) { m.accept("to", "see", "want") }
        repeat(3) { m.accept("to", "go", "have") }
        m.save()
        val loaded = fresh(f)
        loaded.probability("to", "see", "want") shouldBeGreaterThan loaded.probability("to", "see")
        loaded.remove("want")
        loaded.probability("to", "see", "want") shouldBe (loaded.probability("to", "see") plusOrMinus 1e-12)
    }

    test("totals updated in place after each word match a rebuild from the saved file") {
        val f = kotlin.io.path.createTempFile("personal", ".tsv").toFile()
        val m = fresh(f)
        val text = "see you at the game tonight see you at the park see you soon at the game".split(' ')
        // query between accepts so the totals are built, then kept up to date in place
        for (i in text.indices) {
            m.accept(text.getOrNull(i - 1), text[i], text.getOrNull(i - 2))
            m.mix(0.01, "you", "at")
        }
        m.save(); awaitPendingWrites()
        val rebuilt = fresh(f)
        for ((prev, w, prev2) in listOf(Triple("you", "at", "see"), Triple("the", "game", "at"), Triple(null, "see", null), Triple("at", "the", "you"))) {
            m.mix(0.01, prev, w, prev2) shouldBe (rebuilt.mix(0.01, prev, w, prev2) plusOrMinus 1e-9)
        }
        f.delete()
    }
})
