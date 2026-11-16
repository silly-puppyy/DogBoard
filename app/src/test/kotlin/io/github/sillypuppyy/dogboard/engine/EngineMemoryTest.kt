package io.github.sillypuppyy.dogboard.engine

import io.kotest.core.spec.style.FunSpec
import java.io.File

/** Heap held by each part of the word data once loaded (DOGBOARD_SPEED set): what the keyboard keeps. */
class EngineMemoryTest : FunSpec({
    test("engine memory").config(enabled = System.getenv("DOGBOARD_SPEED") != null) {
        val rt = Runtime.getRuntime()
        fun used(): Long { repeat(4) { System.gc(); Thread.sleep(150) }; return rt.totalMemory() - rt.freeMemory() }
        val a = "src/main/assets/dogboard/"
        val keep = ArrayList<Any>()
        var last = used()
        fun part(name: String, load: () -> Any) {
            keep.add(load())
            val now = used()
            println("ENGINEMEM %-14s %7.1f MB".format(name, (now - last) / 1048576.0))
            last = now
        }
        val lexicon = File(a + "en.lexicon.tsv").bufferedReader().use { Lexicon.parse(it) }.also { keep.add(it) }
        println("ENGINEMEM %-14s %7.1f MB (%d words)".format("lexicon", (used() - last) / 1048576.0, lexicon.size)); last = used()
        part("bigrams") { File(a + "en.bigrams.tsv").bufferedReader().use { Bigrams.parse(it, lexicon) } }
        part("trigram (mmap, as the app)") {
            java.io.RandomAccessFile(a + "en.ngrams.bin", "r").channel.use { ch -> NgramModel.fromBuffer(ch.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, ch.size()))!! }
        }
        part("letter model") { File(a + "en.charlm.bin").inputStream().use { CharModel.read(it)!! } }
        part("misspellings") { File(a + "en.misspellings.txt").readLines().toHashSet() }
        part("decoder") { Decoder(lexicon, Decoder.Config()) }
    }
})
