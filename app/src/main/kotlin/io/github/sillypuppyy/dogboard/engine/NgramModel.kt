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
    private val vocab: Vocab,
    private val uniLogP: java.nio.FloatBuffer,
    private val uniBackoff: java.nio.FloatBuffer,
    private val biOffsets: java.nio.IntBuffer,
    private val biWords: java.nio.IntBuffer,
    private val biP: java.nio.ByteBuffer,
    private val biB: java.nio.ByteBuffer,
    private val biPBook: FloatArray,
    private val biBBook: FloatArray,
    private val triKeys: java.nio.LongBuffer,
    private val triP: java.nio.ByteBuffer,
    private val triPBook: FloatArray,
) {
    // The large tables are buffers, so the shipped file can be memory-mapped: nothing is copied
    // onto the heap and loading is near-instant ([fromBuffer]).
    val size: Int get() = vocab.size
    val pairCount: Int get() = biWords.limit()
    val tripleCount: Int get() = triKeys.limit()

    /** The model's id for [word] (lowercase), or -1. */
    fun idOf(word: String): Int = vocab.idOf(word)

    /** Writes the vocabulary's index for [fromBuffer]'s `index` (a model read from a buffer only); false otherwise. */
    fun writeVocabIndex(out: java.io.OutputStream): Boolean = (vocab as? MappedVocab)?.writeIndex(out)?.let { true } ?: false

    fun word(id: Int): String = vocab.word(id)

    /**
     * Every [SAMPLE]th pair word and triple key on the heap (~0.55 MB): a search narrows to one block of [SAMPLE] there
     * and reads the mapped file only inside it (~6 reads instead of up to 22). On Android every mapped read is a bounds
     * check and a native call, and these searches were most of a keystroke's decode (simpleperf on the emulator,
     * 2026-11-21); a per-context row cache was tried and kept re-copying rows (many contexts per keystroke).
     */
    private val biSample: IntArray by lazy { IntArray((biWords.limit() + SAMPLE - 1) / SAMPLE) { biWords[it * SAMPLE] } }
    private val triSample: LongArray by lazy { LongArray((triKeys.limit() + SAMPLE - 1) / SAMPLE) { triKeys[it * SAMPLE] } }

    /** Index of the pair ([a], [b]), or -1. */
    private fun pairIndex(a: Int, b: Int): Int {
        if (a < 0 || a + 1 >= biOffsets.limit()) return -1
        var lo = biOffsets[a]; var hi = biOffsets[a + 1] - 1
        if (hi - lo > 2 * SAMPLE) {
            // the samples wholly inside this word's (sorted) run: the last one <= b bounds the block
            val sample = biSample
            val first = (lo + SAMPLE - 1) / SAMPLE; val last = hi / SAMPLE
            var sl = first; var sh = last; var j = first - 1
            while (sl <= sh) { val m = (sl + sh) ushr 1; if (sample[m] <= b) { j = m; sl = m + 1 } else sh = m - 1 }
            if (j >= first) {
                if (sample[j] == b) return j * SAMPLE
                lo = j * SAMPLE + 1; hi = minOf(hi, (j + 1) * SAMPLE - 1)
            } else hi = minOf(hi, first * SAMPLE - 1)
        }
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = biWords[mid]
            if (v < b) lo = mid + 1 else if (v > b) hi = mid - 1 else return mid
        }
        return -1
    }

    /** Index of [key] in the sorted triple keys, or -(insertion point) - 1. */
    private fun triSearch(key: Long): Int {
        val n = triKeys.limit()
        if (n == 0) return -1
        // the last sample <= key bounds the block ([triSample])
        val sample = triSample
        var sl = 0; var sh = sample.size - 1; var j = -1
        while (sl <= sh) { val m = (sl + sh) ushr 1; if (sample[m] <= key) { j = m; sl = m + 1 } else sh = m - 1 }
        if (j < 0) return -1 // before every key: insertion point 0
        if (sample[j] == key) return j * SAMPLE
        var lo = j * SAMPLE + 1; var hi = minOf(n, (j + 1) * SAMPLE) - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = triKeys[mid]
            if (v < key) lo = mid + 1 else if (v > key) hi = mid - 1 else return mid
        }
        return -(lo + 1)
    }

    /**
     * ln P([w] | [a] [b]): [a] and [b] the two words before (-1 when there is none, [b] the nearer);
     * null when [w] is not in the model (the caller falls back to its own unigram).
     */
    val vocabSize: Int get() = vocab.size

    fun logProb(a: Int, b: Int, w: Int): Double? = if (w < 0 || w >= vocab.size) null else logProbOf(a, b, w)

    /** [logProb] for a [w] in the model, unboxed (the decoder asks per state: a boxed Double each was garbage). */
    fun logProbOf(a: Int, b: Int, w: Int): Double {
        if (a >= 0 && b >= 0) {
            val t = triSearch(tri(a, b, w))
            if (t >= 0) return triPBook[triP.get(t).toInt() and 0xff].toDouble()
            val p = pairIndex(a, b)
            val bo = if (p >= 0) biBBook[biB.get(p).toInt() and 0xff].toDouble() else 0.0
            return bo + bigram(b, w)
        }
        if (b >= 0) return bigram(b, w)
        return uniLogP[w].toDouble()
    }

    private fun bigram(b: Int, w: Int): Double {
        val p = pairIndex(b, w)
        if (p >= 0) return biPBook[biP.get(p).toInt() and 0xff].toDouble()
        return uniBackoff[b].toDouble() + uniLogP[w]
    }

    /** The [n] most likely next words after [a] [b] by the trigram and bigram entries, most likely first. */
    fun followers(a: Int, b: Int, n: Int): List<Pair<Int, Double>> {
        if (n <= 0) return emptyList()
        // Called after every word on the way to the bar, holding the engine lock, and a common word
        // has tens of thousands of followers ("the", "to"): one pass, no per-word lookups or boxing,
        // only the best n kept. The trigram entries for "a b" and the bigram entries for "b" are both
        // sorted by word id, so they are walked side by side: a word with a trigram entry scores by
        // it, every other follower of b by backoff(a b) + its bigram entry (as logProb).
        val top = TopN(n)
        var t = -1; var tEnd = -1
        if (a >= 0 && b >= 0) {
            val prefix = tri(a, b, 0)
            t = triSearch(prefix).let { if (it < 0) -it - 1 else it }
            tEnd = t
            while (tEnd < triKeys.limit() && (triKeys[tEnd] and MASK21.inv()) == prefix) tEnd++
            for (i in t until tEnd) top.offer((triKeys[i] and MASK21).toInt(), triPBook[triP.get(i).toInt() and 0xff].toDouble())
        }
        if (b >= 0 && b + 1 < biOffsets.limit()) {
            val bo = if (a >= 0) pairIndex(a, b).let { p -> if (p >= 0) biBBook[biB.get(p).toInt() and 0xff].toDouble() else 0.0 } else 0.0
            for (i in biOffsets[b] until biOffsets[b + 1]) {
                val w = biWords[i]
                while (t in 0 until tEnd && (triKeys[t] and MASK21).toInt() < w) t++
                if (t in 0 until tEnd && (triKeys[t] and MASK21).toInt() == w) continue
                top.offer(w, bo + biPBook[biP.get(i).toInt() and 0xff])
            }
        }
        return top.sorted()
    }

    /** The [n] highest-scoring (word, score) pairs offered, without keeping the rest. */
    private class TopN(private val n: Int) {
        private val words = IntArray(n)
        private val scores = DoubleArray(n)
        private var size = 0
        private var worst = 0  // slot of the lowest kept score, once full

        fun offer(w: Int, s: Double) {
            if (size < n) {
                words[size] = w; scores[size] = s; size++
                if (size == n) findWorst()
            } else if (s > scores[worst]) {
                words[worst] = w; scores[worst] = s
                findWorst()
            }
        }

        private fun findWorst() {
            var k = 0
            for (i in 1 until size) if (scores[i] < scores[k]) k = i
            worst = k
        }

        fun sorted(): List<Pair<Int, Double>> = (0 until size).map { words[it] to scores[it] }.sortedByDescending { it.second }
    }

    /** [followers] the slow, obvious way (every follower through [logProb], full sort): tests compare. */
    internal fun followersReference(a: Int, b: Int, n: Int): List<Pair<Int, Double>> {
        val scores = HashMap<Int, Double>()
        if (b >= 0 && b + 1 < biOffsets.limit()) {
            for (i in biOffsets[b] until biOffsets[b + 1]) scores[biWords[i]] = logProb(a, b, biWords[i]) ?: Double.NEGATIVE_INFINITY
        }
        if (a >= 0 && b >= 0) {
            val prefix = tri(a, b, 0)
            var i = triSearch(prefix).let { if (it < 0) -it - 1 else it }
            while (i < triKeys.limit() && (triKeys[i] and MASK21.inv()) == prefix) {
                scores[(triKeys[i] and MASK21).toInt()] = triPBook[triP.get(i).toInt() and 0xff].toDouble()
                i++
            }
        }
        return scores.entries.sortedByDescending { it.value }.take(n).map { it.key to it.value }
    }

    /** The compact binary form ([readBinary]); the converter writes the same layout. */
    fun writeBinary(out: java.io.OutputStream) {
        val d = java.io.DataOutputStream(java.io.BufferedOutputStream(out, 1 shl 16))
        d.writeInt(MAGIC); d.writeInt(vocab.size); d.writeInt(pairCount); d.writeInt(tripleCount)
        for (i in 0 until vocab.size) { val b = vocab.word(i).toByteArray(); d.writeShort(b.size); d.write(b) }
        // the tables start 8-byte aligned, so they can be viewed in place (d.size() counts bytes written)
        while (d.size() % 8 != 0) d.writeByte(0)
        for (i in 0 until vocab.size) d.writeFloat(uniLogP[i])
        for (i in 0 until vocab.size) d.writeFloat(uniBackoff[i])
        for (x in biPBook) d.writeFloat(x)
        for (x in biBBook) d.writeFloat(x)
        for (i in 0 until biOffsets.limit()) d.writeInt(biOffsets[i])
        for (i in 0 until pairCount) d.writeInt(biWords[i])
        for (i in 0 until pairCount) d.writeByte(biP[i].toInt())
        for (i in 0 until pairCount) d.writeByte(biB[i].toInt())
        for (x in triPBook) d.writeFloat(x)
        while (d.size() % 8 != 0) d.writeByte(0)
        for (i in 0 until tripleCount) d.writeLong(triKeys[i])
        for (i in 0 until tripleCount) d.writeByte(triP[i].toInt())
        d.flush()
    }

    companion object {
        const val START = "<s>"
        private const val MAGIC = 0x44474e32 // "DGN2"
        private const val MASK21 = (1L shl 21) - 1
        private val LN10 = ln(10.0)

        private fun tri(a: Int, b: Int, c: Int): Long = (a.toLong() shl 42) or (b.toLong() shl 21) or c.toLong()

        /** One heap entry per this many pair words / triple keys ([biSample], [triSample]). */
        private const val SAMPLE = 64

        /** Reads [writeBinary]'s layout from a stream (copies it into memory); null if it isn't one. */
        fun readBinary(input: java.io.InputStream): NgramModel? =
            runCatching { fromBuffer(java.nio.ByteBuffer.wrap(input.readBytes())) }.getOrNull()

        /**
         * Views [writeBinary]'s layout in [buf] (big-endian) without copying the tables: pass a
         * memory-mapped file to keep them off the heap. Null if it isn't one.
         */
        fun fromBuffer(buf: java.nio.ByteBuffer, index: java.nio.ByteBuffer? = null): NgramModel? = runCatching {
            val b = buf.duplicate().order(java.nio.ByteOrder.BIG_ENDIAN)
            if (b.int != MAGIC) return null
            val nv = b.int; val nb = b.int; val nt = b.int
            // the words stay in the buffer (a mapped file: off the heap), found through a hash table of ids: read
            // from [index] (written by writeVocabIndex) when it fits, else built here
            val start = b.position()
            val words = (b.duplicate().position(start) as java.nio.ByteBuffer).slice()
            var vocab = index?.let { MappedVocab.readIndex(words, it, nv) }
            if (vocab == null) {
                val offsets = IntArray(nv + 1)
                for (i in 0 until nv) { offsets[i] = b.position() - start; val len = b.short.toInt() and 0xffff; b.position(b.position() + len) }
                offsets[nv] = b.position() - start
                vocab = MappedVocab.build(words, offsets)
            } else {
                for (i in 0 until nv) { val len = b.short.toInt() and 0xffff; b.position(b.position() + len) }
            }
            while (b.position() % 8 != 0) b.get()
            fun floats(n: Int) = (b.slice().order(java.nio.ByteOrder.BIG_ENDIAN).asFloatBuffer().limit(n) as java.nio.FloatBuffer).also { b.position(b.position() + 4 * n) }
            fun ints(n: Int) = (b.slice().order(java.nio.ByteOrder.BIG_ENDIAN).asIntBuffer().limit(n) as java.nio.IntBuffer).also { b.position(b.position() + 4 * n) }
            fun bytes(n: Int) = (b.slice().limit(n) as java.nio.ByteBuffer).also { b.position(b.position() + n) }
            fun book() = FloatArray(256) { b.float }
            val up = floats(nv); val ub = floats(nv)
            val pb = book(); val bb = book()
            val off = ints(nv + 1); val bw = ints(nb)
            val bp = bytes(nb); val bbo = bytes(nb)
            val tb = book()
            while (b.position() % 8 != 0) b.get()
            val tk = (b.slice().order(java.nio.ByteOrder.BIG_ENDIAN).asLongBuffer().limit(nt) as java.nio.LongBuffer).also { b.position(b.position() + 8 * nt) }
            val tp = bytes(nt)
            NgramModel(vocab!!, up, ub, off, bw, bp, bbo, pb, bb, tk, tp, tb)
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
                ArrayVocab(vocab.toTypedArray()), java.nio.FloatBuffer.wrap(uniP.toFloatArray()), java.nio.FloatBuffer.wrap(uniB.toFloatArray()),
                java.nio.IntBuffer.wrap(offsets), java.nio.IntBuffer.wrap(IntArray(bi.size) { (bi[it].first and 0xffffffffL).toInt() }),
                java.nio.ByteBuffer.wrap(pCodes), java.nio.ByteBuffer.wrap(bCodes), pBook, bBook,
                java.nio.LongBuffer.wrap(LongArray(tri.size) { tri[it].first }), java.nio.ByteBuffer.wrap(tCodes), tBook,
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
        return if (w >= model.vocabSize) unigramLogProb else model.logProbOf(a, b, w)
    }
}

/** The model's words: by id, and an id by word. */
internal interface Vocab {
    val size: Int
    fun word(id: Int): String
    /** The id of [word], or -1. */
    fun idOf(word: String): Int
}

/** Words held as strings (a model built in memory). */
internal class ArrayVocab(private val words: Array<String>) : Vocab {
    private val index = HashMap<String, Int>(words.size * 2).also { m -> words.forEachIndexed { i, w -> m[w] = i } }
    override val size: Int get() = words.size
    override fun word(id: Int): String = words[id]
    override fun idOf(word: String): Int = index[word] ?: -1
}

/**
 * Words left in the (memory-mapped) file: [offsets] of each word's UTF-8 bytes in [bytes], and an
 * open-addressing table of ids by the bytes' hash. The string table and HashMap it replaces held
 * 33 MB of heap for the shipped model; this holds about 12 bytes a word, and makes a string only
 * when one is asked for.
 */
/**
 * The vocabulary as stored in the model file (a 2-byte length, then the bytes, per word), found through [offsets]
 * (one per word, plus the end) and a hash table of ids ([table]: id + 1, 0 = empty). Both are built at load
 * (~7 MB of heap for the English vocabulary), or viewed from an index file written once ([writeIndex], kept with
 * the lexicon snapshot): then nothing of the model sits on the heap (2026-11-20, low-RAM phones).
 */
internal class MappedVocab private constructor(
    private val bytes: java.nio.ByteBuffer,
    private val offsets: java.nio.IntBuffer,
    private val table: java.nio.IntBuffer,
) : Vocab {
    override val size: Int get() = offsets.limit() - 1

    /** Each entry: a 2-byte length, then the bytes: [offsets] point at the length. */
    private fun start(id: Int) = offsets[id] + 2
    private fun length(id: Int) = offsets[id + 1] - offsets[id] - 2

    /** [offsets] and [table] for an index file ([readIndex]). */
    fun writeIndex(out: java.io.OutputStream) {
        val d = java.io.DataOutputStream(java.io.BufferedOutputStream(out, 1 shl 16))
        d.writeInt(INDEX_MAGIC); d.writeInt(offsets.limit()); d.writeInt(table.limit())
        for (i in 0 until offsets.limit()) d.writeInt(offsets[i])
        for (i in 0 until table.limit()) d.writeInt(table[i])
        d.flush()
    }

    companion object {
        private const val INDEX_MAGIC = 0x44474e49 // "DGNI"

        /** Builds the index of the words in [bytes] at [offsets] (an array on the heap). */
        fun build(bytes: java.nio.ByteBuffer, offsets: IntArray): MappedVocab {
            val size = offsets.size - 1
            val t = IntArray(Integer.highestOneBit(maxOf(4, size) * 2) shl 1)
            val v = MappedVocab(bytes, java.nio.IntBuffer.wrap(offsets), java.nio.IntBuffer.wrap(t))
            val mask = t.size - 1
            for (id in 0 until size) {
                var h = v.hash(id) and mask
                while (t[h] != 0) h = (h + 1) and mask
                t[h] = id + 1
            }
            return v
        }

        /** The index [writeIndex] wrote, viewed in [index] (mapped), if it fits [words] words; else null. */
        fun readIndex(bytes: java.nio.ByteBuffer, index: java.nio.ByteBuffer, words: Int): MappedVocab? = runCatching {
            val b = index.duplicate().order(java.nio.ByteOrder.BIG_ENDIAN)
            if (b.int != INDEX_MAGIC) return null
            val no = b.int; val nt = b.int
            if (no != words + 1 || nt != Integer.highestOneBit(maxOf(4, words) * 2) shl 1) return null
            val ints = b.slice().order(java.nio.ByteOrder.BIG_ENDIAN).asIntBuffer()
            val off = (ints.duplicate().limit(no) as java.nio.IntBuffer).slice()
            val tab = (ints.duplicate().position(no).limit(no + nt) as java.nio.IntBuffer).slice()
            MappedVocab(bytes, off, tab)
        }.getOrNull()
    }

    private fun hash(id: Int): Int {
        var h = -0x7ee3623b // FNV-1a
        val s = start(id)
        for (k in 0 until length(id)) h = (h xor (bytes[s + k].toInt() and 0xff)) * 0x01000193
        return h
    }

    override fun word(id: Int): String {
        val b = ByteArray(length(id))
        val s = start(id)
        for (k in b.indices) b[k] = bytes[s + k]
        return String(b, Charsets.UTF_8)
    }

    override fun idOf(word: String): Int {
        val w = word.toByteArray(Charsets.UTF_8)
        var h = -0x7ee3623b
        for (x in w) h = (h xor (x.toInt() and 0xff)) * 0x01000193
        val mask = table.limit() - 1
        h = h and mask
        while (true) {
            val e = table[h]
            if (e == 0) return -1
            val id = e - 1
            if (length(id) == w.size) {
                val s = start(id)
                var k = 0
                while (k < w.size && bytes[s + k] == w[k]) k++
                if (k == w.size) return id
            }
            h = (h + 1) and mask
        }
    }
}
