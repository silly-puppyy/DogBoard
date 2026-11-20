package io.github.sillypuppyy.dogboard.engine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.math.ln

/** ARPA back-off arithmetic (log10 in the file, ln in the model). */
class NgramModelTest : FunSpec({
    val arpa = """
        \data\
        ngram 1=5
        ngram 2=3
        ngram 3=1

        \1-grams:
        -1.0	<s>	-0.5
        -1.5	i	-0.2
        -2.0	love	-0.3
        -2.0	you	-0.1
        -3.0	lobe

        \2-grams:
        -0.4	<s> i	-0.1
        -1.0	i love	-0.25
        -0.3	love you

        \3-grams:
        -0.2	i love you

        \end\
    """.trimIndent()
    val m = NgramModel.parseArpa(arpa.reader().buffered())
    val ln10 = ln(10.0)
    fun id(w: String) = m.idOf(w)

    test("a seen triple uses its own probability") {
        m.logProb(id("i"), id("love"), id("you"))!! shouldBe (-0.2 * ln10 plusOrMinus 1e-6)
    }
    test("an unseen triple backs off: BO(a b) + P(w | b)") {
        // "<s> i love": no triple, pair "<s> i" has BO -0.1, then P2(i love) = -1.0
        m.logProb(id("<s>"), id("i"), id("love"))!! shouldBe ((-0.1 - 1.0) * ln10 plusOrMinus 1e-6)
    }
    test("an unseen pair backs off: BO(b) + P1(w)") {
        // P(lobe | i): no pair "i lobe": BO(i) -0.2 + P1(lobe) -3.0
        m.logProb(-1, id("i"), id("lobe"))!! shouldBe ((-0.2 - 3.0) * ln10 plusOrMinus 1e-6)
        // and through a missing pair for the triple context: BO(love lobe)=0 (no such pair) path
        m.logProb(id("love"), id("you"), id("lobe"))!! shouldBe ((-0.1 - 3.0) * ln10 plusOrMinus 1e-6)
    }
    test("context makes the right word likelier: love over lobe after i") {
        (m.logProb(-1, id("i"), id("love"))!! > m.logProb(-1, id("i"), id("lobe"))!!) shouldBe true
    }
    test("unknown words return null; followers rank by probability") {
        m.logProb(-1, id("i"), m.idOf("zebra")) shouldBe null
        m.followers(id("i"), id("love"), 2).first().first shouldBe id("you")
    }
    test("the binary form reads back the same model") {
        val bytes = java.io.ByteArrayOutputStream().also { m.writeBinary(it) }.toByteArray()
        val r = NgramModel.readBinary(bytes.inputStream())!!
        r.size shouldBe m.size
        r.logProb(r.idOf("i"), r.idOf("love"), r.idOf("you")) shouldBe m.logProb(id("i"), id("love"), id("you"))
        r.logProb(r.idOf("<s>"), r.idOf("i"), r.idOf("love")) shouldBe m.logProb(id("<s>"), id("i"), id("love"))
        NgramModel.readBinary("junk".byteInputStream()) shouldBe null
    }

    test("a vocabulary index written once reads back the same model (the app maps it: nothing on the heap)") {
        val bytes = java.io.ByteArrayOutputStream().also { m.writeBinary(it) }.toByteArray()
        val built = NgramModel.fromBuffer(java.nio.ByteBuffer.wrap(bytes))!!
        val index = java.io.ByteArrayOutputStream().also { built.writeVocabIndex(it) shouldBe true }.toByteArray()
        val mapped = NgramModel.fromBuffer(java.nio.ByteBuffer.wrap(bytes), java.nio.ByteBuffer.wrap(index))!!
        mapped.size shouldBe built.size
        for (w in listOf("i", "love", "you", "<s>", "lobe")) mapped.idOf(w) shouldBe built.idOf(w)
        mapped.idOf("nope") shouldBe -1
        mapped.logProb(mapped.idOf("i"), mapped.idOf("love"), mapped.idOf("you")) shouldBe built.logProb(built.idOf("i"), built.idOf("love"), built.idOf("you"))
        (0 until mapped.size).map { mapped.word(it) } shouldBe (0 until built.size).map { built.word(it) }
        // an index for another vocabulary is not used (the model builds its own)
        NgramModel.fromBuffer(java.nio.ByteBuffer.wrap(bytes), java.nio.ByteBuffer.wrap("junk1234junk".toByteArray()))!!.idOf("love") shouldBe built.idOf("love")
    }
})
