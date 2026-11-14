package io.github.sillypuppyy.dogboard.engine

import java.io.BufferedReader
import kotlin.math.ln

/**
 * A back-off trigram language model (Kneser-Ney from KenLM's lmplz, ARPA format), with natural-log
 * probabilities. Words are the model's own ids ([idOf]); the caller maps lexicon words to them.
 *
 * P(w | a b) = P3(a b w) if seen, else BO(a b) + P(w | b);
 * P(w | b)   = P2(b w) if seen, else BO(b) + P1(w).
 * Lookups are binary searches in sorted key arrays: pairs as (a << 32 | b), triples as
 * (a << 42 | b << 21 | c), so ids must be below 2^21.
 */
class NgramModel private constructor(
    private val vocab: Array<String>,
    private val uniLogP: FloatArray,
    private val uniBackoff: FloatArray,
    private val biKeys: LongArray,
    private val biLogP: FloatArray,
    private val biBackoff: FloatArray,
    private val triKeys: LongArray,
    private val triLogP: FloatArray,
) {
    private val index: HashMap<String, Int> = HashMap<String, Int>(vocab.size * 2).also { m ->
        vocab.forEachIndexed { i, w -> m[w] = i }
    }

    val size: Int get() = vocab.size
    val pairCount: Int get() = biKeys.size
    val tripleCount: Int get() = triKeys.size

    /** The model's id for [word] (lowercase), or -1. */
    fun idOf(word: String): Int = index[word] ?: -1

    fun word(id: Int): String = vocab[id]

    /**
     * ln P([w] | [a] [b]): [a] and [b] the two words before (-1 when there is none, [b] the nearer);
     * null when [w] is not in the model (the caller falls back to its own unigram).
     */
    fun logProb(a: Int, b: Int, w: Int): Double? {
        if (w < 0 || w >= vocab.size) return null
        if (a >= 0 && b >= 0) {
            val t = triKeys.binarySearch(tri(a, b, w))
            if (t >= 0) return triLogP[t].toDouble()
            val p = biKeys.binarySearch(pair(a, b))
            val bo = if (p >= 0) biBackoff[p].toDouble() else 0.0
            return bo + bigram(b, w)
        }
        if (b >= 0) return bigram(b, w)
        return uniLogP[w].toDouble()
    }

    private fun bigram(b: Int, w: Int): Double {
        val p = biKeys.binarySearch(pair(b, w))
        if (p >= 0) return biLogP[p].toDouble()
        return uniBackoff[b].toDouble() + uniLogP[w]
    }

    /** The [n] most likely next words after [a] [b] by the trigram and bigram entries, most likely first. */
    fun followers(a: Int, b: Int, n: Int): List<Pair<Int, Double>> {
        val scores = HashMap<Int, Double>()
        if (b >= 0) {
            var i = biKeys.binarySearch(pair(b, 0)).let { if (it < 0) -it - 1 else it }
            while (i < biKeys.size && (biKeys[i] ushr 32).toInt() == b) {
                val w = (biKeys[i] and 0xffffffffL).toInt()
                scores[w] = logProb(a, b, w) ?: Double.NEGATIVE_INFINITY
                i++
            }
        }
        if (a >= 0 && b >= 0) {
            var i = triKeys.binarySearch(tri(a, b, 0)).let { if (it < 0) -it - 1 else it }
            val prefix = tri(a, b, 0)
            while (i < triKeys.size && (triKeys[i] and MASK21.inv()) == prefix) {
                val w = (triKeys[i] and MASK21).toInt()
                scores[w] = triLogP[i].toDouble()
                i++
            }
        }
        return scores.entries.sortedByDescending { it.value }.take(n).map { it.key to it.value }
    }

    companion object {
        const val START = "<s>"
        private const val MASK21 = (1L shl 21) - 1
        private val LN10 = ln(10.0)

        private fun pair(a: Int, b: Int): Long = (a.toLong() shl 32) or b.toLong()
        private fun tri(a: Int, b: Int, c: Int): Long = (a.toLong() shl 42) or (b.toLong() shl 21) or c.toLong()

        /** Reads an ARPA file (log10 probabilities, as lmplz writes them) of order up to 3. */
        fun parseArpa(reader: BufferedReader): NgramModel {
            val vocab = ArrayList<String>()
            val uniP = ArrayList<Float>(); val uniB = ArrayList<Float>()
            val bi = ArrayList<Triple<Long, Float, Float>>()
            val tri = ArrayList<Pair<Long, Float>>()
            val ids = HashMap<String, Int>()
            var section = 0
            reader.forEachLine { raw ->
                val line = raw.trim()
                when {
                    line.isEmpty() || line.startsWith("ngram ") || line == "\\data\\" || line == "\\end\\" -> Unit
                    line.startsWith("\\") && line.endsWith("-grams:") -> section = line[1].digitToInt()
                    else -> {
                        val f = line.split('\t')
                        val logp = (f[0].toDouble() * LN10).toFloat()
                        val words = f[1].split(' ')
                        val backoff = if (f.size > 2) (f[2].toDouble() * LN10).toFloat() else 0f
                        when (section) {
                            1 -> { ids[words[0]] = vocab.size; vocab.add(words[0]); uniP.add(logp); uniB.add(backoff) }
                            2 -> {
                                val a = ids[words[0]] ?: return@forEachLine; val b = ids[words[1]] ?: return@forEachLine
                                bi.add(Triple(pair(a, b), logp, backoff))
                            }
                            3 -> {
                                val a = ids[words[0]] ?: return@forEachLine; val b = ids[words[1]] ?: return@forEachLine
                                val c = ids[words[2]] ?: return@forEachLine
                                tri.add(tri(a, b, c) to logp)
                            }
                        }
                    }
                }
            }
            require(vocab.size < (1 shl 21)) { "vocabulary too large for the key packing: ${vocab.size}" }
            bi.sortBy { it.first }; tri.sortBy { it.first }
            return NgramModel(
                vocab.toTypedArray(), uniP.toFloatArray(), uniB.toFloatArray(),
                LongArray(bi.size) { bi[it].first }, FloatArray(bi.size) { bi[it].second }, FloatArray(bi.size) { bi[it].third },
                LongArray(tri.size) { tri[it].first }, FloatArray(tri.size) { tri[it].second },
            )
        }
    }
}
