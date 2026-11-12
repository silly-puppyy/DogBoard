package io.github.sillypuppyy.dogboard.feel

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.milliseconds

class FeelTest : FunSpec({
    context("WordDelete.charsToDelete") {
        withData(
            nameFn = { (text, n) -> "'$text' -> $n" },
            "hello world" to 5,
            "hello world " to 6,
            "hello world   " to 8,
            "don't" to 5,
            "end." to 1,
            "wait..." to 3,
            "a" to 1,
            "   " to 3,
            "" to 0,
            "x ?! " to 3,
        ) { (text, n) ->
            WordDelete.charsToDelete(text) shouldBe n
        }
    }

    context("DeleteRepeat.step") {
        test("first 19 repeats delete characters every 100 ms (20 chars including the press)") {
            for (i in 0 until 19) {
                DeleteRepeat.step(i) shouldBe DeleteRepeat.Step(DeleteRepeat.Unit.CHARACTER, 100.milliseconds)
            }
        }
        test("from the 20th repeat on, whole words every 350 ms") {
            for (i in 19 until 40) {
                DeleteRepeat.step(i) shouldBe DeleteRepeat.Step(DeleteRepeat.Unit.WORD, 350.milliseconds)
            }
        }
    }

    context("Haptics") {
        test("letters are a soft tap, space/return crisper, suggestions silent") {
            Haptics.baseTap(KeyFeedbackClass.TEXT) shouldBe HapticTap(0.51f, 0.31f)
            Haptics.baseTap(KeyFeedbackClass.SEPARATOR) shouldBe HapticTap(0.43f, 0.64f)
            Haptics.baseTap(KeyFeedbackClass.CANDIDATE) shouldBe null
        }
        test("slow typing plays the exact base tap") {
            Haptics.tapFor(KeyFeedbackClass.TEXT, msSinceLastPress = 900) shouldBe HapticTap(0.51f, 0.31f)
            Haptics.tapFor(KeyFeedbackClass.TEXT, msSinceLastPress = null) shouldBe HapticTap(0.51f, 0.31f)
        }
        test("fast typing varies intensity by at most 5% and keeps sharpness") {
            val random = kotlin.random.Random(42)
            repeat(1000) {
                val tap = Haptics.tapFor(KeyFeedbackClass.TEXT, msSinceLastPress = 150, random = random)!!
                tap.sharpness shouldBe 0.31f
                (tap.intensity in (0.51f * 0.95f)..(0.51f * 1.05f)) shouldBe true
            }
        }
        test("sharpness maps to 80..230 Hz") {
            Haptics.frequencyHz(0f) shouldBe (80.0 plusOrMinus 0.01)
            Haptics.frequencyHz(1f) shouldBe (230.0 plusOrMinus 0.01)
            Haptics.frequencyHz(0.31f) shouldBe (111.0 plusOrMinus 1.0)
        }
    }
})
