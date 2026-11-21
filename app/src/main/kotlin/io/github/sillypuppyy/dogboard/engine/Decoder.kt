package io.github.sillypuppyy.dogboard.engine

import io.github.sillypuppyy.dogboard.touch.GaussianTouch
import io.github.sillypuppyy.dogboard.touch.KeyTarget
import kotlin.math.ln

/**
 * One tap of the current word, in dp relative to the keyboard's top-left corner, and when it
 * happened ([timeMs], uptime; 0 if unknown).
 */
/** The personal model's hook in the decoder (a primitive interface: a Kotlin function type boxed both numbers and the result per call). */
fun interface PersonalMix {
    fun logProb(node: Int, logProb: Double): Double
}

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
    /** The language's rules (its pack's rules.json). */
    private val rules: LanguageRules = LanguageRules.ENGLISH,
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

    /**
     * Known misspellings, lowercase: a typed string on this list is not scored by [unknownWords] (it
     * looks like a word because it is one, misspelled: "seperate"), so the correction it means wins.
     */
    var knownMisspellings: Set<String> = emptySet()

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
         * With [splitWords]: a word may also end where no tap was (a space the user never touched,
         * "callyou"). iOS's English beam never does this (only its neural tap model splits words);
         * a tap read as the space bar is always allowed.
         */
        val missedSpace: Boolean = true,
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

        private const val LAYER_POOL = 64
        /** Completions this far (ln) below the best reading are not built (odds below 1e-13). */
        private const val COMPLETION_CUT = 30.0

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
    private class Prefix(
        /** The words before: the earlier prefix and the word at [node] (in its first casing). */
        val parent: Prefix?, val node: Int,
        val languageModel: Double, val support: Double, val last: Int, val count: Int, val secondLast: Int = -1,
    ) {
        // built when a candidate needs it: most restarts end in no candidate (their text was a fifth
        // of a keystroke's garbage)
        private var textCache: String? = null
        fun text(lexicon: Lexicon): String = textCache ?: run {
            val form = lexicon.firstForm(node)!!
            (if (parent == null) form else parent.text(lexicon) + " " + form).also { textCache = it }
        }
    }

    /**
     * A search layer: states by (prefix, node) key, in insertion order. Primitive keys and open
     * addressing: a HashMap boxed every key and made an entry object per state (a keystroke makes
     * thousands), and iterating it made iterators.
     */
    private class LayerMap(capacity: Int = 64) {
        // one entry per state: its key holds the prefix and node, the arrays the rest (a State object
        // per entry was a third of the typing path's garbage)
        private var keys = LongArray(capacity)
        private var geometry = DoubleArray(capacity)
        private var edits = IntArray(capacity)
        /** prune's score for the entry, worked out once (NaN until then). */
        private var rank = DoubleArray(capacity)
        private var table = IntArray(tableSizeFor(capacity)) // entry index + 1, 0 = empty
        var size = 0
            private set

        fun key(i: Int): Long = keys[i]
        fun node(i: Int): Int = keys[i].toInt()
        fun prefix(i: Int): Int = (keys[i] ushr 32).toInt()
        fun geometry(i: Int): Double = geometry[i]
        fun edits(i: Int): Int = edits[i]
        fun rank(i: Int): Double = rank[i]
        fun setRank(i: Int, r: Double) { rank[i] = r }

        /** The entry index for key [k], or -1. */
        fun indexOf(k: Long): Int {
            val mask = table.size - 1
            var h = mix(k) and mask
            while (true) {
                val e = table[h]
                if (e == 0) return -1
                if (keys[e - 1] == k) return e - 1
                h = (h + 1) and mask
            }
        }

        /** Adds the state, or replaces the one with the same key if [g] is a better geometry. */
        fun offer(k: Long, g: Double, e: Int, r: Double) {
            val mask = table.size - 1
            var h = mix(k) and mask
            while (true) {
                val at = table[h]
                if (at == 0) break
                if (keys[at - 1] == k) {
                    if (g > geometry[at - 1]) { geometry[at - 1] = g; edits[at - 1] = e; rank[at - 1] = r }
                    return
                }
                h = (h + 1) and mask
            }
            if (size == keys.size) {
                val c = size * 2
                keys = keys.copyOf(c); geometry = geometry.copyOf(c); edits = edits.copyOf(c); rank = rank.copyOf(c)
            }
            keys[size] = k; geometry[size] = g; edits[size] = e; rank[size] = r; size++
            table[h] = size
            if (size * 2 > table.size) rehash()
        }

        fun clear() {
            java.util.Arrays.fill(table, 0)
            size = 0
        }

        /** Empty, with room for [capacity] states (reused from the pool: keeps larger arrays). */
        fun reset(capacity: Int) {
            if (keys.size < capacity) { keys = LongArray(capacity); geometry = DoubleArray(capacity); edits = IntArray(capacity); rank = DoubleArray(capacity) }
            val t = tableSizeFor(capacity)
            if (table.size < t) table = IntArray(t) else java.util.Arrays.fill(table, 0)
            size = 0
        }

        /** Replaces the contents with [o]'s states (keeps this map's arrays). */
        fun loadFrom(o: LayerMap) {
            clear()
            for (i in 0 until o.size) offer(o.keys[i], o.geometry[i], o.edits[i], o.rank[i])
        }

        private fun rehash() {
            table = IntArray(table.size * 2)
            val mask = table.size - 1
            for (i in 0 until size) {
                var h = mix(keys[i]) and mask
                while (table[h] != 0) h = (h + 1) and mask
                table[h] = i + 1
            }
        }

        companion object {
            fun tableSizeFor(n: Int): Int = Integer.highestOneBit(maxOf(4, n) * 2 - 1) shl 1
            fun mix(k: Long): Int { val x = k * -7046029254386353131L; return (x xor (x ushr 32)).toInt() }
        }
    }

    /** Long -> Int map without boxing ([LayerMap]'s layout), for restart's per-(prefix, node) cache. */
    private class LongIntMap {
        var keys = LongArray(64)
        var values = IntArray(64)
        var table = IntArray(LayerMap.tableSizeFor(64)) // entry index + 1, 0 = empty
        var size = 0

        /** The value for [k], or -1. */
        fun get(k: Long): Int {
            val mask = table.size - 1
            var h = LayerMap.mix(k) and mask
            while (true) {
                val e = table[h]
                if (e == 0) return -1
                if (keys[e - 1] == k) return values[e - 1]
                h = (h + 1) and mask
            }
        }

        inline fun getOrPut(k: Long, make: () -> Int): Int {
            val mask = table.size - 1
            var h = LayerMap.mix(k) and mask
            while (true) {
                val e = table[h]
                if (e == 0) break
                if (keys[e - 1] == k) return values[e - 1]
                h = (h + 1) and mask
            }
            val v = make()
            put(k, v)
            return v
        }

        // [make] may have added entries: the slot is looked up again
        fun put(k: Long, v: Int) {
            if (size == keys.size) { keys = keys.copyOf(size * 2); values = values.copyOf(size * 2) }
            keys[size] = k; values[size] = v; size++
            if (size * 2 > table.size) table = IntArray(table.size * 2)
            else { var h = LayerMap.mix(k) and (table.size - 1); while (table[h] != 0) h = (h + 1) and (table.size - 1); table[h] = size; return }
            val mask = table.size - 1
            for (i in 0 until size) {
                var h = LayerMap.mix(keys[i]) and mask
                while (table[h] != 0) h = (h + 1) and mask
                table[h] = i + 1
            }
        }

        fun clear() {
            java.util.Arrays.fill(table, 0)
            size = 0
        }
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
    ) {
        // the same lookups without boxing a Char (the search asks per lexicon child: accented letters
        // are boxed afresh each time, a tenth of a keystroke's garbage)
        private val ascii = DoubleArray(128) { Double.NaN }.also { a -> for ((c, v) in likelihood) if (c.code < 128) a[c.code] = v }
        private val asciiNear = BooleanArray(128).also { a -> for (c in nearby) if (c.code < 128) a[c.code] = true }
        private val wide = likelihood.keys.any { it.code >= 128 }

        /** ln likelihood of key [c] for this tap, NaN when [c] is no key. */
        fun ll(c: Char): Double = if (c.code < 128) ascii[c.code] else if (!wide) Double.NaN else likelihood[c] ?: Double.NaN
        fun near(c: Char): Boolean = if (c.code < 128) asciiNear[c.code] else wide && c in nearby
    }

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
        /** restart's result per (prefix, node) key: the text is built once per new word pair (reused from the last search, cleared). */
        val restartIds: LongIntMap = LongIntMap(),
    ) {
        val taps = ArrayList<Tap>()
        val info = ArrayList<TapInfo>()
        val raw = ArrayList<LayerMap>()
        val interior = ArrayList<LayerMap>()
        /** prefixes[0] is "no earlier word". */
        val prefixes = arrayListOf<Prefix?>(null)

        /** Keeps what the first [m] taps decide; the dropped layers go to [recycle]. */
        fun truncate(m: Int, recycle: (LayerMap) -> Unit) {
            while (taps.size > m) taps.removeAt(taps.size - 1)
            while (info.size > m) info.removeAt(info.size - 1)
            while (raw.size > m + 1) recycle(raw.removeAt(raw.size - 1))
            while (interior.size > m) recycle(interior.removeAt(interior.size - 1))
            if (m == 0) { // a new word: no state refers to an earlier word any more
                while (prefixes.size > 1) prefixes.removeAt(prefixes.size - 1)
                restartIds.clear()
            }
        }
    }

    private var search: Search? = null

    // layers dropped by a backspace or a new word, reused (a new layer per tap and a copy of it per
    // keystroke were a quarter of the typing path's garbage)
    private val layerPool = ArrayList<LayerMap>()
    private fun obtainLayer(capacity: Int): LayerMap =
        if (layerPool.isEmpty()) LayerMap(capacity) else layerPool.removeAt(layerPool.size - 1).also { it.reset(capacity) }
    private fun recycleLayer(l: LayerMap) { if (layerPool.size < LAYER_POOL) layerPool.add(l) }

    /**
     * iOS RescoreShortWords (English): "u" and "o" score as well as "you" and "oh" would in the
     * same place, so typing them on purpose isn't taken for a rare word. Node of the short word
     * -> node of its long form, looked up again when the lexicon changes.
     */
    // short-word pairs (node of the short form -> node of the long form) as plain arrays: looked up
    // for every word scored, a map lookup boxed the node each time
    private var shortKeys = IntArray(0)
    private var shortVals = IntArray(0)
    private var shortWordsVersion = -1

    private fun refreshShortWords() {
        if (shortWordsVersion != lexicon.version) {
            val pairs = rules.shortWords.filter { it.size == 2 }.mapNotNull { (short, long) ->
                val a = lexicon.find(short)
                val b = lexicon.find(long)
                if (a != Lexicon.NONE && b != Lexicon.NONE && lexicon.isWord(a) && lexicon.isWord(b)) a to b else null
            }.toMap() // one long form per short form, the last listed wins (as before)
            shortKeys = pairs.keys.toIntArray(); shortVals = pairs.values.toIntArray()
            shortWordsVersion = lexicon.version
        }
    }

    /** The long form's node for a short form's [node] ("u" -> "you"), or [Lexicon.NONE]. */
    private fun shortWordFor(node: Int): Int {
        for (i in shortKeys.indices) if (shortKeys[i] == node) return shortVals[i]
        return Lexicon.NONE
    }

    private fun key(prefix: Int, node: Int) = (prefix.toLong() shl 32) or node.toLong()

    private fun offer(layer: LayerMap, node: Int, geometry: Double, edits: Int, prefix: Int, rank: Double = Double.NaN) =
        layer.offer(key(prefix, node), geometry, edits, rank)

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
        personal: PersonalMix? = null,
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
            sr?.let { old -> old.raw.forEach(::recycleLayer); old.interior.forEach(::recycleLayer) }
            sr = Search(keys, params, previous, previous2, personal, context, lexicon.version, sr?.restartIds?.also { it.clear() } ?: LongIntMap()).also { search = it }
        }
        var m = 0
        while (m < minOf(n, sr.taps.size) && sr.taps[m] == taps[m]) m++
        sr.truncate(m, ::recycleLayer)
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
        // a tap read as key c: the score (NaN: no match) and whether it costs an edit, apart (no
        // Pair: this runs for every child of every state on every keystroke)
        fun tapCost(c: Char, i: Int): Double {
            val ll = info[i].ll(c)
            if (ll.isNaN()) return Double.NaN
            if (ll < prune) return config.farTap?.let { it + info[i].q + prune } ?: Double.NaN
            return if (info[i].near(c)) ll else SUBSTITUTION + info[i].q + ll
        }
        fun tapEdit(c: Char, i: Int): Int {
            val ll = info[i].ll(c)
            return if (ll >= prune && info[i].near(c)) 0 else 1
        }

        fun context(prefix: Int) = prefixes[prefix]?.last ?: previous
        fun context2(prefix: Int) = prefixes[prefix]?.secondLast ?: previous2
        refreshShortWords()
        fun lm(node: Int, prev: Int, prev2: Int = -1): Double {
            fun of(n: Int): Double {
                // no ?. / ?: chains on Doubles here: each boxed the value (per search state)
                val ctx = sr.context; val bg = bigrams
                val base = if (ctx != null) ctx.logProb(prev2, prev, lexicon.wordId(n), lexicon.logProb(n))
                    else if (bg != null) bg.logProb(prev, lexicon.wordId(n), lexicon.logProb(n)) else lexicon.logProb(n)
                // the personal model mixed in (PersonalModel: iOS dynamic model weights)
                return if (personal != null) personal.logProb(n, base) else base
            }
            val own = of(node)
            val long = shortWordFor(node)
            return if (long == Lexicon.NONE) own else maxOf(own, of(long))
        }
        /** The prefix for [sPrefix]'s words plus the word at [sNode]. */
        fun newRestart(sPrefix: Int, sNode: Int): Int {
            // one prefix per (earlier prefix, word): restartIds keeps that, and distinct words have distinct
            // first forms, so no two prefixes share a text
            val p = prefixes[sPrefix]
            if (!lexicon.isWord(sNode)) return -1
            val l = lm(sNode, context(sPrefix), context2(sPrefix))
            val support = (p?.support ?: 0.0) + (l - lexicon.logProb(sNode))
            prefixes.add(Prefix(p, sNode, (p?.languageModel ?: 0.0) + l, support, lexicon.wordId(sNode), (p?.count ?: 0) + 1, context(sPrefix)))
            return prefixes.size - 1
        }
        fun restart(sPrefix: Int, sNode: Int): Int = sr.restartIds.getOrPut(key(sPrefix, sNode)) { newRestart(sPrefix, sNode) }
        /** The language score a restart of [sPrefix] with the word at [sNode] has, without making its prefix. */
        fun restartLanguageModel(sPrefix: Int, sNode: Int): Double {
            sr.restartIds.get(key(sPrefix, sNode)).takeIf { it >= 0 }?.let { id -> prefixes[id]?.let { return it.languageModel } }
            return (prefixes[sPrefix]?.languageModel ?: 0.0) + lm(sNode, context(sPrefix), context2(sPrefix))
        }

        /** The score prune ranks a state by. */
        fun pruneScore(node: Int, geometry: Double, prefix: Int): Double {
            val best = lexicon.bestWord(node)
            return geometry + w * ((prefixes[prefix]?.languageModel ?: 0.0) + (if (best == Lexicon.NONE) -50.0 else lexicon.logProb(best)))
        }
        var insertCut = Double.NEGATIVE_INFINITY

        /** Layer i as reached from layers i-1 (tap i-1) and i-2 (taps i-2 and i-1 swapped). */
        fun pull(i: Int): LayerMap {
            // sized from the layer it grows from (each state reaches a few nearby keys' children)
            val out = obtainLayer(if (i == 0) 16 else maxOf(64, sr.interior[i - 1].size * 4))
            if (i == 0) {
                offer(out, Lexicon.ROOT, 0.0, 0, 0)
                return out
            }
            val t = i - 1
            val from = sr.interior[t]
            for (j in 0 until from.size) {
                val sNode = from.node(j); val sGeometry = from.geometry(j); val sEdits = from.edits(j); val sPrefix = from.prefix(j)
                lexicon.forEachChild(sNode) { c, child ->
                    val score = tapCost(c, t)
                    if (!score.isNaN()) offer(out, child, sGeometry + score, sEdits + tapEdit(c, t), sPrefix)
                }
                // The tap was the space bar: a whole word ends here and the next begins (iOS: the space
                // is one of the keys a touch may have meant, at its touch cost only; "donyou" -> "do you")
                info[t].space?.let { ls ->
                    if (ls >= prune && config.splitWords && t > 0 && lexicon.isWord(sNode) && (prefixes[sPrefix]?.count ?: 0) < MAX_WORDS - 1) {
                        val p = restart(sPrefix, sNode)
                        if (p >= 0) offer(out, Lexicon.ROOT, sGeometry + ls + config.spaceTap, sEdits + 1, p)
                    }
                }
                // An extra tap that belongs to no letter, the first one included (iOS: "ghello" → "hello")
                val repeated = t > 0 && info[t].nearest == info[t - 1].nearest
                val extra = if (repeated) DELETION_REPEATED + info[t].q else DELETION + info[t].q + DELETION_LANGUAGE
                offer(out, sNode, sGeometry + extra, sEdits + 1, sPrefix)
            }
            // Swapped pair: tap i-2 belongs to the next letter, tap i-1 to this one.
            if (i >= 2) {
                val t2 = i - 2
                val from2 = sr.interior[t2]
                for (j in 0 until from2.size) {
                    val sNode = from2.node(j); val sGeometry = from2.geometry(j); val sEdits = from2.edits(j); val sPrefix = from2.prefix(j)
                    lexicon.forEachChild(sNode) { c, child ->
                        val ll1 = info[t2 + 1].ll(c)
                        if (ll1 >= prune) { // NaN (no key) fails
                            lexicon.forEachChild(child) { c2, grandchild ->
                                val ll2 = info[t2].ll(c2)
                                if (c2 != c && ll2 >= prune) {
                                    offer(out, grandchild, sGeometry + TRANSPOSITION + info[t2 + 1].qRaw + ll1 + ll2, sEdits + 1, sPrefix)
                                }
                            }
                        }
                    }
                }
            }
            return out
        }

        /** Skipped keys, word restarts (not after the last tap) and pruning, on layer i of n. */
        fun process(layer: LayerMap, i: Int) {
            val qi = info[minOf(i, n - 1)].q
            // Keys the user did not tap (and apostrophes) consume no tap, so they extend this layer:
            // one skipped key per position, optionally after an apostrophe (iOS compares words by
            // a sort key without apostrophes, so "dont" reaches "don't" at no cost; a hyphen too, so "tshirt" reaches
            // "T-shirt", as iOS's hyphenated words are reached; user report 'nonbnglish' 2026-11-19); an ampersand
            // likewise ("hm" reaches "H&M", "att" "AT&T": the sort key has letters only)
            fun insertAfter(sPrefix: Int, sNode: Int, sGeometry: Double, sEdits: Int, allowApostrophe: Boolean) {
                // an insertion makes the node one deeper: at most MAX_EXTRA_LETTERS beyond the i taps (iOS 2;
                // the cap allowed 3 until 2026-11-20, audit; the trie root has depth 0)
                if (lexicon.depth(sNode) >= i + MAX_EXTRA_LETTERS) return
                lexicon.forEachChild(sNode) { c, child ->
                    if (c == '\'' || c == '’' || c == '&' || c == '-') {
                        if (allowApostrophe) {
                            offer(layer, child, sGeometry, sEdits, sPrefix)
                            layer.indexOf(key(sPrefix, child)).takeIf { it >= 0 }?.let { j -> insertAfter(sPrefix, child, layer.geometry(j), layer.edits(j), allowApostrophe = false) }
                        }
                    } else if (i > 0) { // no inserting before the first tap
                        val g = sGeometry + (if (i == n) config.endInsertion ?: INSERTION else INSERTION) + qi
                        // a state below the beam's cut-off before the insertions can't survive the
                        // prune (insertions only raise the cut): not made at all, unless it ends a word
                        // (a word end may start the next word); same result, far fewer states
                        val r = if (insertCut == Double.NEGATIVE_INFINITY) Double.NaN else pruneScore(child, g, sPrefix)
                        if (r.isNaN() || r >= insertCut || lexicon.isWord(child)) offer(layer, child, g, sEdits + 1, sPrefix, r)
                    }
                }
            }
            insertCut = beamCut(layer, w, prefixes)
            // the layer grows while it is extended: a snapshot first (a reused array, no list per step)
            val count = layer.size
            if (iKeys.size < count) { val c = maxOf(count, iKeys.size * 2); iKeys = LongArray(c); iGeometry = DoubleArray(c); iEdits = IntArray(c) }
            for (j in 0 until count) { iKeys[j] = layer.key(j); iGeometry[j] = layer.geometry(j); iEdits[j] = layer.edits(j) }
            for (k in 0 until count) insertAfter((iKeys[k] ushr 32).toInt(), iKeys[k].toInt(), iGeometry[k], iEdits[k], allowApostrophe = true)
            // a whole word may end here and the next tap begin another: the space key was not
            // tapped, which costs what any skipped key does (iOS prices a missed space as a key
            // error); only the most likely few, which keeps the search near its one-word size
            if (config.splitWords && config.missedSpace && i in 1 until n) {
                // the best MAX_RESTARTS by score, ties in layer order (no boxing: this runs on every
                // layer of every keystroke). Only the winners get a prefix: making one for every word end
                // to read its score was 56 % of the decoder's allocations (JFR, 2026-11-21); the score is
                // the same sum newRestart stores, so the choice is unchanged
                val topGeometry = DoubleArray(MAX_RESTARTS); val topEdits = IntArray(MAX_RESTARTS); val topScore = DoubleArray(MAX_RESTARTS)
                val topFrom = IntArray(MAX_RESTARTS); val topNode = IntArray(MAX_RESTARTS)
                var count = 0
                for (j in 0 until layer.size) {
                    val sNode = layer.node(j); val sGeometry = layer.geometry(j); val sEdits = layer.edits(j); val sPrefix = layer.prefix(j)
                    if (!lexicon.isWord(sNode) || (prefixes[sPrefix]?.count ?: 0) >= MAX_WORDS - 1) continue
                    val sc = sGeometry + w * restartLanguageModel(sPrefix, sNode)
                    if (count == MAX_RESTARTS && sc <= topScore[count - 1]) continue
                    var at = if (count < MAX_RESTARTS) count++ else count - 1
                    while (at > 0 && topScore[at - 1] < sc) {
                        topGeometry[at] = topGeometry[at - 1]; topEdits[at] = topEdits[at - 1]; topFrom[at] = topFrom[at - 1]; topNode[at] = topNode[at - 1]; topScore[at] = topScore[at - 1]; at--
                    }
                    topGeometry[at] = sGeometry; topEdits[at] = sEdits; topFrom[at] = sPrefix; topNode[at] = sNode; topScore[at] = sc
                }
                for (t in 0 until count) {
                    val p = restart(topFrom[t], topNode[t])
                    if (p >= 0) offer(layer, Lexicon.ROOT, topGeometry[t] + INSERTION + qi, topEdits[t] + 1, p)
                }
            }
            prune(layer, w, prefixes)
        }

        var last: LayerMap? = null
        for (i in 0..n) {
            if (i >= sr.raw.size) sr.raw.add(pull(i))
            if (i < n && i < sr.interior.size) continue
            // processed in a reused map (skipped keys grow a layer many times over before the prune),
            // kept at its pruned size
            val layer = work.also { it.loadFrom(sr.raw[i]) }
            process(layer, i)
            if (i < n) sr.interior.add(obtainLayer(maxOf(16, layer.size)).also { it.loadFrom(layer) }) else last = layer
        }

        val results = HashMap<String, Candidate>()
        // One candidate per word. A word reached as a typo fix (skipped keys, incl. one after the
        // last tap: iOS inserts a key before the space touch) keeps that reading over its completion
        // reading, which may score higher but autocorrect never applies ("befor" -> "before" was
        // hidden behind the completion "befor..." and left as typed).
        var bestScore = Double.NEGATIVE_INFINITY
        fun add(candidate: Candidate) {
            if (candidate.score > bestScore) bestScore = candidate.score
            val old = results[candidate.word]
            val replace = when {
                old == null -> true
                old.isCompletion != candidate.isCompletion -> !candidate.isCompletion
                else -> candidate.score > old.score
            }
            if (replace) results[candidate.word] = candidate
        }
        val lastLayer = last!!
        for (j in 0 until lastLayer.size) {
            val sNode = lastLayer.node(j); val sGeometry = lastLayer.geometry(j); val sEdits = lastLayer.edits(j); val sPrefix = lastLayer.prefix(j)
            val p = prefixes[sPrefix]
            val prev = context(sPrefix)
            val prev2 = context2(sPrefix)
            fun words(lastWord: String) = if (p == null) lastWord else p.text(lexicon) + " " + lastWord
            val before = p?.languageModel ?: 0.0
            val count = (p?.count ?: 0) + 1
            if (lexicon.isWord(sNode)) {
                val l = lm(sNode, prev, prev2)
                val support = if (p == null) 0.0 else p.support + (l - lexicon.logProb(sNode))
                // each casing of the word ("google", "Google") with its share of the word's probability
                val nodeLp = lexicon.logProb(sNode)
                if (!lexicon.hasCasings(sNode)) { // one casing (most words): no list of forms
                    val lf = before + l
                    add(Candidate(words(lexicon.word(sNode)!!), sGeometry + w * lf, sGeometry, lf, sEdits, isCompletion = false, words = count, contextSupport = support))
                } else for ((form, formLp) in lexicon.forms(sNode)) {
                    val lf = before + l + (formLp - nodeLp)
                    add(Candidate(words(form), sGeometry + w * lf, sGeometry, lf, sEdits, isCompletion = false, words = count, contextSupport = support))
                }
                // a word plus a lone "s" ("puppy s") is more likely the possessive "puppy's", which
                // the lexicon stores only when frequent: generated here, the missed apostrophe
                // priced like the missed space (the suffix is the language's, rules.json possessive_suffix)
                if (p != null && p.count == 1 && rules.possessiveLetters != null && lexicon.word(sNode) == rules.possessiveLetters) {
                    val lp = p.languageModel + POSSESSIVE_LOG_RATIO
                    add(Candidate(p.text(lexicon) + rules.possessiveSuffix, sGeometry + w * lp, sGeometry, lp, sEdits, isCompletion = false))
                }
            }
            if (includeCompletions) {
                val best = lexicon.bestWord(sNode)
                if (best == Lexicon.NONE || best == sNode) continue
                val depth = lexicon.depth(sNode)
                val added = lexicon.depth(best) - depth
                val geometry = sGeometry + w * (PREDICTION_COSTS[minOf(depth, 5)] + (added - 1) * PREDICTION_PER_LETTER)
                val l = lm(best, prev, prev2)
                // a completion far below the best reading so far can't be among the results (a
                // completion never replaces another reading of its word): no candidate, no text built
                // (most states' completions; they were most of a keystroke's garbage)
                if (geometry + w * (before + l) < bestScore - COMPLETION_CUT) continue
                val support = if (p == null) 0.0 else p.support + (l - lexicon.logProb(best))
                add(Candidate(words(lexicon.word(best)!!), geometry + w * (before + l), geometry, before + l, sEdits, isCompletion = true, words = count, contextSupport = support))
            }
        }
        if (typed != null && typed.length == n && !lexicon.contains(typed)) {
            var geometry = 0.0
            for ((i, c) in typed.withIndex()) geometry += info[i].likelihood[c.lowercaseChar()] ?: continue
            val l = typedStringLogProb(typed)
            add(Candidate(typed, geometry + w * l, geometry, l, 0, isCompletion = false, isTypedString = true))
        }
        return withHyphensPricedAsSpaces(results.values).sortedByDescending { it.score }.take(maxResults)
    }

    /**
     * A skipped hyphen is free (so "tshirt" reaches "T-shirt") and a skipped space is not, so "thankyou" went to
     * "thank-you" though "thank you" is the likelier reading (user 2026-11-20; on the iPhone it is "thank you").
     * When the same letters are a candidate with spaces too, the hyphenated one pays the same as the spaced one,
     * and the language model decides ("well-known" keeps its lead over "well known").
     */
    private fun withHyphensPricedAsSpaces(cs: Collection<Candidate>): Collection<Candidate> {
        if (cs.none { '-' in it.word }) return cs
        val spaced = cs.filter { ' ' in it.word }.associateBy { it.word.lowercase() }
        return cs.map { c ->
            val s = if ('-' in c.word) spaced[c.word.lowercase().replace('-', ' ')] else null
            if (s == null || s.geometry >= c.geometry) c
            else c.copy(geometry = s.geometry, score = c.score - (c.geometry - s.geometry))
        }
    }

    /**
     * Language score of letters that are not a known word: ln P(letter | prefix) through the
     * lexicon while some known word continues them, [UNKNOWN_LETTER] for each letter after that
     * and for the missing word end.
     */
    fun typedStringLogProb(typed: String): Double {
        // a word the lexicon lacks, scored by how much it looks like one (names, slang, brands)
        unknownWords?.takeIf { typed.lowercase() !in knownMisspellings }?.let { m -> m.logProb(typed)?.let { return m.unknownLogProb + it } }
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
    private var pGeometry = DoubleArray(0)
    private var pEdits = IntArray(0)
    private var pRank = DoubleArray(0)
    private var pScores = DoubleArray(0)
    private var pWork = DoubleArray(0)
    private var pKept = IntArray(0)
    // the layer being processed (decode's scratch; its last layer stays here until the results are read)
    private val work = LayerMap(1024)
    // process's snapshot of a layer before its skipped keys extend it
    private var iKeys = LongArray(0)
    private var iGeometry = DoubleArray(0)
    private var iEdits = IntArray(0)

    /**
     * States kept per layer: [Config.beamWidth], lowered on slow phones while decodes take too long (TypingEngine.
     * adaptBeam; iOS: at least 30 node expansions per touch, up to 90 within 10 ms, ios-decomp notes). 100 -> 30 cost
     * 0.3 points of fixed typos on the main set, 0.2 on the ITE dev set (2026-11-21).
     */
    @Volatile var beamWidth: Int = config.beamWidth

    private fun prune(layer: LayerMap, w: Double, prefixes: List<Prefix?>) {
        val n = layer.size
        val beam = beamWidth
        if (n <= beam) return
        if (pKeys.size < n) {
            val c = maxOf(n, pKeys.size * 2)
            pKeys = LongArray(c); pGeometry = DoubleArray(c); pEdits = IntArray(c); pRank = DoubleArray(c); pScores = DoubleArray(c); pWork = DoubleArray(c)
        }
        if (pKept.size < beam) pKept = IntArray(beam)
        val keys = pKeys; val scores = pScores
        // each state's score once (a sort by a selector recomputed it in every comparison: 58 % of
        // the typing path's time), then the states above the beam's cut-off, ties in layer order,
        // put back best first: the same states in the same order as a stable sort
        var i = 0
        for (j in 0 until n) {
            scores[i] = rankOf(layer, j, w, prefixes)
            keys[i] = layer.key(j); pGeometry[i] = layer.geometry(j); pEdits[i] = layer.edits(j); pRank[i] = layer.rank(j)
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
        for (t in 0 until m) { val j = kept[t]; layer.offer(keys[j], pGeometry[j], pEdits[j], pRank[j]) }
    }

    private fun rankOf(layer: LayerMap, j: Int, w: Double, prefixes: List<Prefix?>): Double {
        val r = layer.rank(j)
        if (!r.isNaN()) return r
        val best = lexicon.bestWord(layer.node(j))
        return (layer.geometry(j) + w * ((prefixes[layer.prefix(j)]?.languageModel ?: 0.0) + (if (best == Lexicon.NONE) -50.0 else lexicon.logProb(best))))
            .also { layer.setRank(j, it) }
    }

    /** The prune cut-off of [layer] as it stands (the beam's lowest score), or -inf below a full beam. */
    private fun beamCut(layer: LayerMap, w: Double, prefixes: List<Prefix?>): Double {
        val n = layer.size
        val beam = beamWidth
        if (n < beam) return Double.NEGATIVE_INFINITY
        if (pWork.size < n) pWork = DoubleArray(maxOf(n, pWork.size * 2))
        var i = 0
        for (j in 0 until n) pWork[i++] = rankOf(layer, j, w, prefixes)
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
