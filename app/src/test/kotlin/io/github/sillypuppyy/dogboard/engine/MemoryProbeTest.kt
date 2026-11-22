package io.github.sillypuppyy.dogboard.engine

import io.kotest.core.spec.style.FunSpec
import java.io.File

/**
 * Heap kept by each part of a language's word engine (a GC before each reading), for the memory work
 * (notes checklist C1b): DOGBOARD_PROBE_MEM=<pack dir> DOGBOARD_PACK_LANG=ru, DOGBOARD_TEST_HEAP=4g.
 */
class MemoryProbeTest : FunSpec({
    test("heap per part").config(enabled = System.getenv("DOGBOARD_PROBE_MEM") != null) {
        val dir = File(System.getenv("DOGBOARD_PROBE_MEM"))
        val lang = System.getenv("DOGBOARD_PACK_LANG") ?: "en"
        val rules = File(dir, "rules.json").takeIf { it.isFile }?.let { LanguageRules.parse(it.readText()) } ?: LanguageRules()
        Lexicon.distinctLetters = rules.distinctLetters.toSet()
        fun used(): Long { repeat(3) { System.gc(); Thread.sleep(200) }; val r = Runtime.getRuntime(); return r.totalMemory() - r.freeMemory() }
        val m0 = used()
        val snap = File(dir, "$lang-lexicon.dgz")
        // as the phone: the snapshot unpacked to a file and mapped (its cold fields stay off the heap)
        val unpacked = File.createTempFile("lex", ".bin").apply { deleteOnExit() }
        java.util.zip.GZIPInputStream(snap.inputStream()).use { i -> unpacked.outputStream().use { i.copyTo(it) } }
        val lexicon = java.io.RandomAccessFile(unpacked, "r").channel.use { c -> Lexicon.readSnapshot(c.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, c.size())) }!!
        val m1 = used()
        // DOGBOARD_PROBE_HISTO=1: the heap's biggest classes right after the lexicon (jcmd)
        if (System.getenv("DOGBOARD_PROBE_HISTO") == "1") ProcessBuilder("jcmd", ProcessHandle.current().pid().toString(), "GC.class_histogram")
            .redirectErrorStream(true).start().inputStream.bufferedReader().readLines().take(16).forEach { println("PROBE-HISTO $it") }
        val bigrams = File(dir, "$lang.bigrams.tsv").bufferedReader().use { Bigrams.parse(it, lexicon) }
        val m2 = used()
        val ngFile = File(dir, "$lang.ngrams.bin")
        val model = java.io.RandomAccessFile(ngFile, "r").channel.use { c -> NgramModel.fromBuffer(c.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, c.size()), null) }!!
        val m3 = used()
        val scorer = NgramScorer(model, lexicon, bigrams.sentenceStart)
        val m4 = used()
        println("PROBE-MEM $lang words ${lexicon.size}: lexicon ${(m1 - m0) / 1048576} MB, " +
            "bigrams ${(m2 - m1) / 1048576} MB, trigram (index built) ${(m3 - m2) / 1048576} MB, scorer table ${(m4 - m3) / 1048576} MB")
        check(scorer.hashCode() != 0 || true)
    }
})
