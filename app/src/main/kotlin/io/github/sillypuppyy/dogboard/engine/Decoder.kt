package io.github.sillypuppyy.dogboard.engine

import io.github.sillypuppyy.dogboard.touch.GaussianTouch
import io.github.sillypuppyy.dogboard.touch.KeyTarget
import kotlin.math.ln

/**
 * One tap of the current word, in dp relative to the keyboard's top-left corner, and when it
 * happened ([timeMs], uptime; 0 if unknown).
 */
data class Tap(val x: Float, val y: Float, val timeMs: Long = 0L)

/**
 * A decoded word, ranked by [score] (natural-log, higher is better):
 * score = geometry + w * languageModel, as in the iOS keyboard.
 */
data class Candidate(
    val word: String,
    /** Total score ω. */
    val score: Double,
    /** Tap likelihood plus typing-error costs. */
    val geometry: Double,
    /** ln P(word | previous word), or the unknown-word estimate for the typed string. */
    val languageModel: Double,
    val edits: Int,
    /** True if the taps only cover a prefix of [word]. */
    val isCompletion: Boolean,
    /** True for the typed letters themselves when they are not a known word. */
    val isTypedString: Boolean = false,
)

/**
 * Turns a sequence of taps into likely words: a beam search over the lexicon trie scoring each
 * tap with the Gaussian touch model plus the iOS typing-error costs (a missed key, an extra tap,
 * a swapped pair, a far key), whose size depends on the time between taps.
 */
class Decoder(
    private val lexicon: Lexicon,
    private val config: Config = Config(),
    private val bigrams: Bigrams? = null,
) {
    data class Config(
        /** Language power: 0.24 with a context model, 0.35 without (iOS k_linguistic_context_power). */
        val languagePower: Double = LANGUAGE_POWER_CONTEXT,
        val beamWidth: Int = 100,
    )

    companion object {
        const val LANGUAGE_POWER_CONTEXT = 0.24
        const val LANGUAGE_POWER_UNIGRAM = 0.35

        /** Edit costs: a base plus QUADRATIC * t², t = time since the previous tap clamped to [T_MIN, T_MAX] s. */
        private const val QUADRATIC = -8.16327 // = -1 / 0.35²
        private const val T_MIN = 0.15
        private const val T_MAX = 0.5
        private const val INSERTION = -2.0 // a key the user did not tap
        private const val DELETION_REPEATED = -2.24 // an extra tap on the same key as the one before
        private const val DELETION = -3.2 // any other extra tap
        private val DELETION_LANGUAGE = 0.24 * ln(0.01) // extra language cost for an unrepeated extra tap
        private const val SUBSTITUTION = -3.2 // a tap read as a key outside its nearest keys
        private const val TRANSPOSITION = -2.0
        private const val NEARBY_KEYS = 13

        /** Language score per letter once the typed letters leave every known word. */
        private val UNKNOWN_LETTER = ln(1e-4)

        /** Completion cost by typed stem length (index min(L, 5)), and per extra completed letter. */
        private val PREDICTION_COSTS = doubleArrayOf(0.001, 0.03, 0.0005, 0.0004, 0.0005, 0.0006).map { ln(it) }
        private val PREDICTION_PER_LETTER = ln(0.8)

        /** A finished word may have at most this many more letters than taps. */
        private const val MAX_EXTRA_LETTERS = 2

        /** Time between taps assumed for a first tap with no previous one, in seconds. */
        private const val DEFAULT_INTERVAL_S = 0.3
    }

    private class State(val node: Lexicon.Node, val geometry: Double, val edits: Int)

    /**
     * @param taps the taps of the current word
     * @param keys letter (lowercase) -> touch target, in the same dp space as [taps]
     * @param params touch-model parameters for the current key size and typing speed
     * @param maxResults number of candidates to return, best first
     * @param previous context id of the previous word ([Bigrams.contextId]), -1 for none
     * @param typed the letters of the keys that were hit; added as a candidate if not a known word
     */
    fun decode(
        taps: List<Tap>,
        keys: Map<Char, KeyTarget>,
        params: GaussianTouch.Params,
        maxResults: Int = 8,
        includeCompletions: Boolean = true,
        previous: Int = -1,
        typed: String? = null,
    ): List<Candidate> {
        val n = taps.size
        if (n == 0) return emptyList()
        val w = config.languagePower

        // Per-tap quadratic time term and the 13 nearest keys.
        val q = DoubleArray(n)
        val qRaw = DoubleArray(n)
        for (i in 0 until n) {
            val dtS = if (i > 0 && taps[i].timeMs > 0 && taps[i - 1].timeMs > 0) {
                (taps[i].timeMs - taps[i - 1].timeMs) / 1000.0
            } else DEFAULT_INTERVAL_S
            q[i] = QUADRATIC * dtS.coerceIn(T_MIN, T_MAX).let { it * it }
            qRaw[i] = QUADRATIC * dtS * dtS
        }
        val likelihood = Array(n) { i -> keys.mapValues { (_, k) -> GaussianTouch.logLikelihood(k, taps[i].x, taps[i].y, params) } }
        val nearby = Array(n) { i -> likelihood[i].entries.sortedByDescending { it.value }.take(NEARBY_KEYS).map { it.key }.toSet() }
        val nearest = CharArray(n) { i -> likelihood[i].maxBy { it.value }.key }

        fun tapScore(c: Char, i: Int): Pair<Double, Int>? {
            val ll = likelihood[i][c] ?: return null
            return if (c in nearby[i]) ll to 0 else (SUBSTITUTION + q[i] + ll) to 1
        }

        val layers = Array(n + 1) { HashMap<Lexicon.Node, State>() }
        layers[0][lexicon.root] = State(lexicon.root, 0.0, 0)
        fun offer(layer: HashMap<Lexicon.Node, State>, node: Lexicon.Node, geometry: Double, edits: Int) {
            val old = layer[node]
            if (old == null || geometry > old.geometry) layer[node] = State(node, geometry, edits)
        }

        for (i in 0..n) {
            val layer = layers[i]
            // Keys the user did not tap (and apostrophes) consume no tap, so they extend this layer:
            // one skipped key per position, optionally after an apostrophe (iOS compares words by
            // a sort key without apostrophes, so "dont" reaches "don't" at no cost).
            fun insertAfter(s: State, allowApostrophe: Boolean) {
                if (s.node.depth >= i + MAX_EXTRA_LETTERS + 1) return
                for (k in 0 until s.node.childCount) {
                    val c = s.node.keyAt(k)
                    val child = s.node.childAt(k)
                    if (c == '\'' || c == '’') {
                        if (!allowApostrophe) continue
                        offer(layer, child, s.geometry, s.edits)
                        layer[child]?.let { insertAfter(it, allowApostrophe = false) }
                    } else if (i > 0) { // no inserting before the first tap
                        offer(layer, child, s.geometry + INSERTION + q[minOf(i, n - 1)], s.edits + 1)
                    }
                }
            }
            for (s in layer.values.toList()) insertAfter(s, allowApostrophe = true)
            prune(layer, w)
            if (i == n) break

            for (s in layer.values) {
                for (k in 0 until s.node.childCount) {
                    val c = s.node.keyAt(k)
                    val child = s.node.childAt(k)
                    tapScore(c, i)?.let { (score, edit) -> offer(layers[i + 1], child, s.geometry + score, s.edits + edit) }
                    // Swapped pair: this tap belongs to the next letter, the next tap to this one.
                    if (i + 1 < n) {
                        val ll1 = likelihood[i + 1][c] ?: continue
                        for (k2 in 0 until child.childCount) {
                            val c2 = child.keyAt(k2)
                            if (c2 == c) continue
                            val ll2 = likelihood[i][c2] ?: continue
                            offer(layers[i + 2], child.childAt(k2), s.geometry + TRANSPOSITION + qRaw[i + 1] + ll1 + ll2, s.edits + 1)
                        }
                    }
                }
                // An extra tap that belongs to no letter (not the first one).
                if (i > 0) {
                    val extra = if (nearest[i] == nearest[i - 1]) DELETION_REPEATED + q[i] else DELETION + q[i] + DELETION_LANGUAGE
                    offer(layers[i + 1], s.node, s.geometry + extra, s.edits + 1)
                }
            }
        }

        fun lm(node: Lexicon.Node): Double = bigrams?.logProb(previous, node.id, node.logProb) ?: node.logProb
        val results = HashMap<String, Candidate>()
        fun add(candidate: Candidate) {
            val old = results[candidate.word]
            if (old == null || candidate.score > old.score) results[candidate.word] = candidate
        }
        for (s in layers[n].values) {
            if (s.node.isWord) {
                val l = lm(s.node)
                add(Candidate(s.node.word!!, s.geometry + w * l, s.geometry, l, s.edits, isCompletion = false))
            }
            if (includeCompletions) {
                val best = s.node.bestWord ?: continue
                if (best === s.node) continue
                val added = best.depth - s.node.depth
                val geometry = s.geometry + w * (PREDICTION_COSTS[minOf(s.node.depth, 5)] + (added - 1) * PREDICTION_PER_LETTER)
                val l = lm(best)
                add(Candidate(best.word!!, geometry + w * l, geometry, l, s.edits, isCompletion = true))
            }
        }
        if (typed != null && typed.length == n && !lexicon.contains(typed)) {
            var geometry = 0.0
            for ((i, c) in typed.withIndex()) geometry += likelihood[i][c.lowercaseChar()] ?: continue
            val l = typedStringLogProb(typed)
            add(Candidate(typed, geometry + w * l, geometry, l, 0, isCompletion = false, isTypedString = true))
        }
        return results.values.sortedByDescending { it.score }.take(maxResults)
    }

    /**
     * Language score of letters that are not a known word: ln P(letter | prefix) through the
     * lexicon while some known word continues them, [UNKNOWN_LETTER] for each letter after that
     * and for the missing word end.
     */
    fun typedStringLogProb(typed: String): Double {
        var node: Lexicon.Node? = lexicon.root
        var score = 0.0
        for (c in typed) {
            val child = node?.child(c.lowercaseChar())
            score += if (node != null && child != null && node.mass > 0.0) ln(child.mass / node.mass) else UNKNOWN_LETTER
            node = child
        }
        return score + UNKNOWN_LETTER
    }

    /** Keeps the best states, ranked by geometry plus the best word reachable below each node. */
    private fun prune(layer: HashMap<Lexicon.Node, State>, w: Double) {
        if (layer.size <= config.beamWidth) return
        val keep = layer.values
            .sortedByDescending { it.geometry + w * (it.node.bestWord?.logProb ?: -50.0) }
            .take(config.beamWidth)
        layer.clear()
        for (s in keep) layer[s.node] = s
    }
}
