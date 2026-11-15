package io.github.sillypuppyy.dogboard.engine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

class PersonalOffsetsTest : FunSpec({
    val keys = QwertyGeometry.letterKeys(375f, 216f)
    val letters = "abcdefghijklmnopqrstuvwxyz"

    test("nothing moves until one full bucket (200 taps)") {
        val p = PersonalOffsets()
        repeat(199) { p.record(listOf(Triple(letters[it % 26], 0.4, 0.3))) }
        p.apply(keys) shouldBe keys
        p.record(listOf(Triple('a', 0.4, 0.3)))
        val moved = p.apply(keys).getValue('a')
        (moved.centerX - keys.getValue('a').centerX).toDouble() shouldBe ((0.4 * keys.getValue('a').halfWidth) plusOrMinus 1e-3)
        (moved.centerY - keys.getValue('a').centerY).toDouble() shouldBe ((0.3 * keys.getValue('a').halfHeight) plusOrMinus 1e-3)
    }

    test("the left and right of the keyboard tapped differently get their own clusters") {
        val p = PersonalOffsets()
        val left = "qwertasdfgzxcv"
        repeat(40) { for (c in letters) p.record(listOf(Triple(c, if (c in left) -0.5 else 0.5, 0.0))) }
        val o = p.offsets(keys)
        o.getValue('q').first shouldBe (-0.5 plusOrMinus 1e-9)
        o.getValue('p').first shouldBe (0.5 plusOrMinus 1e-9)
    }

    test("the history keeps the latest 800 taps (4 buckets of 200)") {
        val p = PersonalOffsets()
        repeat(1000) { p.record(listOf(Triple('e', 0.1, 0.1))) }
        p.taps shouldBe 800
    }

    test("kept across restarts, and reset clears it") {
        val f = kotlin.io.path.createTempFile("offsets", ".txt").toFile()
        PersonalOffsets(f).apply { repeat(250) { record(listOf(Triple('t', 0.2, -0.1))) } }
        PersonalOffsets(f).let {
            it.taps shouldBe 250
            it.offsets(keys).getValue('t').first shouldBe (0.2 plusOrMinus 1e-3)
            it.reset()
        }
        PersonalOffsets(f).taps shouldBe 0
        f.delete()
    }
})
