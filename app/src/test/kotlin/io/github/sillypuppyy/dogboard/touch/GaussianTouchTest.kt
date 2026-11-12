package io.github.sillypuppyy.dogboard.touch

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull

/** A 375 x 216 dp QWERTY letter grid: 37.5 dp keys, 54 dp row pitch, home row offset half a key. */
private val ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
private val ROW_START = listOf(0f, 18.75f, 56.25f)
private val LETTERS = ROWS.flatMapIndexed { r, row -> row.map { it } }
private val KEYS = ROWS.flatMapIndexed { r, row ->
    row.mapIndexed { i, _ -> KeyTarget(ROW_START[r] + 37.5f * i + 18.75f, 54f * r + 27f, 18.75f, 27f) }
}

private fun pickLetter(x: Float, y: Float, interval: Double = 0.5): Char? {
    val params = GaussianTouch.params(medianKeyHalfWidth = 18.75, typingInterval = interval)
    return GaussianTouch.pick(KEYS, x, y, params)?.let { LETTERS[it] }
}

class GaussianTouchTest : FunSpec({
    test("a tap at a key centre picks that key") {
        for ((i, key) in KEYS.withIndex()) {
            pickLetter(key.centerX, key.centerY) shouldBe LETTERS[i]
        }
    }

    test("between two keys in a row, the nearer centre wins") {
        val f = KEYS[LETTERS.indexOf('f')]
        val g = KEYS[LETTERS.indexOf('g')]
        val border = (f.centerX + g.centerX) / 2
        pickLetter(border - 1f, f.centerY) shouldBe 'f'
        pickLetter(border + 1f, f.centerY) shouldBe 'g'
    }

    test("a tap in the home row's half-key edge gap still finds a key") {
        // left of 'a' (x < 18.75) is outside every home-row cell
        pickLetter(5f, 81f) shouldBe 'a'
    }

    test("vertical spread is wider than horizontal: a tap 20 dp above 'g' stays on the home row") {
        val g = KEYS[LETTERS.indexOf('g')]
        pickLetter(g.centerX, g.centerY - 20f) shouldBe 'g'
    }

    test("taps far outside the keyboard are rejected") {
        pickLetter(187f, -400f).shouldBeNull()
    }

    test("spread follows the reference: 2.2 x 3.2 mm slow, 3.3 x 4.5 mm fast, at 6.3 dp/mm") {
        val slow = GaussianTouch.params(medianKeyHalfWidth = 15.0, typingInterval = 0.5)
        val fast = GaussianTouch.params(medianKeyHalfWidth = 15.0, typingInterval = 0.1)
        fun sigma(c: Double) = Math.sqrt(-1.0 / (4 * c)) / GaussianTouch.POINTS_PER_MM
        sigma(slow.cx) shouldBe (2.2 plusOrMinus 0.01)
        sigma(slow.cy) shouldBe (3.2 plusOrMinus 0.01)
        sigma(fast.cx) shouldBe (3.3 plusOrMinus 0.01)
        sigma(fast.cy) shouldBe (4.5 plusOrMinus 0.01)
    }

    test("wide keys have a flat centre: no penalty anywhere inside the plateau") {
        val params = GaussianTouch.params(medianKeyHalfWidth = 18.75, typingInterval = 0.5)
        val space = KeyTarget(centerX = 200f, centerY = 189f, halfWidth = 94f, halfHeight = 27f)
        GaussianTouch.logLikelihood(space, 200f, 189f, params) shouldBe 0.0
        GaussianTouch.logLikelihood(space, 200f + 60f, 189f, params) shouldBe 0.0
        GaussianTouch.logLikelihood(space, 200f + 90f, 189f, params) shouldBeLessThan 0.0
    }

    test("a language-model prior can tip an ambiguous tap") {
        val f = KEYS[LETTERS.indexOf('f')]
        val g = KEYS[LETTERS.indexOf('g')]
        val x = (f.centerX + g.centerX) / 2 + 1f
        val params = GaussianTouch.params(medianKeyHalfWidth = 18.75, typingInterval = 0.5)
        val gIndex = LETTERS.indexOf('g')
        val fIndex = LETTERS.indexOf('f')
        GaussianTouch.pick(KEYS, x, f.centerY, params) shouldBe gIndex
        GaussianTouch.pick(KEYS, x, f.centerY, params) { if (it == fIndex) 1.0 else 0.0 } shouldBe fIndex
    }

    test("typing speed is a smoothed press interval capped at 0.5 s") {
        val speed = TypingSpeed()
        speed.interval shouldBe 0.35
        speed.onPress(0)
        speed.interval shouldBe (0.2 * 0.5 + 0.8 * 0.35 plusOrMinus 1e-9)
        repeat(50) { speed.onPress(100L * (it + 1)) }
        speed.interval shouldBe (0.1 plusOrMinus 0.001)
    }
})
