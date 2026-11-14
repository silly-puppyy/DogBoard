package io.github.sillypuppyy.dogboard.engine

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * Reads [TapNet]'s per-frame log-probabilities as lexicon words: a CTC prefix beam search kept on the
 * lexicon trie, each finished word scored ln P_ctc + [lmAlpha] · ln P_lm (iOS's settings: beam 40,
 * lmAlpha 0.8). The LM is the lexicon's word
 * probability, or [context] when given (the decoder's context scorer). Single words only (the word
 * separator and completions iOS also does come later).
 */
class TapNetDecoder(
    private val lexicon: Lexicon,
    private val beam: Int = 40,
    private val lmAlpha: Double = 0.8,
) {
    data class Reading(val word: String, val ctc: Double, val lm: Double, val score: Double)

    /** The best [max] words for [logp] (frames × symbols: 0 blank, 1-26 a-z, 27 apostrophe). */
    fun decode(logp: Array<FloatArray>, max: Int = 8, context: ((node: Int) -> Double)? = null): List<Reading> {
        // per trie node: ln P(prefix, ending in blank), ln P(prefix, ending in its last symbol)
        var beams = HashMap<Int, DoubleArray>().apply { put(Lexicon.ROOT, doubleArrayOf(0.0, NEG)) }
        val lastSym = HashMap<Int, Int>().apply { put(Lexicon.ROOT, 0) }
        for (frame in logp) {
            val next = HashMap<Int, DoubleArray>()
            fun add(node: Int, blank: Double, nonBlank: Double) {
                val p = next.getOrPut(node) { doubleArrayOf(NEG, NEG) }
                p[0] = lse(p[0], blank); p[1] = lse(p[1], nonBlank)
            }
            for ((node, p) in beams) {
                val total = lse(p[0], p[1])
                add(node, total + frame[0], NEG) // a blank keeps the prefix
                val last = lastSym[node] ?: 0
                if (last != 0) add(node, NEG, p[1] + frame[last]) // the same symbol again, merged
                lexicon.forEachChild(node) { key, child ->
                    val sym = symbol(key)
                    if (sym != 0) {
                        // after the same symbol, only a blank in between makes it a new letter
                        val from = if (sym == last) p[0] else total
                        add(child, NEG, from + frame[sym])
                        lastSym[child] = sym
                    }
                }
            }
            beams = HashMap(next.entries.sortedByDescending { lse(it.value[0], it.value[1]) }.take(beam * 4).associate { it.key to it.value })
        }
        val out = ArrayList<Reading>()
        for ((node, p) in beams) {
            if (!lexicon.isWord(node)) continue
            val ctc = lse(p[0], p[1])
            val lm = context?.invoke(node) ?: lexicon.logProb(node)
            out.add(Reading(lexicon.word(node) ?: continue, ctc, lm, ctc + lmAlpha * lm))
        }
        return out.sortedByDescending { it.score }.take(max)
    }

    private fun symbol(key: Char): Int = when (key) {
        in 'a'..'z' -> key - 'a' + 1
        '\'', '’' -> 27
        else -> 0
    }

    private companion object {
        const val NEG = -1e30
        fun lse(a: Double, b: Double): Double {
            if (a <= NEG) return b
            if (b <= NEG) return a
            val m = max(a, b)
            return m + ln(exp(a - m) + exp(b - m))
        }
    }
}
