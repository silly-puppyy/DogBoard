package io.github.sillypuppyy.dogboard.engine.lm

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * The Kotlin tokenizer gives the same ids as SentencePiece + caps.py for the model it ships with
 * (fixture made by the exporter). Needs the piece file (assets/dogboard/lm, not in git); skipped without.
 */
class PieceTokenizerTest : FunSpec({
    val pieces = File("src/main/assets/dogboard/lm/v3.pieces.tsv").takeIf { it.isFile }
    val fixture = File("src/test/resources/lm-tokenizer-fixture.tsv")

    test("same ids as SentencePiece, capitals as CAP, unknown letters as bytes").config(enabled = pieces != null) {
        val tok = pieces!!.bufferedReader().use { PieceTokenizer.parse(it) }
        fixture.readLines().filter { !it.startsWith("#") && it.isNotBlank() }.forEach { line ->
            val (text, ids) = line.split('\t')
            tok.encode(text).joinToString(" ") shouldBe ids
        }
    }

    test("decode puts the spaces and capitals back").config(enabled = pieces != null) {
        val tok = pieces!!.bufferedReader().use { PieceTokenizer.parse(it) }
        tok.decode(tok.encode("Can we meet on Thursday")).trim() shouldBe "Can we meet on Thursday"
    }
})
