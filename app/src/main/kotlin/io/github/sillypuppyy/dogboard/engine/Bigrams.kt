package io.github.sillypuppyy.dogboard.engine

import java.io.BufferedReader
import kotlin.math.exp
import kotlin.math.ln

/**
 * Bigram counts over lexicon word ids, for word probabilities given the previous word. Stored as
 * one sorted array of (previous, word) keys so a few hundred thousand pairs stay a few MB.
 *
 * File format, see tools/data/bigrams_from_tatoeba.py:
 *     #dogboard-bigrams 1
 *     previous<TAB>word<TAB>count      ("<s>" = start of a sentence)
 */
class Bigrams private constructor(
    private val lexicon: Lexicon,
    private val keys: LongArray,
    private val counts: IntArray,
    private val totals: LongArray,
) {
    /** Id of the sentence-start context; word ids are below it. */
    val sentenceStart: Int = totals.size - 1

    /** Context id for a previous word (lowercase, or [START]); -1 if unknown. */
    fun contextId(previous: String): Int = if (previous == START) sentenceStart else lexicon.idOf(previous)
        .takeIf { it < totals.size - 1 } ?: -1 // words added later have no pairs

    /**
     * ln P(word | previous), interpolated with the unigram probability so unseen pairs keep a
     * reasonable score. Returns [unigramLogProb] when [previous] is unknown or has no pairs.
     */
    fun logProb(previous: Int, word: Int, unigramLogProb: Double): Double {
        if (previous < 0 || previous >= totals.size) return unigramLogProb
        val total = totals[previous]
        if (total == 0L) return unigramLogProb
        val i = keys.binarySearch(key(previous, word))
        val count = if (i >= 0) counts[i] else 0
        return ln(LAMBDA * count / total + (1 - LAMBDA) * exp(unigramLogProb))
    }

    /** The [n] most frequent words after [previous], as (word id, count), most frequent first. */
    fun followers(previous: Int, n: Int): List<Pair<Int, Int>> {
        if (previous < 0) return emptyList()
        var i = keys.binarySearch(key(previous, 0)).let { if (it < 0) -it - 1 else it }
        val best = ArrayList<Pair<Int, Int>>(n + 1)
        while (i < keys.size && (keys[i] ushr 32).toInt() == previous) {
            val entry = (keys[i] and 0xffffffffL).toInt() to counts[i]
            if (best.size < n || entry.second > best.last().second) {
                best.add(entry)
                best.sortByDescending { it.second }
                if (best.size > n) best.removeAt(best.size - 1)
            }
            i++
        }
        return best
    }

    /**
     * P(next letter | [prefix], [previous]) from the words seen after [previous] that start with
     * [prefix] (lowercase); null if there are none.
     */
    fun nextLetterProbs(previous: Int, prefix: String): Map<Char, Double>? {
        if (previous < 0 || previous >= totals.size) return null
        var i = keys.binarySearch(key(previous, 0)).let { if (it < 0) -it - 1 else it }
        val letterCounts = HashMap<Char, Long>()
        var total = 0L
        while (i < keys.size && (keys[i] ushr 32).toInt() == previous) {
            val word = lexicon.word(lexicon.nodeOfWord((keys[i] and 0xffffffffL).toInt()))!!
            if (word.length > prefix.length && word.startsWith(prefix, ignoreCase = true)) {
                val c = word[prefix.length].lowercaseChar()
                letterCounts[c] = (letterCounts[c] ?: 0L) + counts[i]
                total += counts[i]
            }
            i++
        }
        if (total == 0L) return null
        return letterCounts.mapValues { it.value.toDouble() / total }
    }

    /** Writes the pairs for [readSnapshot] (see Lexicon.writeSnapshot). */
    fun writeSnapshot(out: java.io.OutputStream) {
        val d = java.io.DataOutputStream(java.io.BufferedOutputStream(out, 1 shl 16))
        d.writeInt(SNAPSHOT_MAGIC); d.writeInt(keys.size); d.writeInt(totals.size)
        for (k in keys) d.writeLong(k)
        for (c in counts) d.writeInt(c)
        for (t in totals) d.writeLong(t)
        d.flush()
    }

    companion object {
        private const val SNAPSHOT_MAGIC = 0x44474231 // "DGB1"

        /** Reads what [writeSnapshot] wrote for the same [lexicon], or null if it isn't one. */
        fun readSnapshot(bytes: ByteArray, lexicon: Lexicon): Bigrams? = readSnapshot(java.nio.ByteBuffer.wrap(bytes), lexicon)

        /** As [readSnapshot] from a buffer (the snapshot file mapped into memory: no heap copy first). */
        fun readSnapshot(b: java.nio.ByteBuffer, lexicon: Lexicon): Bigrams? = runCatching {
            if (b.int != SNAPSHOT_MAGIC) return null
            val n = b.int
            val contexts = b.int
            val keys = LongArray(n).also { b.asLongBuffer().get(it); b.position(b.position() + 8 * n) }
            val counts = IntArray(n).also { b.asIntBuffer().get(it); b.position(b.position() + 4 * n) }
            val totals = LongArray(contexts).also { b.asLongBuffer().get(it) }
            Bigrams(lexicon, keys, counts, totals)
        }.getOrNull()

        const val FORMAT_HEADER = "#dogboard-bigrams 1"
        const val START = "<s>"

        /** Weight of the bigram estimate against the unigram one. */
        private const val LAMBDA = 0.6

        private fun key(previous: Int, word: Int): Long = (previous.toLong() shl 32) or word.toLong()

        fun parse(reader: BufferedReader, lexicon: Lexicon): Bigrams {
            val header = reader.readLine()
            require(header == FORMAT_HEADER) { "not DogBoard bigrams (header: $header)" }
            val entries = ArrayList<Pair<Long, Int>>()
            val totals = LongArray(lexicon.size + 1)
            reader.forEachLine { line ->
                if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                val parts = line.split('\t')
                require(parts.size == 3) { "bad bigram line: $line" }
                val previous = if (parts[0] == START) lexicon.size else lexicon.idOf(parts[0])
                val word = lexicon.idOf(parts[1])
                if (previous < 0 || word < 0) return@forEachLine
                val count = parts[2].toInt()
                entries.add(key(previous, word) to count)
                totals[previous] += count.toLong()
            }
            entries.sortBy { it.first }
            return Bigrams(
                lexicon,
                LongArray(entries.size) { entries[it].first },
                IntArray(entries.size) { entries[it].second },
                totals,
            )
        }
    }
}
