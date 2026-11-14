package io.github.sillypuppyy.dogboard.engine

import java.io.File
import kotlin.math.exp
import kotlin.math.min

/**
 * The personal language model: the words and word pairs the user has accepted, with counts that
 * fade with time, mixed with the static model the way iOS mixes its dynamic model:
 *
 *     P(w | prev) = (1 − d) · P_static(w | prev) + d · P_personal(w | prev)
 *     d = (0.65 with a previous word, else 0.45) · min(1, (words typed / 1000)²)
 *
 * Counts decay by exp(−0.007 · days) (half-life 99 days). P_personal interpolates the triple
 * (two words of context, when that context was seen), the pair and the word counts; iOS's dynamic
 * model goes to order 5, ours to 3. Words are added by [accept] (a word typed and kept, a correction kept, a
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
        previous?.lowercase()?.let { p ->
            pairs[p to w] = bump(pairs[p to w], day)
            previous2?.lowercase()?.let { p2 -> Triple(p2, p, w).let { t -> triples[t] = bump(triples[t], day) } }
        }
        typed++
        cacheDay = -1
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

    /** Words the user has accepted, most used first (for learning into the lexicon). */
    @Synchronized
    fun knownWords(): List<String> = words.entries.sortedByDescending { it.value.value }.map { it.key }

    /** The personal model's weight in the mix (iOS: 0.65 / 0.45, ramped over the first 1000 words). */
    @Synchronized
    fun weight(hasPrevious: Boolean): Double =
        (if (hasPrevious) WEIGHT_CONTEXT else WEIGHT_NO_CONTEXT) * min(1.0, (typed / RAMP_WORDS).let { it * it })

    /** P_personal(word | previous2 previous): triple, pair and word counts interpolated; 0 for unseen words. */
    @Synchronized
    fun probability(previous: String?, word: String, previous2: String? = null): Double {
        if (typed == 0L) return 0.0
        val day = today()
        val w = word.lowercase()
        totals(day)
        val total = totalCache.takeIf { it > 0 } ?: return 0.0
        val unigram = (words[w]?.decayed(day) ?: 0.0) / total
        val p = previous?.lowercase() ?: return unigram
        val after = afterCache[p] ?: 0.0
        if (after <= 0) return unigram
        val bigram = (pairs[p to w]?.decayed(day) ?: 0.0) / after
        val pairMix = PAIR_SHARE * bigram + (1 - PAIR_SHARE) * unigram
        val p2 = previous2?.lowercase() ?: return pairMix
        val after2 = after2Cache[p2 to p] ?: 0.0
        if (after2 <= 0) return pairMix
        val trigram = (triples[Triple(p2, p, w)]?.decayed(day) ?: 0.0) / after2
        return PAIR_SHARE * trigram + (1 - PAIR_SHARE) * pairMix
    }

    /** Mixes a static probability with the personal model ([PersonalModel] doc). */
    fun mix(staticProbability: Double, previous: String?, word: String, previous2: String? = null): Double {
        val d = weight(previous != null)
        if (d <= 0) return staticProbability
        return (1 - d) * staticProbability + d * probability(previous, word, previous2)
    }

    @Synchronized
    fun clear() {
        version++
        words.clear(); pairs.clear(); triples.clear(); uses.clear(); typed = 0; cacheDay = -1
        save()
    }

    @Synchronized
    fun save() {
        dirty = 0
        val f = file ?: return
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.bufferedWriter().use { out ->
                out.write("#dogboard-personal 1\ttyped\t$typed\n")
                for ((w, c) in words) out.write("w\t$w\t${c.value}\t${c.day}\t${uses[w] ?: 0}\n")
                for ((k, c) in pairs) out.write("p\t${k.first}\t${k.second}\t${c.value}\t${c.day}\n")
                for ((k, c) in triples) out.write("t\t${k.first}\t${k.second}\t${k.third}\t${c.value}\t${c.day}\n")
            }
            tmp.renameTo(f)
        }
    }

    private fun load() {
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
        const val RAMP_WORDS = 1000.0
        const val FORGET_BELOW = 7
        private const val PAIR_SHARE = 0.6
        private const val SAVE_EVERY = 20
        private const val MS_PER_DAY = 86_400_000L
    }
}
