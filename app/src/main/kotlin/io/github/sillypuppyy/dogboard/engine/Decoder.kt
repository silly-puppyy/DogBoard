package io.github.sillypuppyy.dogboard.engine

import io.github.sillypuppyy.dogboard.touch.GaussianTouch
import io.github.sillypuppyy.dogboard.touch.KeyTarget

/** One tap of the current word, in dp relative to the keyboard's top-left corner. */
data class Tap(val x: Float, val y: Float)

/** A decoded word. Costs are negative log-probabilities: lower is better. */
data class Candidate(
    val word: String,
    /** Total cost: spatial + language model + edit penalties. */
    val cost: Double,
    /** Spatial cost of the taps, including edit penalties. */
    val spatialCost: Double,
    val edits: Int,
    /** True if the taps only cover a prefix of [word]. */
    val isCompletion: Boolean,
)

/**
 * Turns a sequence of taps into likely words: a beam search over the lexicon trie scoring each
 * letter with the Gaussian touch model, allowing a few typing errors (missed letter, extra tap,
 * swapped pair, a key far from the tap).
 */
class Decoder(
    private val lexicon: Lexicon,
    private val config: Config = Config(),
    private val bigrams: Bigrams? = null,
) {
    data class Config(
        /** Converts [GaussianTouch.logLikelihood] (half a Gaussian log density) into a cost. */
        val spatialWeight: Double = 2.0,
        val lmWeight: Double = 1.0,
        /** Cost of a letter the user did not type. */
        val omissionCost: Double = 7.0,
        /** Cost of a tap that belongs to no letter of the word. */
        val insertionCost: Double = 7.0,
        /** Cost of two neighbouring letters typed in the wrong order. */
        val transpositionCost: Double = 6.0,
        /** Spatial cost at which a tap counts as a wrong key (substitution) instead. */
        val substitutionCost: Double = 9.0,
        /** Skipping an apostrophe ("dont" -> "don't") is nearly free: it is not on the letter plane. */
        val apostropheCost: Double = 0.5,
        /** Extra cost for suggesting a longer word than typed. */
        val completionCost: Double = 2.5,
        val maxEdits: Int = 2,
        val beamWidth: Int = 100, // accuracy unchanged down to 80 in the eval, ~35% faster than 300
    )

    private class State(val node: Lexicon.Node, val cost: Double, val edits: Int)

    /**
     * @param taps the taps of the current word
     * @param keys letter (lowercase) -> touch target, in the same dp space as [taps]
     * @param params touch-model parameters for the current key size and typing speed
     * @param maxResults number of candidates to return, best first
     * @param previous context id of the previous word ([Bigrams.contextId]), -1 for none
     */
    fun decode(
        taps: List<Tap>,
        keys: Map<Char, KeyTarget>,
        params: GaussianTouch.Params,
        maxResults: Int = 8,
        includeCompletions: Boolean = true,
        previous: Int = -1,
    ): List<Candidate> {
        val n = taps.size
        if (n == 0) return emptyList()
        val maxEdits = if (n <= 2) 1 else config.maxEdits
        val layers = Array(n + 1) { HashMap<Lexicon.Node, State>() }
        layers[0][lexicon.root] = State(lexicon.root, 0.0, 0)

        fun offer(layer: HashMap<Lexicon.Node, State>, node: Lexicon.Node, cost: Double, edits: Int): Boolean {
            if (edits > maxEdits) return false
            val old = layer[node]
            if (old != null && old.cost <= cost) return false
            layer[node] = State(node, cost, edits)
            return true
        }

        fun letterCost(c: Char, tap: Tap): Pair<Double, Int>? {
            val key = keys[c] ?: return null
            val cost = -config.spatialWeight * GaussianTouch.logLikelihood(key, tap.x, tap.y, params)
            return if (cost > config.substitutionCost) config.substitutionCost to 1 else cost to 0
        }

        for (i in 0..n) {
            val layer = layers[i]
            // Omitted letters and apostrophes do not consume a tap, so they extend this layer.
            val queue = ArrayDeque(layer.values)
            while (queue.isNotEmpty()) {
                val s = queue.removeFirst()
                if (layer[s.node] !== s) continue
                for (k in 0 until s.node.childCount) {
                    val c = s.node.keyAt(k)
                    val child = s.node.childAt(k)
                    val (cost, edits) = when {
                        c == '\'' -> config.apostropheCost to 0
                        i == 0 -> continue // a word does not start with a letter the user skipped
                        else -> config.omissionCost to 1
                    }
                    if (offer(layer, child, s.cost + cost, s.edits + edits)) queue.addLast(layer[child]!!)
                }
            }
            prune(layer)
            if (i == n) break

            val tap = taps[i]
            for (s in layer.values) {
                for (k in 0 until s.node.childCount) {
                    val c = s.node.keyAt(k)
                    val child = s.node.childAt(k)
                    letterCost(c, tap)?.let { (cost, edits) ->
                        offer(layers[i + 1], child, s.cost + cost, s.edits + edits)
                    }
                    // Swapped pair: this tap belongs to the next letter, the next tap to this one.
                    if (i + 1 < n) {
                        val (c1, e1) = letterCost(c, taps[i + 1]) ?: continue
                        for (k2 in 0 until child.childCount) {
                            val c2 = child.keyAt(k2)
                            if (c2 == c) continue
                            val (c2cost, e2) = letterCost(c2, tap) ?: continue
                            if (e1 + e2 > 0) continue
                            offer(layers[i + 2], child.childAt(k2), s.cost + c1 + c2cost + config.transpositionCost, s.edits + 1)
                        }
                    }
                }
                // Extra tap that belongs to no letter (not the first one).
                if (i > 0) offer(layers[i + 1], s.node, s.cost + config.insertionCost, s.edits + 1)
            }
        }

        val results = HashMap<String, Candidate>()
        fun add(candidate: Candidate) {
            val old = results[candidate.word]
            if (old == null || candidate.cost < old.cost) results[candidate.word] = candidate
        }
        fun lm(node: Lexicon.Node): Double =
            bigrams?.logProb(previous, node.id, node.logProb) ?: node.logProb
        for (s in layers[n].values) {
            if (s.node.isWord) {
                add(Candidate(s.node.word!!, s.cost - config.lmWeight * lm(s.node), s.cost, s.edits, false))
            }
            if (includeCompletions) {
                val best = s.node.bestWord ?: continue
                if (best === s.node) continue
                add(Candidate(best.word!!, s.cost + config.completionCost - config.lmWeight * lm(best), s.cost, s.edits, true))
            }
        }
        return results.values.sortedBy { it.cost }.take(maxResults)
    }

    /**
     * Spatial cost of reading [taps] as exactly [word] (one tap per letter), or null if a letter
     * is not on the keyboard or the lengths differ. Used for the "keep what was typed" choice.
     */
    fun literalCost(taps: List<Tap>, word: String, keys: Map<Char, KeyTarget>, params: GaussianTouch.Params): Double? {
        if (taps.size != word.length) return null
        var cost = 0.0
        for ((i, c) in word.withIndex()) {
            val key = keys[c.lowercaseChar()] ?: return null
            cost -= config.spatialWeight * GaussianTouch.logLikelihood(key, taps[i].x, taps[i].y, params)
        }
        return cost
    }

    /** Keeps the best states, ranked by cost plus the best word reachable below each node. */
    private fun prune(layer: HashMap<Lexicon.Node, State>) {
        if (layer.size <= config.beamWidth) return
        val keep = layer.values
            .sortedBy { it.cost - config.lmWeight * (it.node.bestWord?.logProb ?: -50.0) }
            .take(config.beamWidth)
        layer.clear()
        for (s in keep) layer[s.node] = s
    }
}
