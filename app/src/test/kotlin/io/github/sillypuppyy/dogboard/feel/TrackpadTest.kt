package io.github.sillypuppyy.dogboard.feel

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

/** iOS keyboardTrackpadCurve . */
class TrackpadTest : FunSpec({
    test("parabola, then tangent line from 2, then square root from 5") {
        Trackpad.speed(1.0) shouldBe (0.04 plusOrMinus 1e-9)
        Trackpad.speed(2.0) shouldBe (0.16 plusOrMinus 1e-9)
        Trackpad.speed(5.0) shouldBe (0.64 plusOrMinus 1e-9)
        Trackpad.speed(10.0) shouldBe (kotlin.math.sqrt(0.64 * 0.64 + 2 * 0.64 * 0.16 * 5) plusOrMinus 1e-9)
    }
    test("continuous where the pieces meet, and growing") {
        for (x in listOf(2.0, 5.0)) Trackpad.speed(x - 1e-6) shouldBe (Trackpad.speed(x + 1e-6) plusOrMinus 1e-5)
        (1..40).map { Trackpad.speed(it / 2.0) }.zipWithNext().all { (a, b) -> b > a } shouldBe true
    }
    test("slow drags are almost unaccelerated, fast flicks go further") {
        Trackpad.accelerated(1f).toDouble() shouldBe (1.04 plusOrMinus 1e-6)
        (Trackpad.accelerated(20f) > 20f * 1.9f) shouldBe true
        Trackpad.accelerated(-3f) shouldBe -Trackpad.accelerated(3f)
    }
})
