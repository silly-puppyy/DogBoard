package io.github.sillypuppyy.dogboard.engine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.channels.FileChannel
import kotlin.random.Random

/**
 * DGN2 and DGN3 (tools/data/dgn3.py) give the same model: DOGBOARD_DGN2 and DOGBOARD_DGN3 name one model in both
 * layouts; every lookup and follower list must agree, and both are timed (memory-mapped, as on the phone).
 */
class NgramFormatTest : FunSpec({
    val f2 = System.getenv("DOGBOARD_DGN2")?.let(::File)?.takeIf { it.isFile }
    val f3 = System.getenv("DOGBOARD_DGN3")?.let(::File)?.takeIf { it.isFile }

    fun map(f: File) = FileChannel.open(f.toPath()).use { it.map(FileChannel.MapMode.READ_ONLY, 0, it.size()) }

    test("DGN3 answers as DGN2, and how fast").config(enabled = f2 != null && f3 != null) {
        val m2 = NgramModel.fromBuffer(map(f2!!))!!
        val m3 = NgramModel.fromBuffer(map(f3!!))!!
        m3.size shouldBe m2.size
        val v = m2.size
        val rnd = Random(7)
        // contexts that exist: a pair's words, so the triple groups are exercised, plus random ones
        val queries = IntArray(3 * 1_000_000) { rnd.nextInt(v) }
        var mismatches = 0
        for (q in 0 until 1_000_000) {
            val a = queries[3 * q]; val b = queries[3 * q + 1]; val w = queries[3 * q + 2]
            if (m2.logProbOf(a, b, w) != m3.logProbOf(a, b, w)) mismatches++
        }
        // followers after frequent contexts (low ids are frequent words)
        var followerMismatches = 0
        for (k in 0 until 20_000) {
            val a = rnd.nextInt(2000); val b = rnd.nextInt(2000)
            if (m2.followers(a, b, 8) != m3.followers(a, b, 8)) followerMismatches++
        }
        fun time(m: NgramModel): Double {
            var sink = 0.0
            repeat(2) { for (q in 0 until 1_000_000) sink += m.logProbOf(queries[3 * q] % 5000, queries[3 * q + 1] % 5000, queries[3 * q + 2]) }
            val t0 = System.nanoTime()
            repeat(3) { for (q in 0 until 1_000_000) sink += m.logProbOf(queries[3 * q] % 5000, queries[3 * q + 1] % 5000, queries[3 * q + 2]) }
            val ns = (System.nanoTime() - t0) / 3e6
            if (sink == 42.0) println()
            return ns
        }
        fun timeFollowers(m: NgramModel): Double {
            var sink = 0
            repeat(2) { for (k in 0 until 20_000) sink += m.followers(k % 300, (k * 7) % 300, 8).size }
            val t0 = System.nanoTime()
            for (k in 0 until 20_000) sink += m.followers(k % 300, (k * 7) % 300, 8).size
            if (sink == 42) println()
            return (System.nanoTime() - t0) / 20_000 / 1000.0
        }
        println("NGRAMFORMAT lookups: $mismatches mismatches of 1000000, followers: $followerMismatches of 20000")
        println("NGRAMFORMAT logProbOf ns: DGN2 %.1f, DGN3 %.1f; followers us: DGN2 %.1f, DGN3 %.1f".format(time(m2), time(m3), timeFollowers(m2), timeFollowers(m3)))
        mismatches shouldBe 0
        followerMismatches shouldBe 0
    }
})
