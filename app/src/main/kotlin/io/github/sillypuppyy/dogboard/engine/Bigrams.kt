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
    /** Contexts with pairs, sorted, and each one's total count (sparse: one slot per lexicon word cost Russian 11 MB). */
    private val totalIds: IntArray,
    private val totalCounts: LongArray,
    /** Context ids in use: every lexicon word at load, plus the sentence start (the last). */
    private val contexts: Int,
) {
    /** Id of the sentence-start context; word ids are below it. */
    val sentenceStart: Int = contexts - 1

    /** [previous]'s total count, 0 when it has no pairs. */
    private fun total(previous: Int): Long {
        val i = java.util.Arrays.binarySearch(totalIds, previous)
        return if (i < 0) 0L else totalCounts[i]
    }

    /** Context id for a previous word (lowercase, or [START]); -1 if unknown. */
    fun contextId(previous: String): Int = if (previous == START) sentenceStart else lexicon.idOf(previous)
        .takeIf { it < contexts - 1 } ?: -1 // words added later have no pairs

    /**
     * ln P(word | previous), interpolated with the unigram probability so unseen pairs keep a
     * reasonable score. Returns [unigramLogProb] when [previous] is unknown or has no pairs.
     */
    fun logProb(previous: Int, word: Int, unigramLogProb: Double): Double {
        if (previous < 0 || previous >= contexts) return unigramLogProb
        val total = total(previous)
        if (total == 0L) return unigramLogProb
        val i = keys.binarySearch(key(previous, word))
        val count = if (i >= 0) counts[i] else 0
        return ln(LAMBDA * count / total + (1 - LAMBDA) * exp(unigramLogProb))
    }

    /** The [n] most frequent words after [previous], as (word id, count), most frequent first. */
    fun followers(previous: Int, n: Int): List<Pair<Int, Int>> {
        if (previous < 0) return emptyList()
        if (n <= 0) return emptyList()
        var i = keys.binarySearch(key(previous, 0)).let { if (it < 0) -it - 1 else it }
        // the best n in primitive arrays, kept in order (a common word has thousands of followers: a Pair and
        // a full sort per follower was this call's garbage); same order as before: an entry goes after every
        // kept one with an equal or higher count
        val words = IntArray(n); val best = IntArray(n); var size = 0
        while (i < keys.size && (keys[i] ushr 32).toInt() == previous) {
            val c = counts[i]
            if (size < n || c > best[size - 1]) {
                var at = size
                while (at > 0 && best[at - 1] < c) at--
                val end = minOf(size, n - 1)
                for (k in end downTo at + 1) { words[k] = words[k - 1]; best[k] = best[k - 1] }
                words[at] = (keys[i] and 0xffffffffL).toInt(); best[at] = c
                if (size < n) size++
            }
            i++
        }
        return List(size) { words[it] to best[it] }
    }

    /**
     * P(next letter | [prefix], [previous]) from the words seen after [previous] that start with
     * [prefix] (lowercase); null if there are none.
     */
    fun nextLetterProbs(previous: Int, prefix: String): Map<Char, Double>? {
        if (previous < 0 || previous >= contexts) return null
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
        d.writeInt(SNAPSHOT_MAGIC); d.writeInt(keys.size); d.writeInt(contexts)
        for (k in keys) d.writeLong(k)
        for (c in counts) d.writeInt(c)
        // the format keeps one total per context (unchanged), written from the sparse table
        var j = 0
        for (id in 0 until contexts) d.writeLong(if (j < totalIds.size && totalIds[j] == id) totalCounts[j++] else 0L)
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
            // read straight into the sparse table (no array of every context first)
            val view = b.asLongBuffer()
            val ids = ArrayList<Int>(); val tots = ArrayList<Long>()
            for (id in 0 until contexts) { val t = view.get(id); if (t != 0L) { ids.add(id); tots.add(t) } }
            Bigrams(lexicon, keys, counts, ids.toIntArray(), tots.toLongArray(), contexts)
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
            val ids = (0 until totals.size).filter { totals[it] != 0L }.toIntArray()
            return Bigrams(
                lexicon,
                LongArray(entries.size) { entries[it].first },
                IntArray(entries.size) { entries[it].second },
                ids, LongArray(ids.size) { totals[ids[it]] }, totals.size,
            )
        }
    }
}
