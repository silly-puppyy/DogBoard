package io.github.sillypuppyy.dogboard.engine

import java.io.BufferedReader
import kotlin.math.ln

/**
 * A back-off trigram language model (Kneser-Ney from KenLM's lmplz, ARPA format), with natural-log
 * probabilities. Words are the model's own ids ([idOf]); the caller maps lexicon words to them.
 *
 * P(w | a b) = P3(a b w) if seen, else BO(a b) + P(w | b);
 * P(w | b)   = P2(b w) if seen, else BO(b) + P1(w).
 *
 * Compact storage (the shipped "DGN2" file, tools/data/arpa_to_dogboard.py --compact): pair and
 * triple probabilities and pair back-offs are 8-bit codes into 256-value codebooks (quantile bins);
 * pairs are grouped by their first word ([biOffsets]) with the second words sorted inside; triples
 * are sorted keys (a << 42 | b << 21 | c), so ids must be below 2^21.
 */
class NgramModel private constructor(
    private val vocab: Array<String>,
    private val uniLogP: FloatArray,
    private val uniBackoff: FloatArray,
    private val biOffsets: IntArray,
    private val biWords: IntArray,
    private val biP: ByteArray,
    private val biB: ByteArray,
    private val biPBook: FloatArray,
    private val biBBook: FloatArray,
    private val triKeys: LongArray,
    private val triP: ByteArray,
    private val triPBook: FloatArray,
) {
    private val index: HashMap<String, Int> = HashMap<String, Int>(vocab.size * 2).also { m ->
        vocab.forEachIndexed { i, w -> m[w] = i }
    }

    val size: Int get() = vocab.size
    val pairCount: Int get() = biWords.size
    val tripleCount: Int get() = triKeys.size

    /** The model's id for [word] (lowercase), or -1. */
    fun idOf(word: String): Int = index[word] ?: -1

    fun word(id: Int): String = vocab[id]

    /** Index of the pair ([a], [b]), or -1. */
    private fun pairIndex(a: Int, b: Int): Int {
        if (a < 0 || a + 1 >= biOffsets.size) return -1
        val i = java.util.Arrays.binarySearch(biWords, biOffsets[a], biOffsets[a + 1], b)
        return if (i >= 0) i else -1
    }

    /**
     * ln P([w] | [a] [b]): [a] and [b] the two words before (-1 when there is none, [b] the nearer);
     * null when [w] is not in the model (the caller falls back to its own unigram).
     */
    fun logProb(a: Int, b: Int, w: Int): Double? {
        if (w < 0 || w >= vocab.size) return null
        if (a >= 0 && b >= 0) {
            val t = triKeys.binarySearch(tri(a, b, w))
            if (t >= 0) return triPBook[triP[t].toInt() and 0xff].toDouble()
            val p = pairIndex(a, b)
            val bo = if (p >= 0) biBBook[biB[p].toInt() and 0xff].toDouble() else 0.0
            return bo + bigram(b, w)
        }
        if (b >= 0) return bigram(b, w)
        return uniLogP[w].toDouble()
    }

    private fun bigram(b: Int, w: Int): Double {
        val p = pairIndex(b, w)
        if (p >= 0) return biPBook[biP[p].toInt() and 0xff].toDouble()
        return uniBackoff[b].toDouble() + uniLogP[w]
    }

    /** The [n] most likely next words after [a] [b] by the trigram and bigram entries, most likely first. */
    fun followers(a: Int, b: Int, n: Int): List<Pair<Int, Double>> {
        val scores = HashMap<Int, Double>()
        if (b >= 0 && b + 1 < biOffsets.size) {
            for (i in biOffsets[b] until biOffsets[b + 1]) scores[biWords[i]] = logProb(a, b, biWords[i]) ?: Double.NEGATIVE_INFINITY
        }
        if (a >= 0 && b >= 0) {
            val prefix = tri(a, b, 0)
            var i = triKeys.binarySearch(prefix).let { if (it < 0) -it - 1 else it }
            while (i < triKeys.size && (triKeys[i] and MASK21.inv()) == prefix) {
                scores[(triKeys[i] and MASK21).toInt()] = triPBook[triP[i].toInt() and 0xff].toDouble()
                i++
            }
        }
        return scores.entries.sortedByDescending { it.value }.take(n).map { it.key to it.value }
    }

    /** The compact binary form ([readBinary]); the converter writes the same layout. */
    fun writeBinary(out: java.io.OutputStream) {
        val d = java.io.DataOutputStream(java.io.BufferedOutputStream(out, 1 shl 16))
        d.writeInt(MAGIC); d.writeInt(vocab.size); d.writeInt(biWords.size); d.writeInt(triKeys.size)
        for (w in vocab) d.writeUTF(w)
        for (x in uniLogP) d.writeFloat(x)
        for (x in uniBackoff) d.writeFloat(x)
        for (x in biPBook) d.writeFloat(x)
        for (x in biBBook) d.writeFloat(x)
        for (x in biOffsets) d.writeInt(x)
        for (x in biWords) d.writeInt(x)
        d.write(biP); d.write(biB)
        for (x in triPBook) d.writeFloat(x)
        for (x in triKeys) d.writeLong(x)
        d.write(triP)
        d.flush()
    }

    companion object {
        const val START = "<s>"
        private const val MAGIC = 0x44474e32 // "DGN2"
        private const val MASK21 = (1L shl 21) - 1
        private val LN10 = ln(10.0)

        private fun tri(a: Int, b: Int, c: Int): Long = (a.toLong() shl 42) or (b.toLong() shl 21) or c.toLong()

        /** Reads [writeBinary]'s layout; null if it isn't one. */
        fun readBinary(input: java.io.InputStream): NgramModel? = runCatching {
            val d = java.io.DataInputStream(java.io.BufferedInputStream(input, 1 shl 16))
            if (d.readInt() != MAGIC) return null
            val nv = d.readInt(); val nb = d.readInt(); val nt = d.readInt()
            val vocab = Array(nv) { d.readUTF() }
            val up = FloatArray(nv) { d.readFloat() }; val ub = FloatArray(nv) { d.readFloat() }
            val pb = FloatArray(256) { d.readFloat() }; val bb = FloatArray(256) { d.readFloat() }
            val off = IntArray(nv + 1) { d.readInt() }
            val bw = IntArray(nb) { d.readInt() }
            val bp = ByteArray(nb).also { d.readFully(it) }; val bbo = ByteArray(nb).also { d.readFully(it) }
            val tb = FloatArray(256) { d.readFloat() }
            val tk = LongArray(nt) { d.readLong() }
            val tp = ByteArray(nt).also { d.readFully(it) }
            NgramModel(vocab, up, ub, off, bw, bp, bbo, pb, bb, tk, tp, tb)
        }.getOrNull()

        /** Quantile codebook of [values] (256 bins' means) and each value's code; exact for <= 256 values. */
        private fun quantise(values: FloatArray): Pair<FloatArray, ByteArray> {
            val n = values.size
            val order = (0 until n).sortedBy { values[it] }
            val book = FloatArray(256)
            val codes = ByteArray(n)
            for (q in 0 until 256) {
                val lo = q * n / 256; val hi = (q + 1) * n / 256
                if (lo >= hi) { book[q] = if (q > 0) book[q - 1] else 0f; continue }
                var sum = 0.0
                for (k in lo until hi) { sum += values[order[k]]; codes[order[k]] = q.toByte() }
                book[q] = (sum / (hi - lo)).toFloat()
            }
            return book to codes
        }

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
                                bi.add(Triple((a.toLong() shl 32) or b.toLong(), logp, backoff))
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
            val offsets = IntArray(vocab.size + 1)
            for ((k, _, _) in bi) offsets[(k ushr 32).toInt() + 1]++
            for (i in 0 until vocab.size) offsets[i + 1] += offsets[i]
            val (pBook, pCodes) = quantise(FloatArray(bi.size) { bi[it].second })
            val (bBook, bCodes) = quantise(FloatArray(bi.size) { bi[it].third })
            val (tBook, tCodes) = quantise(FloatArray(tri.size) { tri[it].second })
            return NgramModel(
                vocab.toTypedArray(), uniP.toFloatArray(), uniB.toFloatArray(),
                offsets, IntArray(bi.size) { (bi[it].first and 0xffffffffL).toInt() }, pCodes, bCodes, pBook, bBook,
                LongArray(tri.size) { tri[it].first }, tCodes, tBook,
            )
        }
    }
}

/**
 * Scores lexicon words with an [NgramModel] for the decoder: lexicon word ids and context ids
 * ([Bigrams.contextId]: a lexicon word id, or [sentenceStart] for the start of a sentence) are
 * mapped to the model's ids once; words the model lacks (names, learned words) keep the lexicon's
 * own probability.
 */
class NgramScorer(private val model: NgramModel, private val lexicon: Lexicon, private val sentenceStart: Int) : Decoder.ContextScorer {
    private val lexToModel = IntArray(lexicon.size) { id -> lexicon.word(lexicon.nodeOfWord(id))?.let { model.idOf(it.lowercase()) } ?: -1 }
    private val start = model.idOf(NgramModel.START)

    private fun contextToModel(ctx: Int): Int = when {
        ctx < 0 -> -1
        ctx == sentenceStart -> start
        ctx < lexToModel.size -> lexToModel[ctx]
        else -> -1
    }

    override fun logProb(prev2: Int, prev: Int, wordId: Int, unigramLogProb: Double): Double {
        val w = if (wordId in lexToModel.indices) lexToModel[wordId] else -1
        if (w < 0) return unigramLogProb
        val b = contextToModel(prev)
        val a = if (b >= 0) contextToModel(prev2) else -1
        return model.logProb(a, b, w) ?: unigramLogProb
    }
}
