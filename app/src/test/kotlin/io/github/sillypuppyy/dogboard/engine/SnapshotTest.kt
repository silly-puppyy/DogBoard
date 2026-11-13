package io.github.sillypuppyy.dogboard.engine

import io.github.sillypuppyy.dogboard.touch.GaussianTouch
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.File

/** The binary snapshot (fast start) must behave exactly like the parsed word data. */
class SnapshotTest : FunSpec({
    test("a lexicon and pairs read from a snapshot decode exactly like the parsed ones") {
        val lex = File("src/main/assets/dogboard/en.lexicon.tsv").bufferedReader().use { Lexicon.parse(it) }
        val big = File("src/main/assets/dogboard/en.bigrams.tsv").bufferedReader().use { Bigrams.parse(it, lex) }
        val lex2 = Lexicon.readSnapshot(ByteArrayOutputStream().also { lex.writeSnapshot(it) }.toByteArray())!!
        val big2 = Bigrams.readSnapshot(ByteArrayOutputStream().also { big.writeSnapshot(it) }.toByteArray(), lex2)!!
        lex2.size shouldBe lex.size
        lex2.formsOf("google") shouldBe lex.formsOf("google")
        lex2.storedForm("i'm") shouldBe lex.storedForm("i'm")
        val keys = QwertyGeometry.letterKeys(375f, 216f)
        val params = GaussianTouch.params(medianKeyHalfWidth = 18.75, typingInterval = 0.3)
        for (w in listOf("hrllo", "becuse", "teh", "helloeveryone", "google")) {
            val taps = w.map { c -> keys.getValue(c).let { Tap(it.centerX, it.centerY) } }
            val a = Decoder(lex, bigrams = big).decode(taps, keys, params, previous = big.contextId("i"), typed = w)
            val b = Decoder(lex2, bigrams = big2).decode(taps, keys, params, previous = big2.contextId("i"), typed = w)
            b.map { it.word to it.score } shouldBe a.map { it.word to it.score }
        }
        // learned words can still be added after loading a snapshot
        lex2.addWord("zyxandra", -12.0)
        lex2.contains("zyxandra") shouldBe true
    }
    test("anything else is rejected") {
        Lexicon.readSnapshot(ByteArray(16)) shouldBe null
        Bigrams.readSnapshot("not a snapshot".toByteArray(), Lexicon.of(mapOf("a" to 1L))) shouldBe null
    }
})
