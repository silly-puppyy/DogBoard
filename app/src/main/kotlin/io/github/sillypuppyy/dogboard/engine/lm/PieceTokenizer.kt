package io.github.sillypuppyy.dogboard.engine.lm

import java.io.BufferedReader
import java.text.Normalizer

/**
 * The neural model's tokenizer: SentencePiece's unigram encoding (NFKC, one "▁"-word at a time, the
 * best-scoring split by Viterbi, unknown characters as UTF-8 byte pieces) read from an exported piece
 * list, plus capitalisation as a token (Apple's scheme): every word is lowercased and a word that
 * started with a capital gets [CAP] before its first piece; "I", "I'm", "I'll", "I've", "I'd" keep theirs.
 * File: one "piece<TAB>score<TAB>type" line per id (n normal, d user-defined, b byte, c control, u unknown).
 */
class PieceTokenizer private constructor(
    private val pieces: List<String>,
    private val scores: FloatArray,
    private val byId: Map<String, Int>,
    private val byteIds: IntArray,
    private val maxLen: Int,
) {
    val size: Int get() = pieces.size

    fun piece(id: Int): String = pieces[id]

    /** Token ids for [text], as the model was trained (no BOS). */
    fun encode(text: String): IntArray {
        val out = ArrayList<Int>()
        for (word in text.split(WHITESPACE)) {
            if (word.isEmpty()) continue
            val keep = word in KEEP
            val firstLetter = word.firstOrNull { it.isLetter() }
            if (!keep && firstLetter != null && firstLetter.isUpperCase()) out.add(CAP)
            encodeWord(SPACE + normalize(if (keep) word else word.lowercase()), out)
        }
        return out.toIntArray()
    }

    /** The text a run of ids stands for ("▁" as a space; [CAP] capitalises the next word). */
    fun decode(ids: IntArray): String {
        val sb = StringBuilder()
        var cap = false
        for (id in ids) {
            if (id == CAP) { cap = true; continue }
            var p = pieces[id].replace(SPACE, ' ')
            if (cap && p.isNotBlank()) {
                val i = p.indexOfFirst { !it.isWhitespace() }
                p = p.substring(0, i) + p[i].uppercaseChar() + p.substring(i + 1)
                cap = false
            }
            sb.append(p)
        }
        return sb.toString()
    }

    private fun encodeWord(w: String, out: MutableList<Int>) {
        val n = w.length
        val best = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        val from = IntArray(n + 1) { -1 }
        val via = IntArray(n + 1) { -1 } // piece id, or -1 for an unknown character (byte fallback)
        best[0] = 0.0
        for (i in 0 until n) {
            if (best[i] == Double.NEGATIVE_INFINITY) continue
            var matched = false
            for (j in i + 1..minOf(n, i + maxLen)) {
                val id = byId[w.substring(i, j)] ?: continue
                matched = matched || j == i + Character.charCount(w.codePointAt(i))
                val s = best[i] + scores[id]
                if (s > best[j]) { best[j] = s; from[j] = i; via[j] = id }
            }
            if (!matched) { // no piece for this character: its UTF-8 bytes, priced like an unknown
                val j = i + Character.charCount(w.codePointAt(i))
                val s = best[i] + UNKNOWN_SCORE
                if (s > best[j]) { best[j] = s; from[j] = i; via[j] = -1 }
            }
        }
        val path = ArrayList<IntArray>()
        var j = n
        while (j > 0) { path.add(intArrayOf(from[j], j, via[j])); j = from[j] }
        for (k in path.indices.reversed()) {
            val (i, end, id) = path[k]
            if (id >= 0) out.add(id) else for (b in w.substring(i, end).toByteArray(Charsets.UTF_8)) out.add(byteIds[b.toInt() and 0xff])
        }
    }

    companion object {
        const val CAP = 4
        const val BOS = 1
        const val SPACE = '▁'
        private val WHITESPACE = Regex("\\s+")
        private val KEEP = setOf("I", "I'm", "I'll", "I've", "I'd", "I’m", "I’ll", "I’ve", "I’d")
        private const val UNKNOWN_SCORE = -30.0

        /** SentencePiece's nmt_nfkc, enough for typed text: NFKC. */
        fun normalize(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKC)

        fun parse(reader: BufferedReader): PieceTokenizer {
            val pieces = ArrayList<String>(); val scores = ArrayList<Float>(); val byId = HashMap<String, Int>()
            val byteIds = IntArray(256) { -1 }
            var maxLen = 1
            reader.lineSequence().filter { !it.startsWith("#dogboard-pieces") }.forEach { line ->
                val (p, score, type) = line.split('\t')
                val id = pieces.size
                pieces.add(p); scores.add(score.toFloat())
                when (type) {
                    "n" -> { byId[p] = id; maxLen = maxOf(maxLen, p.length) }
                    "b" -> byteIds[p.substring(3, 5).toInt(16)] = id
                }
            }
            return PieceTokenizer(pieces, scores.toFloatArray(), byId, byteIds, maxLen)
        }
    }
}
