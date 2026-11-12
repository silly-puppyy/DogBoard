package io.github.sillypuppyy.dogboard.feel

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
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
})
