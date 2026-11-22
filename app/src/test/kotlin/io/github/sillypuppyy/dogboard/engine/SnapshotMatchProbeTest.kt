package io.github.sillypuppyy.dogboard.engine

import io.kotest.core.spec.style.FunSpec
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * A pack's word list as the phone reads it (the snapshot, DOGBOARD_SNAPSHOT_LEXICON) against the text list the JVM
 * evals read (DOGBOARD_TEXT_LEXICON): sizes, probabilities and accent folding of DOGBOARD_PROBE_WORDS (probe only:
 * the Spanish ñ slips were fixed in the JVM eval and left on the device, 2026-11-22).
 */
class SnapshotMatchProbeTest : FunSpec({
    val snap = System.getenv("DOGBOARD_SNAPSHOT_LEXICON")?.let(::File)?.takeIf { it.isFile }
    val text = System.getenv("DOGBOARD_TEXT_LEXICON")?.let(::File)?.takeIf { it.isFile }

    test("snapshot and text word lists agree").config(enabled = snap != null && text != null) {
        Lexicon.distinctLetters = System.getenv("DOGBOARD_DISTINCT")?.toSet() ?: emptySet()
        val fromText = text!!.bufferedReader().use { Lexicon.parse(it) }
        val fromSnap = Lexicon.readSnapshot(GZIPInputStream(snap!!.inputStream()).readBytes())!!
        println("SNAPMATCH sizes: text ${fromText.size}, snapshot ${fromSnap.size}")
        for (w in (System.getenv("DOGBOARD_PROBE_WORDS") ?: "").split(',').filter { it.isNotBlank() }) {
            println("SNAPMATCH $w: text ${fromText.logProb(w)} node ${fromText.find(w)}, snapshot ${fromSnap.logProb(w)} node ${fromSnap.find(w)}")
        }
    }
})
