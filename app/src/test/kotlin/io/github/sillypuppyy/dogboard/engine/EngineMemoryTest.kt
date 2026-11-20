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
        // the trigram's vocabulary index, written before measuring (the app writes it on the first start)
        val ngramIndex = File.createTempFile("ngramindex", ".bin").also { it.deleteOnExit() }
        java.io.RandomAccessFile(a + "en.ngrams.bin", "r").channel.use { ch ->
            NgramModel.fromBuffer(ch.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, ch.size()))!!.let { m -> ngramIndex.outputStream().use { m.writeVocabIndex(it) } }
        }
        var last = used()
        fun part(name: String, load: () -> Any) {
            keep.add(load())
            val now = used()
            println("ENGINEMEM %-14s %7.1f MB".format(name, (now - last) / 1048576.0))
            last = now
        }
        val lexicon = File(a + "en.lexicon.tsv").bufferedReader().use { Lexicon.parse(it) }.also { keep.add(it) }
        println("ENGINEMEM %-14s %7.1f MB (%d words, %d trie nodes = %.1f MB of node arrays at 32 B each)".format("lexicon", (used() - last) / 1048576.0, lexicon.size, lexicon.nodes, lexicon.nodes * 32 / 1048576.0)); last = used()
        // the word strings the engine caches (one String per word id, made on first use)
        part("word strings (all cached)") { (0 until lexicon.size).map { lexicon.word(lexicon.nodeOfWord(it)) } }
        // what later starts hold: the lexicon as loaded from its snapshot (exact-size arrays)
        val snap = File.createTempFile("lexicon", ".bin").also { it.deleteOnExit() }
        snap.outputStream().use { lexicon.writeSnapshot(it) }
        part("lexicon (snapshot, a second copy)") {
            java.io.RandomAccessFile(snap, "r").channel.use { ch -> Lexicon.readSnapshot(ch.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, ch.size()))!! }
        }
        part("bigrams") { File(a + "en.bigrams.tsv").bufferedReader().use { Bigrams.parse(it, lexicon) } }
        part("trigram (mmap, as the app)") {
            java.io.RandomAccessFile(a + "en.ngrams.bin", "r").channel.use { ch -> NgramModel.fromBuffer(ch.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, ch.size()))!! }
        }
        part("trigram (mmap + mapped index, as the app from the 2nd start)") {
            val idx = java.io.RandomAccessFile(ngramIndex, "r").channel.use { it.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, it.size()) }
            java.io.RandomAccessFile(a + "en.ngrams.bin", "r").channel.use { ch -> NgramModel.fromBuffer(ch.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, ch.size()), idx)!! }
        }
        part("letter model") { File(a + "en.charlm.bin").inputStream().use { CharModel.read(it)!! } }
        part("misspellings") { File(a + "en.misspellings.txt").readLines().toHashSet() }
        part("decoder") { Decoder(lexicon, Decoder.Config()) }
    }
})
