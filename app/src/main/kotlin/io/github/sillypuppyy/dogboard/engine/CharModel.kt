package io.github.sillypuppyy.dogboard.engine

import java.io.BufferedReader

/**
 * How plausible a word our lexicon lacks is, letter by letter: a 5-gram over a-z and the
 * apostrophe, trained on the words FineWeb has and our lexicon doesn't (names, slang, brands;
 * tools/data/build_charlm.py). "Kaito" and "Gumroad" score like words, "thw" and "xqzt" don't.
 * It stands in for iOS's neural model in its exact-typed-string veto.
 *
 * Stored as sorted long keys (context letters and the next letter, 5 bits each) with float values,
 * so the ~330k entries take a few MB and no strings.
 */
class CharModel private constructor(
    /** ln P(a word is one our lexicon lacks), from the training corpus. */
    val unknownLogProb: Double,
    private val keys: LongArray,
    private val values: FloatArray,
) {
    /** ln P(word | unknown word), or null if [word] has letters the model doesn't know. */
    fun logProb(word: String): Double? {
        val codes = IntArray(word.length + 1)
        for ((i, c) in word.withIndex()) codes[i] = code(Lexicon.fold(c)).takeIf { it > 0 } ?: return null
        codes[word.length] = END
        var total = 0.0
        for (i in codes.indices) {
            var ctx = 0L
            var len = 0
            // the longest history first: up to ORDER - 1 letters, padded with word starts
            for (k in 1..ORDER - 1) {
                val j = i - k
                val cd = if (j >= 0) codes[j] else START
                ctx = ctx or (cd.toLong() shl (5 * (k - 1)))
                len = k
            }
            total += prob(ctx, len, codes[i])
        }
        return total
    }

    /** ln P(next | the last [len] letters of [ctx]), backing off through shorter histories. */
    private fun prob(ctx: Long, len: Int, next: Int): Double {
        var backoff = 0.0
        var l = len
        while (l >= 0) {
            val c = ctx and ((1L shl (5 * l)) - 1)
            lookup(pairKey(c, l, next))?.let { return backoff + it }
            lookup(contextKey(c, l))?.let { backoff += it }
            l--
        }
        return backoff + UNSEEN
    }

    private fun lookup(key: Long): Double? {
        val i = keys.binarySearch(key)
        return if (i >= 0) values[i].toDouble() else null
    }

    companion object {
        const val ORDER = 5
        private const val START = 28
        private const val END = 29
        private val UNSEEN = kotlin.math.ln(1.0 / 30)

        private fun code(c: Char): Int = when (c) {
            in 'a'..'z' -> c - 'a' + 1
            '\'', '’' -> 27
            '^' -> START
            '$' -> END
            else -> 0
        }

        // a context of [len] letters (5 bits each) in bits 0..19, its length in bits 20..22, the
        // next letter (or 31 for the context's own back-off entry) in bits 23..27
        private fun pairKey(ctx: Long, len: Int, next: Int): Long = ctx or (len.toLong() shl 20) or (next.toLong() shl 23)
        private fun contextKey(ctx: Long, len: Int): Long = pairKey(ctx, len, 31)

        /**
         * Reads the binary form (build_charlm.py --binary: "DGC1", ln P(unknown), the entry count,
         * the sorted keys, then the values; big-endian): one bulk read, no parsing. Null if it isn't one.
         */
        fun read(input: java.io.InputStream): CharModel? {
            val bytes = input.readBytes()
            val buf = java.nio.ByteBuffer.wrap(bytes)
            if (bytes.size < 16 || bytes[0] != 'D'.code.toByte() || bytes[1] != 'G'.code.toByte() ||
                bytes[2] != 'C'.code.toByte() || bytes[3] != '1'.code.toByte()
            ) return null
            buf.position(4)
            val unknown = buf.double
            val n = buf.int
            if (n <= 0 || bytes.size != 16 + n * 12) return null
            val keys = LongArray(n).also { buf.asLongBuffer().get(it) }
            buf.position(16 + n * 8)
            val values = FloatArray(n).also { buf.asFloatBuffer().get(it) }
            return CharModel(unknown, keys, values)
        }

        /** Reads build_charlm.py's text output; null if it isn't one. */
        fun parse(reader: BufferedReader): CharModel? {
            var unknown = Double.NaN
            val entries = ArrayList<Pair<Long, Float>>(400_000)
            fun ctxOf(s: String): Long? {
                var ctx = 0L
                // the string's last letter is the most recent: bits 0..4
                for ((k, ch) in s.reversed().withIndex()) ctx = ctx or ((code(ch).takeIf { it > 0 } ?: return null).toLong() shl (5 * k))
                return ctx
            }
            reader.forEachLine { line ->
                if (line.startsWith("#unknown_logprob ")) unknown = line.substringAfter(' ').toDouble()
                if (line.startsWith("#")) return@forEachLine
                val f = line.split('\t')
                when {
                    f[0] == "c" && f.size == 3 -> ctxOf(f[1])?.let { entries.add(contextKey(it, f[1].length) to f[2].toFloat()) }
                    f[0] == "p" && f.size == 4 -> {
                        val next = code(f[2].single())
                        if (next > 0) ctxOf(f[1])?.let { entries.add(pairKey(it, f[1].length, next) to f[3].toFloat()) }
                    }
                }
            }
            if (unknown.isNaN() || entries.isEmpty()) return null
            entries.sortBy { it.first }
            return CharModel(unknown, LongArray(entries.size) { entries[it].first }, FloatArray(entries.size) { entries[it].second })
        }
    }
}
