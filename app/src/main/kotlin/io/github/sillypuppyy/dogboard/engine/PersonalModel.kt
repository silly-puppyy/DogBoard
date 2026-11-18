package io.github.sillypuppyy.dogboard.engine

import java.io.File
import kotlin.math.exp
import kotlin.math.min

/**
 * The personal language model: the words and word pairs the user has accepted, with counts that
 * fade with time, mixed with the static model the way iOS mixes its dynamic model:
 *
 *     P(w | prev) = (1 − d) · P_static(w | prev) + d · P_personal(w | prev)
 *     d = 0.65 with a previous word, else 0.45
 *
 * iOS ramps d in over the first 1000 words, but fills its counts from the user's sent messages
 * first, so it is at full weight from day one; we can't read messages, and Gboard has no ramp
 * either: full weight from the start (user 2026-11-18). Counts decay by exp(−0.007 · days)
 * (half-life 99 days). P_personal, as iOS's dynamic model (decompile 2026-11-18): each order n
 * the context allows (word, pair, triple; iOS goes to 5) adds min(1, c(n-gram) / c(its context))
 * at weight 1/n_orders, but only once that n-gram was used twice (count ≥ 1.01, so one use, or
 * a faded second, adds nothing) and words only at count ≥ 2 once the model holds 50 uses: a
 * typo or a one-off word typed once moves nothing. Words are added by [accept] (a word typed and kept, a correction kept, a
 * suggestion picked); [forget] removes a word used fewer than 7 times, else lowers it.
 */
class PersonalModel(private val file: File? = null, private val now: () -> Long = System::currentTimeMillis) {
    private class Count(var value: Double, var day: Long)

    private val words = HashMap<String, Count>()
    private val pairs = HashMap<Pair<String, String>, Count>()
    private val triples = HashMap<Triple<String, String, String>, Count>()
    private val uses = HashMap<String, Int>() // undecayed uses, for forgetting
    private var typed = 0L
    private var dirty = 0

    /** Changes with every accept, forget or clear (callers cache what they derive from the model). */
    @Volatile
    var version = 0
        private set

    // decayed totals, per day, rebuilt after any change (the decoder asks ~100 times per keystroke)
    private var cacheDay = -1L
    private var totalCache = 0.0
    private val afterCache = HashMap<String, Double>()
    private val after2Cache = HashMap<Pair<String, String>, Double>()

    private fun totals(day: Long) {
        if (cacheDay == day) return
        totalCache = words.values.sumOf { it.decayed(day) }
        afterCache.clear()
        for ((k, c) in pairs) afterCache[k.first] = (afterCache[k.first] ?: 0.0) + c.decayed(day)
        after2Cache.clear()
        for ((k, c) in triples) (k.first to k.second).let { after2Cache[it] = (after2Cache[it] ?: 0.0) + c.decayed(day) }
        cacheDay = day
    }

    init {
        load()
    }

    val wordsTyped: Long @Synchronized get() = typed

    private fun today(): Long = now() / MS_PER_DAY

    private fun Count.decayed(day: Long): Double = value * exp(DECAY_PER_DAY * (day - this.day).coerceAtLeast(0))

    private fun bump(c: Count?, day: Long): Count = (c ?: Count(0.0, day)).apply { value = decayed(day) + 1.0; this.day = day }

    /** [word] (as written) was accepted after [previous] (null at the start of a text), and [previous2] before that. */
    @Synchronized
    fun accept(previous: String?, word: String, previous2: String? = null) {
        version++
        val day = today()
        val w = word.lowercase()
        words[w] = bump(words[w], day)
        uses[w] = (uses[w] ?: 0) + 1
        // the decayed totals move by exactly 1 (bump: decayed value + 1, dated today), so they are
        // updated in place: rebuilding them after every word allocated per learned pair and triple
        // (tens of MB a word with a well-used model: the typing stalls on garbage collection, 2026-11-16)
        val keep = cacheDay == day
        if (keep) totalCache += 1.0
        previous?.lowercase()?.let { p ->
            pairs[p to w] = bump(pairs[p to w], day)
            if (keep) afterCache[p] = (afterCache[p] ?: 0.0) + 1.0
            previous2?.lowercase()?.let { p2 ->
                Triple(p2, p, w).let { t -> triples[t] = bump(triples[t], day) }
                if (keep) (p2 to p).let { k -> after2Cache[k] = (after2Cache[k] ?: 0.0) + 1.0 }
            }
        }
        typed++
        if (!keep) cacheDay = -1
        if (++dirty >= SAVE_EVERY) save()
    }

    /** Negative evidence for [word]: removed if used fewer than [FORGET_BELOW] times, else halved. */
    /** Removes [word] completely (the user removed it in settings; [forget] only weakens a much-used word). */
    @Synchronized
    fun remove(word: String) {
        version++
        val w = word.lowercase()
        words.remove(w); uses.remove(w)
        pairs.keys.removeAll { it.first == w || it.second == w }
        triples.keys.removeAll { it.first == w || it.second == w || it.third == w }
        cacheDay = -1
        save()
    }

    @Synchronized
    fun forget(word: String) {
        version++
        val w = word.lowercase()
        if ((uses[w] ?: 0) < FORGET_BELOW) {
            words.remove(w); uses.remove(w)
            pairs.keys.removeAll { it.first == w || it.second == w }
        triples.keys.removeAll { it.first == w || it.second == w || it.third == w }
        } else {
            words[w]?.let { it.value = it.decayed(today()) / 2; it.day = today() }
        }
        cacheDay = -1
        save()
    }

    /** Whether the user has accepted [word] (any casing). */
    @Synchronized
    fun knows(word: String): Boolean = word.lowercase() in words

    /** How many times [word] was accepted (not decayed). */
    @Synchronized
    fun uses(word: String): Int = uses[word.lowercase()] ?: 0

    /** Every word the model holds (lowercase), unsorted. */
    @Synchronized
    fun wordKeys(): List<String> = words.keys.toList()

    /** Words the user has accepted, most used first (for learning into the lexicon). */
    @Synchronized
    fun knownWords(): List<String> = words.entries.sortedByDescending { it.value.value }.map { it.key }

    /** The personal model's weight in the mix (iOS: 0.65 / 0.45; nothing before the first word). */
    @Synchronized
    fun weight(hasPrevious: Boolean): Double =
        if (typed == 0L) 0.0 else if (hasPrevious) WEIGHT_CONTEXT else WEIGHT_NO_CONTEXT

    /** P_personal(word | previous2 previous): triple, pair and word counts interpolated; 0 for unseen words. */
    @Synchronized
    fun probability(previous: String?, word: String, previous2: String? = null): Double {
        if (typed == 0L) return 0.0
        val day = today()
        val w = word.lowercase()
        totals(day)
        fun order(count: Double, context: Double, threshold: Double): Double =
            if (context > 0 && count >= threshold) min(1.0, count / context) else 0.0
        val total = totalCache
        var sum = if (total > UNIGRAM_TOTAL_MIN) order(words[w]?.decayed(day) ?: 0.0, total, UNIGRAM_MIN) else 0.0
        val p = previous?.lowercase() ?: return sum
        sum += order(pairs[p to w]?.decayed(day) ?: 0.0, afterCache[p] ?: 0.0, NGRAM_MIN)
        val p2 = previous2?.lowercase() ?: return sum / 2
        sum += order(triples[Triple(p2, p, w)]?.decayed(day) ?: 0.0, after2Cache[p2 to p] ?: 0.0, NGRAM_MIN)
        return sum / 3
    }

    /** Mixes a static probability with the personal model ([PersonalModel] doc). */
    fun mix(staticProbability: Double, previous: String?, word: String, previous2: String? = null): Double {
        val d = weight(previous != null)
        if (d <= 0) return staticProbability
        return (1 - d) * staticProbability + d * probability(previous, word, previous2)
    }

    /**
     * The decoder's hook: ln of [mix] for the word at a [lexicon] node given its static ln P. A word the user
     * never accepted has no personal counts (P_personal = 0): its mix is (1 - d) x P, worked out without
     * building the word's text and keys (the decoder asks per search state; that was the typing path's
     * largest garbage, perf 2026-11-18). Nodes of the words the model knows (a superset: casings and
     * accents share a node) take the full path.
     */
    fun decoderMix(lexicon: Lexicon, previous: String?, previous2: String?): (Int, Double) -> Double {
        val known = java.util.BitSet()
        for (w in wordKeys()) lexicon.find(w).takeIf { it != Lexicon.NONE }?.let { known.set(it) }
        val keep = kotlin.math.ln(1 - weight(previous != null))
        val floor = kotlin.math.ln(1e-300)
        return { node, logProb ->
            if (!known.get(node)) maxOf(keep + logProb, floor)
            else fullMix(lexicon, node, logProb, previous, previous2)
        }
    }

    /** [decoderMix] without the shortcut. */
    fun fullMix(lexicon: Lexicon, node: Int, logProb: Double, previous: String?, previous2: String?): Double {
        val word = lexicon.word(node) ?: return logProb
        return kotlin.math.ln(mix(exp(logProb), previous, word, previous2).coerceAtLeast(1e-300))
    }

    @Synchronized
    fun clear() {
        version++
        words.clear(); pairs.clear(); triples.clear(); uses.clear(); typed = 0; cacheDay = -1
        save()
    }

    /**
     * The whole model as text, built under the lock (in memory, quick) and written on the background
     * writer: saving every [SAVE_EVERY] words on the typing thread rewrote the file there and grew with
     * it (short freezes at word ends, user 2026-11-16).
     */
    @Synchronized
    fun save() {
        dirty = 0
        val f = file ?: return
        val text = StringBuilder().apply {
            append("#dogboard-personal 1\ttyped\t").append(typed).append('\n')
            for ((w, c) in words) append("w\t").append(w).append('\t').append(c.value).append('\t').append(c.day).append('\t').append(uses[w] ?: 0).append('\n')
            for ((k, c) in pairs) append("p\t").append(k.first).append('\t').append(k.second).append('\t').append(c.value).append('\t').append(c.day).append('\n')
            for ((k, c) in triples) append("t\t").append(k.first).append('\t').append(k.second).append('\t').append(k.third).append('\t').append(c.value).append('\t').append(c.day).append('\n')
        }.toString()
        f.writeTextLater(text)
    }

    private fun load() {
        awaitPendingWrites()
        val f = file ?: return
        if (!f.exists()) return
        f.forEachLine { line ->
            val p = line.split('\t')
            when {
                p[0].startsWith("#") && p.size >= 3 -> typed = p[2].toLongOrNull() ?: 0
                p[0] == "w" && p.size == 5 -> { words[p[1]] = Count(p[2].toDouble(), p[3].toLong()); uses[p[1]] = p[4].toInt() }
                p[0] == "p" && p.size == 5 -> pairs[p[1] to p[2]] = Count(p[3].toDouble(), p[4].toLong())
                p[0] == "t" && p.size == 6 -> triples[Triple(p[1], p[2], p[3])] = Count(p[4].toDouble(), p[5].toLong())
            }
        }
    }

    companion object {
        const val DECAY_PER_DAY = -0.007
        const val WEIGHT_CONTEXT = 0.65
        const val WEIGHT_NO_CONTEXT = 0.45
        // iOS DynamicNgramCountThreshold, DynamicUnigramCountThreshold, DynamicTotalUnigramCountThreshold
        const val NGRAM_MIN = 1.01
        const val UNIGRAM_MIN = 2.0
        const val UNIGRAM_TOTAL_MIN = 50.0
        const val FORGET_BELOW = 7
        private const val SAVE_EVERY = 20
        private const val MS_PER_DAY = 86_400_000L
    }
}
