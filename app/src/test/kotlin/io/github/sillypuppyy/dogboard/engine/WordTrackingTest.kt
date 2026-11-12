package io.github.sillypuppyy.dogboard.engine

import io.github.sillypuppyy.dogboard.touch.GaussianTouch
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

private val KEYS = QwertyGeometry.letterKeys(375f, 216f)
private val PARAMS = GaussianTouch.params(medianKeyHalfWidth = 18.75, typingInterval = 0.3)

private fun candidate(word: String, cost: Double, completion: Boolean = false) =
    Candidate(word, cost, cost, 0, completion)

class WordTrackingTest : FunSpec({
    context("CurrentWord") {
        test("keeps one tap per letter and drops it again on backspace") {
            val word = CurrentWord()
            word.addLetter('h', Tap(10f, 20f), KEYS, PARAMS)
            word.addLetter('i', Tap(30f, 40f), KEYS, PARAMS)
            word.text shouldBe "hi"
            word.tapsOrNull() shouldBe listOf(Tap(10f, 20f), Tap(30f, 40f))
            word.removeLast()
            word.text shouldBe "h"
            word.tapsOrNull() shouldBe listOf(Tap(10f, 20f))
        }
        test("a letter without a tap uses its key's centre") {
            val word = CurrentWord()
            word.addLetter('h', Tap(10f, 20f), KEYS, PARAMS)
            word.addLetter('E', null, emptyMap(), null)
            val e = KEYS.getValue('e')
            word.tapsOrNull()!![1] shouldBe Tap(e.centerX, e.centerY)
        }
        test("a character that is not on the keyboard makes the word undecodable until cleared") {
            val word = CurrentWord()
            word.addLetter('c', Tap(1f, 1f), KEYS, PARAMS)
            word.addLetter('é', null, emptyMap(), null)
            word.tapsOrNull().shouldBeNull()
            word.clear()
            word.addLetter('a', Tap(1f, 1f), KEYS, PARAMS)
            word.tapsOrNull() shouldBe listOf(Tap(1f, 1f))
        }
        test("a snapshot is independent of later typing") {
            val word = CurrentWord()
            word.addLetter('a', Tap(1f, 1f), KEYS, PARAMS)
            val copy = word.snapshot()
            word.addLetter('b', Tap(2f, 2f), KEYS, PARAMS)
            copy.text shouldBe "a"
        }
    }

    context("SuggestionStrip") {
        test("typed word in quotes, then the correction, then the next best word") {
            val strip = SuggestionStrip.build(
                "teh",
                listOf(candidate("the", 1.0), candidate("tea", 2.0), candidate("ten", 3.0)),
                correction = "the",
            )
            strip shouldBe listOf(
                Suggestion("teh", Suggestion.Kind.LITERAL),
                Suggestion("the", Suggestion.Kind.AUTOCORRECT),
                Suggestion("tea", Suggestion.Kind.WORD),
            )
        }
        test("without a correction: typed word, then the best words, in the typed casing") {
            val strip = SuggestionStrip.build(
                "Hel",
                listOf(candidate("help", 1.0, completion = true), candidate("hello", 2.0, completion = true)),
                correction = null,
            )
            strip.map { it.word } shouldBe listOf("Hel", "Help", "Hello")
        }
        test("nothing typed, nothing shown") {
            SuggestionStrip.build("", listOf(candidate("the", 1.0)), null) shouldBe emptyList()
        }
    }
})
