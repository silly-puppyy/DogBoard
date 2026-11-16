package io.github.sillypuppyy.dogboard.engine

import io.github.sillypuppyy.dogboard.touch.GaussianTouch
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

class SpaceJoinTest : FunSpec({
    val lexicon = File("src/main/assets/dogboard/en.lexicon.tsv").bufferedReader().use { Lexicon.parse(it) }
    val bigrams = File("src/main/assets/dogboard/en.bigrams.tsv").bufferedReader().use { Bigrams.parse(it, lexicon) }
    val keys = QwertyGeometry.keysWithSpace(375f, 216f)
    val params = GaussianTouch.params(medianKeyHalfWidth = 18.75, typingInterval = 0.3)
    fun decoder(split: Boolean = true) = Decoder(lexicon, Decoder.Config(splitWords = split), bigrams)
    fun taps(w: String, t0: Long) = w.mapIndexed { i, c -> keys.getValue(c).let { Tap(it.centerX, it.centerY, t0 + 250L * i) } }

    /** The joined word, or null when the two words stay apart. */
    fun join(a: String, space: Tap, b: String, before: String = Bigrams.START): String? {
        val ta = taps(a, 0); val tb = taps(b, 250L * (a.length + 1))
        val first = decoder().decode(ta, keys, params, 4, includeCompletions = false, previous = bigrams.contextId(before), typed = a).first()
        val second = decoder().decode(tb, keys, params, 4, includeCompletions = false, previous = bigrams.contextId(first.word), typed = b).first()
        val j = SpaceJoin.best(decoder(split = false), ta, space, tb, keys, params, bigrams.contextId(before)) ?: return null
        return j.word.takeIf { SpaceJoin.wins(j, first.score + second.score) }
    }
    val rowH = 216f / 4

    test("a letter tap that landed on the space bar, under the letter, is rejoined") {
        // "meeti g": the n was tapped at the top of the space bar, below the n key
        join("meeti", Tap(keys.getValue('n').centerX, 3 * rowH + 0.2f * rowH, 5 * 250L), "g") shouldBe "meeting"
    }

    test("two real words with a normal space stay apart") {
        join("to", Tap(keys.getValue(' ').centerX, 3.5f * rowH, 500L), "the") shouldBe null
        join("in", Tap(keys.getValue(' ').centerX + 20f, 3.5f * rowH, 500L), "a") shouldBe null
    }
})
