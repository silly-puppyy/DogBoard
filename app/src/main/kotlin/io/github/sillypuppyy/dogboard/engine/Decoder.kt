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
    /** How many words [word] is: more than one when the taps ran words together ("helloeveryone"). */
    val words: Int = 1,
    /**
     * ln of (the words' probability in context / their probability alone), for iOS's check that a
     * multi-word correction has linguistic support (Rule 3).
     */
    val contextSupport: Double = 0.0,
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
    /** A two-word-context model ([NgramScorer]); when set it scores words instead of [bigrams]. */
    context: ContextScorer? = null,
) {
    /**
     * [c] (one word) as if it followed [previous] (and [previous2]): its geometry kept, its language
     * model redone; a cheap stand-in for decoding the same taps again in another context.
     */
    fun rescored(c: Candidate, previous: Int, previous2: Int = -1): Candidate {
        if (c.words != 1 || c.isTypedString) return c
        val node = lexicon.find(c.word).takeIf { it != Lexicon.NONE && lexicon.isWord(it) } ?: return c
        val nodeLp = lexicon.logProb(node)
        val base = context?.logProb(previous2, previous, lexicon.wordId(node), nodeLp)
            ?: bigrams?.logProb(previous, lexicon.wordId(node), nodeLp) ?: nodeLp
        val formLp = lexicon.forms(node).firstOrNull { it.first == c.word }?.second ?: nodeLp
        val l = base + (formLp - nodeLp)
        return c.copy(score = c.geometry + config.languagePower * l, languageModel = l)
    }

    /** How plausible a word our lexicon lacks is ([CharModel]); null: iOS's dead-trie fallback alone. */
    @Volatile
    var unknownWords: CharModel? = null

    /** Can be attached after loading (TypingEngine.attachNgrams), so startup isn't slowed. */
    @Volatile
    var context: ContextScorer? = context
    /** ln P(word | prev2 prev) for lexicon word ids and [Bigrams.contextId]-style context ids (-1: none). */
    fun interface ContextScorer {
        fun logProb(prev2: Int, prev: Int, wordId: Int, unigramLogProb: Double): Double
    }

    data class Config(
        /** Language power: 0.24 with a context model, 0.35 without (iOS k_linguistic_context_power). */
        val languagePower: Double = LANGUAGE_POWER_CONTEXT,
        val beamWidth: Int = 100,
        /** Split taps that run words together into several words ("helloeveryone"). */
        val splitWords: Boolean = true,
        /**
         * A tap beyond the pruning radius may still be read as any key at this cost (+ the time term + the
         * radius's likelihood), for taps that land far off; null = never, as on iOS. 1.5 (eval 2026-11-15,
         * stray taps from the IMK data at realistic 0.5-1 %: control 97.9 -> 98.2 / 97.3 -> 97.7, rare
         * 95.6 -> 95.9 / 94.9 -> 95.3, clean taps identical word for word).
         */
        val farTap: Double? = FAR_TAP,
        /**
         * Cost of the one key the user skipped after the last tap ("befor" -> "before"; iOS inserts a key
         * only before a later touch, the space, at the same cost as any skipped key). null = INSERTION.
         * -1.0 with Autocorrect.Level.minStemEndOmission (2026-11-16, RealTypoEvalTest sweep).
         */
        val endInsertion: Double? = END_INSERTION,
        /**
         * Added to a tap read as the space bar (iOS: none, the touch cost only). -1.0 (2026-11-16, 831
         * real run-together words from Aalto first attempts, fixed / wrong word: no space key 42.1 / 44.9,
         * 0.0 72.7 / 15.3, -1.0 73.4 / 13.8, -2.0 70.8 / 16.4; single-word real typos 47.9 / 12.4 without,
         * 47.7 / 12.6 at -1.0, correct words broken 0.6 throughout).
         */
        val spaceTap: Double = SPACE_TAP,
    )

    companion object {
        const val LANGUAGE_POWER_CONTEXT = 0.24
        const val LANGUAGE_POWER_UNIGRAM = 0.35

        /** [Config.farTap]'s default: DogBoard's addition to the iOS edit costs. */
        const val FAR_TAP = 1.5

        /** Edit costs: a base plus QUADRATIC * t², t = time since the previous tap clamped to [T_MIN, T_MAX] s. */
        private const val QUADRATIC = -8.16327 // = -1 / 0.35²
        private const val T_MIN = 0.15
        private const val T_MAX = 0.5
        private const val INSERTION = -2.0 // a key the user did not tap
        private const val DELETION_REPEATED = -2.24 // an extra tap on the same key as the one before
        private const val DELETION = -3.2 // any other extra tap
        const val END_INSERTION = -1.0 // the one key skipped after the last tap (Config.endInsertion)
        const val SPACE_TAP = -1.0 // a tap read as the space bar (Config.spaceTap)
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

        /**
         * Most words one run of taps may be split into. iOS has no fixed limit (its beam decides);
         * three keeps the search small and covers what people run together.
         */
        const val MAX_WORDS = 3

        private val SHORT_WORDS = listOf("u" to "you", "o" to "oh")

        /**
         * ln of how often a word's possessive occurs relative to the word: about the 25th
         * percentile of the stored pairs (median 0.033, kept low as those are the frequent ones).
         */
        private val POSSESSIVE_LOG_RATIO = ln(0.02)

        /** Word restarts kept per tap: the most likely few. */
        private const val MAX_RESTARTS = 3
    }

    /**
     * Words already finished inside the current taps, as iOS restarts a word inside an input
     * segment (UnigramTypingModel extending_key_could_terminate_input_segment: any key may begin
     * a new word once the letters before it are a whole word, at the cost of that word ending).
     */
    private class Prefix(val text: String, val languageModel: Double, val support: Double, val last: Int, val count: Int, val secondLast: Int = -1)

    private class State(val node: Int, val geometry: Double, val edits: Int, val prefix: Int = 0) {
        /** prune's score for this state, worked out once (NaN until then). */
        var rank = Double.NaN
    }

    /** What one tap says: each key's touch likelihood, its nearest keys, and its time terms. */
    private class TapInfo(
        val likelihood: Map<Char, Double>,
        val nearby: Set<Char>,
        val nearest: Char,
        /** Edit-cost time term, interval clamped to [T_MIN, T_MAX]. */
        val q: Double,
        /** The same, unclamped (transpositions). */
        val qRaw: Double,
        /** The tap read as the space bar (log-likelihood), when the keys include it (' '). */
        val space: Double? = null,
    )

    /**
     * The search so far, kept between keystrokes: layer i (states after i taps) depends only on
     * taps 0..i-1, so a new tap adds one layer and a backspace drops back to an earlier one.
     * [raw] is each layer as reached from the layers before it; [interior] is the same layer after
     * skipped keys, word restarts and pruning (as it is for a tap that is not the last one).
     */
    private class Search(
        val keys: Map<Char, KeyTarget>,
        val params: GaussianTouch.Params,
        val previous: Int,
        val previous2: Int,
        val personal: Any?,
        val context: ContextScorer?,
        val lexiconVersion: Int,
    ) {
        val taps = ArrayList<Tap>()
        val info = ArrayList<TapInfo>()
        val raw = ArrayList<HashMap<Long, State>>()
        val interior = ArrayList<HashMap<Long, State>>()
        /** prefixes[0] is "no earlier word". */
        val prefixes = arrayListOf<Prefix?>(null)
        val prefixIds = HashMap<String, Int>()
        /** restart's result per (prefix, node) key: the text is built once per new word pair. */
        val restartIds = HashMap<Long, Int>()

        /** Keeps what the first [m] taps decide. */
        fun truncate(m: Int) {
            while (taps.size > m) taps.removeAt(taps.size - 1)
            while (info.size > m) info.removeAt(info.size - 1)
            while (raw.size > m + 1) raw.removeAt(raw.size - 1)
            while (interior.size > m) interior.removeAt(interior.size - 1)
            if (m == 0) { // a new word: no state refers to an earlier word any more
                while (prefixes.size > 1) prefixes.removeAt(prefixes.size - 1)
                prefixIds.clear()
                restartIds.clear()
            }
        }
    }

    private var search: Search? = null

    /**
     * iOS RescoreShortWords (English): "u" and "o" score as well as "you" and "oh" would in the
     * same place, so typing them on purpose isn't taken for a rare word. Node of the short word
     * -> node of its long form, looked up again when the lexicon changes.
     */
    private var shortWords: Map<Int, Int> = emptyMap()
    private var shortWordsVersion = -1

    private fun shortWordMap(): Map<Int, Int> {
        if (shortWordsVersion != lexicon.version) {
            shortWords = SHORT_WORDS.mapNotNull { (short, long) ->
                val a = lexicon.find(short)
                val b = lexicon.find(long)
                if (a != Lexicon.NONE && b != Lexicon.NONE && lexicon.isWord(a) && lexicon.isWord(b)) a to b else null
            }.toMap()
            shortWordsVersion = lexicon.version
        }
        return shortWords
    }

    private fun key(prefix: Int, node: Int) = (prefix.toLong() shl 32) or node.toLong()

    private fun offer(layer: HashMap<Long, State>, node: Int, geometry: Double, edits: Int, prefix: Int, rank: Double = Double.NaN) {
        val k = key(prefix, node)
        val old = layer[k]
        if (old == null || geometry > old.geometry) layer[k] = State(node, geometry, edits, prefix).also { it.rank = rank }
    }

    /**
     * @param taps the taps of the current word
     * @param keys letter (lowercase) -> touch target, in the same dp space as [taps]
     * @param params touch-model parameters for the current key size and typing speed
     * @param maxResults number of candidates to return, best first
     * @param previous context id of the previous word ([Bigrams.contextId]), -1 for none
     * @param typed the letters of the keys that were hit; added as a candidate if not a known word
     * @param personal the personal model's mix; pass the same object while it is unchanged, so the
     *   search can continue from the previous keystroke
     */
    @Synchronized
    fun decode(
        taps: List<Tap>,
        keys: Map<Char, KeyTarget>,
        params: GaussianTouch.Params,
        maxResults: Int = 8,
        includeCompletions: Boolean = true,
        previous: Int = -1,
        typed: String? = null,
        personal: ((node: Int, logProb: Double) -> Double)? = null,
        /** The word before [previous] (same kind of id), -1 for none: for a two-word-context model. */
        previous2: Int = -1,
    ): List<Candidate> {
        val n = taps.size
        if (n == 0) return emptyList()
        val w = config.languagePower

        var sr = search
        if (sr == null || sr.keys !== keys || sr.params != params || sr.previous != previous || sr.previous2 != previous2 || sr.personal !== personal || sr.context !== context ||
            sr.lexiconVersion != lexicon.version
        ) {
            sr = Search(keys, params, previous, previous2, personal, context, lexicon.version).also { search = it }
        }
        var m = 0
        while (m < minOf(n, sr.taps.size) && sr.taps[m] == taps[m]) m++
        sr.truncate(m)
        for (i in m until n) {
            sr.taps.add(taps[i])
            val dtS = if (i > 0 && taps[i].timeMs > 0 && taps[i - 1].timeMs > 0) {
                (taps[i].timeMs - taps[i - 1].timeMs) / 1000.0
            } else DEFAULT_INTERVAL_S
            // letters only; the space bar (' ') is scored apart, for a word break at this tap
            val likelihood = keys.filterKeys { it != ' ' }.mapValues { (_, k) -> GaussianTouch.logLikelihood(k, taps[i].x, taps[i].y, params) }
            sr.info.add(TapInfo(
                likelihood,
                likelihood.entries.sortedByDescending { it.value }.take(NEARBY_KEYS).map { it.key }.toSet(),
                likelihood.maxBy { it.value }.key,
                QUADRATIC * dtS.coerceIn(T_MIN, T_MAX).let { it * it },
                QUADRATIC * dtS * dtS,
                keys[' ']?.let { GaussianTouch.logLikelihood(it, taps[i].x, taps[i].y, params) },
            ))
        }
        val info = sr.info
        val prefixes = sr.prefixes

        // keys beyond the touch model's pruning radius never match a tap (iOS key-match pruning)
        val prune = params.pruneThreshold
        fun tapScore(c: Char, i: Int): Pair<Double, Int>? {
            val ll = info[i].likelihood[c] ?: return null
            if (ll < prune) return config.farTap?.let { (it + info[i].q + prune) to 1 }
            return if (c in info[i].nearby) ll to 0 else (SUBSTITUTION + info[i].q + ll) to 1
        }

        fun context(prefix: Int) = prefixes[prefix]?.last ?: previous
        fun context2(prefix: Int) = prefixes[prefix]?.secondLast ?: previous2
        val shortWords = shortWordMap()
        fun lm(node: Int, prev: Int, prev2: Int = -1): Double {
            fun of(n: Int): Double {
                val base = sr.context?.logProb(prev2, prev, lexicon.wordId(n), lexicon.logProb(n))
                    ?: bigrams?.logProb(prev, lexicon.wordId(n), lexicon.logProb(n)) ?: lexicon.logProb(n)
                // the personal model mixed in (PersonalModel: iOS dynamic model weights)
                return personal?.invoke(n, base) ?: base
            }
            val own = of(node)
            return shortWords[node]?.let { maxOf(own, of(it)) } ?: own
        }
        /** The prefix for [s]'s words plus the word at [s]'s node. */
        fun newRestart(s: State): Int {
            val p = prefixes[s.prefix]
            val form = lexicon.forms(s.node).firstOrNull()?.first ?: return -1
            val text = if (p == null) form else p.text + " " + form
            sr.prefixIds[text]?.let { return it }
            val l = lm(s.node, context(s.prefix), context2(s.prefix))
            val support = (p?.support ?: 0.0) + (l - lexicon.logProb(s.node))
            prefixes.add(Prefix(text, (p?.languageModel ?: 0.0) + l, support, lexicon.wordId(s.node), (p?.count ?: 0) + 1, context(s.prefix)))
            return (prefixes.size - 1).also { sr.prefixIds[text] = it }
        }
        fun restart(s: State): Int = sr.restartIds.getOrPut(key(s.prefix, s.node)) { newRestart(s) }

        /** The score prune ranks a state by. */
        fun pruneScore(node: Int, geometry: Double, prefix: Int): Double {
            val best = lexicon.bestWord(node)
            return geometry + w * ((prefixes[prefix]?.languageModel ?: 0.0) + (if (best == Lexicon.NONE) -50.0 else lexicon.logProb(best)))
        }
        var insertCut = Double.NEGATIVE_INFINITY

        /** Layer i as reached from layers i-1 (tap i-1) and i-2 (taps i-2 and i-1 swapped). */
        fun pull(i: Int): HashMap<Long, State> {
            val out = HashMap<Long, State>()
            if (i == 0) {
                out[key(0, Lexicon.ROOT)] = State(Lexicon.ROOT, 0.0, 0)
                return out
            }
            val t = i - 1
            for (s in sr.interior[t].values) {
                lexicon.forEachChild(s.node) { c, child ->
                    tapScore(c, t)?.let { (score, edit) -> offer(out, child, s.geometry + score, s.edits + edit, s.prefix) }
                }
                // The tap was the space bar: a whole word ends here and the next begins (iOS: the space
                // is one of the keys a touch may have meant, at its touch cost only; "donyou" -> "do you")
                info[t].space?.let { ls ->
                    if (ls >= prune && config.splitWords && t > 0 && lexicon.isWord(s.node) && (prefixes[s.prefix]?.count ?: 0) < MAX_WORDS - 1) {
                        val p = restart(s)
                        if (p >= 0) offer(out, Lexicon.ROOT, s.geometry + ls + config.spaceTap, s.edits + 1, p)
                    }
                }
                // An extra tap that belongs to no letter, the first one included (iOS: "ghello" → "hello")
                val repeated = t > 0 && info[t].nearest == info[t - 1].nearest
                val extra = if (repeated) DELETION_REPEATED + info[t].q else DELETION + info[t].q + DELETION_LANGUAGE
                offer(out, s.node, s.geometry + extra, s.edits + 1, s.prefix)
            }
            // Swapped pair: tap i-2 belongs to the next letter, tap i-1 to this one.
            if (i >= 2) {
                val t2 = i - 2
                for (s in sr.interior[t2].values) {
                    lexicon.forEachChild(s.node) { c, child ->
                        val ll1 = info[t2 + 1].likelihood[c]
                        if (ll1 != null && ll1 >= prune) {
                            lexicon.forEachChild(child) { c2, grandchild ->
                                val ll2 = info[t2].likelihood[c2]
                                if (c2 != c && ll2 != null && ll2 >= prune) {
                                    offer(out, grandchild, s.geometry + TRANSPOSITION + info[t2 + 1].qRaw + ll1 + ll2, s.edits + 1, s.prefix)
                                }
                            }
                        }
                    }
                }
            }
            return out
        }

        /** Skipped keys, word restarts (not after the last tap) and pruning, on layer i of n. */
        fun process(layer: HashMap<Long, State>, i: Int) {
            val qi = info[minOf(i, n - 1)].q
            // Keys the user did not tap (and apostrophes) consume no tap, so they extend this layer:
            // one skipped key per position, optionally after an apostrophe (iOS compares words by
            // a sort key without apostrophes, so "dont" reaches "don't" at no cost).
            fun insertAfter(s: State, allowApostrophe: Boolean) {
                if (lexicon.depth(s.node) >= i + MAX_EXTRA_LETTERS + 1) return
                lexicon.forEachChild(s.node) { c, child ->
                    if (c == '\'' || c == '’') {
                        if (allowApostrophe) {
                            offer(layer, child, s.geometry, s.edits, s.prefix)
                            layer[key(s.prefix, child)]?.let { insertAfter(it, allowApostrophe = false) }
                        }
                    } else if (i > 0) { // no inserting before the first tap
                        val g = s.geometry + (if (i == n) config.endInsertion ?: INSERTION else INSERTION) + qi
                        // a state below the beam's cut-off before the insertions can't survive the
                        // prune (insertions only raise the cut): not made at all, unless it ends a word
                        // (a word end may start the next word); same result, far fewer states
                        val r = if (insertCut == Double.NEGATIVE_INFINITY) Double.NaN else pruneScore(child, g, s.prefix)
                        if (r.isNaN() || r >= insertCut || lexicon.isWord(child)) offer(layer, child, g, s.edits + 1, s.prefix, r)
                    }
                }
            }
            insertCut = beamCut(layer, w, prefixes)
            for (s in layer.values.toList()) insertAfter(s, allowApostrophe = true)
            // a whole word may end here and the next tap begin another: the space key was not
            // tapped, which costs what any skipped key does (iOS prices a missed space as a key
            // error); only the most likely few, which keeps the search near its one-word size
            if (config.splitWords && i in 1 until n) {
                // the best MAX_RESTARTS by score, ties in layer order (no boxing: this runs on every
                // layer of every keystroke); every word end still gets its prefix, as before
                val topState = arrayOfNulls<State>(MAX_RESTARTS); val topPrefix = IntArray(MAX_RESTARTS); val topScore = DoubleArray(MAX_RESTARTS)
                var count = 0
                for (s in layer.values) {
                    if (!lexicon.isWord(s.node) || (prefixes[s.prefix]?.count ?: 0) >= MAX_WORDS - 1) continue
                    val p = restart(s)
                    if (p < 0) continue
                    val sc = s.geometry + w * prefixes[p]!!.languageModel
                    if (count == MAX_RESTARTS && sc <= topScore[count - 1]) continue
                    var at = if (count < MAX_RESTARTS) count++ else count - 1
                    while (at > 0 && topScore[at - 1] < sc) {
                        topState[at] = topState[at - 1]; topPrefix[at] = topPrefix[at - 1]; topScore[at] = topScore[at - 1]; at--
                    }
                    topState[at] = s; topPrefix[at] = p; topScore[at] = sc
                }
                for (t in 0 until count) offer(layer, Lexicon.ROOT, topState[t]!!.geometry + INSERTION + qi, topState[t]!!.edits + 1, topPrefix[t])
            }
            prune(layer, w, prefixes)
        }

        var last: HashMap<Long, State>? = null
        for (i in 0..n) {
            if (i >= sr.raw.size) sr.raw.add(pull(i))
            if (i < n && i < sr.interior.size) continue
            val layer = HashMap(sr.raw[i])
            process(layer, i)
            if (i < n) sr.interior.add(layer) else last = layer
        }

        val results = HashMap<String, Candidate>()
        // One candidate per word. A word reached as a typo fix (skipped keys, incl. one after the
        // last tap: iOS inserts a key before the space touch) keeps that reading over its completion
        // reading, which may score higher but autocorrect never applies ("befor" -> "before" was
        // hidden behind the completion "befor..." and left as typed).
        fun add(candidate: Candidate) {
            val old = results[candidate.word]
            val replace = when {
                old == null -> true
                old.isCompletion != candidate.isCompletion -> !candidate.isCompletion
                else -> candidate.score > old.score
            }
            if (replace) results[candidate.word] = candidate
        }
        for (s in last!!.values) {
            val p = prefixes[s.prefix]
            val prev = context(s.prefix)
            val prev2 = context2(s.prefix)
            fun words(lastWord: String) = if (p == null) lastWord else p.text + " " + lastWord
            val before = p?.languageModel ?: 0.0
            val count = (p?.count ?: 0) + 1
            if (lexicon.isWord(s.node)) {
                val l = lm(s.node, prev, prev2)
                val support = if (p == null) 0.0 else p.support + (l - lexicon.logProb(s.node))
                // each casing of the word ("google", "Google") with its share of the word's probability
                val nodeLp = lexicon.logProb(s.node)
                for ((form, formLp) in lexicon.forms(s.node)) {
                    val lf = before + l + (formLp - nodeLp)
                    add(Candidate(words(form), s.geometry + w * lf, s.geometry, lf, s.edits, isCompletion = false, words = count, contextSupport = support))
                }
                // a word plus a lone "s" ("puppy s") is more likely the possessive "puppy's", which
                // the lexicon stores only when frequent: generated here, the missed apostrophe
                // priced like the missed space
                if (p != null && p.count == 1 && lexicon.word(s.node) == "s") {
                    val lp = p.languageModel + POSSESSIVE_LOG_RATIO
                    add(Candidate(p.text + "'s", s.geometry + w * lp, s.geometry, lp, s.edits, isCompletion = false))
                }
            }
            if (includeCompletions) {
                val best = lexicon.bestWord(s.node)
                if (best == Lexicon.NONE || best == s.node) continue
                val depth = lexicon.depth(s.node)
                val added = lexicon.depth(best) - depth
                val geometry = s.geometry + w * (PREDICTION_COSTS[minOf(depth, 5)] + (added - 1) * PREDICTION_PER_LETTER)
                val l = lm(best, prev, prev2)
                val support = if (p == null) 0.0 else p.support + (l - lexicon.logProb(best))
                add(Candidate(words(lexicon.word(best)!!), geometry + w * (before + l), geometry, before + l, s.edits, isCompletion = true, words = count, contextSupport = support))
            }
        }
        if (typed != null && typed.length == n && !lexicon.contains(typed)) {
            var geometry = 0.0
            for ((i, c) in typed.withIndex()) geometry += info[i].likelihood[c.lowercaseChar()] ?: continue
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
        // a word the lexicon lacks, scored by how much it looks like one (names, slang, brands)
        unknownWords?.let { m -> m.logProb(typed)?.let { return m.unknownLogProb + it } }
        var node = Lexicon.ROOT
        var score = 0.0
        for (c in typed) {
            val child = if (node == Lexicon.NONE) Lexicon.NONE else lexicon.child(node, Lexicon.fold(c))
            score += if (child != Lexicon.NONE && lexicon.mass(node) > 0.0) ln(lexicon.mass(child) / lexicon.mass(node)) else UNKNOWN_LETTER
            node = child
        }
        return score + UNKNOWN_LETTER
    }

    /** Keeps the best states, ranked by geometry plus the best word reachable below each node. */
    // prune's scratch arrays, reused across calls (decode is synchronized): a keystroke prunes a
    // few layers of thousands of states, and fresh arrays were a third of the typing path's garbage
    private var pKeys = LongArray(0)
    private var pStates = arrayOfNulls<State>(0)
    private var pScores = DoubleArray(0)
    private var pWork = DoubleArray(0)
    private var pKept = IntArray(0)

    private fun prune(layer: HashMap<Long, State>, w: Double, prefixes: List<Prefix?>) {
        val n = layer.size
        val beam = config.beamWidth
        if (n <= beam) return
        if (pKeys.size < n) {
            val c = maxOf(n, pKeys.size * 2)
            pKeys = LongArray(c); pStates = arrayOfNulls(c); pScores = DoubleArray(c); pWork = DoubleArray(c)
        }
        if (pKept.size < beam) pKept = IntArray(beam)
        val keys = pKeys; val states = pStates; val scores = pScores
        // each state's score once (a sort by a selector recomputed it in every comparison: 58 % of
        // the typing path's time), then the states above the beam's cut-off, ties in layer order,
        // put back best first: the same states in the same order as a stable sort
        var i = 0
        for ((k, s) in layer) {
            keys[i] = k; states[i] = s; scores[i] = rankOf(s, w, prefixes)
            i++
        }
        System.arraycopy(scores, 0, pWork, 0, n)
        val cut = kthSmallest(pWork, n, n - beam)
        var above = 0
        for (j in 0 until n) if (scores[j] > cut) above++
        var ties = beam - above
        val kept = pKept
        var m = 0
        for (j in 0 until n) {
            if (scores[j] > cut || (scores[j] == cut && ties-- > 0)) {
                // insertion by score, best first; equal scores keep layer order (stable)
                var at = m
                while (at > 0 && scores[kept[at - 1]] < scores[j]) { kept[at] = kept[at - 1]; at-- }
                kept[at] = j; m++
            }
        }
        layer.clear()
        for (t in 0 until m) layer[keys[kept[t]]] = states[kept[t]]!!
        java.util.Arrays.fill(states, 0, n, null)
    }

    private fun rankOf(s: State, w: Double, prefixes: List<Prefix?>): Double {
        if (!s.rank.isNaN()) return s.rank
        val best = lexicon.bestWord(s.node)
        return (s.geometry + w * ((prefixes[s.prefix]?.languageModel ?: 0.0) + (if (best == Lexicon.NONE) -50.0 else lexicon.logProb(best))))
            .also { s.rank = it }
    }

    /** The prune cut-off of [layer] as it stands (the beam's lowest score), or -inf below a full beam. */
    private fun beamCut(layer: HashMap<Long, State>, w: Double, prefixes: List<Prefix?>): Double {
        val n = layer.size
        val beam = config.beamWidth
        if (n < beam) return Double.NEGATIVE_INFINITY
        if (pWork.size < n) pWork = DoubleArray(maxOf(n, pWork.size * 2))
        var i = 0
        for (s in layer.values) pWork[i++] = rankOf(s, w, prefixes)
        return kthSmallest(pWork, n, n - beam)
    }

    /** The [k]-th smallest of a[0 until n] (0-based), reordering a: quickselect, O(n) on average. */
    private fun kthSmallest(a: DoubleArray, n: Int, k: Int): Double {
        var lo = 0; var hi = n - 1
        while (lo < hi) {
            val pivot = a[(lo + hi) ushr 1]
            var l = lo; var r = hi
            while (l <= r) {
                while (a[l] < pivot) l++
                while (a[r] > pivot) r--
                if (l <= r) { val t = a[l]; a[l] = a[r]; a[r] = t; l++; r-- }
            }
            if (k <= r) hi = r else if (k >= l) lo = l else return a[k]
        }
        return a[k]
    }
}
