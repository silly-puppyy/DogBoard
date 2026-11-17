package io.github.sillypuppyy.dogboard.engine.swipe

import io.github.sillypuppyy.dogboard.engine.Lexicon
import kotlin.math.exp
import kotlin.math.ln1p

/**
 * Lexicon-constrained CTC beam (tools/swipe/decode.py, same algorithm), as iOS decodes swipes: the
 * swipe model's per-point letter log-probabilities walked through the lexicon's letter tree, each
 * finished word scored with its prior (ln P x [alpha]). Apostrophes cost nothing: "dont" reaches
 * "don't" (iOS's equivalent forms). Sentence context comes after, as for taps.
 */
object SwipeBeam {
    private const val NEG = -1e30
    private const val BLANK = 0

    class Candidate(val node: Int, val score: Double)

    private fun lse(a: Double, b: Double): Double {
        val hi = maxOf(a, b); val lo = minOf(a, b)
        return if (lo <= NEG) hi else hi + ln1p(exp(lo - hi))
    }

    private class Beam(var blank: Double, var letter: Double, val last: Int)

    /**
     * [logp]: [steps x 27] row-major natural log-probs (0 = blank, 1..26 = a..z). The best [top] word
     * nodes in [lexicon], best first. [minLogProb] leaves out words rarer than that (ln P).
     */
    fun decode(
        logp: FloatArray, steps: Int, lexicon: Lexicon, width: Int = 48, alpha: Double = 1.0,
        top: Int = 5, prune: Float = -8f, minLogProb: Double = Double.NEGATIVE_INFINITY,
    ): List<Candidate> {
        var beams = HashMap<Int, Beam>().apply { put(Lexicon.ROOT, Beam(0.0, NEG, -1)) }
        val allowed = IntArray(26)
        for (t in 0 until steps) {
            val row = 27 * t
            var nAllowed = 0
            for (k in 1..26) if (logp[row + k] > prune) allowed[nAllowed++] = k
            val next = HashMap<Int, Beam>(beams.size * 4)
            for ((node, b) in beams) {
                val total = lse(b.blank, b.letter)
                val stay = next.getOrPut(node) { Beam(NEG, NEG, b.last) }
                stay.blank = lse(stay.blank, total + logp[row + BLANK])
                if (b.last > 0) stay.letter = lse(stay.letter, b.letter + logp[row + b.last])
                for (i in 0 until nAllowed) {
                    val k = allowed[i]
                    val c = 'a' + (k - 1)
                    // a repeated letter needs a blank in between
                    val p = (if (b.last == k) b.blank else total) + logp[row + k]
                    fun extend(child: Int) {
                        if (child == Lexicon.NONE) return
                        val e = next.getOrPut(child) { Beam(NEG, NEG, k) }
                        e.letter = lse(e.letter, p)
                    }
                    extend(lexicon.child(node, c))
                    val apo = lexicon.child(node, '\'')
                    if (apo != Lexicon.NONE) extend(lexicon.child(apo, c))
                }
            }
            beams = if (next.size <= width) next else HashMap<Int, Beam>(width * 2).apply {
                next.entries.sortedByDescending { lse(it.value.blank, it.value.letter) }.take(width).forEach { put(it.key, it.value) }
            }
        }
        return beams.mapNotNull { (node, b) ->
            if (!lexicon.isWord(node)) return@mapNotNull null
            val lp = lexicon.logProb(node)
            if (lp < minLogProb) null else Candidate(node, lse(b.blank, b.letter) + alpha * lp)
        }.sortedByDescending { it.score }.take(top)
    }
}
