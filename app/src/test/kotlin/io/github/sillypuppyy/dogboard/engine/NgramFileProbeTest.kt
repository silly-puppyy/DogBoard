package io.github.sillypuppyy.dogboard.engine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/** The converter's file (DOGBOARD_NGRAMS) reads back in place and gives sensible context effects. */
class NgramFileProbeTest : FunSpec({
    val file = System.getenv("DOGBOARD_NGRAMS")?.let { File(it) }?.takeIf { it.isFile }
    test("converter output reads in place").config(enabled = file != null) {
        val t0 = System.nanoTime()
        val m = java.io.RandomAccessFile(file!!, "r").channel.use { ch ->
            NgramModel.fromBuffer(ch.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, ch.size()))
        }!!
        println("NGRAM load ${(System.nanoTime() - t0) / 1_000_000} ms: ${m.size} words, ${m.pairCount} pairs, ${m.tripleCount} triples")
        val i = m.idOf("i"); val love = m.idOf("love")
        (m.logProb(i, love, m.idOf("you"))!! > m.logProb(i, love, m.idOf("yours"))!!) shouldBe true
        (m.logProb(-1, m.idOf("thank"), m.idOf("you"))!! > m.logProb(-1, m.idOf("thank"), m.idOf("yew"))!!) shouldBe true
        println("NGRAM followers of 'i love': " + m.followers(i, love, 5).joinToString { m.word(it.first) })
    }
})
