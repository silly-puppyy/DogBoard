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

    test("full weight from the first word: 0.65 with context, 0.45 without") {
        val m = fresh()
        m.weight(true) shouldBe 0.0
        m.accept(null, "word")
        m.weight(true) shouldBe 0.65
        m.weight(false) shouldBe 0.45
    }
    test("one use moves nothing; the second does (iOS count thresholds)") {
        val m = fresh()
        repeat(60) { m.accept(null, "filler") }
        m.accept("the", "dogpark")
        m.probability(null, "dogpark") shouldBe 0.0
        m.probability("the", "dogpark") shouldBe 0.0
        m.accept("the", "dogpark")
        m.probability("the", "dogpark") shouldBeGreaterThan 0.5
        m.probability(null, "dogpark") shouldBeGreaterThan 0.0
    }
    test("words count only once the model holds 50 uses") {
        val m = fresh()
        repeat(10) { m.accept(null, "woof") }
        m.probability(null, "woof") shouldBe 0.0
        repeat(41) { m.accept(null, "bark") }
        m.probability(null, "woof") shouldBe (10.0 / 51 plusOrMinus 1e-9)
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
        repeat(60) { m.accept(null, "filler") }
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
        repeat(50) { m.accept(null, "other") } // so a lower count shows as a lower share (words count from 50 uses)
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
        loaded.probability("hi", "there") shouldBe (0.5 plusOrMinus 1e-9) // the pair (1.0) of two orders; too few uses for words
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
        // an unseen two-word context still counts as an order (iOS normalises over every order asked)
        m.probability("to", "see", "need") shouldBe (m.probability("to", "see") * 2 / 3 plusOrMinus 1e-12)
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
        loaded.probability("to", "see", "want") shouldBe (loaded.probability("to", "see", "need") plusOrMinus 1e-12)
    }

    test("the decoder's shortcut for words never typed gives the full mix's value") {
        val lexicon = Lexicon.of(mapOf("park" to 50L, "dog" to 80L, "the" to 500L, "Paris" to 20L, "café" to 5L, "zebra" to 3L))
        val m = fresh()
        repeat(60) { m.accept("the", "dog") }
        repeat(3) { m.accept("dog", "park", "the") }
        m.accept(null, "paris"); m.accept(null, "cafe")
        for ((prev, prev2) in listOf(null to null, "the" to null, "dog" to "the")) {
            val hook = m.decoderMix(lexicon, prev, prev2)
            for (w in listOf("park", "dog", "the", "Paris", "café", "zebra")) {
                val node = lexicon.find(w)
                for (lp in listOf(-1.0, -7.5, -30.0, -800.0)) {
                    hook.logProb(node, lp) shouldBe (m.fullMix(lexicon, node, lp, prev, prev2) plusOrMinus 1e-9)
                }
            }
        }
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
