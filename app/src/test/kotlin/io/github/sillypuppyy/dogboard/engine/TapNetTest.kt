package io.github.sillypuppyy.dogboard.engine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * The Kotlin forward pass matches PyTorch's (tools/ml/mltt_export.py --golden). Needs the exported net:
 * DOGBOARD_TAPNET=path/to/tapnet.bin (its .golden.txt next to it); skipped without it.
 */
class TapNetTest : FunSpec({
    val bin = System.getenv("DOGBOARD_TAPNET")?.let { File(it) }?.takeIf { it.isFile }
    test("log-probabilities match the PyTorch golden file").config(enabled = bin != null) {
        val net = TapNet.read(bin!!.readBytes())!!
        val golden = File(bin.path.removeSuffix(".bin") + ".golden.txt").readLines()
        var i = 0
        var worst = 0.0
        while (i < golden.size) {
            val word = golden[i++]
            val out = net.forward(IntArray(word.length) { word[it] - 'a' + 1 })
            for (row in out) {
                val want = golden[i++].split(' ').map { it.toDouble() }
                for (k in row.indices) worst = maxOf(worst, kotlin.math.abs(row[k] - want[k]))
            }
            println("TAPNET $word -> ${TapNet.greedy(out)}")
        }
        println("TAPNET worst difference from PyTorch: $worst")
        worst shouldBeLessThan 1e-3
    }

    test("read through the lexicon, slips become words").config(enabled = bin != null) {
        val net = TapNet.read(bin!!.readBytes())!!
        val lexicon = File("src/main/assets/dogboard/en.lexicon.tsv").bufferedReader().use { Lexicon.parse(it) }
        val dec = TapNetDecoder(lexicon)
        for ((typed, want) in listOf("tje" to "the", "wprd" to "word", "helli" to "hello", "keyboars" to "keyboard", "becuase" to "because")) {
            val t0 = System.nanoTime()
            val readings = dec.decode(net.forward(IntArray(typed.length) { typed[it] - 'a' + 1 }))
            println("TAPNET-LEX $typed -> ${readings.take(3).joinToString { "%s %.2f".format(it.word, it.score) }} (${(System.nanoTime() - t0) / 1_000_000} ms)")
            readings.first().word.lowercase() shouldBe want
        }
    }
})
